package com.azu.bookkeeper.automation.dedup

/**
 * 重复判定的统一准入规则（v1.3.2）。
 *
 * 真机反馈：触发一次重复提醒后，之后每一笔都被判成重复，**金额不同也一样**。
 * 根因是去重键在「金额无效 / 商户名泛化」时会退化成同一个值：
 * - 金额抽不到时键里写 0，所有候选共享；
 * - 商户只抽到「支付 / 已完成 / 微信支付」这类页面标题，所有账单的备注前缀相同；
 * - 旧账单只被命中一次，却反复提醒。
 *
 * 规则：金额必须为正、商户必须是具体名字（长度 ≥2 且不是泛词），否则**一律不判重复**。
 * 宁可漏判，也不能把正常消费误报成重复——误报会直接挡住自动记账。
 */
object DuplicateRules {

    /** 这些词是页面标题 / 状态 / 支付方式，不是商户名。 */
    private val GENERIC_MERCHANTS = listOf(
        "支付", "付款", "收款", "账单", "交易", "订单", "详情", "完成", "已完成", "成功",
        "微信", "微信支付", "支付宝", "零钱", "余额", "银行卡", "支付方式", "付款方式",
        "金额", "实付", "合计", "商品", "说明", "时间", "转账", "消费", "支出", "收入",
        "退款", "待支付", "支付成功", "付款成功", "交易成功"
    )

    /** 商户名必须具体：长度 ≥2 且不是页面标题 / 状态词。 */
    fun isValidMerchant(remark: String): Boolean {
        val name = remark.trim()
        if (name.length < 2) return false
        return GENERIC_MERCHANTS.none { name == it || (name.startsWith(it) && name.length <= it.length + 1) }
    }

    /** 去重键是否可用于判定：金额无效 / 商户泛化 → false。 */
    fun isValidPair(amountFen: Long?, remark: String): Boolean =
        amountFen != null && amountFen > 0L && isValidMerchant(remark)

    /** 商户前缀匹配（备注写成「商户 · 购买内容」，商户始终是前缀）。 */
    fun merchantMatches(savedRemark: String, merchant: String): Boolean {
        if (!isValidMerchant(merchant)) return false
        val saved = savedRemark.trim()
        val name = merchant.trim()
        return saved == name || saved.startsWith(name)
    }
}
