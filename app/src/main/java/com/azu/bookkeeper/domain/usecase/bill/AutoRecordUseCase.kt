package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.composeRemark
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.core.normalizeRemark
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.VerifyStatus
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高置信度识屏结果的**自动记账**。
 *
 * 与 [InstantBillCreator] 的区别：那个建的是金额 0 的占位单（等用户补），
 * 这里建的是**完整可用的真实账单**——识屏已经拿到金额和商户，用户不需要再动手。
 *
 * 取值优先级（每一层都有兜底，绝不因为缺配置而失败）：
 * 1. 账本：settings.defaultBookId → 默认账本 → 第一个账本；
 * 2. 分类：按商户关键词猜（餐饮/购物/交通/娱乐/医疗/生活）→ settings.defaultExpenseCategoryId
 *    → 含「其他」的分类 → 第一个支出分类（无分类时先补默认分类）；
 * 3. 账户：settings.defaultAccountId → 第一个账户 → 0（账户非必填，AddBillUseCase 已放宽）；
 * 4. 备注：商户 + 购买内容（能识别到时），格式「商户 · 购买内容」(composeRemark)。
 *
 * 写库走 [AddBillUseCase]，保证余额事务与手动记账走同一个入口（架构 N4 单一写入口）。
 */
@Singleton
class AutoRecordUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val billRepository: BillRepository,
    private val addBill: AddBillUseCase
) {

    suspend operator fun invoke(
        amountFen: Long,
        merchant: String,
        goods: String = "",
        confidence: Float = 1f,
        rawText: String = "",
        captureType: CaptureType = CaptureType.ACCESSIBILITY_TEXT
    ): Result<Long> {
        if (amountFen <= 0L) {
            return Result.Error(
                AppError.Validation(code = "E_AUTO_001", userMessage = "金额无效，无法自动记账")
            )
        }
        val bookId = resolveBookId()
        if (bookId <= 0L) {
            return Result.Error(
                AppError.Validation(code = "E_VALID_002", userMessage = "请先创建一个账本")
            )
        }
        val categoryId = resolveCategoryId(bookId, merchant)
        val accountId = resolveAccountId(bookId)

        val bill = Bill(
            bookId = bookId,
            billType = BillType.EXPENSE,
            amountFen = amountFen,
            categoryId = categoryId,
            accountFrom = accountId,
            billTime = DateTimeUtils.nowMillis(),
            remark = composeRemark(merchant, goods),
            source = BillSource.AUTO_CAPTURE,
            captureType = captureType,
            ocrConfidence = confidence,
            ocrRawText = rawText.take(500),
            verifyStatus = VerifyStatus.AUTO_PASSED
        )
        return addBill(bill)
    }

    /**
     * 疑似重复查询：近 [DUPLICATE_WINDOW_MILLIS] 内是否有「同商户 + 同金额」的一笔。
     *
     * 命中后**不丢弃、不自动写库**，交给上层弹轻确认让用户决定（用户要求）。
     * 商户为空 / 金额非正 / 查库异常一律返回 null（宁可不提示，也不能卡住记账）。
     *
     * 用既有的 [BillRepository.findByTimeRange] 在内存里比对，而不是新增一条 DAO 查询：
     * 24 小时的账单量（通常几十到几百条）对内存过滤毫无压力，却省掉一次 schema 相关的改库风险。
     */
    suspend fun findRecentDuplicate(amountFen: Long, merchant: String): DuplicateHint? {
        val name = merchant.normalizeRemark()
        if (amountFen <= 0L || name.length < 2) return null
        val bookId = resolveBookId()
        if (bookId <= 0L) return null
        val now = System.currentTimeMillis()
        val bills = runCatching {
            billRepository.findByTimeRange(bookId, now - DUPLICATE_WINDOW_MILLIS, now).getOrNull()
        }.getOrNull().orEmpty()
        val hit = bills.filter { bill ->
            bill.amountFen == amountFen && matchesMerchant(bill.remark, name)
        }.maxByOrNull { it.billTime } ?: return null
        return DuplicateHint(
            billId = hit.billId,
            amountFen = hit.amountFen,
            remark = hit.remark,
            billTime = hit.billTime
        )
    }

    /** 备注是否属于这个商户：自动记账写的是「商户 · 购买内容」，所以商户是前缀。 */
    private fun matchesMerchant(remark: String, merchant: String): Boolean {
        val text = remark.normalizeRemark()
        return text == merchant || text.startsWith(merchant)
    }

    private suspend fun resolveBookId(): Long {
        val preferred = runCatching { settings.defaultBookId.first() }.getOrDefault(0L)
        if (preferred > 0L) return preferred
        val fromDefault = runCatching { bookRepository.findDefault() }.getOrNull()?.getOrNull()?.bookId ?: 0L
        if (fromDefault > 0L) return fromDefault
        return runCatching { bookRepository.observeAll().first().firstOrNull()?.bookId ?: 0L }
            .getOrDefault(0L)
    }

    private suspend fun resolveCategoryId(bookId: Long, merchant: String): Long {
        var categories = runCatching {
            categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
        }.getOrDefault(emptyList())
        if (categories.isEmpty()) {
            runCatching { categoryRepository.ensureDefaults(bookId) }
            categories = runCatching {
                categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
            }.getOrDefault(emptyList())
        }
        // 1) 商户关键词 → 分类名
        guessCategoryName(merchant)?.let { guess ->
            categories.firstOrNull { it.name.contains(guess) }?.let { return it.categoryId }
        }
        // 2) 用户设置的默认支出分类
        val preferred = runCatching { settings.defaultExpenseCategoryId.first() }.getOrDefault(0L)
        categories.firstOrNull { it.categoryId == preferred }?.let { return it.categoryId }
        // 3) 「其他」→ 第一个
        return categories.firstOrNull { it.name.contains("其他") }?.categoryId
            ?: categories.firstOrNull()?.categoryId
            ?: 0L
    }

    private suspend fun resolveAccountId(bookId: Long): Long {
        val preferred = runCatching { settings.defaultAccountId.first() }.getOrDefault(0L)
        val accounts = runCatching { accountRepository.observeAll(bookId).first() }
            .getOrDefault(emptyList())
        return accounts.firstOrNull { it.accountId == preferred }?.accountId
            ?: accounts.firstOrNull()?.accountId
            ?: 0L
    }

    /** 商户名 → 支出分类名（只做粗粒度归类，用户事后可改）。 */
    private fun guessCategoryName(merchant: String): String? {
        if (merchant.isBlank()) return null
        return when {
            CATERING.any { it in merchant } -> "餐饮"
            SHOPPING.any { it in merchant } -> "购物"
            TRANSPORT.any { it in merchant } -> "交通"
            FUN.any { it in merchant } -> "娱乐"
            MEDICAL.any { it in merchant } -> "医疗"
            LIVING.any { it in merchant } -> "生活"
            else -> null
        }
    }

    private companion object {
        /** 疑似重复的回溯窗口：24 小时（跨零点也认，避免半夜前后两笔漏判）。 */
        const val DUPLICATE_WINDOW_MILLIS = 24L * 60 * 60 * 1000

        val CATERING = listOf(
            "餐", "饭", "食", "咖啡", "奶茶", "茶", "火锅", "烧烤", "面", "粉", "饺",
            "肯德基", "麦当劳", "星巴克", "瑞幸", "汉堡", "寿司", "烘焙", "小吃"
        )
        val SHOPPING = listOf("超市", "便利", "商场", "百货", "购物", "旗舰店", "专营店", "店")
        val TRANSPORT = listOf("滴滴", "打车", "出租", "地铁", "公交", "高铁", "火车", "加油", "停车", "航空", "机票")
        val FUN = listOf("电影", "游戏", "娱乐", "KTV", "音乐", "视频", "网吧", "剧本", "密室")
        val MEDICAL = listOf("药", "医院", "诊所", "体检", "口腔", "眼科")
        val LIVING = listOf("话费", "宽带", "流量", "电费", "水费", "燃气", "物业", "快递")
    }
}

/** 疑似重复命中信息：展示给用户「什么时候记过一笔同样的」。 */
data class DuplicateHint(
    val billId: Long,
    val amountFen: Long,
    val remark: String,
    val billTime: Long
)
