package com.azu.bookkeeper.automation

import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.ocr.ConfidenceCalculator
import com.azu.bookkeeper.automation.ocr.ExtractedFields
import com.azu.bookkeeper.automation.ocr.OcrResult
import com.azu.bookkeeper.automation.ocr.RuleHit
import com.azu.bookkeeper.automation.receiver.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 六因子置信度单元测试（PRD 22.8 / 增量 AC-18）。
 *
 * 权重：OCR .30 + 金额 .25 + 时间 .15 + 商家 .10 + 分类 .15 + 账户 .05 = 1.00。
 * 阈值 0.75 由 DataStore 控制，本文件只验证分数计算本身。
 */
class ConfidenceCalculatorTest {

    private val calculator = ConfidenceCalculator()

    private val fullFields = ExtractedFields(
        amountFen = 12_345L,
        merchant = "星巴克",
        billTime = 1_700_000_000_000L,
        accountHint = "微信"
    )
    private val fullHit = RuleHit(categoryId = 7L, accountId = 3L, keyword = "星巴克", matched = true)

    @Test
    fun `六因子权重和等于1`() {
        assertEquals(1.0f, calculator.calculate(null, fullFields, fullHit, textSource = true))
    }

    @Test
    fun `文本源OCR因子取满值`() {
        val asText = calculator.calculate(null, fullFields, fullHit, textSource = true)
        val asImageLowOcr = calculator.calculate(
            OcrResult("", 0.5f), fullFields, fullHit, textSource = false
        )
        assertEquals(1.0f, asText)
        assertEquals(0.85f, asImageLowOcr, 0.0001f)
    }

    @Test
    fun `缺失金额扣025`() {
        val fields = fullFields.copy(amountFen = null)
        assertEquals(0.75f, calculator.calculate(null, fields, fullHit, textSource = true), 0.0001f)
    }

    @Test
    fun `零金额按未命中处理`() {
        val fields = fullFields.copy(amountFen = 0L)
        assertEquals(0.75f, calculator.calculate(null, fields, fullHit, textSource = true), 0.0001f)
    }

    @Test
    fun `缺失时间扣015`() {
        val fields = fullFields.copy(billTime = null)
        assertEquals(0.85f, calculator.calculate(null, fields, fullHit, textSource = true), 0.0001f)
    }

    @Test
    fun `短信到达时间时间因子减半扣0075`() {
        val score = calculator.calculate(
            null, fullFields, fullHit, textSource = true, timeSource = TimeSource.SMS_ARRIVAL
        )
        assertEquals(0.925f, score, 0.0001f)
    }

    @Test
    fun `缺失商家扣010`() {
        val fields = fullFields.copy(merchant = "")
        assertEquals(0.90f, calculator.calculate(null, fields, fullHit, textSource = true), 0.0001f)
    }

    @Test
    fun `规则未命中扣015`() {
        val hit = RuleHit(categoryId = 0L, accountId = 3L, matched = false)
        assertEquals(0.85f, calculator.calculate(null, fullFields, hit, textSource = true), 0.0001f)
    }

    @Test
    fun `账户未命中扣005`() {
        val hit = RuleHit(categoryId = 7L, accountId = 0L, matched = true)
        assertEquals(0.95f, calculator.calculate(null, fullFields, hit, textSource = true), 0.0001f)
    }

    @Test
    fun `阈值边界 075恰好达标`() {
        // sign: 全字段 + 未命中分类 + 无账户 → 1.0 - .15 - .05 = .80；
        // 再叠加短信到达时间 -.075 → .725 < .75（不达标）。
        val below = calculator.calculate(
            null, fullFields, RuleHit(matched = false),
            textSource = true, timeSource = TimeSource.SMS_ARRIVAL
        )
        assertEquals(0.725f, below, 0.0001f)
        assertTrue(below < Constants.THRESHOLD)
    }

    private object Constants {
        const val THRESHOLD = 0.75f
    }

    // ---------- 以下为「缺陷复现」用例：当前源码预期失败，工程师修复后应转绿 ----------

    @Test
    fun `BUG复现 辅助规则命中但无分类时不应拿满分类因子`() {
        // 背景：SeedRuleInitializer 以 priority=90 注入「支付成功」(categoryId=0)，
        // ClassificationRuleEngine.classify 把 matched=true 返回。
        // 期望：无有效 categoryId 时分类因子应为 0（总分 0.85）；
        // 绝不能是满分 1.0 导致无分类账单被自动入账（PRD 22.10「未命中归其他支出并弹窗」）。
        val helperHit = RuleHit(categoryId = 0L, accountId = 3L, keyword = "支付成功", matched = true)
        val score = calculator.calculate(null, fullFields, helperHit, textSource = true)
        assertEquals("无分类命中不应得满分（当前实现错误返回 1.0）", 0.85f, score, 0.0001f)
    }

    @Test
    fun `抖音标签不改变分母 由上游规则命中体现`() {
        // 设计约定：businessTag 不单独加分；抖音种子规则命中后 ruleHit.matched=true，
        // 分数自然达到 1.0（见 ClassificationEngineTest）。
        val withTag = calculator.calculate(
            null, fullFields, fullHit, textSource = true, businessTag = BusinessTag.DOUYIN
        )
        assertEquals(1.0f, withTag)
    }
}
