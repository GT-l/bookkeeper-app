package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.VerifyStatus
import com.azu.bookkeeper.domain.repository.AccountRepository
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
 * 3. 账户：settings.defaultAccountId → 第一个账户 → 0（账户非必填，AddBillUseCase 已放宽）。
 *
 * 写库走 [AddBillUseCase]，保证余额事务与手动记账走同一个入口（架构 N4 单一写入口）。
 */
@Singleton
class AutoRecordUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val addBill: AddBillUseCase
) {

    suspend operator fun invoke(
        amountFen: Long,
        merchant: String,
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
            remark = merchant,
            source = BillSource.AUTO_CAPTURE,
            captureType = captureType,
            ocrConfidence = confidence,
            ocrRawText = rawText.take(500),
            verifyStatus = VerifyStatus.AUTO_PASSED
        )
        return addBill(bill)
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
