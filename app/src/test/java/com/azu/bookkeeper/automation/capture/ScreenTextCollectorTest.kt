package com.azu.bookkeeper.automation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.1：统一文本采集的纯 JVM 部分（不依赖 Android 节点，避免真机才能验证）。 */
class ScreenTextCollectorTest {

    @Test
    fun `多段文本按行去重并保持顺序`() {
        val merged = ScreenTextCollector.merge(
            listOf("支付成功\n¥23.00\n麦当劳", "¥23.00\n完成\n麦当劳")
        )
        assertEquals("支付成功\n¥23.00\n麦当劳\n完成\n", merged)
    }

    @Test
    fun `稀疏文本触发有界重读`() {
        assertTrue(ScreenTextCollector.isSparse("支付"))
        assertTrue(ScreenTextCollector.isSparse("支付\n¥"))
        assertFalse(ScreenTextCollector.isSparse("支付成功\n¥23.00\n麦当劳"))
    }

    @Test
    fun `日志预览把多行压成单行`() {
        assertEquals("支付成功|¥23.00", ScreenTextCollector.preview(" 支付成功 \n ¥23.00 "))
    }
}
