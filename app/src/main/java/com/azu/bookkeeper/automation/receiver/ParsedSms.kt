package com.azu.bookkeeper.automation.receiver

/** 短信时间优先级：BODY 高于到达时间；仅到达时间时置信度因子减半（PRD 23.1.5）。 */
enum class TimeSource { BODY, SMS_ARRIVAL }

/** 银行扣款短信解析结果；短信原文不落库、不联网、不写日志。 */
data class ParsedSms(
    val amountFen: Long,
    val merchant: String,
    val billTime: Long,
    val timeSource: TimeSource,
    val bankShort: String,
    val cardTailMasked: String,
    val douyinHit: Boolean,
    val accountId: Long = 0L,
    val parseHint: Float = 1f
)
