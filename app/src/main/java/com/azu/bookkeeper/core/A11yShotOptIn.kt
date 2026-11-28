package com.azu.bookkeeper.core

import android.content.Context

/**
 * 无障碍截屏（takeScreenshot）开关。
 *
 * **v1.3.12 起默认开启**（旧版本默认关闭）。
 *
 * 变更原因：用户 realme RMX3888 / Android 37 的日志显示，
 * 微信账单详情这类自绘 / WebView 页面读不到任何节点文本，而媒体投影会话
 * 在 App 重启后必然失效（Android 14+ 规则）。两条通道同时关闭的结果是
 * 摇一摇与自动识屏全部失效 —— 「免授权」的无障碍截屏是唯一不依赖会话的兜底，
 * 必须默认可用。
 *
 * 崩溃风险由 [CaptureBreakerStore] 兜底：调用前写 in-flight 标记，若某 ROM 上
 * takeScreenshot 把进程打崩，标记残留会在下次启动自动熔断该通道，不会反复闪退。
 * 用户也可以在自动化设置里手动关闭本开关。
 */
object A11yShotOptIn {

    private const val PREFS = "a11y_shot_optin"
    private const val KEY_ENABLED = "enabled"

    /** 默认开启（未显式设置过时返回 true）。 */
    fun isEnabled(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }.getOrDefault(true)

    fun setEnabled(context: Context, enabled: Boolean) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
