package com.azu.bookkeeper.domain.usecase.stats

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** 某一天的收支汇总（日历视图 / 热力图的最小单元）。 */
data class DaySummary(
    val date: LocalDate,
    val expenseFen: Long = 0L,
    val incomeFen: Long = 0L,
    val count: Int = 0
) {
    val hasData: Boolean get() = count > 0
}

/** 环比 / 同比对比结果；基期为 0 时 ratio 记为 null（避免除零）。 */
data class CompareResult(
    val currentFen: Long,
    val baseFen: Long,
    val ratio: Float?,
    val deltaFen: Long
) {
    val hasBase: Boolean get() = baseFen > 0L
    /** 是否为增长（支出增长通常是坏事，UI 用不同颜色）。 */
    val increased: Boolean get() = deltaFen > 0L
}

/**
 * 统计增强的纯计算：日历聚合 / 月度热力 / 同比环比。
 *
 * 全部是纯函数（只吃 List<Bill>，不查库），便于 JVM 单测；
 * 调用方负责把账本与时间范围里的账单查出来传进来。
 */
class StatsEnhanceCalculator @Inject constructor() {

    /** 按自然日聚合；余额校准流水与转账不算进收支。 */
    fun aggregateByDay(bills: List<Bill>): Map<LocalDate, DaySummary> {
        val zone = ZoneId.systemDefault()
        val mutable = mutableMapOf<LocalDate, DaySummary>()
        bills.forEach { bill ->
            if (bill.isAdjustment || bill.billType == BillType.TRANSFER) return@forEach
            val date = Instant.ofEpochMilli(bill.billTime).atZone(zone).toLocalDate()
            val prev = mutable[date] ?: DaySummary(date)
            mutable[date] = when (bill.billType) {
                BillType.EXPENSE -> prev.copy(
                    expenseFen = prev.expenseFen + bill.amountFen,
                    count = prev.count + 1
                )
                BillType.INCOME -> prev.copy(
                    incomeFen = prev.incomeFen + bill.amountFen,
                    count = prev.count + 1
                )
                else -> prev
            }
        }
        return mutable
    }

    /** 生成日历网格数据：当月 1 号到月末，缺失日期补 0。 */
    fun monthCalendar(bills: List<Bill>, yearMonth: YearMonth): List<DaySummary> {
        val byDay = aggregateByDay(bills)
        return (1..yearMonth.lengthOfMonth()).map { day ->
            val date = yearMonth.atDay(day)
            byDay[date] ?: DaySummary(date)
        }
    }

    /**
     * 月度热力图：最近 N 个月的每月支出（不含余额校准 / 转账）。
     * 返回 (YearMonth -> 支出分)，供 UI 映射成色阶。
     */
    fun monthlyHeatmap(bills: List<Bill>, endMonth: YearMonth, months: Int = 12): List<Pair<YearMonth, Long>> {
        val zone = ZoneId.systemDefault()
        val sums = mutableMapOf<YearMonth, Long>()
        bills.forEach { bill ->
            if (bill.isAdjustment || bill.billType != BillType.EXPENSE) return@forEach
            val ym = YearMonth.from(Instant.ofEpochMilli(bill.billTime).atZone(zone).toLocalDate())
            sums[ym] = (sums[ym] ?: 0L) + bill.amountFen
        }
        return (months - 1 downTo 0).map { offset ->
            val ym = endMonth.minusMonths(offset.toLong())
            ym to (sums[ym] ?: 0L)
        }
    }

    /** 环比：当前支出与上个月同口径对比。 */
    fun monthOverMonth(bills: List<Bill>, nowMillis: Long): CompareResult {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val thisMonth = YearMonth.from(today)
        val lastMonth = thisMonth.minusMonths(1)
        fun sumOf(ym: YearMonth): Long {
            val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            return bills.filter {
                !it.isAdjustment && it.billType == BillType.EXPENSE && it.billTime in start..end
            }.sumOf { it.amountFen }
        }
        return compare(sumOf(thisMonth), sumOf(lastMonth))
    }

    /** 同比：当前支出与去年同月对比。 */
    fun yearOverYear(bills: List<Bill>, nowMillis: Long): CompareResult {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val thisMonth = YearMonth.from(today)
        val lastYear = thisMonth.minusYears(1)
        fun sumOf(ym: YearMonth): Long {
            val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            return bills.filter {
                !it.isAdjustment && it.billType == BillType.EXPENSE && it.billTime in start..end
            }.sumOf { it.amountFen }
        }
        return compare(sumOf(thisMonth), sumOf(lastYear))
    }

    private fun compare(current: Long, base: Long): CompareResult = CompareResult(
        currentFen = current,
        baseFen = base,
        ratio = if (base > 0L) current.toFloat() / base.toFloat() else null,
        deltaFen = current - base
    )

    /** 年度累计支出（用于年度预算进度）。 */
    fun yearExpense(bills: List<Bill>, year: Int): Long {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year, 12, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        return bills.filter {
            !it.isAdjustment && it.billType == BillType.EXPENSE && it.billTime in start..end
        }.sumOf { it.amountFen }
    }
}
