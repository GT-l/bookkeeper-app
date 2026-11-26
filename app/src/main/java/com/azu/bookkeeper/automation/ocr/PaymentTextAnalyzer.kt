package com.azu.bookkeeper.automation.ocr

import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.SafeRegex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能扣款信息分析器（识屏专用，替代早期简单正则的「第一个命中就返回」策略）。
 *
 * 算法思路：支付页面整页 OCR 后文本是乱的（按钮、标题、订单号、时间混在一起），
 * 单靠第一条正则命中经常抓到订单号或历史金额。这里改为「候选生成 → 多因子打分 → 择优」：
 *
 * 1. 金额候选：逐行抓取 ¥/￥符号金额、「xx元」金额；打分因子：
 *    - 同行含「实付/付款金额/支付金额/扣款金额/交易金额/合计」等标签 → 强加分；
 *    - 带货币符号加分；带「元」后缀加分；
 *    - 上下行含「支付成功/交易成功」等状态词加分；
 *    - 硬性淘汰：7 位以上无小数整数（订单号/卡号）、手机号、日期时间片段、0 元。
 * 2. 商户候选：优先「收款方/商户/商家/交易对方/付款给」标签行；排除键名行
 *    （付款方式/支付方式/订单号等）；无标签时按商户后缀词（店/公司/超市/便利…）兜底。
 * 3. 购买内容候选：优先「商品说明/商品名称/订单标题/购买内容/支付说明」等标签
 *    （支持「标签 值」同行与「标签独占一行、值在下一行」两种版式）。
 *    **识别不出来一律返回空串**，不弹窗、不打扰用户——这是明确的产品要求。
 * 4. 状态检测：出现「支付成功/扣款成功/交易成功」→ 提升置信度；
 *    「待支付/支付失败/已退款」→ 降低置信度并标记。
 */
@Singleton
class PaymentTextAnalyzer @Inject constructor() {

    fun analyze(rawText: String): PaymentAnalysis {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val paidConfirmed = lines.any { line -> PAID_OK_KEYWORDS.any { it in line } }
        val paymentRejected = lines.any { line -> PAID_BAD_KEYWORDS.any { it in line } }

        val amountFen = extractBestAmount(lines, paidConfirmed)
        val merchant = extractBestMerchant(lines)
        val goods = extractBestGoods(lines, merchant)

        var confidence = 0f
        if (amountFen != null) confidence += 0.5f
        if (merchant.isNotBlank()) confidence += 0.3f
        if (paidConfirmed) confidence += 0.2f
        if (paymentRejected) confidence -= 0.3f

        return PaymentAnalysis(
            amountFen = amountFen,
            merchant = merchant,
            goods = goods,
            paidConfirmed = paidConfirmed,
            confidence = confidence.coerceIn(0f, 1f)
        )
    }

    // ---------- 金额 ----------

    private fun extractBestAmount(lines: List<String>, paidConfirmed: Boolean): Long? {
        data class Candidate(val fen: Long, val score: Int, val order: Int)

        var best: Candidate? = null
        lines.forEachIndexed { index, line ->
            val context = buildString {
                append(line)
                lines.getOrNull(index - 1)?.let { append('\n'); append(it) }
                lines.getOrNull(index + 1)?.let { append('\n'); append(it) }
            }

            fun consider(raw: String, base: Int) {
                val fen = Money.parse(raw) ?: return
                if (fen <= 0L) return
                if (!isPlausibleAmount(line, raw)) return
                var score = base
                AMOUNT_STRONG_LABELS.firstOrNull { it in line }?.let { score += 30 }
                if (AMOUNT_SOFT_LABELS.any { it in context }) score += 10
                if (paidConfirmed) score += 8
                if (line.contains("¥") || line.contains("￥")) score += 20
                if (line.endsWith("元") || line.contains("元")) score += 8
                val candidate = Candidate(fen, score, index)
                if (best == null || candidate.score > best!!.score) best = candidate
            }

            AMOUNT_SYMBOL.find(line)?.groupValues?.get(1)?.let { consider(it, 0) }
            AMOUNT_LABELLED.find(line)?.groupValues?.get(1)?.let { consider(it, 25) }
            AMOUNT_YUAN.find(line)?.groupValues?.get(1)?.let { consider(it, 5) }
        }
        return best?.fen
    }

    /** 淘汰明显不是金额的数字：订单号/卡号/手机号/日期。 */
    private fun isPlausibleAmount(line: String, raw: String): Boolean {
        val cleaned = raw.replace(",", "")
        val dot = cleaned.indexOf('.')
        val intDigits = if (dot >= 0) dot else cleaned.length
        // 7 位以上整数部分且没有小数（或小数位全 0）：订单号/卡号特征
        if (intDigits >= 7 && (dot < 0 || cleaned.substring(dot + 1).all { it == '0' })) return false
        // 手机号
        if (PHONE_REGEX.containsMatchIn(line)) return false
        // 日期时间行中的数字（2026-11-26 12:30）
        if (DATE_REGEX.containsMatchIn(line) && !line.contains("¥") && !line.contains("￥") && !line.contains("元")) {
            return false
        }
        return true
    }

    // ---------- 商户 ----------

    private fun extractBestMerchant(lines: List<String>): String {
        // 第一优先：标签行
        for (line in lines) {
            val match = MERCHANT_LABELLED.find(line) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (isPlausibleMerchant(value)) return value
        }
        // 兜底：带商户后缀词的独立行
        for (line in lines) {
            if (line.length !in 2..30) continue
            if (MERCHANT_SUFFIXES.any { line.endsWith(it) || line.contains(it) }) {
                val token = line.split('\\', ' ', '：', ':').last().trim()
                if (token.length in 2..30 && !MERCHANT_EXCLUDES.any { it in token }) return token
            }
        }
        return ""
    }

    private fun isPlausibleMerchant(value: String): Boolean {
        if (value.isBlank() || value.length !in 2..30) return false
        if (MERCHANT_EXCLUDES.any { it in value }) return false
        if (value.all { it.isDigit() }) return false
        return true
    }

    // ---------- 购买内容 ----------

    /**
     * 购买内容（商品名）识别。两条路：
     * 1) 「商品说明 生椰拿铁」这类标签同行；
     * 2) 「商品说明」独占一行、值在下一行（支付宝账单详情常见）。
     *
     * 任何一步拿不准就返回空串：宁可没有购买内容，也不能给用户塞一条错的内容
     * （错误内容会被写进备注，用户还得手动改，比不填更烦）。
     */
    private fun extractBestGoods(lines: List<String>, merchant: String): String {
        for (line in lines) {
            val match = GOODS_LABELLED.find(line) ?: continue
            val cleaned = normalizeGoods(match.groupValues.getOrNull(1).orEmpty(), merchant)
            if (cleaned.isNotBlank()) return cleaned
        }
        for ((index, line) in lines.withIndex()) {
            if (line !in GOODS_BARE_LABELS) continue
            val next = lines.getOrNull(index + 1).orEmpty()
            val cleaned = normalizeGoods(next, merchant)
            if (cleaned.isNotBlank()) return cleaned
        }
        return ""
    }

    /** 校验 + 清洗：长度、纯数字、金额/状态词、与商户重复。不合格返回空串。 */
    private fun normalizeGoods(raw: String, merchant: String): String {
        var value = raw.trim().trim('：', ':', '-', '—', '·', '|', '，', ',').trim()
        if (value.length !in 2..40) return ""
        if (value.none { it.isLetter() }) return ""
        if (LONG_DIGITS.containsMatchIn(value)) return ""
        if (GOODS_EXCLUDES.any { it in value }) return ""
        val name = merchant.trim()
        if (name.length >= 2 && value.startsWith(name)) {
            value = value.removePrefix(name).trim(' ', '-', '—', '·', '：', ':', '（', '(')
        }
        // 只去掉「多余」的右括号：正常商品名「生椰拿铁（大杯）」必须完整保留
        var tail = value.trim()
        while (tail.endsWith("）") && tail.count { it == '）' } > tail.count { it == '（' }) {
            tail = tail.dropLast(1).trim()
        }
        while (tail.endsWith(")") && tail.count { it == ')' } > tail.count { it == '(' }) {
            tail = tail.dropLast(1).trim()
        }
        if (tail.length !in 2..40) return ""
        if (name.isNotBlank() && tail == name) return ""
        if (GOODS_EXCLUDES.any { it in tail }) return ""
        return tail
    }

    companion object {
        // 全部走 SafeRegex：任何一条正则在本机 ICU 上编译失败，都会让本类初始化失败，
        // 进而让「注入本类的 ViewModel」创建即闪退（真实事故见 SafeRegex 注释）。
        private val AMOUNT_SYMBOL = SafeRegex.of("(?:¥|￥)\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)")
        private val AMOUNT_LABELLED = SafeRegex.of(
            "(?:实付(?:款|金额)?|付款金额|支付金额|扣款金额|交易金额|订单金额|合计)[^0-9]{0,6}([0-9][0-9,]*(?:\\.\\d{1,2})?)"
        )
        private val AMOUNT_YUAN = SafeRegex.of("([0-9][0-9,]*(?:\\.\\d{1,2})?)\\s*元")

        private val AMOUNT_STRONG_LABELS = listOf("实付", "付款金额", "支付金额", "扣款金额", "交易金额")
        private val AMOUNT_SOFT_LABELS = listOf("订单金额", "合计", "金额", "支付成功", "付款成功", "交易成功", "扣款成功")

        private val PAID_OK_KEYWORDS = listOf("支付成功", "付款成功", "扣款成功", "交易成功", "已支付", "支付完成")
        private val PAID_BAD_KEYWORDS = listOf("待支付", "支付失败", "付款失败", "已退款", "已取消")

        // 注意：字符类里**绝不能用 \s** ——「\s + 全角标点」这种混排，在 Android 17 的 ICU 上
        // 会被误判成 Unicode property，直接抛 PatternSyntaxException（真实崩溃点，见 SafeRegex）。
        // 这里字符类只保留「普通空格 + 中文标点」的否定，语义几乎等价且各版本都能编译。
        // 用 raw string：普通字符串里的 \s 会被 Kotlin 当非法转义，编译就挂。
        private val MERCHANT_LABELLED = SafeRegex.of(
            """(?:收款方|收款人|收款单位|商户名称|商户全称|商户|商家|交易对方|付款给|付款至|向)\s*[:：]?\s*([^ ，。；;：:]{2,30})"""
        )
        private val MERCHANT_EXCLUDES = listOf(
            "付款方式", "支付方式", "零钱", "余额", "银行卡", "订单", "单号", "时间", "备注",
            "状态", "优惠", "红包", "积分", "余额宝"
        )
        private val MERCHANT_SUFFIXES = listOf(
            "店", "公司", "超市", "便利店", "餐饮", "银行", "科技", "商贸", "贸易", "旗舰店", "专营店", "咖啡", "茶"
        )

        private val PHONE_REGEX = SafeRegex.of("1[3-9]\\d{9}")
        private val DATE_REGEX = SafeRegex.of("20\\d{2}[-/年]\\d{1,2}[-/月]\\d{1,2}")

        /** 购买内容标签：长标签在前，避免「商品」抢先匹配掉「商品说明」。 */
        private val GOODS_LABELLED = SafeRegex.of(
            """(?:商品说明|商品名称|商品信息|订单标题|订单名称|购买内容|购买商品|套餐名称|服务名称|支付说明|付款说明|转账说明|交易说明|商品)\s*[:：]?\s*([^，。；;：:]{2,40})"""
        )

        /** 标签独占一行时的精确匹配（下一行才是值）。 */
        private val GOODS_BARE_LABELS = setOf(
            "商品说明", "商品名称", "商品信息", "订单标题", "订单名称",
            "购买内容", "购买商品", "支付说明", "付款说明", "转账说明", "交易说明"
        )

        /** 出现这些词说明抓到的不是商品名（金额、状态、按钮文案等）。 */
        private val GOODS_EXCLUDES = listOf(
            "金额", "价格", "总价", "单价", "数量", "小计", "合计", "优惠", "红包", "积分",
            "支付成功", "付款成功", "扣款", "订单号", "交易号", "流水号", "时间", "状态",
            "成功", "失败", "退款", "余额", "卡号", "¥", "￥",
            "查看", "详情", "账单", "客服", "投诉", "评价", "完成", "返回", "确认", "取消",
            "分享", "更多", "到账", "收款方", "商户", "商家"
        )

        /** 一串 7 位以上连续数字基本是订单号/卡号，不可能出现在商品名里。 */
        private val LONG_DIGITS = SafeRegex.of("\\d{7,}")
    }
}

/** 智能分析结果：confidence 是抽取置信度（0~1），与 OCR 置信度独立。 */
data class PaymentAnalysis(
    val amountFen: Long?,
    val merchant: String,
    /** 购买内容 / 商品名；识别不出来时为 ""（不确认、不打扰）。 */
    val goods: String = "",
    val paidConfirmed: Boolean,
    val confidence: Float
)
