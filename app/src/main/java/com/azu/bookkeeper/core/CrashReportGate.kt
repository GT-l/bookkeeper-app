package com.azu.bookkeeper.core

import android.content.Context

/**
 * 崩溃报告闸门：决定「这次启动要不要主动把上次的崩溃栈怼到用户面前」。
 *
 * 为什么需要：之前把崩溃栈藏在「识屏自检」页里，结果自检页本身一点就崩，
 * 用户既看不到栈也进不去页面，信息等于丢失。现在改为启动时主动弹窗——
 * 只要发生过新崩溃，打开 App 第一眼就能看到并可一键复制。
 *
 * 去重：记住上次已展示过的崩溃时间戳，同一份崩溃只弹一次，避免每次启动都骚扰。
 */
object CrashReportGate {

    private const val PREFS = "crash_report_gate"
    private const val KEY_SHOWN_AT = "shown_crash_time"

    /** 若存在「尚未展示过」的崩溃，返回可读摘要；否则 null。 */
    fun pending(context: Context): String? {
        val raw = CrashLog.last(context) ?: return null
        val crashTime = raw.lineSequence()
            .firstOrNull { it.startsWith("time=") }
            ?.removePrefix("time=")
            ?.toLongOrNull()
            ?: return null
        val shown = prefs(context).getLong(KEY_SHOWN_AT, 0L)
        if (crashTime <= shown) return null
        return raw
    }

    /** 标记该崩溃已展示（用户点过「知道了」或已复制）。 */
    fun markShown(context: Context) {
        val raw = CrashLog.last(context) ?: return
        val crashTime = raw.lineSequence()
            .firstOrNull { it.startsWith("time=") }
            ?.removePrefix("time=")
            ?.toLongOrNull()
            ?: return
        prefs(context).edit().putLong(KEY_SHOWN_AT, crashTime).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
