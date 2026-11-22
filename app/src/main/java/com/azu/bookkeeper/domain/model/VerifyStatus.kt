package com.azu.bookkeeper.domain.model

/** 自动化账单的校对状态（PRD §22.9）。 */
enum class VerifyStatus(val code: Int, val label: String) {
    PENDING(0, "待校对"),
    AUTO_PASSED(1, "自动通过"),
    USER_CONFIRMED(2, "用户确认"),
    USER_CORRECTED(3, "用户改写");

    companion object {
        fun fromCode(code: Int): VerifyStatus = entries.firstOrNull { it.code == code } ?: PENDING
    }
}
