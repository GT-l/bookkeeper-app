package com.azu.bookkeeper.core

import android.content.Context

/**
 * 截屏通道熔断器（SharedPreferences 持久化，进程被杀也留得住）。
 *
 * 生命周期：
 * 1. 调用前 [markInflight]；
 * 2. 正常结束（成功或已知失败）[clearInflight]；
 * 3. App 下次启动时 [reconcile]：若 inflight 仍在 → 上次必定是崩溃，打开熔断并记录原因；
 * 4. 用户在自检页点「恢复截屏通道」→ [reset] 全部清除。
 */
object CaptureBreakerStore {

    private const val PREFS = "capture_breaker"
    private const val KEY_OPEN_PREFIX = "open_"
    private const val KEY_INFLIGHT_PREFIX = "inflight_"
    private const val KEY_REASON = "last_reason"
    private const val KEY_CRASHED_AT = "last_crashed_at"

    fun markInflight(context: Context, channel: CaptureChannel) = runCatching {
        edit(context).putBoolean(KEY_INFLIGHT_PREFIX + channel.key, true).apply()
    }

    fun clearInflight(context: Context, channel: CaptureChannel) = runCatching {
        edit(context).putBoolean(KEY_INFLIGHT_PREFIX + channel.key, false).apply()
    }

    fun isOpen(context: Context, channel: CaptureChannel): Boolean = runCatching {
        prefs(context).getBoolean(KEY_OPEN_PREFIX + channel.key, false)
    }.getOrDefault(false)

    fun open(context: Context, channel: CaptureChannel) = runCatching {
        edit(context)
            .putBoolean(KEY_OPEN_PREFIX + channel.key, true)
            .putBoolean(KEY_INFLIGHT_PREFIX + channel.key, false)
            .putString(KEY_REASON, CaptureGate.breakerReason(channel))
            .putLong(KEY_CRASHED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 启动时对账：发现残留 inflight → 上次崩在该通道，自动熔断。返回被熔断的通道说明。 */
    fun reconcile(context: Context): String? = runCatching {
        val prefs = prefs(context)
        CaptureChannel.entries.firstOrNull { channel ->
            prefs.getBoolean(KEY_INFLIGHT_PREFIX + channel.key, false)
        }?.let { crashed ->
            open(context, crashed)
            CaptureGate.breakerReason(crashed)
        }
    }.getOrNull()

    fun lastReason(context: Context): String? = runCatching {
        prefs(context).getString(KEY_REASON, null)
    }.getOrNull()

    fun lastCrashedAt(context: Context): Long = runCatching {
        prefs(context).getLong(KEY_CRASHED_AT, 0L)
    }.getOrDefault(0L)

    /** 用户手动恢复：清掉全部熔断与残留标记。 */
    fun reset(context: Context) = runCatching {
        edit(context).clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun edit(context: Context) = prefs(context).edit()
}
