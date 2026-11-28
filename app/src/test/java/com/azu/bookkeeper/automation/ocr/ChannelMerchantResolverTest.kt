package com.azu.bookkeeper.automation.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 受 [规则一] 约束：财付通向上追溯真实商户。
 * 样本直接取自工单「验证样本」（原文= / 上一行= / 上两行=）。
 */
class ChannelMerchantResolverTest {

    @Test
    fun `样本一 小程序名在财付通上一行时取小程序名`() {
        // 原文=财付通 / 上一行=喜茶点单小程序 → merchant_name = "喜茶点单小程序"
        val lines = listOf("喜茶点单小程序", "财付通")
        assertEquals("喜茶点单小程序", ChannelMerchantResolver.backtrack(lines))
    }

    @Test
    fun `样本二 上一行是时间上一两行是微信支付时不回填`() {
        // 原文=财付通 / 上一行=2024-01-01 / 上两行=微信支付 → merchant_name = null
        val lines = listOf("微信支付", "2024-01-01", "财付通")
        assertNull(ChannelMerchantResolver.backtrack(lines))
    }

    @Test
    fun `优先级 小程序名高于店铺名`() {
        val lines = listOf("张三水果店", "喜茶点单小程序", "财付通")
        assertEquals("喜茶点单小程序", ChannelMerchantResolver.backtrack(lines))
    }

    @Test
    fun `优先级 店铺品牌名高于商品描述行`() {
        val lines = listOf(" Nike Air Force 1", "瑞幸咖啡", "财付通")
        assertEquals("瑞幸咖啡", ChannelMerchantResolver.backtrack(lines))
    }

    @Test
    fun `第三优先级 含SKU的商品描述行`() {
        val lines = listOf("SKU A1234 运动鞋", "财付通")
        assertEquals("SKU A1234 运动鞋", ChannelMerchantResolver.backtrack(lines))
    }

    @Test
    fun `没有财付通行时不触发回溯`() {
        assertNull(ChannelMerchantResolver.backtrack(listOf("喜茶点单小程序", "支付成功")))
    }

    @Test
    fun `待支付页面不回填商户`() {
        val lines = listOf("喜茶点单小程序", "待支付 ¥15.00", "财付通")
        assertNull(ChannelMerchantResolver.backtrack(lines))
    }

    @Test
    fun `只回溯最近三行`() {
        // 喜茶点单小程序 在财付通之上第 4 行（超出 3 行窗口），窗口内只有 UI 词 → null
        val lines = listOf("喜茶点单小程序", "订单详情", "更多", "返回", "财付通")
        assertNull(ChannelMerchantResolver.backtrack(lines))
    }

    @Test
    fun `接入分析器 财付通页拿到真实商户`() {
        val text = """
            喜茶点单小程序
            财付通
            付款金额 ¥18.00
            支付成功
        """.trimIndent()
        val r = PaymentTextAnalyzer().analyze(text)
        assertEquals("喜茶点单小程序", r.merchant)
    }

    // ---------- v1.3.22 问题四方案 D：多通道回溯 ----------

    @Test
    fun `网联银联快钱易宝拉卡拉通道也能向上追溯`() {
        listOf("网联", "银联", "快钱", "易宝", "拉卡拉").forEach { channel ->
            assertEquals(
                "喜茶点单小程序",
                ChannelMerchantResolver.backtrack(listOf("喜茶点单小程序", channel))
            )
        }
    }
}
