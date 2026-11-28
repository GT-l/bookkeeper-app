package com.azu.bookkeeper.core

import android.content.Context

/**
 * 无障碍事件到达统计（v1.3.13）。
 *
 * 为什么需要它：用户反馈「微信所有界面都不触发识屏，log 都不触发」。
 * 这句话有两种完全不同的成因，靠现有日志分不出来：
 *
 * 1. **系统根本没有把事件投递给服务**（服务被解绑 / ROM 限制 / 开关被关）；
 * 2. 事件到了，但被包名白名单、页面识别或节流日志挡掉了。
 *
 * 这里在 `dispatchEvent` 的第一行（任何过滤之前）计数，把「今天来了多少条事件、
 * 其中多少条来自支付应用、最后一条是什么包名什么类型」摊在自检页上：
 *
 * - 计数为 0 → 事件没到，问题在系统侧（不需要再改识别规则）；
 * - 计数 > 0 但支付应用为 0 → 包名过滤问题；
 * - 计数正常 → 问题在识别/抽取，可以按页面继续查。
 *
 * 计数放内存、按天重置，落盘节流 5 秒，不会因为微信页面事件频繁而拖慢主线程。
 */
object AccessibilityEventStats {

    private const val PREFS = "a11y_event_stats"
    private const val KEY_DAY = "day"
    private const val KEY_TOTAL = "total"
    private const val KEY_PAYMENT = "payment"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val KEY_LAST_TYPE = "last_type"
    private const val FLUSH_INTERVAL_MS = 5_000L

    /** 快照：自检页直接渲染。 */
    data class Snapshot(
        val day: Int,
        val total: Int,
        val paymentTotal: Int,
        val lastAt: Long,
        val lastPackage: String,
        val lastEventType: Int
    ) {
        val hasAny: Boolean get() = total > 0
        val hasPayment: Boolean get() = paymentTotal > 0
    }

    @Volatile private var day: Int = 0
    @Volatile private var total: Int = 0
    @Volatile private var paymentTotal: Int = 0
    @Volatile private var lastAt: Long = 0L
    @Volatile private var lastPackage: String = ""
    @Volatile private var lastEventType: Int = 0
    @Volatile private var loaded = false
    @Volatile private var lastFlushAt = 0L

    /**
     * 记录一条事件。必须在任何包名 / 页面过滤**之前**调用，
     * 否则统计本身也会被过滤掉，就失去诊断价值了。
     */
    @Synchronized
    fun onEvent(context: Context, packageName: String, eventType: Int, isPaymentPackage: Boolean) {
        val today = todayKey()
        ensureLoaded(context, today)
        if (today != day) {
            day = today
            total = 0
            paymentTotal = 0
            lastAt = 0L
            lastPackage = ""
            lastEventType = 0
        }
        total++
        if (isPaymentPackage) paymentTotal++
        lastAt = System.currentTimeMillis()
        lastPackage = packageName
        lastEventType = eventType

        val now = lastAt
        if (now - lastFlushAt >= FLUSH_INTERVAL_MS || total % 50 == 0) {
            lastFlushAt = now
            flush(context)
        }
    }

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        ensureLoaded(context, todayKey())
        return Snapshot(day, total, paymentTotal, lastAt, lastPackage, lastEventType)
    }

    /** 清空统计（自检页「重置计数」用）。 */
    @Synchronized
    fun reset(context: Context) {
        day = todayKey()
        total = 0
        paymentTotal = 0
        lastAt = 0L
        lastPackage = ""
        lastEventType = 0
        loaded = true
        flush(context)
    }

    private fun ensureLoaded(context: Context, today: Int) {
        if (loaded) return
        loaded = true
        runCatching {
            val prefs = prefs(context)
            val savedDay = prefs.getInt(KEY_DAY, 0)
            if (savedDay == today) {
                day = savedDay
                total = prefs.getInt(KEY_TOTAL, 0)
                paymentTotal = prefs.getInt(KEY_PAYMENT, 0)
                lastAt = prefs.getLong(KEY_LAST_AT, 0L)
                lastPackage = prefs.getString(KEY_LAST_PKG, "").orEmpty()
                lastEventType = prefs.getInt(KEY_LAST_TYPE, 0)
            } else {
                day = today
            }
        }
    }

    private fun flush(context: Context) = runCatching {
        prefs(context).edit()
            .putInt(KEY_DAY, day)
            .putInt(KEY_TOTAL, total)
            .putInt(KEY_PAYMENT, paymentTotal)
            .putLong(KEY_LAST_AT, lastAt)
            .putString(KEY_LAST_PKG, lastPackage)
            .putInt(KEY_LAST_TYPE, lastEventType)
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** yyyyMMdd，按天重置，避免历史累计把「今天没事件」掩盖掉。 */
    private fun todayKey(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.YEAR) * 10_000 +
            (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
            cal.get(java.util.Calendar.DAY_OF_MONTH)
    }
}
