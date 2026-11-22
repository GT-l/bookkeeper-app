package com.azu.bookkeeper.domain.model

/** 周期账单类型（PRD §4.1）。 */
enum class CycleType(val code: Int, val label: String) {
    NONE(0, "不重复"),
    DAILY(1, "每日"),
    WEEKLY(2, "每周"),
    MONTHLY(3, "每月"),
    YEARLY(4, "每年");

    companion object {
        fun fromCode(code: Int): CycleType = entries.firstOrNull { it.code == code } ?: NONE
    }
}
