package com.azu.bookkeeper.automation.capture

import com.azu.bookkeeper.core.SafeRegex

/**
 * v1.3.23 · 受 [问题一-全场景触发] 约束：微信「最终支付页」通用判定。
 *
 * 为什么需要它：旧的 UNKNOWN 分支只有 `looksLikeReceipt`（金额 + 交易单号 / 账单详情
 * 等固定词）才提交。微信扫码、公众号、小程序支付成功页常常只有「支付成功 + ¥xx + 商户」，
 * 没有那些固定词 → 事件到了、文本读到了，却什么都不做，用户感觉「识屏模块没触发」。
 *
 * 这里的判据是**结构性的**，不是词表穷举：
 *   1. 有最终完成态（支付成功 / 交易完成 / 转账成功 / 红包已领取 …）；
 *   2. 有金额；
 *   3. 没有待支付 / 确认支付 / 收款待确认等中间态。
 * 三条同时满足即认为「这笔钱已经付完，可以交给记账链路」。
 *
 * 安全边界（受 [问题一-全场景触发] 约束）：
 * - 待支付 / 支付中 / 确认收款 / 付款码 / 收银台一律 false；
 * - 聊天页（按住说话 / 发消息）一律 false，聊天气泡里的「支付成功」不可信；
 * - 退款页 false（退款是冲正，不是新支出，按既有工单不自动记）；
 * - 没有金额时不猜；
 * - 冻结的 [PaymentScreenRecognizer] 转账 / 红包词表与状态机一个字未改，
 *   本对象是独立补充，仅用于 UNKNOWN 分支的兜底提交。
 *
 * 纯函数，不依赖 Android / Hilt，可直接喂原文样本做单测。
 */
object WechatPayFinalPageResolver {

    /**
     * 受 [问题一-全场景触发] 约束：这一页是不是微信的最终支付结果页 / 单笔详情页。
     */
    fun isFinalPayPage(text: String): Boolean {
        if (text.isBlank()) return false
        val compact = text.replace("\n", "").replace("\r", "")
            .replace(" ", "").replace("\u00A0", "").replace("\u3000", "")

        // 聊天页 / 主界面结构性提示：气泡里的成功词一律不可信
        if (CHAT_UI_HINTS.any { compact.contains(it) }) return false
        // 中间态 / 否定态优先，绝不让「确认支付 ¥21.60」混进最终页
        if (PENDING_HINTS.any { compact.contains(it) }) return false
        if (NEGATIVE_HINTS.any { compact.contains(it) }) return false
        // 退款单独处理，不在这里当新支出
        if (REFUND_HINTS.any { compact.contains(it) }) return false
        if (!hasAmount(text)) return false
        return FINAL_HINTS.any { compact.contains(it) }
    }

    /** 受 [问题一-全场景触发] 约束：收支方向，与摇一摇链路的口径一致。 */
    fun direction(text: String): BillDirection {
        if (EXPENSE_STRONG.any { text.contains(it) }) return BillDirection.EXPENSE
        if (INCOME_HINTS.any { text.contains(it) }) return BillDirection.INCOME
        return BillDirection.EXPENSE
    }

    /** 受 [问题一-全场景触发] 约束：场景标签，仅用于备注前缀与去重口径。 */
    fun scene(text: String): PaymentScene = when {
        RED_PACKET_HINTS.any { text.contains(it) } ->
            if (INCOME_HINTS.any { text.contains(it) }) PaymentScene.WECHAT_RED_PACKET_IN
            else PaymentScene.WECHAT_RED_PACKET_OUT
        TRANSFER_HINTS.any { text.contains(it) } ->
            if (INCOME_HINTS.any { text.contains(it) }) PaymentScene.WECHAT_TRANSFER_IN
            else PaymentScene.WECHAT_TRANSFER_OUT
        text.contains("账单详情") || text.contains("交易详情") ||
            text.contains("订单详情") -> PaymentScene.WECHAT_BILL_DETAIL
        else -> PaymentScene.WECHAT_MERCHANT_PAY
    }

    /** 页面里有没有一笔真实金额（¥ / 实付 / 整行纯数字）。 */
    private fun hasAmount(text: String): Boolean = text.lines().any { line ->
        val value = line.trim()
        AMOUNT_LINE.matches(value) ||
            (value.contains("¥") || value.contains("￥")) && AMOUNT_NUMBER.find(value) != null ||
            AMOUNT_LABEL.find(value) != null
    }

    private val AMOUNT_LINE = SafeRegex.of("^[-+−]?(?:¥|￥)?[0-9][0-9,]*(?:\\.[0-9]{1,2})?元?$")
    private val AMOUNT_NUMBER = SafeRegex.of("[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
    private val AMOUNT_LABEL = SafeRegex.of("(?:(?:实付|付款|支付|扣款|交易)金额?|合计)[^0-9]{0,6}[0-9]")

    /**
     * 最终完成态。覆盖用户列出的全部微信支付场景：扫码 / 付款码 / 公众号 / 小程序 /
     * 第三方 App 拉起微信支付 / 转账收发 / 红包收发 / 零钱与账单里的单笔详情。
     */
    private val FINAL_HINTS = listOf(
        "支付成功", "支付完成", "支付已完成", "付款成功", "付款完成", "交易成功",
        "交易完成", "已支付", "已付款", "扣款成功", "订单支付成功", "下单成功",
        "订单完成", "微信支付凭证", "支付凭证",
        // 转账收发
        "转账成功", "已收钱", "已收款", "收款成功", "对方已收款", "转账已发出",
        "已存入对方零钱", "已到账", "转账完成",
        // 红包收发
        "红包已领取", "红包已发出", "已发送红包", "已存入零钱", "已存入你的零钱",
        // 单笔详情（详情页本身即最终结果）
        "账单详情", "交易详情", "订单详情", "转账详情", "红包详情"
    )

    /** 中间态：钱没真正付完 / 没真正到账，绝不自动记账。 */
    private val PENDING_HINTS = listOf(
        "待支付", "未支付", "待付款", "支付中", "处理中", "待确认", "待确认收款",
        "确认收款", "等待对方确认收款", "立即支付", "确认支付", "确认付款",
        "请输入支付密码", "付款码", "向商家付款", "收付款", "选择付款方式",
        "选择支付方式", "待发货", "待收货", "退款中"
    )

    private val NEGATIVE_HINTS = listOf("支付失败", "付款失败", "交易失败", "已取消", "订单关闭")
    private val REFUND_HINTS = listOf("退款成功", "已退款", "已原路退回")

    /** 聊天页结构提示，不是消息正文。 */
    private val CHAT_UI_HINTS = listOf("按住说话", "发消息", "输入消息", "语音通话", "表情按钮")

    private val INCOME_HINTS = listOf(
        "已收钱", "已收款", "收款成功", "对方已收款", "已到账", "收到转账",
        "红包已领取", "已存入零钱", "已存入你的零钱", "二维码收款", "转入"
    )
    private val EXPENSE_STRONG = listOf(
        "已支付", "付款成功", "支付成功", "扣款成功", "消费成功", "支出",
        "转账已发出", "已存入对方零钱", "已发送红包", "红包已发出", "付款完成"
    )
    private val RED_PACKET_HINTS = listOf("红包", "已存入零钱", "已存入你的零钱")
    private val TRANSFER_HINTS = listOf("转账", "已收钱", "已存入对方零钱")
}
