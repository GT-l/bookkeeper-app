package com.azu.bookkeeper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间解析与秒级去重键单元测试（导入多格式 + 自动化去重契约）。
 */
class DateTimeUtilsTest {

    @Test
    fun `dedupSecond 与 truncateToSecond 语义一致`() {
        val t = 1_700_000_000_999L
        assertEquals(1_700_000_000L, DateTimeUtils.dedupSecond(t))
        assertEquals(1_700_000_000_000L, DateTimeUtils.truncateToSecond(t))
        assertEquals(
            DateTimeUtils.dedupSecond(t).toString(),
            (DateTimeUtils.truncateToSecond(t) / 1000L).toString()
        )
    }

    @Test
    fun `parseFlexible 支持标准日期时间`() {
        assertNotNull(DateTimeUtils.parseFlexible("2026-11-22 12:30:45"))
        assertNotNull(DateTimeUtils.parseFlexible("2026-11-22 12:30"))
        assertNotNull(DateTimeUtils.parseFlexible("2026/11/22"))
    }

    @Test
    fun `parseFlexible 支持中文与紧凑格式`() {
        assertNotNull(DateTimeUtils.parseFlexible("2026年11月22日"))
        assertNotNull(DateTimeUtils.parseFlexible("2026年11月22日 12:30"))
        assertNotNull(DateTimeUtils.parseFlexible("20261122"))
        assertNotNull(DateTimeUtils.parseFlexible("2026.11.22"))
    }

    @Test
    fun `parseFlexible 无年份时按今年补齐`() {
        val parsed = DateTimeUtils.parseFlexible("11月22日")
        assertNotNull(parsed)
        val year = DateTimeUtils.localDateTime(System.currentTimeMillis()).year
        assertEquals(year, DateTimeUtils.localDateTime(parsed!!).year)
        assertEquals(11, DateTimeUtils.localDateTime(parsed).monthValue)
        assertEquals(22, DateTimeUtils.localDateTime(parsed).dayOfMonth)
    }

    @Test
    fun `parseFlexible 支持斜杠短日期`() {
        assertNotNull(DateTimeUtils.parseFlexible("06/15"))
        assertNotNull(DateTimeUtils.parseFlexible("6/15"))
    }

    @Test
    fun `parseFlexible 非法文本返回 null`() {
        assertNull(DateTimeUtils.parseFlexible(""))
        assertNull(DateTimeUtils.parseFlexible("not a date"))
        assertNull(DateTimeUtils.parseFlexible("2026-13-45"))
    }

    @Test
    fun `parse 严格分秒格式`() {
        val parsed = DateTimeUtils.parse("2026-11-22 12:30:45", "yyyy-MM-dd HH:mm:ss")
        assertNotNull(parsed)
        assertEquals(45, DateTimeUtils.localDateTime(parsed!!).second)
    }

    @Test
    fun `startOfDay 与 endOfDay 覆盖整天`() {
        val now = DateTimeUtils.parse("2026-11-22 09:30", "yyyy-MM-dd HH:mm")!!
        val start = DateTimeUtils.startOfDay(now)
        val end = DateTimeUtils.endOfDay(now)
        assertTrue(end > start)
        assertTrue("整天长度应在 24h 附近（兼容夏令时切换）", end - start in 86_380_000L..86_400_000L)
        assertEquals(start, DateTimeUtils.startOfDay(end))
    }

    @Test
    fun `monthKey 为 yyyyMM`() {
        val t = DateTimeUtils.parse("2026-11-22 12:00", "yyyy-MM-dd HH:mm")!!
        assertEquals(202611, DateTimeUtils.monthKey(t))
    }

    @Test
    fun `fromIso 与 toIso 往返一致`() {
        val t = 1_700_000_000_123L
        assertEquals(t, DateTimeUtils.fromIso(DateTimeUtils.toIso(t)))
    }
}
