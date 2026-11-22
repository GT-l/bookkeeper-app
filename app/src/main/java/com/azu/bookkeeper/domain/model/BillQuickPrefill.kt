package com.azu.bookkeeper.domain.model

import kotlinx.serialization.Serializable

/**
 * 快捷入口 → 记账表单的预填契约（T7 的 APP_SHORTCUT / APP_WIDGET / FLOATING_BALL /
 * FG_NOTIFICATION / DOUYIN_FALLBACK 共用）。
 *
 * 仅作为路由参数 JSON 传递，不落数据库；fromCaptureEventId 在契约层保留，
 * DB 层不新增列（增量架构 §1.4）。
 *
 * [entry] 为 T7 新增字段并给出默认值，保证 T3/T5 已写入路由的旧 JSON 仍可反序列化。
 */
@Serializable
data class BillQuickPrefill(
    val amountFen: Long? = null,
    val merchant: String = "",
    val billTime: Long? = null,
    val bookId: Long? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val source: BillSource = BillSource.QUICK_MANUAL,
    val fromCaptureEventId: Long? = null,
    val entry: QuickEntry = QuickEntry.APP_SHORTCUT
) {
    /** 还原为统一入口契约，供 BillEditViewModel 的保存分支调用 QuickCaptureUseCase。 */
    fun toRequest(): QuickCaptureRequest = QuickCaptureRequest.fromPrefill(this)
}
