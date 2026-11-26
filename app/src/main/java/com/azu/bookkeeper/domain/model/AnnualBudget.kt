package com.azu.bookkeeper.domain.model

/** 年度预算：year 形如 2026，与月度预算（month=202611）分开存储，互不覆盖。 */
data class AnnualBudget(
    val budgetId: Long = 0L,
    val bookId: Long,
    val year: Int,
    val totalLimitFen: Long
)
