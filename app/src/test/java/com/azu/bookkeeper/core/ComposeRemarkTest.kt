package com.azu.bookkeeper.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** composeRemark：商户 + 购买内容 合成备注，商户必须始终是前缀（疑似重复查询依赖它）。 */
class ComposeRemarkTest {

    @Test
    fun `只有商户时原样返回`() {
        assertEquals("瑞幸咖啡", composeRemark("瑞幸咖啡", ""))
    }

    @Test
    fun `只有购买内容时用内容`() {
        assertEquals("生椰拿铁", composeRemark("", "生椰拿铁"))
    }

    @Test
    fun `两项都在时用中点拼接`() {
        assertEquals("瑞幸咖啡 · 生椰拿铁", composeRemark("瑞幸咖啡", "生椰拿铁"))
    }

    @Test
    fun `内容已包含商户时不重复拼接`() {
        assertEquals("瑞幸咖啡-生椰拿铁", composeRemark("瑞幸咖啡", "瑞幸咖啡-生椰拿铁"))
    }

    @Test
    fun `两者相同只保留一份`() {
        assertEquals("瑞幸咖啡", composeRemark("瑞幸咖啡", "瑞幸咖啡"))
    }

    @Test
    fun `首尾空白会被清理`() {
        assertEquals("瑞幸咖啡 · 生椰拿铁", composeRemark("  瑞幸咖啡 ", " 生椰拿铁  "))
    }
}
