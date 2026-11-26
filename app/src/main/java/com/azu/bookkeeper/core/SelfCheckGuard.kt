package com.azu.bookkeeper.core

import android.content.Context

/**
 * 自检崩溃留痕。
 *
 * 背景：有些崩溃是 native 崩溃 / OOM，Java 层 try-catch 完全接不住（进程直接消失），
 * 用户只会看到「点自检 App 就退出」，且重启后什么都没有。
 *
 * 做法：自检每一步开始前把「当前步骤」写进 SharedPreferences 并标记 running=true，
 * 正常结束写 running=false。下次打开 App 时如果发现 running 仍为 true，
 * 就说明上次自检在第 N 步被系统终止 —— 这就是真正的「自查」结论。
 */
object SelfCheckGuard {

    private const val PREFS = "selfcheck_guard"
    private const val KEY_RUNNING = "running"
    private const val KEY_STEP = "step"
    private const val KEY_TIME = "time"

    fun markStep(context: Context, step: String) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, true)
            .putString(KEY_STEP, step)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun markFinished(context: Context) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, false).apply()
    }

    /** 上次自检是否异常中断（进程被杀），返回中断时的步骤名。 */
    fun lastInterruptedStep(context: Context): String? = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_RUNNING, false)) {
            prefs.getString(KEY_STEP, null)?.ifBlank { "未知步骤" }
        } else {
            null
        }
    }.getOrNull()

    /** 用户看到结论后清除标记。 */
    fun clear(context: Context) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
