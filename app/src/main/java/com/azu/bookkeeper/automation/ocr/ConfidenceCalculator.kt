package com.azu.bookkeeper.automation.ocr

import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.receiver.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/** 六因子加权置信度（主架构 §5.3 + 增量 §3.3，分母与权重恒定）。 */
@Singleton
class ConfidenceCalculator @Inject constructor() {

    fun calculate(result: OcrResult?, fields: ExtractedFields, ruleHit: RuleHit): Float =
        calculate(result, fields, ruleHit, textSource = false)

    /**
     * @param textSource 文本源（读屏/通知/短信）无 OCR 误差，OCR 因子直接取 1.0
     * @param timeSource 仅到达时间时时间因子取 0.5
     * @param businessTag 抖音种子命中时分类因子满分；包名命中但无关键词仍为 0
     */
    fun calculate(
        result: OcrResult?,
        fields: ExtractedFields,
        ruleHit: RuleHit,
        textSource: Boolean = false,
        timeSource: TimeSource = TimeSource.BODY,
        businessTag: BusinessTag = BusinessTag.NONE
    ): Float {
        val ocrScore = if (textSource) 1f else (result?.confidence?.coerceIn(0f, 1f) ?: 0f)
        val amountScore = if ((fields.amountFen ?: 0L) > 0L) 1f else 0f
        val timeScore = if (fields.billTime == null) 0f
            else if (timeSource == TimeSource.SMS_ARRIVAL) 0.5f else 1f
        val merchantScore = if (fields.merchant.isNotBlank()) 1f else 0f
        // 只有「命中且 categoryId 有效」才算分类因子满分。
        // 辅助词命中（matched=true 但 categoryId=0）不得拿满 0.15，否则会凑成 1.0 直接自动入账（QA F-02）。
        val categoryScore = if (ruleHit.matched && ruleHit.categoryId > 0L) 1f else 0f
        val accountScore = if (ruleHit.accountId > 0L) 1f else 0f

        val score = 0.30f * ocrScore +
            0.25f * amountScore +
            0.15f * timeScore +
            0.10f * merchantScore +
            0.15f * categoryScore +
            0.05f * accountScore
        // businessTag 不单独加分（分母不变），抖音增强体现在 classification 的 categoryScore/accountScore
        return score.coerceIn(0f, 1f)
    }
}
