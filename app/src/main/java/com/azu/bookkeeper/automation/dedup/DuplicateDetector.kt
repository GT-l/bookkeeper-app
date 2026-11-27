package com.azu.bookkeeper.automation.dedup

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.core.normalizeRemark
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.model.DedupKey
import com.azu.bookkeeper.domain.repository.BillRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双路 / 多源去重（主架构 §5.3 + 增量 §2.4）：
 * - 精确键 buildKey：bookId + 秒级时间 + 金额 + 账户 + 归一化备注，内存 LRU 优先零成本命中；
 * - 容差键 isDuplicateWithTolerance：仅短信源启用，回查 Room ±N 秒窗口，键刻意不含 remark。
 */
@Singleton
class DuplicateDetector @Inject constructor(
    private val billRepository: BillRepository
) {

    private val recentKeys = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val mutex = Mutex()

    /** 精确去重键（与 Bill.dedupKey 语义一致，备注归一化）。 */
    fun buildKey(candidate: CaptureCandidate): String {
        val seconds = DateTimeUtils.dedupSecond(candidate.billTime ?: 0L)
        return "${candidate.bookId}|$seconds|${candidate.amountFen ?: 0L}|" +
            "${candidate.accountId ?: 0L}|${candidate.merchant.normalizeRemark()}"
    }

    /** 单元格内存去重：命中返回 true 并记录；首次调用返回 false。 */
    suspend fun isDuplicate(key: String): Boolean = mutex.withLock {
        if (key in seen) {
            true
        } else {
            seen.add(key)
            recentKeys.addLast(key)
            if (recentKeys.size > MAX_RECENT_KEYS) {
                seen.remove(recentKeys.removeFirst())
            }
            false
        }
    }

    suspend fun isDuplicate(candidate: CaptureCandidate): Boolean = isDuplicate(buildKey(candidate))

    /**
     * 三层去重（QA F-03 修复）：
     * 1) 内存精确键（进程内零成本命中）；
     * 2) **库级精确键回查（所有捕获源）**：解决进程重启、内存 LRU 淘汰后重复建单；
     * 3) 时间窗回查：短信源 ±toleranceSec（键不含 remark，跨源商户名不同是常态）；
     *    非短信源 ±2s 小窗口，覆盖「读屏 12:00:00.9 / 通知 12:00:01.1」跨秒边界，
     *    但要求备注一致，防止短时间内同额同账户的两笔真实消费被误合并。
     *
     * 查询失败（DB 异常 / 单元测试 relaxed mock 返回 null）一律用 runCatching 兜底为「未命中」，
     * 去重失败不阻断写入，写入侧事务仍保证原子性。
     */
    suspend fun isDuplicateWithTolerance(
        candidate: CaptureCandidate,
        toleranceSec: Long = 0L
    ): Boolean {
        // v1.3.2：金额抽不到 / 商户只是页面标题时，去重键会退化成同一个值，
        // 表现为「触发一次后每笔都重复」。这类候选一律放行，宁可漏判也不误挡记账。
        val amount = candidate.amountFen
        val remark = candidate.merchant.normalizeRemark()
        if (!DuplicateRules.isValidPair(amount, remark)) return false
        if (isDuplicate(candidate)) return true

        // 2) 库级精确键回查：所有源，含归一化备注，不会误合并
        val exactDbHit = runCatching {
            billRepository.findDuplicate(toDedupKey(candidate)).getOrNull()
        }.getOrNull()
        if (exactDbHit != null) return true

        // 3) 时间窗回查
        val isSms = candidate.captureType == CaptureType.SMS
        val windowSec = if (isSms) toleranceSec else CROSS_SOURCE_WINDOW_SEC
        if (windowSec <= 0L) return false
        val time = candidate.billTime ?: return false
        val accountId = candidate.accountId ?: 0L
        val windowMs = windowSec * 1000L
        val windowHit = runCatching {
            billRepository.findDuplicateInTimeRange(
                bookId = candidate.bookId,
                amountFen = amount,
                accountFrom = accountId,
                startMillis = time - windowMs,
                endMillis = time + windowMs
            ).getOrNull()
        }.getOrNull() ?: return false
        // 短信源：键不含 remark，命中即判重复（沿用增量 §2.4 设计）
        if (isSms) return true
        // 非短信源：金额 / 账户 / 商户三元组严格一致才算重复（SQL 已过滤，这里再兜一层，
        // 防止 mock / 未来 DAO 改动引入「金额不同也命中」的回归）
        if (windowHit.amountFen != amount) return false
        if (windowHit.accountFrom != accountId) return false
        return DuplicateRules.merchantMatches(windowHit.remark, remark)
    }

    /** 供领域层重用的精确键构造（避免两处字符串拼接漂移）。 */
    fun toDedupKey(candidate: CaptureCandidate): DedupKey = DedupKey(
        bookId = candidate.bookId,
        billTime = DateTimeUtils.truncateToSecond(candidate.billTime ?: 0L),
        amountFen = candidate.amountFen ?: 0L,
        accountFrom = candidate.accountId ?: 0L,
        remark = candidate.merchant.normalizeRemark()
    )

    private companion object {
        const val MAX_RECENT_KEYS = 2000
        /** 非短信源跨秒边界容差（秒）：读屏与通知捕获同一笔付款可能落在相邻两秒。 */
        const val CROSS_SOURCE_WINDOW_SEC = 2L
    }
}
