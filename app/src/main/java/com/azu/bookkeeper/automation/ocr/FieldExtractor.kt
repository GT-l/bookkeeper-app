package com.azu.bookkeeper.automation.ocr

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.SafeRegex
import javax.inject.Inject
import javax.inject.Singleton

/** 从 OCR / 读屏 / 通知 / 短信文本中抽取金额、商家、时间、账户线索。 */
@Singleton
class FieldExtractor @Inject constructor() {

    // 注意：全部走 SafeRegex + 字符类里不用 \s。
    // 「[^\s，。；;：:]」这种混排在 Android 17 的 ICU 上会抛 Incorrect Unicode property，
    // 而本类是 @Singleton，构造失败会让注入它的识屏组件直接崩（真实事故）。
    private val amountWithSymbol = SafeRegex.of("(?:¥|￥|RMB|人民币)\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)")
    private val amountWithYuan = SafeRegex.of("([0-9][0-9,]*(?:\\.\\d{1,2})?)\\s*元")
    private val amountLabelled = SafeRegex.of("(?:金额|订单金额|付款金额|交易金额|扣款金额)\\s*[:：]?\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)")
    private val merchantRegex = SafeRegex.of("""(?:商户名称|商户|收款方|交易对方|付款给|向)\s*[:：]?\s*([^ ，。；;：:\n]{2,20})""")
    private val accountRegex = SafeRegex.of("""(?:银行卡|储蓄卡|信用卡|微信零钱|支付宝余额|付款方式)\s*[:：]?\s*([^ ，。；;：:\n]{2,16})""")

    fun extract(text: String, fallbackTime: Long? = null): ExtractedFields {
        val amountFen = extractAmount(text)
        val merchant = merchantRegex.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val billTime = extractTime(text) ?: fallbackTime
        val accountHint = accountRegex.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return ExtractedFields(amountFen, merchant, billTime, accountHint)
    }

    private fun extractAmount(text: String): Long? {
        val raw = amountWithSymbol.find(text)?.groupValues?.get(1)
            ?: amountLabelled.find(text)?.groupValues?.get(1)
            ?: amountWithYuan.find(text)?.groupValues?.get(1)
            ?: return null
        val fen = Money.parse(raw) ?: return null
        return fen.takeIf { it > 0L }
    }

    /**
     * 逐行尝试多格式解析，避免把金额数字误当日期（QA F-10）。
     * 通知 / 读屏常见「交易时间：2026-11-22 12:30:45」带标签前缀，
     * 整行直接 parseFlexible 必然失败 → 先抽出日期时间子串再解析，保留秒。
     */
    private fun extractTime(text: String): Long? = text.lines()
        .map { it.trim() }
        .firstNotNullOfOrNull { line ->
            val token = DATE_TIME_TOKEN.find(line)?.value?.trim()
                ?: line.takeIf { it.length >= 8 && it.any { c -> c == '-' || c == '/' || c == '年' } }
            token?.let { DateTimeUtils.parseFlexible(it) }
        }

    private companion object {
        /** 从带标签的行中抓取 ISO/中文日期时间子串（含可选秒）。 */
        val DATE_TIME_TOKEN = SafeRegex.of(
            """20\d{2}[-/年]\d{1,2}[-/月]\d{1,2}日?(?:[ \t\u00a0\u3000]+\d{1,2}:\d{2}(?::\d{2})?)?"""
        )
    }
}
