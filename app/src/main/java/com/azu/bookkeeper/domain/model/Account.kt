package com.azu.bookkeeper.domain.model

/** 资产账户类型（PRD §12）；信用卡余额为负数表示欠款。 */
enum class AccountType(val code: Int, val label: String) {
    CASH(0, "现金"),
    WECHAT(1, "微信零钱"),
    ALIPAY(2, "支付宝余额"),
    DEBIT_CARD(3, "储蓄卡"),
    CREDIT_CARD(4, "信用卡"),
    WEALTH(5, "理财账户"),
    VIRTUAL_WALLET(6, "虚拟钱包"),
    CUSTOM(7, "自定义");

    companion object {
        fun fromCode(code: Int): AccountType = entries.firstOrNull { it.code == code } ?: CUSTOM
    }
}

/** 账户领域模型；balanceFen 只允许由 BillRepository 的单一事务写入口变更。 */
data class Account(
    val accountId: Long = 0L,
    val bookId: Long,
    val name: String,
    val type: AccountType = AccountType.CUSTOM,
    val initialBalanceFen: Long = 0L,
    val balanceFen: Long = initialBalanceFen,
    val icon: String = "",
    val remark: String = "",
    val enabled: Boolean = true,
    /** 信用卡账单日（1~28）；0 表示未设置。 */
    val billDay: Int = 0,
    /** 信用卡还款日（1~28）；0 表示未设置。 */
    val dueDay: Int = 0,
    /** 是否开启账单日 / 还款日提醒。 */
    val reminderEnabled: Boolean = false
) {
    /** 信用卡负债在总资产中按负值计入净资产。 */
    val netBalanceFen: Long get() = balanceFen

    /** 是否配置了有效的账单日提醒。 */
    val hasCardReminder: Boolean get() = reminderEnabled && billDay in 1..28 && dueDay in 1..28
}
