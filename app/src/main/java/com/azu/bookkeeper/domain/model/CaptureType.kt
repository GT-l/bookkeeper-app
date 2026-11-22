package com.azu.bookkeeper.domain.model

/**
 * 自动化捕获通道（PRD §4.2）。
 * 抖音免密支付不新增枚举值，通过 CaptureCandidate.packageName（com.ss.android.ugc.aweme）+ 种子规则表达。
 */
enum class CaptureType(val code: Int, val label: String) {
    NONE(-1, "无"),
    ACCESSIBILITY_TEXT(0, "读屏文本"),
    PROJECTION_IMAGE(1, "截屏图像"),
    NOTIFICATION(2, "通知"),
    SMS(3, "短信");

    companion object {
        fun fromCode(code: Int): CaptureType = entries.firstOrNull { it.code == code } ?: NONE
    }
}
