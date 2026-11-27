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
 * 识别顺序：结果页类名 → 付款界面类名 → 完成态文案（按平台扩展）→ 付款界面文案 → 账单详情。
 * 强否定词（支付失败 / 已取消 / 已退款）不会单独把页面判成成功，只降低置信度。
 */
object PaymentScreenRecognizer {

    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"

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

    /** 结果页里常见的无关按钮/文案，不能当商户名。 */
    private val NOISE_LINES = setOf(
        "完成", "返回", "查看订单", "查看账单", "知道了", "确定", "支付成功", "已完成",
        "微信支付", "付款成功", "查看明细", "再买一笔", "联系商家", "返回商家"
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

        // 3) 付款界面（中间态）：明确识别，但绝不触发记账
        val entryHit = ENTRY_TEXT.firstOrNull { t.contains(it) }
        if (entryHit != null) {
            return Recognition(PaymentScreen.ENTRY, "keyword:$entryHit", 0.70f)
        }

        // 4) 账单详情页：可识别但不记账，留给主动识屏/摇一摇
        val detailHit = DETAIL_TEXT.firstOrNull { t.contains(it) }
        if (detailHit != null) {
            return Recognition(PaymentScreen.DETAIL, "keyword:$detailHit", 0.60f)
        }
        return Recognition(PaymentScreen.UNKNOWN, "none", 0f)
    }

    /**
     * 微信结果页节点结构适配。
     *
     * 微信「支付成功」页常把金额和商户放在成功文案下面的独立节点里，且没有标签，
     * FieldExtractor 的 `金额：` / `商户：` 正则命中不到。这里在原文后面追加两行带标签的
     * 结构化文本（不删原行），让既有抽取器直接命中，从而提升自动记账成功率。
     *
     * 全程不用正则：项目约定正则一律走 SafeRegex.of，且 Android 17 ICU 对字符类里的 \s 会抛异常。
     */
    fun refineWeChatText(text: String): String {
        if (text.isBlank()) return text
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val successIndex = lines.indexOfFirst { line ->
            (WECHAT_SUCCESS + COMMON_SUCCESS).any { line.contains(it) }
        }
        if (successIndex < 0) return text

        val window = lines.drop(successIndex + 1).take(6)
        val amountLine = window.firstOrNull { looksLikeAmount(it) }
        val merchantLine = window.firstOrNull { line ->
            line != amountLine && line !in NOISE_LINES && !looksLikeAmount(line) &&
                line.length in 2..20 && line.any { it.code > 0x7F || it.isLetter() }
        }
        if (amountLine == null && merchantLine == null) return text

        return buildString {
            append(text)
            if (!text.endsWith('\n')) append('\n')
            amountLine?.let {
                append("金额：")
                    .append(it.trim().removePrefix("¥").removePrefix("￥").removeSuffix("元").trim())
                    .append('\n')
            }
            merchantLine?.let { append("商户：").append(it).append('\n') }
        }
    }

    /** 纯字符判断，零正则：`¥23.00` / `￥23` / `23.00元` / `1,280.00` 均视为金额行。 */
    private fun looksLikeAmount(line: String): Boolean {
        val s = line.trim()
            .removePrefix("¥").removePrefix("￥")
            .removeSuffix("元").trim()
        if (s.isEmpty() || !s.any { it.isDigit() }) return false
        if (s.count { it == '.' } > 1) return false
        return s.all { it.isDigit() || it == '.' || it == ',' }
    }
}
