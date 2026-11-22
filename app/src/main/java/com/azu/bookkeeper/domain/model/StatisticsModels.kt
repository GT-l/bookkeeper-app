package com.azu.bookkeeper.domain.model

/** 分类汇总（饼图 / 分类明细表）。 */
data class CategoryAmount(
    val categoryId: Long,
    val amountFen: Long,
    val count: Int,
    val ratio: Float = 0f
)

/** 趋势点（按天 / 周 / 月聚合）。 */
data class TrendPoint(
    val bucketStart: Long,
    val incomeFen: Long = 0L,
    val expenseFen: Long = 0L
)

/** 概览卡片数据；转账不计入收支，仅单独展示。 */
data class StatisticsSummary(
    val incomeFen: Long = 0L,
    val expenseFen: Long = 0L,
    val netFen: Long = incomeFen - expenseFen,
    val transferFen: Long = 0L,
    val budgetLimitFen: Long = 0L,
    val budgetUsedFen: Long = 0L
)

/** 账户余额快照（资产页 / 统计页共用）。 */
data class AccountBalance(
    val accountId: Long,
    val name: String,
    val balanceFen: Long
)

/** 标签支出统计。 */
data class TagAmount(
    val tagId: Long,
    val amountFen: Long
)

/** 统计页一次性返回的全部数据。 */
data class StatisticsBundle(
    val summary: StatisticsSummary = StatisticsSummary(),
    val expenseByCategory: List<CategoryAmount> = emptyList(),
    val incomeByCategory: List<CategoryAmount> = emptyList(),
    val trends: List<TrendPoint> = emptyList(),
    val accounts: List<AccountBalance> = emptyList(),
    val tags: List<TagAmount> = emptyList()
)
