package com.azu.bookkeeper.automation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 支付页面识别单测：覆盖「打开付款界面」「微信支付成功页」「微信空节点类名」等
 * 真机上最容易漏掉的场景，以及微信结果页金额/商户标签化。
 */
class PaymentScreenRecognizerTest {

    private val wechat = PaymentScreenRecognizer.WECHAT
    private val alipay = PaymentScreenRecognizer.ALIPAY

    @Test
    fun `微信付款码页文案识别为付款界面`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "收付款\n付款码\n向商家付款\n使用密码"
        )
        assertEquals(PaymentScreen.ENTRY, r.screen)
    }

    @Test
    fun `微信收银台 Activity 识别为付款界面`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI", ""
        )
        assertEquals(PaymentScreen.ENTRY, r.screen)
        assertTrue(r.reason.startsWith("className"))
    }

    @Test
    fun `微信支付结果页只有类名也能识别为成功页`() {
        // 花有谱实测：部分微信成功页在普通无障碍服务下只返回空根节点
        val r = PaymentScreenRecognizer.classify(
            wechat, "com.tencent.mm.plugin.wallet.pay.ui.WalletPayResultUI", ""
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertTrue(PaymentScreenRecognizer.looksLikeResultClass(
            "com.tencent.mm.plugin.wallet.pay.ui.WalletPayResultUI"
        ))
    }

    @Test
    fun `微信已完成与微信支付文案都算成功页`() {
        assertEquals(
            PaymentScreen.SUCCESS,
            PaymentScreenRecognizer.classify(wechat, "", "微信支付\n已完成").screen
        )
        assertEquals(
            PaymentScreen.SUCCESS,
            PaymentScreenRecognizer.classify(wechat, "", "已存入对方零钱").screen
        )
    }

    @Test
    fun `支付宝支付成功文案识别为成功页`() {
        assertEquals(
            PaymentScreen.SUCCESS,
            PaymentScreenRecognizer.classify(alipay, "", "付款成功\n¥12.50").screen
        )
        assertTrue(PaymentScreenRecognizer.isSuccessText("交易成功"))
    }

    @Test
    fun `确认支付与输密码是中间态不判成功`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "确认支付\n请输入支付密码\n¥88.00"
        )
        assertEquals(PaymentScreen.ENTRY, r.screen)
        assertFalse(PaymentScreenRecognizer.isSuccessText("确认支付"))
    }

    @Test
    fun `支付失败不会被误判为成功`() {
        val r = PaymentScreenRecognizer.classify(wechat, "", "支付失败\n请重试")
        assertFalse(r.screen == PaymentScreen.SUCCESS)
        assertFalse(PaymentScreenRecognizer.isSuccessText("支付失败"))
    }

    @Test
    fun `账单详情页单独识别且不记账`() {
        assertEquals(
            PaymentScreen.DETAIL,
            PaymentScreenRecognizer.classify(wechat, "", "账单详情\n2026-11-27").screen
        )
    }

    @Test
    fun `微信子进程包名归一化后仍命中支付应用白名单`() {
        assertEquals("com.tencent.mm", PaymentScreenRecognizer.normalizePackage("com.tencent.mm:tools"))
        assertTrue(PaymentScreenRecognizer.isPaymentPackage("com.tencent.mm:appbrand0"))
        assertFalse(PaymentScreenRecognizer.isPaymentPackage("com.example.chat"))
    }

    @Test
    fun `微信结果页金额与商户被补成带标签的行`() {
        val refined = PaymentScreenRecognizer.refineWeChatText(
            "支付成功\n¥23.00\n麦当劳\n完成"
        )
        assertTrue(refined.contains("金额：23.00"))
        assertTrue(refined.contains("商户：麦当劳"))
        assertTrue(refined.contains("支付成功"))
    }

    @Test
    fun `微信结果页带元后缀的金额也能补标签`() {
        val refined = PaymentScreenRecognizer.refineWeChatText(
            "已完成\n1,280.00元\n星巴克"
        )
        assertTrue(refined.contains("金额：1,280.00"))
        assertTrue(refined.contains("商户：星巴克"))
    }

    @Test
    fun `没有成功文案时原文保持不变`() {
        val raw = "收银台\n请选择支付方式"
        assertEquals(raw, PaymentScreenRecognizer.refineWeChatText(raw))
    }

    @Test
    fun `非支付页面返回 UNKNOWN`() {
        val r = PaymentScreenRecognizer.classify(wechat, "", "聊天列表\n联系人")
        assertEquals(PaymentScreen.UNKNOWN, r.screen)
        assertTrue(r.confidence == 0f)
    }
}
