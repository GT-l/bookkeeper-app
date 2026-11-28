package com.azu.bookkeeper.automation.ocr

import com.azu.bookkeeper.core.SafeRegex

/**
 * v1.3.23 · 受 [问题四-位置规则] 约束：按「金额上方找收款方」定位真实商户。
 *
 * 真机证据（用户原文）：微信支付成功页里，真正的付款接收方**只在金额上方一行提示**，
 * 金额下方那一堆字是付款方式（零钱 / 招商银行储蓄卡）、交易单号、时间等辅助信息。
 * 旧实现用「整页找带后缀的独立行」兜底，`招商银行储蓄卡` 含「银行」二字，
 * 于是被当成商户名——钱付给了谁没读到，钱从哪张卡扣的倒记上了。
 *
 * 本对象只做一件事：找到金额行，从它**向上**最多看 4 行，取第一条有效收款方。
 * 不向下找（下方是付款方式/单号区），不猜词表，不改任何已有判定。
 *
 * 防错边界（受 [问题四-位置规则] 约束）：
 * - 付款方式、银行卡 / 储蓄卡 / 信用卡 / 零钱 / 尾号一律排除；
 * - 财付通 / 网联 / 银联 / 微信支付等通道名与收单机构排除；
 * - 状态行、金额行、时间行、单号行、UI 按钮文案排除；
 * - 4 行内无命中返回 null，绝不拿下方文本回填。
 *
 * 纯函数，不依赖 Android / Hilt，可直接喂原文样本做单测。
 */
object PaymentMerchantLocator {

    /**
     * 受 [问题四-位置规则] 约束
     * @param lines OCR / 读屏得到的文本行（顺序必须与页面阅读顺序一致）
     * @return 金额上方最近的有效收款方；没有则 null
     */
    fun locateAboveAmount(lines: List<String>): String? {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val amountIndex = cleaned.indexOfFirst { isAmountLine(it) }
        if (amountIndex < 0) return null

        val from = maxOf(0, amountIndex - LOOK_UP_LINES)
        for (index in amountIndex - 1 downTo from) {
            val line = cleaned[index]
            // 显式标签行：收款方：喜茶 —— 值就在同一行
            labelledValue(line)?.let { value ->
                if (isUsablePayee(value)) return clean(value)
            }
            // 普通行：微信支付成功页常见的「喜茶点单小程序」独占一行
            if (isUsablePayee(line) && looksLikePayee(line)) return clean(line)
        }
        return null
    }

    /** 受 [问题四-位置规则] 约束：这一行是不是金额行（含 ¥ / 实付 / 纯数字）。 */
    private fun isAmountLine(line: String): Boolean {
        if (AMOUNT_LINE.matches(line)) return true
        if (line.contains("¥") || line.contains("￥")) {
            return AMOUNT_AMOUNT.find(line) != null
        }
        return AMOUNT_LABEL.find(line) != null
    }

    /**
     * 受 [问题四-位置规则] 约束：这行能不能当收款方。
     * 这里是「排除法」，宁可漏掉也不把付款方式/通道名记成商户。
     */
    private fun isUsablePayee(value: String): Boolean {
        if (value.length !in 2..MAX_PAYEE_LENGTH) return false
        if (value.all { it.isDigit() }) return false
        if (EXCLUDED.any { it in value }) return false
        if (LABEL_FRAGMENTS.any { it == value }) return false
        if (UI_WORDS.any { it == value }) return false
        if (PAYMENT_METHOD.any { it in value }) return false
        if (CHANNEL_WORDS.any { it in value }) return false
        if (STATUS_WORDS.any { it in value }) return false
        if (DATE_LINE.containsMatchIn(value)) return false
        return true
    }

    /** 受 [问题四-位置规则] 约束：像实体的行才算收款方，避免抓到任意说明文字。 */
    private fun looksLikePayee(value: String): Boolean {
        if (value.contains("小程序")) return true
        if (SHOP_SUFFIXES.any { value.contains(it) }) return true
        // 中英混排品牌（喜茶 HEYTEA / Apple Store）
        val hasCjk = value.any { it.code in 0x4E00..0x9FFF }
        val hasLatin = value.any { it in 'A'..'z' }
        if (hasCjk && hasLatin) return true
        // 纯英文品牌（Apple Store / STARBUCKS）
        if (!hasCjk && value.any { it.isLetter() } && value.length in 2..30 &&
            value.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".-&'()" }
        ) return true
        // 纯中文短行：微信「商户名」常就是 2~12 个汉字独占一行
        return hasCjk && value.length in 2..12 && NON_PAYEE_TEXT.none { it in value }
    }

    private fun labelledValue(line: String): String? =
        PAYEE_LABEL.find(line)?.groupValues?.getOrNull(1)?.trim()

    private fun clean(value: String): String =
        value.trim().trim('：', ':', '·', '-', '—', '|', '，', ',').trim()

    private const val LOOK_UP_LINES = 4
    private const val MAX_PAYEE_LENGTH = 40

    private val PAYEE_LABEL = SafeRegex.of(
        """(?:收款方|收款人|收款单位|商户名称|商户全称|商户简称|商户|商家|交易对方|付款给|付款至)\s*[:：]?\s*([^ ，。；;：:]{2,30})"""
    )
    private val AMOUNT_LINE = SafeRegex.of("^[-+−]?(?:¥|￥)?[0-9][0-9,]*(?:\\.[0-9]{1,2})?元?$")
    private val AMOUNT_AMOUNT = SafeRegex.of("[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
    private val AMOUNT_LABEL = SafeRegex.of("(?:(?:实付|付款|支付|扣款|交易)金额?|合计)[^0-9]{0,6}[0-9]")
    private val DATE_LINE = SafeRegex.of("20\\d{2}[-/年]\\d{1,2}[-/月]\\d{1,2}")

    /** 受 [问题四-位置规则] 约束：钱从哪来，不是钱付给谁。 */
    private val PAYMENT_METHOD = listOf(
        "付款方式", "支付方式", "支付方法", "零钱", "零钱通", "余额", "银行卡",
        "储蓄卡", "信用卡", "尾号", "卡号", "余额宝", "银行", "数字人民币"
    )
    /** 受 [问题四-位置规则] 约束：钱走哪条通道，不是钱付给谁。 */
    private val CHANNEL_WORDS = listOf(
        "财付通", "网联", "银联", "微信支付", "支付宝", "支付服务", "支付机构",
        "收单机构", "清算机构", "拉卡拉", "快钱", "易宝", "银联商务"
    )
    private val STATUS_WORDS = listOf(
        "支付成功", "付款成功", "扣款成功", "交易成功", "交易完成", "已支付",
        "支付完成", "已付款", "退款成功", "已退款", "已取消", "支付失败"
    )
    private val EXCLUDED = listOf(
        "单号", "编号", "流水", "时间", "备注", "说明", "订单", "商品", "金额",
        "优惠", "红包", "积分", "状态", "客服", "账单", "明细", "记录"
    )
    private val LABEL_FRAGMENTS = setOf("全称", "简称", "名称", "商户全称", "商户简称", "商户名称")
    private val UI_WORDS = setOf(
        "返回", "完成", "取消", "确认", "更多", "详情", "查看", "支付", "付款",
        "收款", "交易", "成功", "失败", "全屏", "客服", "订单", "账单"
    )
    private val NON_PAYEE_TEXT = listOf("支付", "付款", "收款", "交易", "订单", "成功", "失败", "返回")
    private val SHOP_SUFFIXES = listOf(
        "店", "铺", "馆", "坊", "超市", "便利", "咖啡", "茶", "餐饮", "旗舰店",
        "专营店", "商行", "药房", "医院", "酒店", "食堂", "公司", "Ltd", "Inc"
    )
}
