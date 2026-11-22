package com.azu.bookkeeper.data.repository

import androidx.room.withTransaction
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.chunkedBySize
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.core.normalizeRemark
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.BookkeeperDatabase
import com.azu.bookkeeper.data.local.db.dao.AccountDao
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.data.local.db.entity.BillEntity
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toDomainBills
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillQuery
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.CategoryAmount
import com.azu.bookkeeper.domain.model.DedupKey
import com.azu.bookkeeper.domain.repository.BillRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账单仓储实现。
 *
 * 架构 N4 / §9.5：所有新增、修改、删除、导入的余额变更均收敛到
 * addWithBalanceTx / updateWithBalanceTx / deleteWithBalanceTx / bulkInsert 四个事务入口，
 * 每个入口内部通过 Room.withTransaction 保证「账单 + 账户余额」原子提交。
 */
@Singleton
class BillRepositoryImpl @Inject constructor(
    private val database: BookkeeperDatabase,
    private val billDao: BillDao,
    private val accountDao: AccountDao,
    private val captureDao: CaptureDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BillRepository {

    override fun observeBills(bookId: Long): Flow<List<Bill>> =
        billDao.observeByBook(bookId).map { entities -> entities.toDomainBills() }

    override suspend fun getBill(billId: Long): Result<Bill?> = safeResult {
        withContext(ioDispatcher) { billDao.findById(billId)?.toDomain() }
    }

    override suspend fun addWithBalanceTx(
        bill: Bill,
        sourceEventId: Long?,
        terminalStatus: CaptureEventStatus
    ): Result<Long> = safeResult {
        withContext(ioDispatcher) {
            val entity = bill.normalized().toEntity()
            database.withTransaction {
                val billId = billDao.insert(entity)
                applyDeltas(entity.netDeltas())
                // 捕获事件置终态与建单、余额变更同处一个事务，杜绝「已入账但事件仍提醒」。
                // terminalStatus 由调用方裁决：自动入账=CONSUMED_AUTO；用户介入=USER_CORRECTED（T7 显式传）。
                if (sourceEventId != null) {
                    val marked = captureDao.markStatus(sourceEventId, terminalStatus.code)
                    if (marked <= 0) {
                        throw IllegalStateException("capture event $sourceEventId not found")
                    }
                }
                billId
            }
        }
    }

    override suspend fun updateWithBalanceTx(bill: Bill): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val old = billDao.findById(bill.billId)
                ?: throw IllegalArgumentException("bill ${bill.billId} not found")
            val updated = bill.normalized().copy(
                billId = bill.billId,
                createAt = old.createAt
            ).toEntity()
            database.withTransaction {
                billDao.update(updated)
                applyDeltas(old.netDeltas().merge(updated.netDeltas(), reverseOld = true))
            }
        }
    }

    override suspend fun deleteWithBalanceTx(billId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val old = billDao.findById(billId)
                ?: throw IllegalArgumentException("bill $billId not found")
            database.withTransaction {
                billDao.deleteById(billId)
                applyDeltas(old.netDeltas().negated())
            }
        }
    }

    override suspend fun bulkInsert(bills: List<Bill>): Result<Int> = safeResult {
        withContext(ioDispatcher) {
            var inserted = 0
            bills.chunkedBySize(Constants.IMPORT_BATCH_SIZE).forEach { batch ->
                database.withTransaction {
                    batch.forEach { bill ->
                        val entity = bill.normalized().toEntity()
                        billDao.insert(entity)
                        applyDeltas(entity.netDeltas())
                        inserted++
                    }
                }
            }
            inserted
        }
    }

    override suspend fun findDuplicate(key: DedupKey): Result<Bill?> = safeResult {
        withContext(ioDispatcher) {
            // 库内账单写入前经 Bill.normalized() 归一化 remark，查询键必须同步归一化，
            // 否则连续空白差异会导致库内精确去重漏判（QA F-07）。
            billDao.findDuplicate(
                bookId = key.bookId,
                billTime = key.billTime,
                amountFen = key.amountFen,
                accountFrom = key.accountFrom,
                remark = key.remark.normalizeRemark()
            )?.toDomain()
        }
    }

    override suspend fun isCaptureEventHandled(eventId: Long): Boolean = safeResult {
        withContext(ioDispatcher) {
            val event = captureDao.findByEventId(eventId)
            // 只有「已生成账单」的终态才算已补记；DISCARDED（被去重丢掉 / 用户主动丢弃）
            // 并没有建单，必须允许通过快捷补记重新录入，且文案不应谎称「该笔已补记」（QA F-09）。
            event == null || when (CaptureEventStatus.fromCode(event.status)) {
                CaptureEventStatus.CONSUMED_AUTO, CaptureEventStatus.USER_CORRECTED -> true
                else -> false
            }
        }
    }.getOrDefault(true)

    override suspend fun findDuplicateInTimeRange(
        bookId: Long,
        amountFen: Long,
        accountFrom: Long,
        startMillis: Long,
        endMillis: Long
    ): Result<Bill?> = safeResult {
        withContext(ioDispatcher) {
            billDao.findDuplicateInTimeRange(
                bookId = bookId,
                amountFen = amountFen,
                accountFrom = accountFrom,
                start = startMillis,
                end = endMillis
            )?.toDomain()
        }
    }

    override suspend fun search(query: BillQuery): Result<List<Bill>> = safeResult {
        withContext(ioDispatcher) {
            val keyword = query.keyword.trim()
            val result = billDao.findByTimeRange(query.bookId, query.startMillis, query.endMillis)
                .toDomainBills()
                .asSequence()
                .filter { bill ->
                    keyword.isEmpty() ||
                        bill.remark.contains(keyword, ignoreCase = true) ||
                        bill.ocrRawText.contains(keyword, ignoreCase = true)
                }
                .filter { query.billType == null || it.billType == query.billType }
                .filter { query.categoryIds.isEmpty() || it.categoryId in query.categoryIds }
                .filter { query.accountIds.isEmpty() || it.accountFrom in query.accountIds || it.accountTo in query.accountIds }
                .filter { query.tagIds.isEmpty() || it.tagIds.any { id -> id in query.tagIds } }
                .filter { query.sources.isEmpty() || it.source in query.sources }
                .filter { query.minAmountFen == null || it.amountFen >= query.minAmountFen }
                .filter { query.maxAmountFen == null || it.amountFen <= query.maxAmountFen }
                .sortedBy { it.billTime }
                .toList()
            val sorted = if (query.sortDescending) result.asReversed() else result
            sorted.drop(query.offset).take(query.limit)
        }
    }

    override suspend fun findByTimeRange(
        bookId: Long,
        startMillis: Long,
        endMillis: Long
    ): Result<List<Bill>> = safeResult {
        withContext(ioDispatcher) {
            billDao.findByTimeRange(bookId, startMillis, endMillis).toDomainBills()
        }
    }

    override suspend fun sumByCategory(
        bookId: Long,
        type: BillType,
        startMillis: Long,
        endMillis: Long
    ): Result<List<CategoryAmount>> = safeResult {
        withContext(ioDispatcher) {
            val rows = billDao.sumByCategory(
                bookId = bookId,
                billType = type.code,
                startMillis = startMillis,
                endMillis = endMillis,
                excludedSource = BillSource.ADJUSTMENT.code
            )
            val total = rows.sumOf { it.totalFen }
            rows.map { row ->
                CategoryAmount(
                    categoryId = row.categoryId,
                    amountFen = row.totalFen,
                    count = row.count,
                    ratio = if (total > 0L) row.totalFen.toFloat() / total.toFloat() else 0f
                )
            }
        }
    }

    override suspend fun sumByType(
        bookId: Long,
        startMillis: Long,
        endMillis: Long
    ): Result<Map<BillType, Long>> = safeResult {
        withContext(ioDispatcher) {
            billDao.sumByType(bookId, startMillis, endMillis, BillSource.ADJUSTMENT.code)
                .associate { BillType.fromCode(it.billType) to it.totalFen }
        }
    }

    override suspend fun findBySource(bookId: Long, source: BillSource): Result<List<Bill>> =
        safeResult {
            withContext(ioDispatcher) {
                billDao.findBySource(bookId, source.code).toDomainBills()
            }
        }

    // ---------------- 事务内部工具 ----------------

    private suspend fun applyDeltas(deltas: Map<Long, Long>) {
        deltas.forEach { (accountId, delta) ->
            if (delta != 0L) {
                val affected = accountDao.updateBalance(accountId, delta)
                if (affected <= 0) {
                    throw IllegalStateException("account $accountId not found, delta=$delta")
                }
            }
        }
    }

    /** 统一秒级时间与备注，确保去重键一致。 */
    private fun Bill.normalized(): Bill = copy(
        billTime = DateTimeUtils.truncateToSecond(billTime),
        remark = remark.normalizeRemark(),
        updateAt = DateTimeUtils.nowMillis()
    )

    /** 一条 bill 对所有账户造成的净余额变动。 */
    private fun BillEntity.netDeltas(): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        fun add(accountId: Long, delta: Long) {
            if (accountId <= 0L || delta == 0L) return
            result[accountId] = (result[accountId] ?: 0L) + delta
        }
        when (billType) {
            BillType.EXPENSE.code -> add(accountFrom, -amountFen)
            BillType.INCOME.code -> add(accountFrom, amountFen)
            BillType.TRANSFER.code -> {
                add(accountFrom, -amountFen)
                add(accountTo, amountFen)
            }
        }
        return result
    }

    private fun Map<Long, Long>.negated(): Map<Long, Long> = mapValues { -it.value }

    /**
     * 修改账单时：旧账单的净变动取反 + 新账单的净变动。
     * 即使账户发生变更（A→B）也能正确回滚 A 并调整 B。
     */
    private fun Map<Long, Long>.merge(newDeltas: Map<Long, Long>, reverseOld: Boolean): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        this.forEach { (id, delta) -> result[id] = (result[id] ?: 0L) + if (reverseOld) -delta else delta }
        newDeltas.forEach { (id, delta) -> result[id] = (result[id] ?: 0L) + delta }
        return result.filterValues { it != 0L }
    }
}
