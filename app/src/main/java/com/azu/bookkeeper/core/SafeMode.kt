package com.azu.bookkeeper.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全模式：悬浮球这类需要「悬浮窗 + ROM 特殊放行」的能力，在部分机型上
 * 一旦在启动阶段崩溃，会导致「每次打开 App 都在同一位置崩溃 → 永远进不去」。
 *
 * 机制：
 * - CrashGuard 检测到「上次启动即崩」→ 调 [activate]，此后不再自动拉起任何悬浮层服务；
 * - App 仍能正常进入，只是这些可选项默认不启动（设置页有醒目提示）；
 * - 用户在设置页**手动**打开对应开关时调 [clear]，恢复自动启动能力。
 * 这是一个纯本地开关（SharedPreferences），不依赖数据库/DataStore，保证崩溃时也能读到。
 */
object SafeMode {

    private const val PREFS = "safe_mode"
    private const val KEY_ACTIVE = "active"
    private const val KEY_REASON = "reason"
    private const val KEY_SINCE = "since"
    private const val KEY_OVERLAY_VERIFIED = "overlay_verified"

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    fun reason(context: Context): String? =
        prefs(context).getString(KEY_REASON, null)

    fun activate(context: Context, reason: String) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_REASON, "$reason（$time）")
            .putLong(KEY_SINCE, System.currentTimeMillis())
            // 崩溃过 → 之前的「悬浮层可用」判定作废
            .putBoolean(KEY_OVERLAY_VERIFIED, false)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).remove(KEY_REASON).apply()
    }

    /** 悬浮层至少成功挂载过一次（本次安装内），可作为「本机支持」的正向信号。 */
    fun markOverlayVerified(context: Context) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_VERIFIED, true).apply()
    }

    fun isOverlayVerified(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_VERIFIED, false)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
