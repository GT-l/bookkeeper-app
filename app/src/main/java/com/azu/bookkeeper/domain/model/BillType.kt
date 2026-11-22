package com.azu.bookkeeper.domain.model

/** 账单三类型（PRD §4）：支出 / 收入 / 转账。 */
enum class BillType(val code: Int, val label: String) {
    EXPENSE(0, "支出"),
    INCOME(1, "收入"),
    TRANSFER(2, "转账");

    companion object {
        fun fromCode(code: Int): BillType = entries.firstOrNull { it.code == code } ?: EXPENSE
    }
}
