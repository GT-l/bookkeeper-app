package com.azu.bookkeeper.automation.receiver

import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.AccountType
import com.azu.bookkeeper.core.SafeRegex
import javax.inject.Inject
import javax.inject.Singleton

/** 银行信息（白名单命中结果）。 */
data class BankInfo(
    val code: String,
    val shortName: String,
    val senderNumbers: Set<String>
)

/**
 * 银行号白名单（增量 §2.3）：数字短号精确 + alpha sender 正则兜底。
 * 非白名单直接丢弃，不落入 CaptureEvent。
 */
@Singleton
class BankSmsWhitelist @Inject constructor() {

    private val banks: List<BankInfo> = listOf(
        BankInfo("ICBC", "工商银行", setOf("95588", "95516")),
        BankInfo("CCB", "建设银行", setOf("95533")),
        BankInfo("BOC", "中国银行", setOf("95566", "95519")),
        BankInfo("ABC", "农业银行", setOf("95599")),
        BankInfo("BOCOM", "交通银行", setOf("95559")),
        BankInfo("CMB", "招商银行", setOf("95555")),
        BankInfo("CITIC", "中信银行", setOf("95558")),
        BankInfo("SPDB", "浦发银行", setOf("95528")),
        BankInfo("CMBC", "民生银行", setOf("95568")),
        BankInfo("PAB", "平安银行", setOf("95511")),
        BankInfo("PSBC", "邮储银行", setOf("95580"))
    )

    private val alphaSenderRegex = SafeRegex.of("(?i).*(银行|bank|credit|card).*")

    fun matches(sender: String): BankInfo? {
        val raw = sender.trim()
        if (raw.isEmpty()) return null
        banks.firstOrNull { bank -> raw in bank.senderNumbers || bank.shortName in raw }
            ?.let { return it }
        // 字母发件人（如 ICBC / CMBCHK）按正则兜底，code 取发送方前 8 位
        return if (alphaSenderRegex.matches(raw)) {
            BankInfo(code = raw.take(8).uppercase(), shortName = "银行", senderNumbers = emptySet())
        } else {
            null
        }
    }
}

/**
 * 短信解析规则集（增量 §2.3）：收支关键词、排除类、金额/时间/商家正则。
 * 过滤阶段不提示用户（PRD 23.3.1）。
 */
@Singleton
class SmsRuleSet @Inject constructor() {

    val expenseKeywords: List<String> = listOf("支出", "扣款", "消费", "交易", "支付", "转出")
    val excludeKeywords: List<String> = listOf(
        "验证码", "营销", "退款", "返还", "收入", "存入", "还款入账", "积分", "活动"
    )
    val douyinKeywords: List<String> = DouyinBusinessTagger.KEYWORDS

    // 全部走 SafeRegex：本类在短信广播路径上按需构造，正则编译失败会直接抛到 onReceive，
    // 表现为「收到银行短信瞬间闪退」。字符类里也不用 \s（Android 17 ICU 踩坑）。
    val amountRegex = SafeRegex.of("""(?:¥|￥|RMB|人民币)?\s*([\d,]+\.\d{2})""")
    val yuanAmountRegex = SafeRegex.of("""人民币\s*([\d,]+(?:\.\d{1,2})?)元""")

    /**
     * 标签优先金额（QA F-11）：短信常写「当前余额8888.88元，本次支出1234.56元」，
     * 通用 amountRegex 会误取余额。优先取「支出/消费/扣款/交易金额」标签后的数字。
     */
    val labelledAmountRegex = SafeRegex.of(
        """(?:支出|消费|扣款|支付金额|交易金额|订单金额|付款金额|扣费|支付)[^0-9]{0,8}([\d,]+\.\d{2})"""
    )

    /** 余额 / 额度片段：无标签时先剥离，避免被当成消费金额。 */
    val balanceSegmentRegex = SafeRegex.of(
        """(?:账户余额|可用余额|可用额度|信用额度|余额)[^0-9]{0,6}[\d,]+\.\d{2}\s*元?"""
    )
    val timeRegexes: List<Regex> = listOf(
        SafeRegex.of("""(20\d{2})[-/年](\d{1,2})[-/月](\d{1,2})日?[ \t\u00a0\u3000]+(\d{1,2}):(\d{2})"""),
        SafeRegex.of("""(\d{1,2})月(\d{1,2})日[ \t\u00a0\u3000]+(\d{1,2}):(\d{2})"""),
        SafeRegex.of("""(20\d{2})[-/年](\d{1,2})[-/月](\d{1,2})日?""")
    )
    val merchantRegex = SafeRegex.of("""(?:商户名称|商户|交易对手|收款方)\s*[:：]\s*([^ ,，。；;：:\n]+)""")
    private val cardTailRegex = SafeRegex.of("""(\d{4})\d{8,11}(\d{4})""")

    /** 命中支出类且不含排除类才进入解析。 */
    fun filterBusinessType(body: String): Boolean =
        expenseKeywords.any { body.contains(it) } &&
            excludeKeywords.none { body.contains(it) }

    fun maskCardTail(body: String): String =
        cardTailRegex.find(body)?.let { match ->
            "${match.groupValues[1]}****${match.groupValues[2]}"
        }.orEmpty()
}

/**
 * 银行短信 → 账户映射（增量 §2.3）：按账户备注含银行简称 / 卡尾匹配储蓄卡或信用卡。
 * 未命中返回 null（走默认账户 + 账户因子 0，不静默）。
 */
@Singleton
class BankAccountMapper @Inject constructor() {

    fun map(bankInfo: BankInfo, accounts: List<Account>, maskedTail: String = ""): Long? {
        val candidates = accounts.filter { it.enabled }
        // 1) 备注 / 名称命中银行简称
        candidates.firstOrNull { account ->
            account.name.contains(bankInfo.shortName) || account.remark.contains(bankInfo.shortName)
        }?.let { return it.accountId }

        // 2) 卡尾匹配（脱敏串 "6222****1234" 取前 4 或后 4）
        if (maskedTail.isNotBlank()) {
            val parts = maskedTail.split("****")
            candidates.firstOrNull { account ->
                parts.any { part -> part.length >= 4 && (account.remark.contains(part) || account.name.contains(part)) }
            }?.let { return it.accountId }
        }

        // 3) 银行类账户兜底（储蓄卡优先、信用卡次之）
        return candidates.firstOrNull { it.type == AccountType.DEBIT_CARD }?.accountId
            ?: candidates.firstOrNull { it.type == AccountType.CREDIT_CARD }?.accountId
    }
}
