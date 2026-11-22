package com.azu.bookkeeper.automation.receiver

import kotlinx.serialization.Serializable

/**
 * 银行短信的结构化摘要（增量 §2.3）：写入 CaptureEventEntity.rawText 的不是短信原文，
 * 而是本对象序列化后的 JSON，兼顾「最小留存」与既有 FieldExtractor 输入形态。
 *
 * 摘要字段全部为脱敏 / 解析后数据，不含短信正文。
 */
@Serializable
data class SmsSummary(
    val src: String = "sms",
    val bank: String = "",
    val amountFen: Long = 0L,
    val merchant: String = "",
    val billTime: Long = 0L,
    val timeSrc: String = "BODY",
    val cardTail: String = "",
    val dy: Boolean = false,
    val acct: Long = 0L
)
