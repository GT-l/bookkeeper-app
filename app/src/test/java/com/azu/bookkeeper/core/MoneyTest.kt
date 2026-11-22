package com.azu.bookkeeper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * 金额全链路单元测试（QA / 金额一致性）。
 * 断言全部为具体分值，覆盖 ¥/千分位/元 清洗、四舍五入、展示与分摊守恒。
 */
class MoneyTest {

    @Test
    fun `parse 人民币符号与千分位`() {
        // 注意：RMB / 人民币 前缀由上游正则（SmsRuleSet / FieldExtractor）剥离，
        // Money.parse 契约只清洗 ¥ ￥ , ， 元 与空白，见源码 21~29 行。
        assertEquals(123_456L, Money.parse("¥1,234.56"))
        assertEquals(123_456L, Money.parse("￥1,234.56"))
    }

    @Test
    fun `parse 中文元与全角逗号`() {
        assertEquals(123_456L, Money.parse("1234.56元"))
        assertEquals(123_456L, Money.parse("1，234.56"))
        assertEquals(123_456L, Money.parse(" 1234.56 元 "))
    }

    @Test
    fun `parse 一位小数补零到分`() {
        assertEquals(1_230L, Money.parse("12.3"))
        assertEquals(100L, Money.parse("1"))
        assertEquals(1L, Money.parse("0.01"))
    }

    @Test
    fun `parse 非法输入返回 null`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("--12"))
    }

    @Test
    fun `toFen 使用 HALF_UP 四舍五入`() {
        assertEquals(101L, Money.toFen(BigDecimal("1.005")))
        assertEquals(100L, Money.toFen(BigDecimal("1.004")))
        assertEquals(-101L, Money.toFen(BigDecimal("-1.005")))
    }

    @Test
    fun `format 带千分位与符号`() {
        assertEquals("¥1,234.56", Money.format(123_456L))
        assertEquals("-¥1,234.56", Money.format(-123_456L))
        assertEquals("¥0.05", Money.format(5L))
    }

    @Test
    fun `formatPlain 不带千分位`() {
        assertEquals("¥1234.56", Money.formatPlain(123_456L))
    }

    @Test
    fun `toYuan 与 format 往返一致`() {
        val fen = 98_765_432L
        assertEquals("¥987,654.32", Money.format(Money.toFen(Money.toYuan(fen))))
    }

    @Test
    fun `distribute 分摊守恒且末项取余`() {
        val result = Money.distribute(100L, listOf(1L, 1L, 1L))
        assertEquals(listOf(33L, 33L, 34L), result)
        assertEquals(100L, result.sum())
    }

    @Test
    fun `distribute 全零权重返回全零`() {
        assertEquals(listOf(0L, 0L), Money.distribute(500L, listOf(0L, 0L)))
    }

    @Test
    fun `normalizeRemark 折叠连续空白`() {
        assertEquals("超市 购物", "  超市   购物 \n".normalizeRemark())
    }
}
