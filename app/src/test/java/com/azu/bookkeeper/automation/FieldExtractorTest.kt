package com.azu.bookkeeper.automation

import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.core.DateTimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 字段抽取单元测试（通知/读屏文本源，PRD AC-1）。
 */
class FieldExtractorTest {

    private val extractor = FieldExtractor()

    @Test
    fun `抽取带符号金额与商家`() {
        val fields = extractor.extract("支付成功\n金额：¥1,234.56\n商户名称：星巴克")
        assertEquals(123_456L, fields.amountFen)
        assertEquals("星巴克", fields.merchant)
    }

    @Test
    fun `抽取元单位金额`() {
        val fields = extractor.extract("交易成功 1234.56元 收款方：瑞幸")
        assertEquals(123_456L, fields.amountFen)
        assertEquals("瑞幸", fields.merchant)
    }

    @Test
    fun `抽取账户线索`() {
        val fields = extractor.extract("付款方式：招商银行(1234)\n金额：88.00\n商户：便利店")
        assertEquals("招商银行(1234)", fields.accountHint)
        assertEquals(8_800L, fields.amountFen)
    }

    @Test
    fun `独立成行的时间可被解析`() {
        val fields = extractor.extract("支付成功\n2026-11-22 12:30:45\n金额：12.00")
        assertNotNull(fields.billTime)
        assertEquals(45, DateTimeUtils.localDateTime(fields.billTime!!).second)
    }

    @Test
    fun `无时间时回落到捕获时间`() {
        val fallback = 1_700_000_000_000L
        val fields = extractor.extract("支付成功\n金额：12.00\n商户：小店", fallbackTime = fallback)
        assertEquals(fallback, fields.billTime)
    }

    @Test
    fun `必填字段缺失时 hasRequiredFields 为 false`() {
        assertNull(extractor.extract("啥也没有").amountFen)
        assertEquals(false, extractor.extract("金额：12.00").hasRequiredFields)
        assertEquals(true, extractor.extract("金额：12.00\n商户：小店\n2026-11-22", 1L).hasRequiredFields)
    }

    // ---------- 缺陷复现 ----------

    @Test
    fun `BUG复现 带标签的时间行应可解析`() {
        // 当前 extractTime 对整行调用 parseFlexible，带「交易时间：」前缀必然失败，
        // 只能回落到到达时间 → 双路去重键漂移 + 时间因子降级。
        val fields = extractor.extract("交易时间：2026-11-22 12:30:45\n金额：12.00\n商户：小店")
        assertNotNull("应从带标签的行提取正文时间，而非静默回落", fields.billTime)
        assertEquals(45, DateTimeUtils.localDateTime(fields.billTime!!).second)
    }
}
