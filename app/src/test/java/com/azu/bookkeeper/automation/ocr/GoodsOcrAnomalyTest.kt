package com.azu.bookkeeper.automation.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 受 [规则二] 约束：商品名 OCR 误识别提醒。
 * 样本逐字取自工单「验证样本」（原文行1 / 原文行2 / 拼接结果）。
 */
class GoodsOcrAnomalyTest {

    @Test
    fun `样本一 音译乱码命中条件A`() {
        // 原文行1=艾尔普兹｜原文行2=无线 → 拼接=艾尔普兹无线 → 条件A命中 → 弹窗
        assertTrue(GoodsOcrAnomaly.detect("艾尔普兹无线"))
        assertEquals(GoodsOcrAnomaly.WARNING, "⚠️ 识别失败：商品名可能被误识别，建议人工核对")
    }

    @Test
    fun `样本二 中英混合有中文主体不弹窗`() {
        // 原文行1=苹果｜原文行2=AirPods Pro → 拼接=苹果AirPods Pro → 有中文主体 → 不弹窗
        assertFalse(GoodsOcrAnomaly.detect("苹果AirPods Pro"))
    }

    @Test
    fun `样本三 英文被拆碎混入汉字命中条件C`() {
        // 原文行1=A艾尔｜原文行2=P兹 → 拼接=A艾尔P兹 → 条件C命中 → 弹窗
        assertTrue(GoodsOcrAnomaly.detect("A艾尔P兹"))
    }

    @Test
    fun `样本四 有语义的纯中文不弹窗`() {
        // 原文行1=苹果｜原文行2=无线｜原文行3=耳机 → 拼接=苹果无线耳机 → 有语义 → 不弹窗
        assertFalse(GoodsOcrAnomaly.detect("苹果无线耳机"))
    }

    @Test
    fun `纯英文白名单不弹窗`() {
        assertFalse(GoodsOcrAnomaly.detect("AirPods Pro"))
        assertFalse(GoodsOcrAnomaly.detect("Nike Air Force"))
    }

    @Test
    fun `空商品名不弹窗`() {
        assertFalse(GoodsOcrAnomaly.detect(""))
        assertFalse(GoodsOcrAnomaly.detect("   "))
    }

    @Test
    fun `分析器在待支付页不置提醒`() {
        // 受 [规则二] 防错边界：待支付 / 未支付 → 不记账、不弹窗
        val pendingText = """
            待支付
            商品说明 艾尔普兹无线
            财付通
        """.trimIndent()
        assertFalse(PaymentTextAnalyzer().analyze(pendingText).goodsWarning)
    }

    @Test
    fun `分析器命中时置提醒但不改商品名`() {
        val text = """
            支付成功
            实付 ¥99.00
            商品说明 艾尔普兹无线
        """.trimIndent()
        val r = PaymentTextAnalyzer().analyze(text)
        assertTrue(r.goodsWarning)
        assertEquals("艾尔普兹无线", r.goods)
    }
}
