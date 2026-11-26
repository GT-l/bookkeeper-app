package com.azu.bookkeeper.core

import android.content.Context

/**
 * 无障碍截屏（takeScreenshot）实验开关，**默认关闭**。
 *
 * 为什么默认关：无障碍截屏不需要录屏授权，体验最好，但实测在部分国产 ROM
 * （OrangeOS/ColorOS 等）上会直接把 App 进程打崩（系统侧 HardwareBuffer 处理异常，
 * Java 层完全接不住）。既然接不住，就不能默认开着让用户反复闪退。
 *
 * 策略：默认走媒体投影（有授权、稳定）；用户明确知道风险后，可在设置里手动开启本开关。
 * 开启后一旦崩溃，CaptureBreakerStore 还会继续熔断，形成两层保护。
 */
object A11yShotOptIn {

    private const val PREFS = "a11y_shot_optin"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    fun setEnabled(context: Context, enabled: Boolean) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
