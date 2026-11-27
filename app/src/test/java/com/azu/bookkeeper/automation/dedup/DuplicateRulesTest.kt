package com.azu.bookkeeper.automation.dedup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.2：重复判定准入规则，防止「金额不同也判重复」的回归。 */
class DuplicateRulesTest {

    @Test
    fun `金额无效一律不判重复`() {
        assertFalse(DuplicateRules.isValidPair(null, "星巴克"))
        assertFalse(DuplicateRules.isValidPair(0L, "星巴克"))
        assertFalse(DuplicateRules.isValidPair(-1L, "星巴克"))
    }

    @Test
    fun `页面标题与状态词不是商户名`() {
        assertFalse(DuplicateRules.isValidPair(2_300L, "支付"))
        assertFalse(DuplicateRules.isValidPair(2_300L, "微信支付"))
        assertFalse(DuplicateRules.isValidPair(2_300L, "已完成"))
        assertFalse(DuplicateRules.isValidPair(2_300L, "支付成功"))
        assertTrue(DuplicateRules.isValidPair(2_300L, "星巴克"))
        assertTrue(DuplicateRules.isValidPair(2_300L, "星巴克 · 拿铁"))
    }

    @Test
    fun `商户前缀匹配且泛词不匹配`() {
        assertTrue(DuplicateRules.merchantMatches("星巴克 · 拿铁", "星巴克"))
        assertFalse(DuplicateRules.merchantMatches("瑞幸咖啡", "星巴克"))
        assertFalse(DuplicateRules.merchantMatches("支付成功", "支付"))
    }
}
