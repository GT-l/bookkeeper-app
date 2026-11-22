package com.azu.bookkeeper.domain.model

/**
 * 账单来源（PRD §4.1 / §22.9）。
 * ADJUSTMENT 为余额校准专用标记：金额按 EXPENSE/INCOME 方向记账，
 * 但统计与预算必须排除该来源（PRD §12「单独标记，不计入收支统计」）。
 */
enum class BillSource(val code: Int, val label: String) {
    MANUAL(0, "手动"),
    IMPORT(1, "导入"),
    AUTO_CAPTURE(2, "自动捕获"),
    ADJUSTMENT(3, "余额调整"),
    QUICK_MANUAL(4, "快捷补记");

    companion object {
        fun fromCode(code: Int): BillSource = entries.firstOrNull { it.code == code } ?: MANUAL
    }
}
