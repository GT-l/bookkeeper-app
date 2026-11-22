package com.azu.bookkeeper.domain.model

/** 月度预算（month 形如 202611）；categoryLimits 按分类 ID 存上限（分）。 */
data class Budget(
    val budgetId: Long = 0L,
    val bookId: Long,
    val month: Int,
    val totalLimitFen: Long = 0L,
    val categoryLimits: Map<Long, Long> = emptyMap()
)
