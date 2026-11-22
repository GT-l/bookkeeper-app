package com.azu.bookkeeper.automation.classify

import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.AccountType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账户映射（主架构 §5.3 六因子之 account 因子）：
 * 由支付包名 / 文本账户线索映射到已有账户 id，未命中返回 0 并让置信度扣分、触发校对。
 */
@Singleton
class AccountMatcher @Inject constructor() {

    /**
     * @param packageName 捕获来源包名（微信/支付宝/银行/抖音，或 "sms:ICBC"）
     * @param accountHint 文本中抽取的账户线索（如「招商银行(1234)」）
     * @param accounts 当前账本可用账户
     */
    fun match(packageName: String, accountHint: String, accounts: List<Account>): Long {
        val normalized = accountHint.trim()
        if (normalized.isNotEmpty()) {
            accounts.firstOrNull { account -> accountMatchesHint(account, normalized) }?.let {
                return it.accountId
            }
        }
        val expectedType = typeFromPackage(packageName)
        if (expectedType != null) {
            accounts.firstOrNull { it.type == expectedType && it.enabled }?.let { return it.accountId }
        }
        // 抖音免密支付：文本线索优先，其次匹配虚拟钱包 / 自定义账户
        if (packageName == DouyinBusinessTagger.DOUYIN_PACKAGE) {
            accounts.firstOrNull {
                it.type == AccountType.VIRTUAL_WALLET || it.type == AccountType.CUSTOM
            }?.let { return it.accountId }
        }
        return 0L
    }

    /** 银行短信 "sms:ICBC" 的简称回填，供 BankAccountMapper 二次精配。 */
    fun bankCodeFromPackage(packageName: String): String =
        packageName.removePrefix("sms:").takeIf { it != packageName }.orEmpty()

    private fun accountMatchesHint(account: Account, hint: String): Boolean {
        if (account.name.contains(hint, ignoreCase = true)) return true
        if (account.remark.contains(hint, ignoreCase = true)) return true
        // 卡尾匹配：提示串含账户备注中的 4 位尾号
        val tailRegex = Regex("\\d{4}")
        val tails = tailRegex.findAll(hint).map { it.value }.toSet()
        return tails.isNotEmpty() && tails.any { account.remark.contains(it) || account.name.contains(it) }
    }

    private fun typeFromPackage(packageName: String): AccountType? = when {
        packageName == "com.tencent.mm" -> AccountType.WECHAT
        packageName == "com.eg.android.AlipayGphone" -> AccountType.ALIPAY
        packageName.startsWith("sms:") -> AccountType.DEBIT_CARD
        else -> null
    }
}
