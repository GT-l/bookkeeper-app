package com.azu.bookkeeper.automation.classify

import com.azu.bookkeeper.automation.ocr.RuleHit

/**
 * 关键词 → 分类规则的内存表示，由 [ClassificationRuleEngine] 从 Room 的
 * CategoryRuleEntity 加载。priority 越大越优先；抖音种子规则 priority=80~100，
 * 通用商家词 priority=10~50（增量架构 §3.2）。
 */
data class KeywordRule(
    val keyword: String,
    val categoryId: Long,
    val priority: Int = 0,
    val enabled: Boolean = true
)

/** 分类引擎命中结果的语义别名：与置信度计算接收的 RuleHit 共用同一结构。 */
typealias MatchResult = RuleHit
