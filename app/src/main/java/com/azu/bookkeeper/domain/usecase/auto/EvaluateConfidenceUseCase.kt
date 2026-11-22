package com.azu.bookkeeper.domain.usecase.auto

import com.azu.bookkeeper.automation.classify.BusinessTag
import com.azu.bookkeeper.automation.ocr.ConfidenceCalculator
import com.azu.bookkeeper.automation.ocr.ExtractedFields
import com.azu.bookkeeper.automation.ocr.OcrResult
import com.azu.bookkeeper.automation.ocr.RuleHit
import com.azu.bookkeeper.automation.receiver.TimeSource
import javax.inject.Inject

/** 六因子置信度评估用例（领域层门面，纯委托；权重表在 ConfidenceCalculator 中恒定）。 */
class EvaluateConfidenceUseCase @Inject constructor(
    private val calculator: ConfidenceCalculator
) {
    operator fun invoke(
        result: OcrResult?,
        fields: ExtractedFields,
        ruleHit: RuleHit,
        textSource: Boolean = false,
        timeSource: TimeSource = TimeSource.BODY,
        businessTag: BusinessTag = BusinessTag.NONE
    ): Float = calculator.calculate(result, fields, ruleHit, textSource, timeSource, businessTag)
}
