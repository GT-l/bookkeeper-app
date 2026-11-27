package com.azu.bookkeeper.automation.capture

import com.azu.bookkeeper.core.Constants

/** 支付相关页面类型。ENTRY = 付款界面（收银台/付款码/输密码），SUCCESS = 支付结果页。 */
enum class PaymentScreen {
    ENTRY, SUCCESS, DETAIL, UNKNOWN
}

/**
 * 支付页面识别器（纯 Kotlin，不依赖 Android API，可直接跑 JVM 单测）。
 *
 * 为什么要单独做这一层：
 * 1. 旧实现只会问「整页文本里有没有 6 个完成态关键词」，既认不出「用户刚打开付款界面」，
 *    也认不出微信的「已完成 / 微信支付 / 已存入对方零钱」等文案；
 * 2. 市面同类实现（花有谱、BillRecord）都把 **Activity 类名** 当作强信号：
 *    部分微信支付成功页在普通无障碍服务下只返回空根节点，此时类名是唯一线索；
 * 3. 微信支付/小程序常跑在 `com.tencent.mm:tools` 之类子进程，需要先把包名归一化。
 *
 * 识别顺序：结果页类名 → 付款界面类名 → 完成态文案 → **账单详情** → 付款界面文案。
 * v1.3.1：账单详情必须排在付款界面之前（详情页普遍带「付款方式」字样，旧顺序会误判 ENTRY
 * 并被直接丢弃，导致「打开同一笔付款记录」永远触发不了重复检测）。
 * 强否定词（支付失败 / 已取消 / 已退款）不会单独把页面判成成功，只降低置信度。
 */
object PaymentScreenRecognizer {

    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val DOUYIN = "com.ss.android.ugc.aweme"

    data class Recognition(
        val screen: PaymentScreen,
        val reason: String,
        val confidence: Float
    )

    /** 结果页 Activity 特征。微信的 WalletPayResultUI、支付宝的 PayResultActivity 都命中这一组。 */
    private val SUCCESS_CLASS_TOKENS = listOf(
        "WalletPayResult", "PayResult", "PaySuccess", "PayComplete", "PayDone", "PayFinish"
    )

    /** 付款界面 Activity 特征：收银台、付款码、输密码、选支付方式。 */
    private val ENTRY_CLASS_TOKENS = listOf(
        "WalletPayUI", "Cashier", "PayPassword", "PayInput", "SelectPay",
        "PayActivity", "PaymentActivity", "QRPay", "FacePay"
    )

    /** 通用完成态：保留原有 6 个，补齐常见变体。 */
    private val COMMON_SUCCESS = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "已支付", "支付完成",
        "扣款成功", "支付已完成", "付款完成", "已付款", "支付完毕", "成功支付"
    )

    /** 微信专属：结果页常常只写「已完成」，或把金额放在「微信支付」下面。 */
    private val WECHAT_SUCCESS = listOf(
        "微信支付", "已完成", "已存入对方零钱", "转账已发出", "红包已发送",
        "支付成功通知", "已收款", "付款成功", "支付成功"
    )

    /** 付款界面文案。命中即认为「用户打开了付款界面」，不触发记账。 */
    private val ENTRY_TEXT = listOf(
        "付款码", "向商家付款", "收付款", "确认支付", "请输入支付密码", "立即支付",
        "待支付", "收银台", "选择付款方式", "支付金额", "付款方式", "请付款", "确认付款",
        "使用密码", "指纹支付", "向商家付款"
    )

    private val NEGATIVE = listOf(
        "支付失败", "付款失败", "已取消", "已退款", "支付超时", "订单关闭", "交易关闭"
    )

    private val DETAIL_TEXT = listOf("账单详情", "交易详情", "订单详情")

    /**
     * 抖音订单页专属（v1.3.1）：抖音电商完成页常只写「实付 ¥xx」+「订单详情」，
     * 没有「支付成功 / 交易成功」这类通用完成词，旧词表直接判 UNKNOWN → 不提交。
     * 类名 token 只取订单/交易结果页，不取收银台，避免把付款中间态误判成功。
     */
    private val DOUYIN_RESULT_CLASS_TOKENS = listOf(
        "OrderDetail", "TradeDetail", "OrderResult", "TradeResult", "PaymentResult",
        "OrderPayResult", "DouyinPayResult"
    )
    private val DOUYIN_SUCCESS_HINTS = listOf(
        "实付", "订单支付成功", "交易完成", "下单成功", "订单完成", "支付完成"
    )
    /** 「实付」单独出现不够（收银台也有），必须带订单上下文才算完成态。 */
    private val DOUYIN_ORDER_CONTEXT = listOf(
        "订单", "交易", "已支付", "完成", "账单", "收货", "待评价"
    )

    /** 结果页里常见的无关按钮/文案，不能当商户名。 */
    private val NOISE_LINES = setOf(
        "完成", "返回", "查看订单", "查看账单", "知道了", "确定", "支付成功", "已完成",
        "微信支付", "付款成功", "查看明细", "再买一笔", "联系商家", "返回商家",
        // v1.3.1：这些短词常是页面标题/分区名，抽成商户会产生「只记到『支付』」的脏账单
        "支付", "付款", "账单", "详情", "零钱", "微信", "支付宝", "余额", "收款", "转账",
        "交易", "订单", "金额", "支付方式", "付款方式", "服务", "商品", "说明", "时间"
    )

    /** 去掉微信子进程后缀：`com.tencent.mm:tools` → `com.tencent.mm`。 */
    fun normalizePackage(raw: String): String = raw.substringBefore(':').trim()

    /** 是否支付类应用（归一化后匹配 Constants.PAYMENT_PACKAGES）。 */
    fun isPaymentPackage(raw: String): Boolean =
        normalizePackage(raw) in Constants.PAYMENT_PACKAGES

    /** 供通知/短信等非读屏链路复用的纯文本完成态判定。 */
    fun isSuccessText(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.trim()
        return (COMMON_SUCCESS + WECHAT_SUCCESS).any { t.contains(it) }
    }

    /** 是否账单详情页文案：详情页也要提交捕获，才能触发「同一笔已记过」的轻提醒。 */
    fun isDetailText(text: String): Boolean = DETAIL_TEXT.any { text.contains(it) }

    /** 类名是否像支付结果页：文本为空时用它触发有界重读。 */
    fun looksLikeResultClass(className: String): Boolean =
        SUCCESS_CLASS_TOKENS.any { className.contains(it, ignoreCase = true) }

    fun classify(packageName: String, className: String, text: String): Recognition {
        val pkg = normalizePackage(packageName)
        val cls = className.trim()
        val t = text.trim()

        // 1) 类名强信号优先：微信结果页常返回空根节点，此时文本判断完全失效
        if (cls.isNotBlank() && looksLikeResultClass(cls)) {
            return Recognition(PaymentScreen.SUCCESS, "className:$cls", 0.92f)
        }
        if (cls.isNotBlank() && ENTRY_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }) {
            return Recognition(PaymentScreen.ENTRY, "className:$cls", 0.85f)
        }

        // 2) 完成态文案：微信有专属词表
        val successWords = if (pkg == WECHAT) WECHAT_SUCCESS + COMMON_SUCCESS else COMMON_SUCCESS
        val hit = successWords.firstOrNull { t.contains(it) }
        if (hit != null) {
            val hasNegative = NEGATIVE.any { t.contains(it) }
            return Recognition(
                PaymentScreen.SUCCESS,
                "keyword:$hit",
                if (hasNegative) 0.72f else 0.88f
            )
        }

        // 2b) 抖音订单完成页：只有「实付 ¥xx + 订单详情」而没有通用成功词。
        //     收银台也会被 ENTRY_CLASS_TOKENS 先在类名阶段拦截，这里不会误判。
        if (pkg == DOUYIN) {
            val hasResultClass = cls.isNotBlank() &&
                DOUYIN_RESULT_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }
            if (hasResultClass) return Recognition(PaymentScreen.SUCCESS, "douyinClass:$cls", 0.88f)
            val hasOrderContext = DOUYIN_ORDER_CONTEXT.any { t.contains(it) }
            val dHit = DOUYIN_SUCCESS_HINTS.firstOrNull { t.contains(it) }
            if (dHit != null && hasOrderContext && NEGATIVE.none { t.contains(it) }) {
                return Recognition(PaymentScreen.SUCCESS, "douyin:$dHit+订单上下文", 0.86f)
            }
        }

        // 3) 账单详情页：v1.3.1 起**提交捕获**（由 CaptureCoordinator 走去重 → 轻提醒），
        //    不再在 dispatchEvent 里直接 return。必须排在 ENTRY 前，否则「付款方式」会抢先命中。
        val detailHit = DETAIL_TEXT.firstOrNull { t.contains(it) }
        if (detailHit != null) {
            return Recognition(PaymentScreen.DETAIL, "keyword:$detailHit", 0.60f)
        }

        // 4) 付款界面（中间态）：明确识别，但绝不触发记账
        val entryHit = ENTRY_TEXT.firstOrNull { t.contains(it) }
        if (entryHit != null) {
            return Recognition(PaymentScreen.ENTRY, "keyword:$entryHit", 0.70f)
        }
        return Recognition(PaymentScreen.UNKNOWN, "none", 0f)
    }

    /**
     * 微信结果页 / 账单详情页节点结构适配。
     *
     * 微信「支付成功」页常把金额和商户放在成功文案下面的独立节点里，且没有标签，
     * FieldExtractor / PaymentTextAnalyzer 的 `金额：` / `商户：` 标签命中不到。
     * 这里在原文后面追加两行带标签的结构化文本（不删原行），让既有抽取器直接命中。
     *
     * v1.3.1：
     * - 扫描范围从「成功词后 6 行」扩到「成功词后 12 行 / 无成功词时全页前 40 行」，
     *   覆盖账单详情页把金额放在页面中部、底部的情况；
     * - 只在「有成功词」或「有详情词」时精炼，收银台等中间态保持原文不变；
     * - 已有标签行时不重复追加。
     *
     * 全程不用正则：项目约定正则一律走 SafeRegex.of，且 Android 17 ICU 对字符类里的 \s 会抛异常。
     */
    fun refineWeChatText(text: String): String {
        if (text.isBlank()) return text
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val successIndex = lines.indexOfFirst { line ->
            (WECHAT_SUCCESS + COMMON_SUCCESS).any { line.contains(it) }
        }
        val isResult = successIndex >= 0
        if (!isResult && !isDetailText(text)) return text

        val window = if (isResult) lines.drop(successIndex + 1).take(12) else lines.take(40)
        val amountLine = window.firstOrNull { looksLikeAmount(it) }
        val merchantLine = window.firstOrNull { line ->
            line != amountLine && !NOISE_LINES.any { line.contains(it) } &&
                !looksLikeAmount(line) && line.length in 2..20 &&
                line.any { it.code > 0x7F || it.isLetter() }
        }
        val hasAmountLabel = lines.any {
            it.startsWith("金额：") || it.startsWith("实付") || it.startsWith("付款金额") ||
                it.startsWith("支付金额") || it.startsWith("交易金额")
        }
        val hasMerchantLabel = lines.any {
            it.startsWith("商户：") || it.startsWith("收款方") || it.startsWith("商户名称")
        }
        if ((amountLine == null || hasAmountLabel) && (merchantLine == null || hasMerchantLabel)) {
            return text
        }

        return buildString {
            append(text)
            if (!text.endsWith('\n')) append('\n')
            if (amountLine != null && !hasAmountLabel) {
                append("金额：")
                    .append(cleanAmount(amountLine))
                    .append('\n')
            }
            if (merchantLine != null && !hasMerchantLabel) {
                append("商户：").append(merchantLine).append('\n')
            }
        }
    }

    private fun cleanAmount(line: String): String = line.trim()
        .removePrefix("¥").removePrefix("￥")
        .removePrefix("-").removePrefix("−")
        .removeSuffix("元").trim()

    /**
     * 抖音订单页适配（v1.3.1）：抖音把金额写在「实付 ¥xx」行、商户写在「商家 / 店铺」行，
     * 既没有「¥xx」独立行也没有「商户：」标签，抽取器会漏掉。这里补两行结构化文本。
     * 只处理订单现场，收银台等中间态保持原文。
     */
    fun refineDouyinText(text: String): String {
        if (text.isBlank()) return text
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // 只处理订单现场：收银台也会写「实付 ¥xx」，不能因为一个金额标签就补结构化行。
        // 必须有「订单详情 / 交易详情」或「实付 + 订单上下文」才补。
        val isOrderPage = lines.any { isDetailText(it) } ||
            (lines.any { line -> DOUYIN_SUCCESS_HINTS.any { line.contains(it) } } &&
                DOUYIN_ORDER_CONTEXT.any { ctx -> lines.any { it.contains(ctx) } })
        if (!isOrderPage) return text

        val amountLine = lines.firstOrNull { line ->
            (line.contains("实付") || line.contains("支付金额") || line.contains("订单金额") ||
                line.contains("合计")) && line.any { it.isDigit() }
        }
        val amountValue = amountLine?.let { line ->
            val start = line.indexOfFirst { it.isDigit() }
            if (start < 0) null else {
                var end = start
                while (end < line.length && (
                        line[end].isDigit() || line[end] == '.' || line[end] == ','
                    )) end++
                line.substring(start, end).takeIf { it.any { it.isDigit() } }
            }
        }
        val merchantLine = lines.firstOrNull { line ->
            (line.startsWith("商家") || line.startsWith("店铺") || line.startsWith("商户") ||
                line.startsWith("收款方")) && line.length in 2..24 && line !in NOISE_LINES
        }
        val merchantValue = merchantLine?.let { line ->
            line.substringAfter("：").trim().takeIf { it.isNotBlank() && it != line }
                ?: line.substringAfter(":").trim().takeIf { it.isNotBlank() && it != line }
                ?: line.removePrefix("商家").removePrefix("店铺").removePrefix("商户")
                    .removePrefix("收款方").trim().trim('：', ':')
        }?.takeIf { it.length in 2..24 && it !in NOISE_LINES }

        val hasAmountLabel = lines.any { it.startsWith("金额：") || it.startsWith("实付金额") }
        val hasMerchantLabel = lines.any { it.startsWith("商户：") }
        if ((amountValue == null || hasAmountLabel) && (merchantValue == null || hasMerchantLabel)) {
            return text
        }
        return buildString {
            append(text)
            if (!text.endsWith('\n')) append('\n')
            if (amountValue != null && !hasAmountLabel) append("金额：").append(amountValue).append('\n')
            if (merchantValue != null && !hasMerchantLabel) append("商户：").append(merchantValue).append('\n')
        }
    }

    /** 纯字符判断，零正则：`¥23.00` / `￥23` / `23.00元` / `1,280.00` 均视为金额行。 */
    private fun looksLikeAmount(line: String): Boolean {
        val s = cleanAmount(line)
        if (s.isEmpty() || !s.any { it.isDigit() }) return false
        if (s.count { it == '.' } > 1) return false
        return s.all { it.isDigit() || it == '.' || it == ',' }
    }
}
