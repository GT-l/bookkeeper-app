package com.azu.bookkeeper.automation

import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.automation.receiver.BankAccountMapper
import com.azu.bookkeeper.automation.receiver.BankSmsWhitelist
import com.azu.bookkeeper.automation.receiver.SmsCaptureParser
import com.azu.bookkeeper.automation.receiver.SmsRuleSet
import com.azu.bookkeeper.automation.receiver.TimeSource
import com.azu.bookkeeper.core.DateTimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 银行短信解析单元测试（增量 AC-11 / AC-16 / AC-17）。
 * 覆盖金额格式、排除规则、时间优先级、抖音打标与隐私摘要。
 */
class SmsCaptureParserTest {

    private val whitelist = BankSmsWhitelist()
    private val rules = SmsRuleSet()
    private val parser = SmsCaptureParser(whitelist, rules, BankAccountMapper(), DouyinBusinessTagger())

    @Test
    fun `白名单短号与字母发件人`() {
        assertEquals("ICBC", whitelist.matches("95588")?.code)
        assertEquals("CMB", whitelist.matches("95555")?.code)
        // 字母发件人需含「银行/bank/credit/card」才走正则兜底；纯 "ICBC" 不在白名单。
        assertEquals("ICBCBANK", whitelist.matches("ICBCBANK")?.code)
        assertNull(whitelist.matches("ICBC"))
        assertNull(whitelist.matches("10086"))
        assertNull(whitelist.matches(""))
    }

    @Test
    fun `金额正则兼容人民币符号与千分位`() {
        val body = "支出人民币¥1,234.56"
        assertEquals("1,234.56", rules.amountRegex.find(body)?.groupValues?.get(1))
        assertEquals(123_456L, parser.parse("95588", body, 1L, emptyList())?.amountFen)
    }

    @Test
    fun `金额正则兼容1234点56元`() {
        val body = "支出1234.56元"
        assertEquals("1234.56", rules.amountRegex.find(body)?.groupValues?.get(1))
        assertEquals(123_456L, parser.parse("95588", body, 1L, emptyList())?.amountFen)
    }

    @Test
    fun `排除规则 验证码营销退款不建单`() {
        assertFalse(rules.filterBusinessType("您的验证码是123456，请勿泄露"))
        assertFalse(rules.filterBusinessType("营销活动 支出优惠"))
        assertFalse(rules.filterBusinessType("退款1234.56元已返还"))
        assertFalse(rules.filterBusinessType("账户收入500.00元"))
    }

    @Test
    fun `排除规则 真实扣款不被误杀`() {
        assertTrue(rules.filterBusinessType("您尾号1234储蓄卡支出1234.56元，交易成功"))
        assertTrue(rules.filterBusinessType("支付宝支付成功，商户名称：星巴克"))
    }

    @Test
    fun `卡尾脱敏只留前四后四`() {
        assertEquals("6222****0123", rules.maskCardTail("卡号6222021234567890123发生交易"))
    }

    @Test
    fun `抖音关键词打标`() {
        assertEquals(
            BusinessTag.DOUYIN,
            DouyinBusinessTagger().tag("", "抖音商城支出1234.56元")
        )
        assertEquals(
            BusinessTag.DOUYIN,
            DouyinBusinessTagger().tag("com.ss.android.ugc.aweme", "")
        )
        assertEquals(BusinessTag.NONE, DouyinBusinessTagger().tag("com.tencent.mm", "支付成功"))
    }

    @Test
    fun `无正文时间时回落到到达时间并降低parseHint`() {
        val arrival = 1_700_000_000_000L
        val parsed = parser.parse(
            "95588",
            "【工商银行】您尾号1234储蓄卡支出人民币1234.56元，商户名称：星巴克",
            arrival,
            emptyList()
        )!!
        assertEquals(123_456L, parsed.amountFen)
        assertEquals("星巴克", parsed.merchant)
        assertEquals(arrival, parsed.billTime)
        assertEquals(TimeSource.SMS_ARRIVAL, parsed.timeSource)
        // 1.0 - 0.15(仅到达时间) - 0.05(无账户命中) = 0.80
        assertEquals(0.80f, parsed.parseHint, 0.0001f)
    }

    @Test
    fun `非白名单发件人直接丢弃`() {
        assertNull(parser.parse("10086", "支出1234.56元", 1L, emptyList()))
    }

    @Test
    fun `摘要不含短信原文`() {
        val raw = "【工商银行】您尾号1234储蓄卡支出人民币1234.56元，商户名称：星巴克"
        val parsed = parser.parse("95588", raw, 1L, emptyList())!!
        val summary = parser.toSummary(parsed, "ICBC")
        val serialized = summary.toString()
        assertFalse(serialized.contains("【工商银行】"))
        assertFalse(serialized.contains("您尾号"))
        assertEquals(123_456L, summary.amountFen)
        assertEquals("星巴克", summary.merchant)
    }

    // ---------- 缺陷复现：当前源码预期失败（抛 IndexOutOfBoundsException / 时间错配） ----------

    @Test
    fun `BUG复现 完整日期时间应从正文提取`() {
        val bodyTime = DateTimeUtils.parse("2026-11-22 12:30", "yyyy-MM-dd HH:mm")!!
        val parsed = parser.parse(
            "95588",
            "【工商银行】您尾号1234储蓄卡于2026-11-22 12:30支出人民币1234.56元，商户名称：星巴克",
            bodyTime + 3_600_000L, // 到达时间故意晚 1 小时
            emptyList()
        )
        assertEquals("正文时间优先级应高于到达时间", TimeSource.BODY, parsed?.timeSource)
        assertEquals("应按正文时间建单", bodyTime, parsed?.billTime)
    }

    @Test
    fun `BUG复现 中文月日时间应从正文提取`() {
        val parsed = parser.parse(
            "95588",
            "【工商银行】您尾号1234储蓄卡于11月22日 12:30支出人民币1234.56元，商户名称：星巴克",
            1_700_000_000_000L,
            emptyList()
        )
        assertEquals(TimeSource.BODY, parsed?.timeSource)
        assertFalse("不得静默回落到到达时间", parsed?.timeSource == TimeSource.SMS_ARRIVAL)
    }

    // ---------- F-11 回归：短信金额不得误取「余额」 ----------

    @Test
    fun `F11回归 余额在前本次支出在后应取支出金额`() {
        val body = "您尾号1234储蓄卡当前余额8888.88元，本次支出1234.56元"
        assertEquals("余额片段须被剥离，金额取标签后的 1234.56", 123_456L, parser.parse("95588", body, 1L, emptyList())?.amountFen)
    }

    @Test
    fun `F11回归 支出金额在前余额在后仍取支出金额`() {
        val body = "您尾号1234储蓄卡支出人民币1234.56元，账户余额8888.88元"
        assertEquals(123_456L, parser.parse("95588", body, 1L, emptyList())?.amountFen)
    }

    @Test
    fun `F11回归 仅有余额无真实交易金额时金额为零由弹窗兜底`() {
        // 含「支出」通过业务过滤，但唯一小数金额属于「余额」片段 → 不应建出 8888.88 的错误账单
        val body = "您尾号1234卡支出后余额8888.88元"
        assertEquals(0L, parser.parse("95588", body, 1L, emptyList())?.amountFen)
    }

    @Test
    fun `F11回归 新增规则字段可用且amountRegex保持不变`() {
        assertEquals("1234.56", rules.labelledAmountRegex.find("本次支出1234.56元")?.groupValues?.get(1))
        assertTrue(rules.balanceSegmentRegex.containsMatchIn("账户余额8888.88元"))
        // 既有 12 条用例直接断言 amountRegex，本字段必须保持不变
        assertEquals("1,234.56", rules.amountRegex.find("支出人民币¥1,234.56")?.groupValues?.get(1))
    }
}
