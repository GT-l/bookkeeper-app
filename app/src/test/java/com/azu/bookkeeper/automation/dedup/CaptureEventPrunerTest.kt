package com.azu.bookkeeper.automation.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.22 问题五方案 A+E：捕获事件清理策略与原文截断单测。 */
class CaptureEventPrunerTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `首次进入立即清理`() {
        assertTrue(CaptureEventPruner.shouldPrune(now, 0L))
    }

    @Test
    fun `24小时内不重复清理`() {
        assertFalse(CaptureEventPruner.shouldPrune(now, now - 23L * 60 * 60 * 1000))
    }

    @Test
    fun `超过24小时再次清理`() {
        assertTrue(CaptureEventPruner.shouldPrune(now, now - 25L * 60 * 60 * 1000))
    }

    @Test
    fun `保留窗口为7天`() {
        assertEquals(now - 7L * day, CaptureEventPruner.cutoffMillis(now))
    }

    @Test
    fun `原文截断到2000字`() {
        val longText = "支付成功".repeat(1000) // 4000 字
        val bounded = CaptureEventPruner.boundedRawText(longText)
        assertEquals(2000, bounded.length)
        assertEquals(longText.take(2000), bounded)
    }

    @Test
    fun `短原文不被改动`() {
        val text = "支付成功 ¥21.60"
        assertEquals(text, CaptureEventPruner.boundedRawText(text))
    }

    @Test
    fun `1000条记录的原文量级可控`() {
        // 回归场景：1000 条 × 2000 字 ≈ 2MB 字符 = 约 2~6MB（UTF-8 中文 3 字节），
        // 远小于旧逻辑「上限 6000 字且不清理」的无界增长。
        val perRecord = CaptureEventPruner.boundedRawText("中".repeat(6000)).length
        val totalChars = perRecord * 1000
        assertTrue("1000 条原文字符数应 <= 2,000,000，实际 $totalChars", totalChars <= 2_000_000)
    }
}
