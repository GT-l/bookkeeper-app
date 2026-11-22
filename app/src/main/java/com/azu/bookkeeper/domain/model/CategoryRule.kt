package com.azu.bookkeeper.domain.model

/** OCR 关键词 → 分类 的自动分类规则（T5 规则引擎的数据来源）。 */
data class CategoryRule(
    val ruleId: Long = 0L,
    val keyword: String,
    val categoryId: Long,
    val priority: Int = 0,
    val enabled: Boolean = true
)
