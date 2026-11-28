package com.azu.bookkeeper.automation.dedup

import com.azu.bookkeeper.automation.capture.PaymentScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.22 问题三方案 A：静默期判定单测。
 * 核心回归：同一笔转账详情页反复扫描（时间串抖动）不得绕过静默期。
 */
class SettledQuietTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `转账收入静默期为60秒`() {
        assertEquals(60_000L, SettledQuiet.quietMsFor(PaymentScene.WECHAT_TRANSFER_IN))
        assertEquals(60_000L, SettledQuiet.quietMsFor(PaymentScene.WECHAT_RED_PACKET_IN))
        assertEquals(60_000L, SettledQuiet.quietMsFor(PaymentScene.DOUYIN_ORDER))
        // 普通支付维持 12s，不因为本修复变慢
        assertEquals(12_000L, SettledQuiet.quietMsFor(null))
    }

    @Test
    fun `时间串抖动但无法解析时不放行`() {
        // 旧逻辑：两个串不等就 differentBillByTime=true → 静默期被绕过 → 同一转账记两遍
        val mark = SettledQuiet.Mark(now - 1_000L, "100.00", "2026-11-29 10:00:00")
        assertFalse(SettledQuiet.isDifferentBill(mark, "10:00:0")) // 不完整的时间串
        assertTrue(
            SettledQuiet.shouldBlock(
                previous = mark,
                nowMillis = now,
                currentMoney = "100.00",
                currentTimeKey = "10:00:0",
                scene = PaymentScene.WECHAT_TRANSFER_IN
            )
        )
    }

    @Test
    fun `同一笔转账60秒内重复扫描被拦截`() {
        val mark = SettledQuiet.Mark(now - 5_000L, "100.00", "2026-11-29 10:00:00")
        assertTrue(
            SettledQuiet.shouldBlock(
                previous = mark,
                nowMillis = now,
                currentMoney = "100.00",
                currentTimeKey = "2026-11-29 10:00:00",
                scene = PaymentScene.WECHAT_TRANSFER_IN
            )
        )
    }

    @Test
    fun `金额明确不同的另一笔照常放行`() {
        val mark = SettledQuiet.Mark(now - 5_000L, "100.00", "2026-11-29 10:00:00")
        assertFalse(
            SettledQuiet.shouldBlock(
                previous = mark,
                nowMillis = now,
                currentMoney = "200.00",
                currentTimeKey = "2026-11-29 10:00:00",
                scene = PaymentScene.WECHAT_TRANSFER_IN
            )
        )
    }

    @Test
    fun `时间确实不同的两笔转账放行`() {
        val mark = SettledQuiet.Mark(now - 5_000L, "100.00", "2026-11-29 10:00:00")
        assertTrue(SettledQuiet.isDifferentBill(mark, "2026-11-29 10:05:00"))
        assertFalse(
            SettledQuiet.shouldBlock(
                previous = mark,
                nowMillis = now,
                currentMoney = "100.00",
                currentTimeKey = "2026-11-29 10:05:00",
                scene = PaymentScene.WECHAT_TRANSFER_IN
            )
        )
    }

    @Test
    fun `超过静默窗口不再拦截`() {
        val mark = SettledQuiet.Mark(now - 61_000L, "100.00", "2026-11-29 10:00:00")
        assertFalse(
            SettledQuiet.shouldBlock(
                previous = mark,
                nowMillis = now,
                currentMoney = "100.00",
                currentTimeKey = "2026-11-29 10:00:00",
                scene = PaymentScene.WECHAT_TRANSFER_IN
            )
        )
    }

    @Test
    fun `没有历史标记时不拦截`() {
        assertFalse(
            SettledQuiet.shouldBlock(
                previous = null,
                nowMillis = now,
                currentMoney = "100.00",
                currentTimeKey = "",
                scene = PaymentScene.WECHAT_TRANSFER_IN
            )
        )
    }
}
