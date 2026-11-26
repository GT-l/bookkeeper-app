package com.azu.bookkeeper.domain.model

/** 信用卡账单日 / 还款日设置（附在账户上的元数据，1~28 号）。 */
data class CardReminder(
    val accountId: Long,
    val billDay: Int = 0,
    val dueDay: Int = 0,
    val enabled: Boolean = false
) {
    val configured: Boolean get() = enabled && billDay in 1..28 && dueDay in 1..28
}
