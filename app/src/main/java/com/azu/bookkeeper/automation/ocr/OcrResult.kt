package com.azu.bookkeeper.automation.ocr

/** ML Kit OCR 结果（文本源不经过 OCR，调用方传 null）。 */
data class OcrResult(
    val rawText: String,
    val confidence: Float,
    val blockCount: Int = 0
)

/** 正则抽取出的账单字段。 */
data class ExtractedFields(
    val amountFen: Long? = null,
    val merchant: String = "",
    val billTime: Long? = null,
    val accountHint: String = ""
) {
    val hasRequiredFields: Boolean get() = amountFen != null && amountFen > 0L && billTime != null && merchant.isNotBlank()
}

/** 规则引擎命中结果。 */
data class RuleHit(
    val categoryId: Long = 0L,
    val accountId: Long = 0L,
    val keyword: String = "",
    val matched: Boolean = false
)
