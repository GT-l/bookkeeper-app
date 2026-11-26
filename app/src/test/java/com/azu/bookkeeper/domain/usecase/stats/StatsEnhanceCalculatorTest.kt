package com.azu.bookkeeper.domain.usecase.stats

import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 统计增强纯计算：日历聚合 / 热力图 / 同比环比 / 年度支出。 */
class StatsEnhanceCalculatorTest {
    private val calculator = StatsEnhanceCalculator()
    private val zone = ZoneId.systemDefault()

    private fun bill(yuan: Double, date: LocalDate, type: BillType = BillType.EXPENSE, source: BillSource = BillSource.MANUAL): Bill {
        val millis = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return Bill(
            bookId = 1L, billType = type,
            amountFen = (yuan * 100).toLong(),
            billTime = millis, source = source, accountFrom = 1L, categoryId = 1L
        )
    }

    @Test
    fun `日历按天聚合且转账与校准不计数`() {
        val today = LocalDate.of(2026, 11, 15)
        val bills = listOf(
            bill(10.0, today), bill(20.0, today),
            bill(100.0, today, BillType.TRANSFER),
            bill(999.0, today, source = BillSource.ADJUSTMENT),
            bill(5.0, today.plusDays(1))
        )
        val map = calculator.aggregateByDay(bills)
        assertEquals(30_00L, map[today]?.expenseFen)
        assertEquals(2, map[today]?.count)
        assertEquals(5_00L, map[today.plusDays(1)]?.expenseFen)
    }

    @Test
    fun `月历补齐缺失日期`() {
        val ym = YearMonth.of(2026, 2)
        val days = calculator.monthCalendar(listOf(bill(12.0, ym.atDay(3))), ym)
        assertEquals(28, days.size)
        assertEquals(12_00L, days[2].expenseFen)
        assertEquals(0L, days[0].expenseFen)
    }

    @Test
    fun `热力图按最近12个月输出且无除零`() {
        val end = YearMonth.of(2026, 11)
        val bills = listOf(bill(50.0, end.atDay(10)), bill(30.0, end.minusMonths(1).atDay(10)))
        val heat = calculator.monthlyHeatmap(bills, end, 12)
        assertEquals(12, heat.size)
        assertEquals(end, heat.last().first)
        assertEquals(50_00L, heat.last().second)
        assertEquals(0L, heat.first().second)
    }

    @Test
    fun `同比环比为零基数时ratio为空`() {
        val now = Instant.parse("2026-11-20T10:00:00Z").toEpochMilli()
        val r = calculator.monthOverMonth(emptyList(), now)
        assertEquals(0L, r.currentFen)
        assertEquals(0L, r.baseFen)
        assertNull(r.ratio)
        assertFalse(r.hasBase)
    }

    @Test
    fun `同比环比增长方向正确`() {
        val now = Instant.parse("2026-11-20T10:00:00Z").toEpochMilli()
        val thisMonth = LocalDate.of(2026, 11, 10)
        val lastMonth = LocalDate.of(2026, 10, 10)
        val bills = listOf(bill(200.0, thisMonth), bill(100.0, lastMonth))
        val r = calculator.monthOverMonth(bills, now)
        assertEquals(200_00L, r.currentFen)
        assertEquals(100_00L, r.baseFen)
        assertEquals(2.0f, r.ratio ?: 0f, 0.001f)
        assertTrue(r.increased)
    }

    @Test
    fun `年度支出排除校准流水`() {
        val bills = listOf(
            bill(100.0, LocalDate.of(2026, 1, 1)),
            bill(50.0, LocalDate.of(2026, 12, 31)),
            bill(999.0, LocalDate.of(2026, 6, 1), source = BillSource.ADJUSTMENT),
            bill(88.0, LocalDate.of(2025, 12, 31))
        )
        assertEquals(150_00L, calculator.yearExpense(bills, 2026))
    }
}
