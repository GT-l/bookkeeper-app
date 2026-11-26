package com.azu.bookkeeper.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.0 新增：「我的」页汇总指标的纯逻辑单测。
 * UiState 的计算属性决定用户看到的总结是否正确；页面渲染本身由走查用例覆盖。
 */
class ProfileSummaryLogicTest {

    @Test
    fun `结余等于收入减支出 - 可为负`() {
        val state = ProfileViewModel.UiState(monthIncomeFen = 5000L, monthExpenseFen = 8800L)
        assertEquals(-3800L, state.monthBalanceFen)
    }

    @Test
    fun `自动记账占比 - 无账单时为 0`() {
        val state = ProfileViewModel.UiState(totalBills = 0, autoBills = 5)
        assertEquals(0, state.autoPercent)
    }

    @Test
    fun `自动记账占比 - 正常比例取整`() {
        val state = ProfileViewModel.UiState(totalBills = 8, autoBills = 2)
        assertEquals(25, state.autoPercent)
    }

    @Test
    fun `预算使用比例 - 未设预算为 0 且 hasBudget 为假`() {
        val state = ProfileViewModel.UiState(monthExpenseFen = 1000L, budgetLimitFen = 0L)
        assertEquals(0, state.budgetUsedPercent)
        assertFalse(state.hasBudget)
    }

    @Test
    fun `预算使用比例 - 超支时超过 100`() {
        val state = ProfileViewModel.UiState(monthExpenseFen = 15000L, budgetLimitFen = 10000L)
        assertEquals(150, state.budgetUsedPercent)
        assertTrue(state.hasBudget)
    }

    @Test
    fun `分类切片占比原样保留 - 由 UI 决定显示精度`() {
        val slice = ProfileViewModel.CategorySlice(name = "餐饮", amountFen = 2500L, ratio = 0.25f)
        assertEquals("餐饮", slice.name)
        assertEquals(2500L, slice.amountFen)
        assertEquals(0.25f, slice.ratio, 0.0001f)
    }
}
