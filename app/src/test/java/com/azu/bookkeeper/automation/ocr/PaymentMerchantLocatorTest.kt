package com.azu.bookkeeper.automation.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.23 · 受 [问题四-位置规则] 约束：
 * 「真正的收款方在金额上方，付款方式在金额下方」这一真机结构线索的单测。
 */
class PaymentMerchantLocatorTest {

    @Test
    fun `金额上方真实收款方优先于下方付款方式`() {
        val lines = listOf(
            "支付成功",
            "喜茶点单小程序",
            "¥21.60",
            "付款方式：招商银行储蓄卡(1234)",
            "交易单号：4200001234"
        )
        assertEquals("喜茶点单小程序", PaymentMerchantLocator.locateAboveAmount(lines))
    }

    @Test
    fun `金额上方是状态行时继续向上找小程序`() {
        val lines = listOf("喜茶点单小程序", "支付成功", "¥21.60", "付款方式：零钱")
        assertEquals("喜茶点单小程序", PaymentMerchantLocator.locateAboveAmount(lines))
    }

    @Test
    fun `金额上方是储蓄卡时不回填商户`() {
        val lines = listOf("招商银行储蓄卡(1234)", "¥21.60", "支付成功")
        assertNull(PaymentMerchantLocator.locateAboveAmount(lines))
    }

    @Test
    fun `金额上方是财付通时不回填商户`() {
        val lines = listOf("财付通", "¥21.60", "支付成功")
        assertNull(PaymentMerchantLocator.locateAboveAmount(lines))
    }

    @Test
    fun `金额上方收款方标签同行时取标签值`() {
        val lines = listOf("支付成功", "收款方：喜茶", "¥21.60", "付款方式：零钱")
        assertEquals("喜茶", PaymentMerchantLocator.locateAboveAmount(lines))
    }

    @Test
    fun `纯中文与纯英文品牌都能定位`() {
        assertEquals(
            "麦当劳",
            PaymentMerchantLocator.locateAboveAmount(listOf("支付成功", "麦当劳", "实付 ¥23.00"))
        )
        assertEquals(
            "Apple Store",
            PaymentMerchantLocator.locateAboveAmount(listOf("支付成功", "Apple Store", "¥6.00"))
        )
    }

    @Test
    fun `只有金额没有上方文本时不猜`() {
        assertNull(PaymentMerchantLocator.locateAboveAmount(listOf("¥21.60", "付款方式：零钱")))
    }
}
