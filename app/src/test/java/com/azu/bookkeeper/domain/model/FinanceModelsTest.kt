package com.azu.bookkeeper.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.0 新业务模型的边界计算（尾差、结清、剩余额度）。 */
class FinanceModelsTest {

    @Test
    fun `债务剩余与结清百分比`() {
        val debt = Debt(
            bookId = 1L, direction = DebtDirection.BORROW, counterparty = "张三",
            totalFen = 100_00L, settledFen = 30_00L
        )
        assertEquals(70_00L, debt.remainingFen)
        assertEquals(30, debt.settledPercent)
        val settled = debt.copy(settledFen = 100_00L, status = DebtStatus.SETTLED)
        assertEquals(0L, settled.remainingFen)
        assertEquals(100, settled.settledPercent)
    }

    @Test
    fun `债务超还时剩余不为负`() {
        val debt = Debt(bookId = 1L, direction = DebtDirection.LEND, counterparty = "李四",
            totalFen = 100L, settledFen = 150L)
        assertEquals(0L, debt.remainingFen)
    }

    @Test
    fun `分期最后一期吸收尾差`() {
        // 1000 分分 3 期，每期 333 分，最后一期应补 334
        val plan = Installment(bookId = 1L, title = "测试", totalFen = 1000L,
            periods = 3, perPeriodFen = 333L, paidPeriods = 0)
        assertEquals(333L, plan.amountForNextPeriod())
        assertEquals(333L, plan.copy(paidPeriods = 1).amountForNextPeriod())
        assertEquals(334L, plan.copy(paidPeriods = 2).amountForNextPeriod())
        assertEquals(333L * 2 + 334L, plan.totalFen)
    }

    @Test
    fun `分期完成状态`() {
        val plan = Installment(bookId = 1L, title = "测试", totalFen = 1000L,
            periods = 3, perPeriodFen = 333L, paidPeriods = 3)
        assertTrue(plan.finished)
        assertEquals(0, plan.remainingPeriods)
        assertFalse(plan.copy(paidPeriods = 2).finished)
    }

    @Test
    fun `信用卡提醒仅在配置完整时生效`() {
        assertTrue(Account(bookId = 1L, name = "信用卡", type = AccountType.CREDIT_CARD,
            billDay = 5, dueDay = 25, reminderEnabled = true).hasCardReminder)
        assertFalse(Account(bookId = 1L, name = "信用卡", type = AccountType.CREDIT_CARD,
            billDay = 5, dueDay = 25, reminderEnabled = false).hasCardReminder)
        assertFalse(Account(bookId = 1L, name = "信用卡", type = AccountType.CREDIT_CARD,
            billDay = 0, dueDay = 25, reminderEnabled = true).hasCardReminder)
    }

    @Test
    fun `报销状态编码往返`() {
        assertEquals(ReimburseStatus.SUBMITTED, ReimburseStatus.fromCode(1))
        assertEquals(ReimburseStatus.RECEIVED, ReimburseStatus.fromCode(2))
        assertEquals(ReimburseStatus.PENDING, ReimburseStatus.fromCode(999))
    }
}
