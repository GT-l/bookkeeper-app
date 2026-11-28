package com.azu.bookkeeper.automation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.23 · 受 [问题一-全场景触发] 约束：
 * 覆盖用户列出的微信支付场景（扫码 / 公众号 / 小程序 / 转账 / 红包 / 单笔详情），
 * 以及必须拒绝的中间态、聊天页、退款页。
 */
class WechatPayFinalPageResolverTest {

    @Test
    fun `扫码支付成功页触发`() {
        val text = "支付成功\n¥21.60\n喜茶点单小程序\n付款方式：零钱"
        assertTrue(WechatPayFinalPageResolver.isFinalPayPage(text))
        assertEquals(BillDirection.EXPENSE, WechatPayFinalPageResolver.direction(text))
        assertEquals(PaymentScene.WECHAT_MERCHANT_PAY, WechatPayFinalPageResolver.scene(text))
    }

    @Test
    fun `小程序与公众号支付成功页触发`() {
        assertTrue(
            WechatPayFinalPageResolver.isFinalPayPage("小程序支付成功\n实付 ¥18.00\n喜茶")
        )
        assertTrue(
            WechatPayFinalPageResolver.isFinalPayPage("微信支付\n支付完成\n¥35.00\n商户 麦当劳")
        )
    }

    @Test
    fun `转账发出与收到转账都触发且方向正确`() {
        val out = "转账已发出\n¥100.00\n已存入对方零钱"
        assertTrue(WechatPayFinalPageResolver.isFinalPayPage(out))
        assertEquals(BillDirection.EXPENSE, WechatPayFinalPageResolver.direction(out))
        assertEquals(PaymentScene.WECHAT_TRANSFER_OUT, WechatPayFinalPageResolver.scene(out))

        val income = "转账成功\n已收钱\n¥100.00"
        assertTrue(WechatPayFinalPageResolver.isFinalPayPage(income))
        assertEquals(BillDirection.INCOME, WechatPayFinalPageResolver.direction(income))
        assertEquals(PaymentScene.WECHAT_TRANSFER_IN, WechatPayFinalPageResolver.scene(income))
    }

    @Test
    fun `红包收发都触发且方向正确`() {
        val sent = "红包已发出\n¥50.00"
        assertTrue(WechatPayFinalPageResolver.isFinalPayPage(sent))
        assertEquals(BillDirection.EXPENSE, WechatPayFinalPageResolver.direction(sent))
        assertEquals(PaymentScene.WECHAT_RED_PACKET_OUT, WechatPayFinalPageResolver.scene(sent))

        val received = "红包已领取\n¥50.00\n已存入零钱"
        assertTrue(WechatPayFinalPageResolver.isFinalPayPage(received))
        assertEquals(BillDirection.INCOME, WechatPayFinalPageResolver.direction(received))
        assertEquals(PaymentScene.WECHAT_RED_PACKET_IN, WechatPayFinalPageResolver.scene(received))
    }

    @Test
    fun `账单单笔详情触发`() {
        val text = "账单详情\n¥21.60\n交易单号 4200001234"
        assertTrue(WechatPayFinalPageResolver.isFinalPayPage(text))
        assertEquals(PaymentScene.WECHAT_BILL_DETAIL, WechatPayFinalPageResolver.scene(text))
    }

    @Test
    fun `待支付与付款码页不触发`() {
        assertFalse(WechatPayFinalPageResolver.isFinalPayPage("待支付\n¥21.60\n确认支付"))
        assertFalse(WechatPayFinalPageResolver.isFinalPayPage("付款码\n向商家付款"))
        assertFalse(WechatPayFinalPageResolver.isFinalPayPage("选择付款方式\n¥21.60\n立即支付"))
    }

    @Test
    fun `聊天页气泡里的成功词不触发`() {
        assertFalse(WechatPayFinalPageResolver.isFinalPayPage("支付成功\n¥21.60\n按住说话"))
    }

    @Test
    fun `退款页与只有金额都不触发`() {
        assertFalse(WechatPayFinalPageResolver.isFinalPayPage("支付成功\n¥21.60\n已退款"))
        assertFalse(WechatPayFinalPageResolver.isFinalPayPage("¥21.60\n收款方 喜茶"))
    }
}
