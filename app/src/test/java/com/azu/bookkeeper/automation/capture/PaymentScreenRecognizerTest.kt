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

    // ---------- v1.3.1 新增 ----------

    @Test
    fun `抖音订单页只有实付也能识别为成功页`() {
        val r = PaymentScreenRecognizer.classify(
            PaymentScreenRecognizer.DOUYIN,
            "com.ss.android.ugc.aweme.order.OrderDetailUI",
            "订单详情\n订单号 692345678901\n实付 ¥23.00\n商家：麦当劳\n交易完成"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertTrue(r.reason.startsWith("douyin"))
    }

    @Test
    fun `抖音收银台有实付但不是订单上下文不判成功`() {
        // 类名含 Cashier → 付款中间态，绝不能因为出现「实付」就自动入账
        val r = PaymentScreenRecognizer.classify(
            PaymentScreenRecognizer.DOUYIN,
            "com.ss.android.ugc.aweme.pay.CashierUI",
            "确认支付\n实付 ¥23.00\n请选择支付方式"
        )
        assertEquals(PaymentScreen.ENTRY, r.screen)
    }

    @Test
    fun `抖音订单页补齐金额与商户标签`() {
        val refined = PaymentScreenRecognizer.refineDouyinText(
            "订单详情\n实付 ¥23.00\n商家：麦当劳\n交易完成"
        )
        assertTrue(refined.contains("金额：23.00"))
        assertTrue(refined.contains("商户：麦当劳"))
    }

    @Test
    fun `抖音收银台文本不被补标签`() {
        val raw = "确认支付\n实付 ¥23.00\n请选择支付方式"
        assertEquals(raw, PaymentScreenRecognizer.refineDouyinText(raw))
    }

    @Test
    fun `账单详情含付款方式仍判 DETAIL`() {
        // v1.3.0 的顺序会先命中「付款方式」→ ENTRY 并被丢弃，重复检测永远跑不到
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "账单详情\n2026-11-27 12:30\n付款方式：零钱\n商户：星巴克"
        )
        assertEquals(PaymentScreen.DETAIL, r.screen)
    }

    @Test
    fun `微信账单详情也补齐结构化标签`() {
        val refined = PaymentScreenRecognizer.refineWeChatText(
            "账单详情\n2026-11-27 12:30\n¥23.00\n星巴克\n付款方式：零钱"
        )
        assertTrue(refined.contains("金额：23.00"))
        assertTrue(refined.contains("商户：星巴克"))
    }

    // ---------- v1.3.6：转账 / 收款方向 ----------

    @Test
    fun `微信接收转账成功记收入`() {
        // 微信收款成功后写的是「已收钱」，旧词表只有「已收款」，所以永远识别不到
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "微信转账\n¥100.00\n已收钱\n已存入零钱"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.INCOME, r.direction)
        assertEquals(PaymentScene.WECHAT_TRANSFER_IN, r.scene)
    }

    @Test
    fun `收款文案被拆成两行也能识别`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "转账\n¥88.00\n已\n收钱"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.INCOME, r.direction)
    }

    @Test
    fun `微信转出成功记支出`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "转账成功\n¥100.00\n已存入对方零钱"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.EXPENSE, r.direction)
        assertEquals(PaymentScene.WECHAT_TRANSFER_OUT, r.scene)
    }

    @Test
    fun `待确认收款是中间态不能记账`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "微信转账\n¥100.00\n待确认收款\n1天内未确认，将退还给对方"
        )
        assertEquals(PaymentScreen.ENTRY, r.screen)
        assertEquals(BillDirection.NEUTRAL, r.direction)
    }

    @Test
    fun `支付宝转账成功记支出`() {
        val r = PaymentScreenRecognizer.classify(
            alipay, "", "转账成功\n¥66.00\n收款方：小李\n交易单号 20261128"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.EXPENSE, r.direction)
    }

    @Test
    fun `转账结果页类名可识别`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "com.tencent.mm.plugin.transfer.ui.TransferResultUI", ""
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
    }

    // ---------- v1.3.8：红包四场景 / 聊天页排除 / 提现 ----------

    @Test
    fun `收到红包记收入且场景为红包`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "微信红包\n¥10.00\n已存入零钱\n恭喜发财，大吉大利"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.INCOME, r.direction)
        assertEquals(PaymentScene.WECHAT_RED_PACKET_IN, r.scene)
    }

    @Test
    fun `发出红包记支出且场景为红包`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "红包已发送\n¥100.00\n已存入对方零钱"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.EXPENSE, r.direction)
        assertEquals(PaymentScene.WECHAT_RED_PACKET_OUT, r.scene)
    }

    @Test
    fun `红包详情页类名加已领取也能识别`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI", "红包已领取\n¥5.20"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.INCOME, r.direction)
    }

    @Test
    fun `领券页的已领取不会被当成支付成功`() {
        // v1.3.6 词表里有裸的「已领取」，微信卡包 / 优惠券页会被记成支付成功
        val r = PaymentScreenRecognizer.classify(wechat, "", "卡券\n已领取\n星巴克买一送一")
        assertFalse(r.screen == PaymentScreen.SUCCESS)
    }

    @Test
    fun `对方已收款是转出方看到的提示应记支出`() {
        // v1.3.6 把「对方已收款」放进收入表 → 转出去的钱被记成收入
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "转账成功\n¥100.00\n对方已收款"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.EXPENSE, r.direction)
        assertEquals(PaymentScene.WECHAT_TRANSFER_OUT, r.scene)
    }

    @Test
    fun `聊天页不会被记账`() {
        // 聊天页必有「微信支付」服务号名和转账气泡（已收款），旧逻辑必然误记
        val r = PaymentScreenRecognizer.classify(
            wechat, "com.tencent.mm.ui.chatting.ChattingUI",
            "微信支付\n转账\n¥100.00\n已收款"
        )
        assertEquals(PaymentScreen.UNKNOWN, r.screen)
        assertEquals(0f, r.confidence, 0.0001f)
    }

    @Test
    fun `微信主界面不会被记账`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "com.tencent.mm.ui.LauncherUI", "微信支付\n文件传输助手\n已收款"
        )
        assertEquals(PaymentScreen.UNKNOWN, r.screen)
    }

    @Test
    fun `红包编辑页是中间态不记成功`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI", "¥100.00\n塞钱进红包"
        )
        assertEquals(PaymentScreen.ENTRY, r.screen)
    }

    @Test
    fun `零钱提现属于账户内部划转不计收支`() {
        val r = PaymentScreenRecognizer.classify(
            wechat, "", "零钱提现\n¥500.00\n提现成功\n已到账 招商银行(1234)"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.NEUTRAL, r.direction)
        assertEquals(PaymentScene.WECHAT_WITHDRAW, r.scene)
    }

    @Test
    fun `支付宝收红包记收入`() {
        val r = PaymentScreenRecognizer.classify(
            alipay, "", "红包详情\n¥8.88\n已领取红包"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.INCOME, r.direction)
        assertEquals(PaymentScene.ALIPAY_RED_PACKET_IN, r.scene)
    }

    @Test
    fun `云闪付付款成功记支出`() {
        val r = PaymentScreenRecognizer.classify(
            PaymentScreenRecognizer.UNIONPAY, "", "支付成功\n¥36.00\n收款方：便利蜂"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.EXPENSE, r.direction)
    }

    @Test
    fun `转账与红包有中文备注前缀`() {
        assertEquals("微信转账", PaymentScreenRecognizer.remarkPrefix(PaymentScene.WECHAT_TRANSFER_OUT))
        assertEquals("微信收款", PaymentScreenRecognizer.remarkPrefix(PaymentScene.WECHAT_TRANSFER_IN))
        assertEquals("微信红包", PaymentScreenRecognizer.remarkPrefix(PaymentScene.WECHAT_RED_PACKET_IN))
        assertEquals("", PaymentScreenRecognizer.remarkPrefix(PaymentScene.WECHAT_MERCHANT_PAY))
    }

    // ---------- v1.3.10：商户标签被无障碍拆成两个节点 ----------

    @Test
    fun `账单详情补标签时不会把全称当商户`() {
        val refined = PaymentScreenRecognizer.refineWeChatText(
            "账单详情\n-18.60\n已支付\n商户\n全称\n瑞幸咖啡（北京）有限公司\n收单机构\n财付通支付科技有限公司"
        )
        assertTrue(refined.contains("商户：瑞幸咖啡（北京）有限公司"))
        assertFalse(refined.contains("商户：全称"))
    }

    @Test
    fun `只有残片且无真值时宁可不补商户`() {
        val refined = PaymentScreenRecognizer.refineWeChatText(
            "账单详情\n-7.00\n已支付\n商户\n全称\n收单机构\n财付通支付科技有限公司"
        )
        assertFalse(refined.contains("商户："))
    }

    // ---------- v1.3.11：交易时间与收款成功页 ----------

    @Test
    fun `收款成功页识别为收入`() {
        val text = """
            你已收款，资金已存入零钱
            ¥100.00
            零钱余额
            转账时间    2026年11月28日 10:55:25
            收款时间    2026年11月28日 10:55:38
            账单详情
        """.trimIndent()
        val r = PaymentScreenRecognizer.classify(wechat, "com.tencent.mm.plugin.remittance.ui.RemittanceResultUI", text)
        assertEquals(PaymentScreen.SUCCESS, r.screen)
        assertEquals(BillDirection.INCOME, r.direction)
        assertEquals(PaymentScene.WECHAT_TRANSFER_IN, r.scene)
    }

    @Test
    fun `交易时间优先取带标签的行`() {
        val text = """
            你已收款，资金已存入零钱
            ¥100.00
            转账时间    2026年11月28日 10:55:25
            收款时间    2026年11月28日 10:55:38
        """.trimIndent()
        assertEquals("2026年11月28日 10:55:25", PaymentScreenRecognizer.transactionTimeKey(text))
    }

    @Test
    fun `苹果账单详情能抽到支付时间`() {
        val text = """
            Apple
            -16.00
            当前状态    支付成功
            支付时间    2026年11月15日 00:00:07
            交易单号    4200003217202611151634196683
        """.trimIndent()
        assertEquals("2026年11月15日 00:00:07", PaymentScreenRecognizer.transactionTimeKey(text))
    }

    @Test
    fun `没有时间字段时交易时间为空`() {
        assertEquals("", PaymentScreenRecognizer.transactionTimeKey("支付成功\n¥9.90\n收款方：便利店"))
    }

    // ---------- v1.3.14：抖音订单页按页面时间判重 ----------

    @Test
    fun `抖音订单页能抽到下单时间`() {
        val text = """
            订单详情
            实付 ¥59.90
            订单编号 6923456789012345678
            下单时间 2026-11-28 10:55:00
        """.trimIndent()
        assertEquals("2026-11-28 10:55:00", PaymentScreenRecognizer.transactionTimeKey(text))
    }

    @Test
    fun `抖音订单页缺时间时交易时间为空`() {
        val text = """
            订单详情
            实付 ¥59.90
            订单编号 6923456789012345678
        """.trimIndent()
        assertEquals("", PaymentScreenRecognizer.transactionTimeKey(text))
    }

    // ---------- v1.3.18：采集是否够用的判据（交易证据） ----------

    @Test
    fun `只有无关文本时没有交易证据`() {
        // 微信自绘页面根节点的真实形态：几行标题/按钮，既不稀疏也没有金额
        val text = "返回\n支付\n完成\n更多"
        assertFalse(PaymentScreenRecognizer.hasTransactionSignal(text))
        assertFalse(PaymentScreenRecognizer.hasTransactionSignal(""))
    }

    @Test
    fun `有金额就有交易证据`() {
        assertTrue(PaymentScreenRecognizer.hasTransactionSignal("¥23.00"))
        assertTrue(PaymentScreenRecognizer.hasTransactionSignal("实付\n39.90"))
    }

    @Test
    fun `有凭据字段也算交易证据`() {
        assertTrue(PaymentScreenRecognizer.hasTransactionSignal("交易单号\n4200001234"))
        assertTrue(PaymentScreenRecognizer.hasTransactionSignal("账单详情\n商品说明"))
    }

    // ---------- v1.3.19：收入判定（收款被记成支出的修复） ----------

    @Test
    fun `收款到账页面判为收入`() {
        assertTrue(PaymentScreenRecognizer.looksLikeIncome("微信收款\n到账金额 21.60"))
        assertTrue(PaymentScreenRecognizer.looksLikeIncome("收款到账通知\n¥21.60"))
        assertTrue(PaymentScreenRecognizer.looksLikeIncome("二维码收款成功"))
    }

    @Test
    fun `明确支出信号优先于收款词`() {
        // 支出页里也会出现「收款方」，不能被判成收入
        assertFalse(PaymentScreenRecognizer.looksLikeIncome("收款方 某某商店\n付款成功 ¥21.60"))
        assertFalse(PaymentScreenRecognizer.looksLikeIncome("已支付 ¥21.60"))
        assertFalse(PaymentScreenRecognizer.looksLikeIncome("转账已发出 已存入对方零钱"))
    }

    @Test
    fun `空白文本不算收入`() {
        assertFalse(PaymentScreenRecognizer.looksLikeIncome(""))
    }

    // ---------- v1.3.20：宁可漏记不可错记 ----------

    @Test
    fun `抖音订单列表页只有商家和金额时不判成功`() {
        val r = PaymentScreenRecognizer.classify(
            PaymentScreenRecognizer.DOUYIN,
            "com.ss.android.ugc.aweme.order.OrderListActivity",
            "我的订单\n麦当劳\n实付 ¥23.00\n麦当劳\n实付 ¥45.00\n待评价"
        )
        assertEquals(PaymentScreen.UNKNOWN, r.screen)
    }

    @Test
    fun `微信聊天页结构特征不参与判定`() {
        val r = PaymentScreenRecognizer.classify(
            PaymentScreenRecognizer.WECHAT,
            "",
            "支付成功\n¥23.00\n按住说话\n发消息"
        )
        assertEquals(PaymentScreen.UNKNOWN, r.screen)
    }

    @Test
    fun `中间态词与成功词共存时按成功页处理`() {
        val r = PaymentScreenRecognizer.classify(
            PaymentScreenRecognizer.DOUYIN,
            "com.ss.android.ugc.aweme.order.OrderDetailUI",
            "订单详情\n订单号 692345678901\n实付 ¥23.00\n交易完成\n待收货"
        )
        assertEquals(PaymentScreen.SUCCESS, r.screen)
    }
}
