package com.azu.bookkeeper.core

/** 两条截屏通道。 */
enum class CaptureChannel(val key: String, val label: String) {
    ACCESSIBILITY("a11y", "无障碍截屏"),
    PROJECTION("projection", "媒体投影")
}

/**
 * 通道准入的纯逻辑（无 Android 依赖，便于单元测试）。
 *
 * 背景：部分机型上无障碍 takeScreenshot / MediaProjection 会 native 崩溃（SIGSEGV/SIGABRT），
 * Java 层接不住，进程直接消失。既然接不住，就只能「熔断」：
 * 尝试前置一个 in-flight 标记，正常结束清除；下次启动发现标记还在 → 说明上次崩在这一步，
 * 永久停用该通道，避免用户每次点自检都闪退。
 */
object CaptureGate {

    /** 是否允许尝试某通道：通道可用 且 熔断未打开。 */
    fun shouldAttempt(available: Boolean, breakerOpen: Boolean): Boolean =
        available && !breakerOpen

    /** 熔断打开后该通道的说明文案。 */
    fun breakerReason(channel: CaptureChannel): String = when (channel) {
        CaptureChannel.ACCESSIBILITY ->
            "无障碍截屏在本机上次使用时导致进程崩溃，已自动停用；可在自检页「恢复截屏通道」后重试"
        CaptureChannel.PROJECTION ->
            "媒体投影在本机上次使用时导致进程崩溃，已自动停用；重新授权或点「恢复截屏通道」可重试"
    }
}
