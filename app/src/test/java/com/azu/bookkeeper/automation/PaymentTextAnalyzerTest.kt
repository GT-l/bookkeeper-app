package com.azu.bookkeeper.automation.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PaymentTextAnalyzer 多因子打分算法单测：
 * 覆盖标签金额、符号金额、订单号淘汰、商户标签、商户后缀兜底、支付状态检测。
 */
class PaymentTextAnalyzerTest {

    private val analyzer = PaymentTextAnalyzer()

    @Test
    fun `微信支付成功页 - 标签金额优先`() {
        val text = """
            支付成功
            ¥128.50
            当前状态 支付成功
            付款金额 ¥128.50
            收款方 瑞幸咖啡
            支付方式 零钱
            交易时间 2026-11-26 10:30:00
            订单号 202611261234567890
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(12850L, r.amountFen)
        assertEquals("瑞幸咖啡", r.merchant)
        assertTrue(r.paidConfirmed)
        assertTrue(r.confidence >= 0.8f)
    }

    @Test
    fun `支付宝账单 - 实付金额带元后缀`() {
        val text = """
            交易成功
            商户 全聚德烤鸭店
            实付 88元
            订单金额 98.00元
            优惠 10.00元
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(8800L, r.amountFen)
        assertEquals("全聚德烤鸭店", r.merchant)
        assertTrue(r.paidConfirmed)
    }

    @Test
    fun `订单号与手机号不被误判为金额`() {
        val text = """
            支付成功
            订单号 2026112699999999
            商家电话 13812345678
            付款金额 ¥36.80
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(3680L, r.amountFen)
    }

    @Test
    fun `多金额同屏 - 择标签与符号权重最高者`() {
        val text = """
            交易成功
            商品总价 ¥9.90
            运费 ¥5.00
            实付款 ¥14.90
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(1490L, r.amountFen)
    }

    @Test
    fun `无标签时按商户后缀兜底`() {
        val text = """
            支付成功
            ¥25.00
            盒马鲜生超市
            2026-11-26 18:20
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(2500L, r.amountFen)
        assertEquals("盒马鲜生超市", r.merchant)
    }

    @Test
    fun `待支付状态降低置信度`() {
        val text = """
            待支付
            应付金额 ¥66.00
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertFalse(r.paidConfirmed)
        assertTrue(r.confidence < 0.7f)
    }

    @Test
    fun `无金额文本返回空结果`() {
        val r = analyzer.analyze("设置\n关于我们\n版本 1.0.0")
        assertNull(r.amountFen)
        assertEquals("", r.merchant)
        assertEquals(0f, r.confidence)
    }
}
