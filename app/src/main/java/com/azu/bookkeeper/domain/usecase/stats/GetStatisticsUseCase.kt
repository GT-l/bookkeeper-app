package com.azu.bookkeeper.domain.usecase.stats

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CategoryAmount
import com.azu.bookkeeper.domain.model.StatisticsBundle
import com.azu.bookkeeper.domain.model.StatisticsSummary
import com.azu.bookkeeper.domain.model.TrendPoint
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BudgetRepository
import com.azu.bookkeeper.domain.repository.TagRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** 统计时间桶粒度。 */
enum class StatsGranularity { DAY, WEEK, MONTH }

/** 概览 + 饼图 + 趋势 + 账户 / 标签统计（转账与余额调整均不计入收支）。 */
class GetStatisticsUseCase @Inject constructor(
    private val billRepository: BillRepository,
    private val budgetRepository: BudgetRepository,
    private val accountRepository: AccountRepository,
    private val tagRepository: TagRepository
) {

    suspend operator fun invoke(
        bookId: Long,
        startMillis: Long,
        endMillis: Long,
        granularity: StatsGranularity = StatsGranularity.DAY
    ): Result<StatisticsBundle> {
        val billsResult = billRepository.findByTimeRange(bookId, startMillis, endMillis)
        if (billsResult is Result.Error) return Result.Error(billsResult.error)
        val bills = billsResult.getOrDefault(emptyList()).filter { !it.isAdjustment }

        val incomeFen = bills.filter { it.billType == BillType.INCOME }.sumOf { it.amountFen }
        val expenseFen = bills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountFen }
        val transferFen = bills.filter { it.billType == BillType.TRANSFER }.sumOf { it.amountFen }

        // 单月预算只能跟单月支出比。跨月范围（本年 / 跨月自定义 / 跨月周）继续用起始月预算，
        // 会拿 1 月的 5000 元去比全年 6 万元支出，显示「超支 5.5 万」这种假象。
        val sameMonth = DateTimeUtils.monthKey(startMillis) == DateTimeUtils.monthKey(endMillis)
        val budget = if (sameMonth) {
            budgetRepository.findByBookAndMonth(bookId, DateTimeUtils.monthKey(startMillis)).getOrNull()
        } else {
            null
        }
        val summary = StatisticsSummary(
            incomeFen = incomeFen,
            expenseFen = expenseFen,
            transferFen = transferFen,
            // 未设置总预算时按分类预算之和兜底，统计概览的预算进度与预算页保持一致。
            budgetLimitFen = budget?.effectiveTotalLimitFen ?: 0L,
            budgetUsedFen = expenseFen
        )

        return Result.Success(
            StatisticsBundle(
                summary = summary,
                expenseByCategory = groupByCategory(bills, BillType.EXPENSE),
                incomeByCategory = groupByCategory(bills, BillType.INCOME),
                trends = buildTrends(bills, granularity),
                accounts = accountRepository.observeAll(bookId).first().map {
                    com.azu.bookkeeper.domain.model.AccountBalance(
                        accountId = it.accountId,
                        name = it.name,
                        balanceFen = it.balanceFen
                    )
                },
                tags = buildTagStats(bills, tagRepository.observeByBook(bookId).first().map { it.tagId })
            )
        )
    }

    private fun groupByCategory(
        bills: List<com.azu.bookkeeper.domain.model.Bill>,
        type: BillType
    ): List<CategoryAmount> {
        val grouped = bills.filter { it.billType == type }
            .groupBy { it.categoryId }
            .mapValues { (_, items) -> items.sumOf { it.amountFen } to items.size }
        val total = grouped.values.sumOf { it.first }
        return grouped.map { (id, value) ->
            CategoryAmount(
                categoryId = id,
                amountFen = value.first,
                count = value.second,
                ratio = if (total > 0L) value.first.toFloat() / total.toFloat() else 0f
            )
        }.sortedByDescending { it.amountFen }
    }

    private fun buildTrends(
        bills: List<com.azu.bookkeeper.domain.model.Bill>,
        granularity: StatsGranularity
    ): List<TrendPoint> {
        fun bucket(time: Long): Long = when (granularity) {
            StatsGranularity.DAY -> DateTimeUtils.startOfDay(time)
            StatsGranularity.WEEK -> DateTimeUtils.startOfWeek(time)
            StatsGranularity.MONTH -> DateTimeUtils.startOfMonth(time)
        }
        return bills.groupBy { bucket(it.billTime) }
            .map { (time, items) ->
                TrendPoint(
                    bucketStart = time,
                    incomeFen = items.filter { it.billType == BillType.INCOME }.sumOf { it.amountFen },
                    expenseFen = items.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountFen }
                )
            }
            .sortedBy { it.bucketStart }
    }

    private fun buildTagStats(
        bills: List<com.azu.bookkeeper.domain.model.Bill>,
        validTagIds: List<Long>
    ): List<com.azu.bookkeeper.domain.model.TagAmount> = bills
        .filter { it.billType == BillType.EXPENSE }
        .flatMap { bill -> bill.tagIds.filter { it in validTagIds }.map { it to bill.amountFen } }
        .groupBy { it.first }
        .map { (tagId, pairs) ->
            com.azu.bookkeeper.domain.model.TagAmount(tagId, pairs.sumOf { it.second })
        }
        .sortedByDescending { it.amountFen }
}
