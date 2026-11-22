package com.azu.bookkeeper.data.repository

import com.azu.bookkeeper.data.local.db.entity.BillEntity
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余额净变动单元测试（PRD §12 / 架构 N4）。
 *
 * 直接以反射调用 BillRepositoryImpl 的真实私有工具方法 netDeltas / negated / merge，
 * 不复制公式、不写空壳断言。所有金额单位为「分」。
 *
 * 重点场景：新增、修改金额、修改换账户、转账、删除回滚、ADJUSTMENT、信用卡（负余额=欠款）。
 */
@Suppress("UNCHECKED_CAST")
class BalanceCalculationTest {

    private val repo: BillRepositoryImpl = mockk(relaxed = true)

    private fun call(name: String, vararg args: Any?): Any? {
        val method = BillRepositoryImpl::class.java.declaredMethods
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: error("private method $name not found")
        method.isAccessible = true
        return method.invoke(repo, *args)
    }

    private fun deltas(bill: BillEntity): Map<Long, Long> =
        call("netDeltas", bill) as Map<Long, Long>

    private fun negated(map: Map<Long, Long>): Map<Long, Long> =
        call("negated", map) as Map<Long, Long>

    private fun merged(old: Map<Long, Long>, new: Map<Long, Long>): Map<Long, Long> =
        call("merge", old, new, true) as Map<Long, Long>

    private fun bill(
        type: BillType,
        amountFen: Long,
        from: Long,
        to: Long = 0L,
        source: BillSource = BillSource.MANUAL
    ) = BillEntity(
        bookId = 1L,
        billType = type.code,
        amountFen = amountFen,
        accountFrom = from,
        accountTo = to,
        billTime = 1_700_000_000_000L,
        source = source.code
    )

    // ---------------- 新增：netDeltas ----------------

    @Test
    fun `支出 现金账户余额减少100元`() {
        val result = deltas(bill(BillType.EXPENSE, 10_000L, from = 11L))
        assertEquals(1, result.size)
        assertEquals(-10_000L, result[11L])
    }

    @Test
    fun `收入 现金账户余额增加100元`() {
        val result = deltas(bill(BillType.INCOME, 10_000L, from = 11L))
        assertEquals(10_000L, result[11L])
    }

    @Test
    fun `转账 两账户一减一增且总额守恒`() {
        val result = deltas(bill(BillType.TRANSFER, 10_000L, from = 11L, to = 12L))
        assertEquals(2, result.size)
        assertEquals(-10_000L, result[11L])
        assertEquals(10_000L, result[12L])
        assertEquals(0L, result.values.sum())
    }

    @Test
    fun `ADJUSTMENT 校准账单仍改变账户余额`() {
        // PRD §12：校准账单改变余额，但统计/预算排除；两者不可混为一谈。
        val positive = deltas(bill(BillType.INCOME, 5_000L, from = 11L, source = BillSource.ADJUSTMENT))
        val negative = deltas(bill(BillType.EXPENSE, 5_000L, from = 11L, source = BillSource.ADJUSTMENT))
        assertEquals(5_000L, positive[11L])
        assertEquals(-5_000L, negative[11L])
        assertEquals(BillSource.ADJUSTMENT.code, BillSource.fromCode(3).code)
    }

    @Test
    fun `信用卡支出使欠款增加 余额更负`() {
        // 约定：信用卡 balanceFen 为负数表示欠款；支出 100 → 余额 -10000（欠款+100）。
        val creditCardAccountId = 99L
        val startBalance = 0L
        val after = startBalance + (deltas(bill(BillType.EXPENSE, 10_000L, from = creditCardAccountId))[creditCardAccountId] ?: 0L)
        assertEquals(-10_000L, after)
    }

    @Test
    fun `信用卡还款 通过转入使欠款减少`() {
        // 储蓄卡 11 转账 100 至信用卡 99：储蓄卡 -100，信用卡 +100 → 欠款从 -100 归零。
        val result = deltas(bill(BillType.TRANSFER, 10_000L, from = 11L, to = 99L))
        val creditAfter = -10_000L + (result[99L] ?: 0L)
        assertEquals(-10_000L, result[11L])
        assertEquals(0L, creditAfter)
    }

    @Test
    fun `账户号无效时不产生余额变动`() {
        assertTrue(deltas(bill(BillType.EXPENSE, 10_000L, from = 0L)).isEmpty())
    }

    // ---------------- 修改：old 取反 + new ----------------

    @Test
    fun `修改金额 100变200 再扣100`() {
        val old = deltas(bill(BillType.EXPENSE, 10_000L, from = 11L)) // -10000
        val new = deltas(bill(BillType.EXPENSE, 20_000L, from = 11L)) // -20000
        assertEquals(-10_000L, merged(old, new)[11L])
    }

    @Test
    fun `修改金额 200变100 回滚100`() {
        val old = deltas(bill(BillType.EXPENSE, 20_000L, from = 11L))
        val new = deltas(bill(BillType.EXPENSE, 10_000L, from = 11L))
        assertEquals(10_000L, merged(old, new)[11L])
    }

    @Test
    fun `修改账户 支出A变支出B 完全回滚A并扣B`() {
        val old = deltas(bill(BillType.EXPENSE, 10_000L, from = 11L))
        val new = deltas(bill(BillType.EXPENSE, 10_000L, from = 12L))
        val result = merged(old, new)
        assertEquals(10_000L, result[11L])
        assertEquals(-10_000L, result[12L])
        assertEquals(2, result.size)
    }

    @Test
    fun `修改类型 支出100变收入100 净调整正200`() {
        val old = deltas(bill(BillType.EXPENSE, 10_000L, from = 11L))
        val new = deltas(bill(BillType.INCOME, 10_000L, from = 11L))
        assertEquals(20_000L, merged(old, new)[11L])
    }

    @Test
    fun `转账改转入账户 旧B回滚新C入账且A保持`() {
        val old = deltas(bill(BillType.TRANSFER, 10_000L, from = 11L, to = 12L))
        val new = deltas(bill(BillType.TRANSFER, 10_000L, from = 11L, to = 13L))
        val result = merged(old, new)
        assertEquals(-10_000L, result[11L])
        assertEquals(-10_000L, result[12L])
        assertEquals(10_000L, result[13L])
        assertEquals(0L, result.values.sum())
    }

    @Test
    fun `转账改支出 撤销转入账户的入账`() {
        val old = deltas(bill(BillType.TRANSFER, 10_000L, from = 11L, to = 12L))
        val new = deltas(bill(BillType.EXPENSE, 10_000L, from = 11L))
        val result = merged(old, new)
        assertEquals(-10_000L, result[11L])
        assertEquals(-10_000L, result[12L])
    }

    @Test
    fun `相同内容修改 净变动归零且被过滤`() {
        val old = deltas(bill(BillType.EXPENSE, 10_000L, from = 11L))
        assertTrue(merged(old, old).isEmpty())
    }

    // ---------------- 删除：negated ----------------

    @Test
    fun `删除支出回滚账户加回100`() {
        val rollback = negated(deltas(bill(BillType.EXPENSE, 10_000L, from = 11L)))
        assertEquals(10_000L, rollback[11L])
    }

    @Test
    fun `删除转账双向回滚且守恒`() {
        val rollback = negated(deltas(bill(BillType.TRANSFER, 10_000L, from = 11L, to = 12L)))
        assertEquals(10_000L, rollback[11L])
        assertEquals(-10_000L, rollback[12L])
        assertEquals(0L, rollback.values.sum())
    }
}
