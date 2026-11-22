package com.azu.bookkeeper.automation.classify

import com.azu.bookkeeper.automation.ocr.RuleHit
import com.azu.bookkeeper.data.local.db.dao.RuleDao
import com.azu.bookkeeper.domain.model.Account
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则分类引擎（主架构 §5.3 / 增量 §3.2）：
 * 关键词命中分类，priority DESC 取首个；抖音种子规则（priority=80~100）优先于通用词。
 */
@Singleton
class ClassificationRuleEngine @Inject constructor(
    private val ruleDao: RuleDao,
    private val accountMatcher: AccountMatcher,
    private val tagger: DouyinBusinessTagger
) {

    @Volatile
    private var cachedRules: List<KeywordRule> = emptyList()

    /** 重新加载启用中的规则（用户改分类 / 种子注入后调用）。 */
    suspend fun reload() {
        cachedRules = ruleDao.allRules()
            .filter { it.enabled }
            .map { KeywordRule(it.keyword, it.categoryId, it.priority, it.enabled) }
            .sortedByDescending { it.priority }
    }

    /** 基础分类：merchant + 原文；账户因子记为未命中。 */
    suspend fun classify(merchant: String, rawText: String): RuleHit =
        classify(merchant, rawText, packageName = "", accounts = emptyList())

    /**
     * T6 重载：抖音包名作为强信号参与判定；种子规则命中分类因子满分。
     * 包名命中但无关键词 → 不强行分类（categoryId=0），归「其他支出」并由置信度扣分触发弹窗。
     */
    suspend fun classify(
        merchant: String,
        rawText: String,
        packageName: String,
        accounts: List<Account>,
        accountHint: String = ""
    ): RuleHit {
        if (cachedRules.isEmpty()) reload()
        val text = "$merchant\n$rawText"
        // 完成态 / 字段辅助词（categoryId<=0）只表示「支付已发生」，不是分类规则，
        // 必须排除在分类命中之外；否则 priority 90 的「支付成功」会抢占真实商家分类，
        // 产生 categoryId=0 却被判为 matched 的假命中（QA F-02）。
        val hit = cachedRules.firstOrNull { rule ->
            rule.categoryId > 0L && text.contains(rule.keyword, ignoreCase = true)
        }
        val businessTag = tagger.tag(packageName, text)
        val accountId = accountMatcher.match(packageName, accountHint, accounts)
        return RuleHit(
            categoryId = hit?.categoryId ?: 0L,
            accountId = accountId,
            keyword = hit?.keyword ?: "",
            matched = hit != null
        )
    }

    /** 供 CaptureCoordinator 在首次使用前确保规则已加载（Flow 首值）。 */
    suspend fun ensureLoaded() {
        if (cachedRules.isEmpty()) reload()
    }
}
