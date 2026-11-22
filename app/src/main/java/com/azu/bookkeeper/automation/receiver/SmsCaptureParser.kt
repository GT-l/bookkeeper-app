package com.azu.bookkeeper.automation.receiver

import com.azu.bookkeeper.automation.classify.DouyinBusinessTagger
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Account
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 银行扣款短信解析引擎（增量架构 §2.3）。
 *
 * 隐私约束：短信正文只在本类栈帧内做纯正则运算，不打印、不落库、不联网；
 * 向上只返回 [ParsedSms] 结构化字段（含脱敏卡尾），原文绝不出此方法。
 */
@Singleton
class SmsCaptureParser @Inject constructor(
    private val whitelist: BankSmsWhitelist,
    private val rules: SmsRuleSet,
    private val accountMapper: BankAccountMapper,
    private val douyinTagger: DouyinBusinessTagger
) {

    /**
     * 解析一条短信。
     * @return 命中白名单且为支出类时返回结构化结果；验证码 / 营销 / 退款 / 非银行号返回 null。
     */
    fun parse(sender: String, body: String, arrivalMs: Long, accounts: List<Account>): ParsedSms? {
        val bank = whitelist.matches(sender) ?: return null
        if (!rules.filterBusinessType(body)) return null

        val amountFen = extractAmountFen(body)
        val timeResult = extractTime(body, arrivalMs)
        val merchant = extractMerchant(body, bank.shortName)
        val maskedTail = rules.maskCardTail(body)
        val douyinHit = douyinTagger.tag("", body) == com.azu.bookkeeper.automation.classify.BusinessTag.DOUYIN
        val accountId = accountMapper.map(bank, accounts, maskedTail) ?: 0L

        // parseHint：缺失字段降低置信（金额 / 商家为兜底串 / 仅到达时间）
        var hint = 1f
        if (amountFen == null) hint -= 0.30f
        if (merchant.startsWith(FALLBACK_MERCHANT_PREFIX)) hint -= 0.10f
        if (timeResult.second == TimeSource.SMS_ARRIVAL) hint -= 0.15f
        if (accountId == 0L) hint -= 0.05f

        return ParsedSms(
            amountFen = amountFen ?: 0L,
            merchant = merchant,
            billTime = timeResult.first,
            timeSource = timeResult.second,
            bankShort = bank.shortName,
            cardTailMasked = maskedTail,
            douyinHit = douyinHit,
            accountId = accountId,
            parseHint = hint.coerceIn(0f, 1f)
        )
    }

    /**
     * 金额提取（QA F-11）：短信常写「当前余额8888.88元，本次支出1234.56元」，
     * 通用 amountRegex 取首个两位小数会误取余额。策略：
     * 1) 先剥离「余额/可用额度」片段（cleaned），从根上排除余额数字；
     * 2) 在 cleaned 上标签优先：只取「支出/消费/扣款/交易金额」紧随的数字；
     * 3) 再退回通用金额正则（仍只作用于 cleaned）。
     * 不对原文做兜底：若整条短信只剩余额数字、没有真实交易金额，应返回 null，
     * 让 parseHint 扣分 → 低置信度弹窗由用户补录（PRD「不静默失败」）。
     * 注意：amountRegex 本身保持不变，SmsCaptureParserTest 对其有直接断言。
     */
    private fun extractAmountFen(body: String): Long? {
        val cleaned = body.replace(rules.balanceSegmentRegex, " ")
        rules.labelledAmountRegex.find(cleaned)?.groupValues?.get(1)?.let { raw ->
            Money.parse(raw)?.takeIf { it > 0L }?.let { return it }
        }
        rules.amountRegex.find(cleaned)?.groupValues?.get(1)?.let { raw ->
            Money.parse(raw)?.takeIf { it > 0L }?.let { return it }
        }
        rules.yuanAmountRegex.find(cleaned)?.groupValues?.get(1)?.let { raw ->
            Money.parse(raw)?.takeIf { it > 0L }?.let { return it }
        }
        return null
    }

    /**
     * 时间优先级：BODY 解析成功优先，否则回落到到达时间并标记 SMS_ARRIVAL。
     *
     * 注意 groupValues[0] 是「完整匹配」，捕获组从下标 1 开始，因此 size = 捕获组数 + 1：
     * - 正则 1「完整日期 + 时分」有 5 个捕获组 → size=6 → parseFiveGroupTime
     * - 正则 2「月日 + 时分」有 4 个捕获组 → size=5 → parseFourGroupTime
     * - 正则 3「仅日期」有 3 个捕获组 → size=4 → parseDateOnlyTime
     * 全部按索引取值并做空值保护，绝不做 N 元解构（避免越界崩溃）。
     */
    private fun extractTime(body: String, arrivalMs: Long): Pair<Long, TimeSource> {
        for (regex in rules.timeRegexes) {
            val match = regex.find(body) ?: continue
            val g = match.groupValues
            val parsed = when (g.size) {
                6 -> parseFiveGroupTime(g)   // year month day hour minute
                5 -> parseFourGroupTime(g)   // month day hour minute
                4 -> parseDateOnlyTime(g)    // year month day
                else -> null
            }
            if (parsed != null) return parsed to TimeSource.BODY
        }
        return arrivalMs to TimeSource.SMS_ARRIVAL
    }

    /** 5 个捕获组：完整日期 + 时分。按索引取值，缺值直接判失败。 */
    private fun parseFiveGroupTime(g: List<String>): Long? {
        val year = g.getOrNull(1)?.toIntOrNull() ?: return null
        val month = g.getOrNull(2)?.toIntOrNull() ?: return null
        val day = g.getOrNull(3)?.toIntOrNull() ?: return null
        val hour = g.getOrNull(4)?.toIntOrNull() ?: return null
        val minute = g.getOrNull(5)?.toIntOrNull() ?: return null
        return DateTimeUtils.parse(
            "%04d-%02d-%02d %02d:%02d".format(year, month, day, hour, minute),
            "yyyy-MM-dd HH:mm"
        )
    }

    /** 4 个捕获组：月日 + 时分，年份按当前年补齐。 */
    private fun parseFourGroupTime(g: List<String>): Long? {
        val month = g.getOrNull(1)?.toIntOrNull() ?: return null
        val day = g.getOrNull(2)?.toIntOrNull() ?: return null
        val hour = g.getOrNull(3)?.toIntOrNull() ?: return null
        val minute = g.getOrNull(4)?.toIntOrNull() ?: return null
        val year = DateTimeUtils.localDateTime(System.currentTimeMillis()).year
        return DateTimeUtils.parse(
            "%04d-%02d-%02d %02d:%02d".format(year, month, day, hour, minute),
            "yyyy-MM-dd HH:mm"
        )
    }

    /** 3 个捕获组：仅日期，补当天 00:00（仍属 BODY，优先级高于到达时间）。 */
    private fun parseDateOnlyTime(g: List<String>): Long? {
        val year = g.getOrNull(1)?.toIntOrNull() ?: return null
        val month = g.getOrNull(2)?.toIntOrNull() ?: return null
        val day = g.getOrNull(3)?.toIntOrNull() ?: return null
        return DateTimeUtils.parse(
            "%04d-%02d-%02d 00:00".format(year, month, day),
            "yyyy-MM-dd HH:mm"
        )
    }

    private fun extractMerchant(body: String, bankShort: String): String =
        rules.merchantRegex.find(body)?.groupValues?.get(1)?.trim().takeUnless { it.isNullOrBlank() }
            ?: "$FALLBACK_MERCHANT_PREFIX$bankShort"

    /** 由结构化结果生成写库摘要 JSON（调用方注入 serializer）。 */
    fun toSummary(parsed: ParsedSms, bankCode: String): SmsSummary = SmsSummary(
        src = "sms",
        bank = bankCode,
        amountFen = parsed.amountFen,
        merchant = parsed.merchant,
        billTime = parsed.billTime,
        timeSrc = parsed.timeSource.name,
        cardTail = parsed.cardTailMasked,
        dy = parsed.douyinHit,
        acct = parsed.accountId
    )

    private companion object {
        const val FALLBACK_MERCHANT_PREFIX = "银行扣款-"
    }
}
