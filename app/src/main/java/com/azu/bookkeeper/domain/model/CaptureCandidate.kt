package com.azu.bookkeeper.domain.model

/**
 * 自动化捕获候选账单（OCR / 读屏 / 通知三路统一产出）。
 * 字段可空：任一必填字段缺失即强制进入人工校对（AC-2）。
 */
data class CaptureCandidate(
    val eventId: Long = 0L,
    /** 目标账本；短信容差去重回查 BillDao 时必填（增量架构 §2.4）。 */
    val bookId: Long = 0L,
    val captureType: CaptureType = CaptureType.NONE,
    val packageName: String = "",
    val rawText: String = "",
    val amountFen: Long? = null,
    val merchant: String = "",
    val billTime: Long? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val imagePath: String = "",
    val ocrConfidence: Float = 0f,
    val finalScore: Float = 0f,
    val isDuplicate: Boolean = false
) {
    /** 字段缺失（金额 / 时间 / 商家任一）必须弹窗校对。 */
    val needsVerification: Boolean
        get() = amountFen == null || billTime == null || merchant.isBlank()

    /** 是否可直接入账：字段齐全、非重复且分数达标由调用方结合阈值判断。 */
    val isPostable: Boolean
        get() = !needsVerification && !isDuplicate && (amountFen ?: 0L) > 0L
}
