package com.azu.bookkeeper.automation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 受工单交付要求 5 约束：改动后自测——待支付页 / 选择页 / 发起页均不得判为可记账。
 *
 * 本测试**只读** PaymentScreenRecognizer 的现有判定，不修改任何转账 / 红包词表或状态机
 * （工单规则五冻结声明）。冻结文件的 SHA-256 另由静态脚本核对。
 */
class NonFinalPageGuardTest {

    private fun classify(text: String, cls: String = "") =
        PaymentScreenRecognizer.classify(PaymentScreenRecognizer.WECHAT, cls, text)

    @Test
    fun `待支付页判为ENTRY而非成功`() {
        val r = classify("确认支付\n待支付 ¥15.00\n选择付款方式")
        assertEquals(PaymentScreen.ENTRY, r.screen)
    }

    @Test
    fun `选择支付方式页判为ENTRY`() {
        val r = classify("选择付款方式\n零钱\n银行卡\n确认付款")
        assertEquals(PaymentScreen.ENTRY, r.screen)
    }

    @Test
    fun `收银台发起页判为ENTRY`() {
        val r = classify("向商家付款\n付款码\n请输入支付密码")
        assertEquals(PaymentScreen.ENTRY, r.screen)
    }

    @Test
    fun `这些页面都不是SUCCESS`() {
        listOf(
            "待支付", "选择付款方式", "向商家付款", "确认支付", "待确认收款"
        ).forEach { hint ->
            val r = classify("商户 $hint\n¥15.00")
            assertNotEquals("页面「$hint」不应判为 SUCCESS", PaymentScreen.SUCCESS, r.screen)
        }
    }
}
