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
            // v1.3.9：整行就是一个数字（可带正负号 / 货币符号）。
            // 微信「账单详情」的金额节点就是独立的「-23.00」，既没有¥也没有「元」、更没有标签，
            // 上面三条正则全部命中不到 → 表现为「识别到付款但识别不出金额」。
            // 分值压到最低，有标签/符号的行永远优先。
            AMOUNT_BARE.find(line)?.groupValues?.get(1)?.let { consider(it, 3) }
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
        // v1.3.19：单笔 ≥ ¥100,000 一律不认——真机上把累计收款 ¥111,845.00 记成了一笔账
        if (fen >= 10_000_000L) return false
        // 手机号
        if (PHONE_REGEX.containsMatchIn(line)) return false
        // 日期时间行中的数字（2026-11-26 12:30）
        if (DATE_REGEX.containsMatchIn(line) && !line.contains("¥") && !line.contains("￥") && !line.contains("元")) {
            return false
        }
        // v1.3.2：余额 / 可用 / 优惠 / 退款金额不是「本次实付」。
        // 抽到这些固定数字会让不同支付的金额相同，进而触发错误的重复提醒。
        if (NON_PAYMENT_AMOUNT_HINTS.any { line.contains(it) } &&
            AMOUNT_STRONG_LABELS.none { line.contains(it) }
        ) return false
        return true
    }

    // ---------- 商户 ----------

    private fun extractBestMerchant(lines: List<String>): String {
        // 第一优先：标签与值在同一行（支付宝账单详情、多数收银台）
        for (line in lines) {
            // 「商户全称」独占一行时，正则的 `商户` 备选会把 `全称` 当成值（恰好 2 个字），
            // 于是商户名变成「全称」，真正的值在下一行却永远没机会读。
            // 先排除纯标签行，交给下面的「取下一行」分支。
            if (line.trim().trim('：', ':') in MERCHANT_BARE_LABELS) continue
            if (line.trim().trim('：', ':') in MERCHANT_LABEL_FRAGMENTS) continue
            val match = MERCHANT_LABELLED.find(line) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (isPlausibleMerchant(value)) return value
        }
        // v1.3.9：标签独占一行、值在下一行 —— 微信「账单详情」就是这种版式
        // （商户全称 / 商户简称 / 收单机构 各自一行，值在下面）。
        //
        // 旧实现只有「同行」这一条路，于是落到后缀兜底，第一个含「公司 / 科技」的行
        // 恰好是「财付通支付科技有限公司」（收单机构），用户看到的商户就变成了收单机构。
        //
        // v1.3.10：微信无障碍有时把「商户全称」拆成「商户」+「全称」两个节点，
        // 于是下一行是标签残片「全称」，旧实现直接把它当商户名（用户反馈「识别到个全称」）。
        // 现在遇到残片继续往下找，最多看 3 行；碰到别的字段就停，不越界取到收单机构。
        for ((index, line) in lines.withIndex()) {
            val normalized = line.trim().trim('：', ':')
            if (normalized !in MERCHANT_BARE_LABELS) continue
            for (offset in 1..3) {
                val value = lines.getOrNull(index + offset)?.trim().orEmpty()
                if (value.isEmpty()) continue
                if (value.trim('：', ':') in MERCHANT_LABEL_FRAGMENTS) continue
                // v1.3.11：英文公司名常被节点拆成两行
                // （`Apple Distribution International` / `limited`），单行既不完整又超长。
                // 先尝试把紧随的纯英文续行拼起来，拼不出来再用单行判断。
                val merged = mergeLatinContinuation(lines, index + offset)
                if (merged != value && isPlausibleMerchant(merged)) return merged
                if (isPlausibleMerchant(value)) return value
                break
            }
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

    /**
     * v1.3.11：把被节点拆断的英文商户名拼回来。
     *
     * 微信账单详情里 `Apple Distribution International limited` 会被拆成
     * `Apple Distribution International` + `limited` 两行，单行判断要么超长要么不完整。
     * 只在「首行不含中文且下一行也是纯英文」时拼接，中文商户不受影响。
     */
    private fun mergeLatinContinuation(lines: List<String>, startIndex: Int): String {
        val first = lines.getOrNull(startIndex)?.trim().orEmpty()
        if (first.isEmpty() || !isLatinText(first)) return first
        val second = lines.getOrNull(startIndex + 1)?.trim().orEmpty()
        if (second.isEmpty() || !isLatinText(second)) return first
        val merged = "$first $second"
        return if (merged.length <= MAX_MERCHANT_LENGTH) merged else first
    }

    /** 不含中日韩文字、且至少有一个字母 —— 用于识别「英文公司名的续行」。 */
    private fun isLatinText(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.any { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF }) {
            return false
        }
        return value.any { it.isLetter() }
    }

    private fun isPlausibleMerchant(value: String): Boolean {
        if (value.isBlank() || value.length !in 2..MAX_MERCHANT_LENGTH) return false
        if (MERCHANT_EXCLUDES.any { it in value }) return false
        if (value.all { it.isDigit() }) return false
        // 纯标签词（「商户全称」本身就占一行时，下一行才是真正的值）
        if (value in MERCHANT_BARE_LABELS) return false
        if (value.trim('：', ':') in MERCHANT_LABEL_FRAGMENTS) return false
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
            // 「商品说明」独占一行时，正则的 `商品` 备选会把 `说明` 当成值（长度恰好 2），
            // 于是返回假商品名「说明」，真正的值在下一行却永远没机会读。
            // 先把纯标签行排除，交给下面的「取下一行」分支处理。
            if (line in GOODS_BARE_LABELS) continue
            val match = GOODS_LABELLED.find(line) ?: continue
            val captured = match.groupValues.getOrNull(1).orEmpty().trim()
            if (captured in GOODS_BARE_LABELS) continue
            val cleaned = normalizeGoods(captured, merchant)
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
        /** 商户名长度上限。中文店名很少超过 20，英文公司名（Apple Distribution International limited）会到 40+。 */
        private const val MAX_MERCHANT_LENGTH = 60

        // 全部走 SafeRegex：任何一条正则在本机 ICU 上编译失败，都会让本类初始化失败，
        // 进而让「注入本类的 ViewModel」创建即闪退（真实事故见 SafeRegex 注释）。
        private val AMOUNT_SYMBOL = SafeRegex.of("(?:¥|￥)\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)")
        private val AMOUNT_LABELLED = SafeRegex.of(
            "(?:实付(?:款|金额)?|付款金额|支付金额|扣款金额|交易金额|订单金额|合计)[^0-9]{0,6}([0-9][0-9,]*(?:\\.\\d{1,2})?)"
        )
        private val AMOUNT_YUAN = SafeRegex.of("([0-9][0-9,]*(?:\\.\\d{1,2})?)\\s*元")
        /** v1.3.9：整行纯数字（允许 -/+ 与货币符号），兼容微信账单详情的「-23.00」金额节点。 */
        private val AMOUNT_BARE = SafeRegex.of("^[-+−]?(?:¥|￥)?([0-9][0-9,]*(?:\\.\\d{1,2})?)$")

        private val AMOUNT_STRONG_LABELS = listOf("实付", "付款金额", "支付金额", "扣款金额", "交易金额")
        private val NON_PAYMENT_AMOUNT_HINTS = listOf("余额", "剩余", "可用", "优惠", "红包", "积分", "退款", "返现")
        private val AMOUNT_SOFT_LABELS = listOf("订单金额", "合计", "金额", "支付成功", "付款成功", "交易成功", "扣款成功")

        private val PAID_OK_KEYWORDS = listOf(
            "支付成功", "付款成功", "扣款成功", "交易成功", "已支付", "支付完成",
            // v1.3.1 抖音订单页常写「交易完成 / 订单完成 / 下单成功」而非通用成功词
            "交易完成", "订单完成", "下单成功", "订单支付成功"
        )
        private val PAID_BAD_KEYWORDS = listOf("待支付", "支付失败", "付款失败", "已退款", "已取消")

        // 注意：字符类里**绝不能用 \s** ——「\s + 全角标点」这种混排，在 Android 17 的 ICU 上
        // 会被误判成 Unicode property，直接抛 PatternSyntaxException（真实崩溃点，见 SafeRegex）。
        // 这里字符类只保留「普通空格 + 中文标点」的否定，语义几乎等价且各版本都能编译。
        // 用 raw string：普通字符串里的 \s 会被 Kotlin 当非法转义，编译就挂。
        private val MERCHANT_LABELLED = SafeRegex.of(
            """(?:收款方|收款人|收款单位|商户名称|商户全称|商户|商家|交易对方|付款给|付款至|向)\s*[:：]?\s*([^ ，。；;：:]{2,30})"""
        )
        /**
         * v1.3.9：标签独占一行的版式（微信账单详情）。
         * 「收单机构」刻意不在此列——它是收单方（财付通/银联），不是商户。
         */
        private val MERCHANT_BARE_LABELS = setOf(
            "商户全称", "商户名称", "商户简称", "商户", "商家",
            "收款方", "收款人", "收款单位", "交易对方", "付款给", "付款至"
        )
        /**
         * v1.3.10：标签残片。
         *
         * 「商户全称」独占一行时，`MERCHANT_LABELLED` 会回溯成「商户」+「全称」，
         * 把「全称」当成值；微信无障碍还会把这个标签拆成「商户」「全称」两个节点，
         * 于是「取下一行」拿到的也是「全称」。用户看到的就是商户名 = 全称。
         *
         * 这些词只可能是标签的一部分，永远不能当商户值。
         */
        private val MERCHANT_LABEL_FRAGMENTS = setOf(
            "全称", "简称", "名称", "商户全称", "商户简称", "商户名称"
        )
        private val MERCHANT_EXCLUDES = listOf(
            "付款方式", "支付方式", "零钱", "余额", "银行卡", "订单", "单号", "时间", "备注",
            "状态", "优惠", "红包", "积分", "余额宝",
            // v1.3.9：收单机构 / 支付机构 是通道方，不是商户。
            // 微信账单详情里「财付通支付科技有限公司」就在商户下面，旧逻辑会把它当商户名。
            "收单机构", "支付机构", "清算机构", "收单方", "特约商户",
            "财付通", "银联", "支付科技", "支付有限"
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
            "分享", "更多", "到账", "收款方", "商户", "商家",
            // v1.3.11：微信「商品」字段常写成账单链接（apple.com/bill/xxx），
            // 当购买内容会让备注变成一串看不懂的 URL。
            "http", "www.", ".com/", ".cn/", "/bill/"
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
