package com.azu.bookkeeper.domain.usecase.auto

import com.azu.bookkeeper.automation.classify.ClassificationRuleEngine
import com.azu.bookkeeper.automation.ocr.RuleHit
import com.azu.bookkeeper.domain.model.Account
import javax.inject.Inject

/** 分类用例：关键词 → categoryId / accountId（领域层门面，纯委托）。 */
class ClassifyCaptureUseCase @Inject constructor(
    private val classifier: ClassificationRuleEngine
) {
    suspend operator fun invoke(
        merchant: String,
        rawText: String,
        packageName: String = "",
        accounts: List<Account> = emptyList(),
        accountHint: String = ""
    ): RuleHit = classifier.classify(merchant, rawText, packageName, accounts, accountHint)
}
