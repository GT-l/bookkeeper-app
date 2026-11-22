package com.azu.bookkeeper.domain.model

import com.azu.bookkeeper.core.AppError
import kotlinx.serialization.Serializable

/**
 * 快捷补记入口枚举（T7 四入口 + 抖音兜底）。
 *
 * 仅用于路由、埋点与事件关联，不写入账单表（增量架构 §1.4 / PRD 23.2.3）。
 */
enum class QuickEntry {
    APP_SHORTCUT,
    APP_WIDGET,
    FLOATING_BALL,
    FG_NOTIFICATION,
    DOUYIN_FALLBACK
}

/**
 * 统一入口契约：所有快捷入口最终都归一化为本对象，再转成 [BillQuickPrefill] 走同一条路由。
 *
 * 采用 kotlinx.serialization 的 `@Serializable`（与项目既有路由参数编码一致），
 * 通过 Intent 字符串 extra 跨冷启动 / 系统组件传递；不落数据库。
 * 金额固定为「分」的 Long，时间固定为毫秒时间戳。
 */
@Serializable
data class QuickCaptureRequest(
    val entry: QuickEntry = QuickEntry.APP_SHORTCUT,
    val source: BillSource = BillSource.QUICK_MANUAL,
    val amountFen: Long? = null,
    val merchant: String = "",
    val billTime: Long? = null,
    val bookId: Long? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val fromCaptureEventId: Long? = null
) {
    /** 转成路由可携带的预填对象；entry 随 JSON 一起传递，表单据此区分埋点来源。 */
    fun toPrefill(): BillQuickPrefill = BillQuickPrefill(
        entry = entry,
        amountFen = amountFen,
        merchant = merchant,
        billTime = billTime,
        bookId = bookId,
        accountId = accountId,
        categoryId = categoryId,
        source = source,
        fromCaptureEventId = fromCaptureEventId
    )

    companion object {
        /** 从既有预填（如 T5 校对通知）还原契约；缺省入口为抖音兜底。 */
        fun fromPrefill(prefill: BillQuickPrefill): QuickCaptureRequest = QuickCaptureRequest(
            entry = prefill.entry,
            source = prefill.source,
            amountFen = prefill.amountFen,
            merchant = prefill.merchant,
            billTime = prefill.billTime,
            bookId = prefill.bookId,
            accountId = prefill.accountId,
            categoryId = prefill.categoryId,
            fromCaptureEventId = prefill.fromCaptureEventId
        )
    }
}

/** 统一写事务的结果封装；Cancelled 由 UI 取消路径直接短路，不写库。 */
sealed interface QuickCaptureResult {
    data class Saved(val billId: Long) : QuickCaptureResult
    data object Cancelled : QuickCaptureResult
    data class Rejected(val error: AppError) : QuickCaptureResult
}

/**
 * 表单最终值（用户在预填基础上完成编辑后的完整账单字段）。
 * 与 [QuickCaptureRequest] 分离：前者是入口带来的"线索"，后者是用户确认的最终事实。
 */
data class ResolvedBillInput(
    val bookId: Long = 0L,
    val billType: BillType = BillType.EXPENSE,
    val amountFen: Long = 0L,
    val categoryId: Long = 0L,
    val accountFrom: Long = 0L,
    val accountTo: Long = 0L,
    val billTime: Long = 0L,
    val merchant: String = "",
    val tagIds: List<Long> = emptyList(),
    val imagePaths: List<String> = emptyList(),
    val cycleType: CycleType = CycleType.NONE,
    val verifyStatus: VerifyStatus = VerifyStatus.USER_CONFIRMED
)
