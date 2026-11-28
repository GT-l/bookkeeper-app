package com.azu.bookkeeper.automation.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `购买内容 - 商品说明同行且去掉商户前缀`() {
        val text = """
            支付成功
            ¥32.00
            收款方 瑞幸咖啡
            商品说明 瑞幸咖啡-生椰拿铁（大杯）
            支付方式 零钱
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals("瑞幸咖啡", r.merchant)
        assertEquals("生椰拿铁（大杯）", r.goods)
    }

    @Test
    fun `购买内容 - 标签独占一行时取下一行`() {
        val text = """
            交易成功
            ¥15.00
            商户 全家便利店
            商品说明
            关东煮套餐
            交易时间 2026-11-26 08:12
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals("全家便利店", r.merchant)
        assertEquals("关东煮套餐", r.goods)
    }

    @Test
    fun `购买内容 - 商品总价不能被当成商品名`() {
        val text = """
            交易成功
            商品总价 ¥9.90
            运费 ¥5.00
            实付款 ¥14.90
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(1490L, r.amountFen)
        assertEquals("", r.goods)
    }

    @Test
    fun `购买内容 - 识别不到时为空串且不影响金额商户`() {
        val text = """
            支付成功
            ¥9.90
            收款方 地铁
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(990L, r.amountFen)
        assertEquals("地铁", r.merchant)
        assertEquals("", r.goods)
    }

    // ---------- v1.3.9：微信账单详情 ----------

    @Test
    fun `账单详情 - 金额是独立纯数字行也能识别`() {
        // 微信金额节点就是「-23.00」：没有 ¥、没有「元」、没有标签
        val text = """
            账单详情
            -23.00
            已支付
            商户全称
            星巴克咖啡有限公司
            收单机构
            财付通支付科技有限公司
            支付方式
            零钱
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(2300L, r.amountFen)
        assertEquals("星巴克咖啡有限公司", r.merchant)
    }

    @Test
    fun `账单详情 - 商户全称独占一行时取下一行而不是全称两字`() {
        val text = """
            账单详情
            ¥15.00
            商户全称
            全家便利店
            收单机构
            财付通支付科技有限公司
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals("全家便利店", r.merchant)
    }

    @Test
    fun `账单详情 - 收单机构不会被当成商户`() {
        val text = """
            账单详情
            -12.50
            已支付
            收单机构
            财付通支付科技有限公司
            支付方式
            零钱
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(1250L, r.amountFen)
        assertEquals("", r.merchant)
    }

    // ---------- v1.3.10：商户标签被拆成两个节点 ----------

    @Test
    fun `账单详情 - 商户与全称被拆成两个节点时跳过残片取真值`() {
        // 微信无障碍把「商户全称」拆成「商户」「全称」两个节点（用户实际遇到的现场）
        val text = """
            账单详情
            -18.60
            已支付
            商户
            全称
            瑞幸咖啡（北京）有限公司
            收单机构
            财付通支付科技有限公司
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(1860L, r.amountFen)
        assertEquals("瑞幸咖啡（北京）有限公司", r.merchant)
    }

    @Test
    fun `账单详情 - 只有标签残片时宁可留空也不写全称`() {
        val text = """
            账单详情
            -7.00
            已支付
            商户
            全称
            收单机构
            财付通支付科技有限公司
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(700L, r.amountFen)
        assertNotEquals("全称", r.merchant)
        assertEquals("", r.merchant)
    }

    @Test
    fun `标签残片不会被同行正则当成商户值`() {
        // MERCHANT_LABELLED 对「商户全称」独占一行会回溯成「商户」+「全称」
        assertEquals("", analyzer.analyze("支付成功\n¥9.90\n商户全称").merchant)
        assertEquals("", analyzer.analyze("支付成功\n¥9.90\n商户简称：").merchant)
    }

    // ---------- v1.3.11：微信 Apple 账单详情 ----------

    @Test
    fun `账单详情 - 英文商户名被拆成两行时拼接完整`() {
        val text = """
            Apple
            -16.00
            当前状态
            支付成功
            支付时间
            2026年11月15日 00:00:07
            商品
            apple.com/bill/MLF37524K0a0
            商户全称
            Apple Distribution International
            limited
            收单机构
            财付通支付科技有限公司
            支付方式
            零钱
            交易单号
            4200003217202611151634196683
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(1600L, r.amountFen)
        assertEquals("Apple Distribution International limited", r.merchant)
        assertEquals("", r.goods)
    }

    @Test
    fun `购买内容 - 账单链接不会被当成商品名`() {
        val r = analyzer.analyze("支付成功\n¥9.90\n商品说明\napple.com/bill/MLF37524K0a0")
        assertEquals("", r.goods)
    }

    @Test
    fun `优惠金额不会被当成实付金额`() {
        val text = """
            账单详情
            -30.00
            已支付
            优惠
            -5.00
            余额
            88.00
        """.trimIndent()
        val r = analyzer.analyze(text)
        assertEquals(3000L, r.amountFen)
    }
}
