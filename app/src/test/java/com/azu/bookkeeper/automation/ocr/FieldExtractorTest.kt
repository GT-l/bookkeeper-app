package com.azu.bookkeeper.automation.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.19：金额抽取的合理性校验。
 *
 * 真机事故（realme RMX3888）：微信收款页被自动记成 **111845.00 元**，实际是 21.60。
 * 原因是 `extractAmount` 取第一个匹配就返回，页面上的累计额 / 流水号都能被当成金额。
 */
class FieldExtractorTest {

    private val extractor = FieldExtractor()

    @Test
    fun `正常金额照常抽取`() {
        assertEquals(2_160L, extractor.extract("微信支付\n¥21.60").amountFen)
        assertEquals(5_990L, extractor.extract("订单详情\n实付 ¥59.90\n订单编号 6923456789").amountFen)
    }

    @Test
    fun `超过十万元的数字不当单笔金额`() {
        val text = "微信收款\n¥111845.00\n收款时间 2026-11-28 16:38"
        assertNull(extractor.extract(text).amountFen)
        // 边界：99,999.99 仍然接受，100,000.00 起拒绝
        assertEquals(9_999_999L, extractor.extract("¥99999.99").amountFen)
        assertNull(extractor.extract("¥100000.00").amountFen)
    }

    @Test
    fun `流水号与卡号行不抽取金额`() {
        assertNull(extractor.extract("交易单号 ¥1118450.00").amountFen)
        assertNull(extractor.extract("银行卡尾号 ¥1234.56").amountFen)
        assertNull(extractor.extract("累计收款 ¥888.88").amountFen)
    }

    @Test
    fun `实付金额优先于小计与优惠`() {
        val text = "商品小计 ¥100.00\n优惠 ¥5.00\n实付 ¥21.60"
        assertEquals(2_160L, extractor.extract(text).amountFen)
        val text2 = "原价 ¥99.00\n合计 ¥21.60"
        assertEquals(2_160L, extractor.extract(text2).amountFen)
    }
}
