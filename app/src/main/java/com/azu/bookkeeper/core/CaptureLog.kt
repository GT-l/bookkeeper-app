package com.azu.bookkeeper.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识屏日志（只在用户开启识屏/截屏授权后记录）。
 *
 * 目的：识屏链路的失败往往发生在 native 层或系统回调里，用户只能看到「失败」，
 * 开发者拿不到任何上下文。这里把每个环节的输入/输出/异常按时间顺序落盘，
 * 用户可在自检页「一键复制」整段日志发给开发者，直接定位问题。
 *
 * 约束：
 * - 只在开启识屏时记录（[setEnabled]），不做全局埋点，不采集账单内容；
 * - 文件上限 256KB，超出自动裁剪最旧内容，永不无界增长；
 * - 写入失败静默（日志系统本身绝不影响主流程）。
 */
object CaptureLog {

    private const val TAG = "CaptureLog"
    private const val PREFS = "capture_log_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val FILE_NAME = "capture_log.txt"
    private const val MAX_BYTES = 256 * 1024L
    private const val KEEP_LINES_ON_TRIM = 600

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    @Volatile
    private var enabled = false

    fun init(context: Context) {
        // v1.3.13：默认开启。用户反馈「识屏完全没反应、log 里也什么都没有」时，
        // 关闭状态的日志让排查只能靠猜；日志只存本机、上限 256KB 自滚动，
        // 代价远小于「出了问题却没有现场」。
        enabled = runCatching {
            prefs(context).getBoolean(KEY_ENABLED, true)
        }.getOrDefault(true)
    }

    fun isEnabled(): Boolean = enabled

    /**
     * 开启 / 关闭识屏日志。开启时写一条会话头（机型、系统、可用通道），
     * 方便开发者判断环境。
     */
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        runCatching {
            prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        }
        if (value) {
            write(context, "INFO", "SESSION", "识屏日志已开启 device=${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android=${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT})")
        } else {
            write(context, "INFO", "SESSION", "识屏日志已关闭")
        }
    }

    fun i(context: Context, tag: String, message: String) = write(context, "INFO", tag, message)

    fun w(context: Context, tag: String, message: String, t: Throwable? = null) =
        write(context, "WARN", tag, message, t)

    fun e(context: Context, tag: String, message: String, t: Throwable? = null) =
        write(context, "ERROR", tag, message, t)

    /**
     * 关键诊断：即使用户没开启「识屏日志」也强制落盘。
     * 只给无障碍被系统关闭这类「用户必须把 log 发回来才能定位」的事件使用，
     * 不记录账单内容，也不会把普通日志全量打开。
     */
    fun force(context: Context, tag: String, message: String, t: Throwable? = null) =
        write(context, "ERROR", tag, message, t, force = true)

    /**
     * 与 [force] 相同（不受识屏日志开关影响），但以 INFO 级别落盘。
     * 专用于「服务连上」这类正常却关键的现场：v1.2.1 只记录断开不记录连接，
     * 用户复制回来的 log 全是 ERROR，无法还原「连上 → 1 秒后被撤销」的完整时间线。
     */
    fun forceInfo(context: Context, tag: String, message: String) =
        write(context, "INFO", tag, message, force = true)

    /** 导出最近日志（默认 600 行），供用户复制。 */
    fun export(context: Context, maxLines: Int = 600): String = runCatching {
        val file = file(context)
        if (!file.exists()) return@runCatching "（暂无日志：请先在自动化设置里开启识屏，然后复现一次问题）"
        file.readLines().takeLast(maxLines).joinToString("\n")
    }.getOrElse { "（读取日志失败：${it.javaClass.simpleName}）" }

    fun sizeBytes(context: Context): Long = runCatching { file(context).length() }.getOrDefault(0L)

    fun clear(context: Context) = runCatching { file(context).delete() }

    private fun write(
        context: Context,
        level: String,
        tag: String,
        message: String,
        t: Throwable? = null,
        force: Boolean = false
    ) {
        // 未开启识屏时只写 logcat，不落盘（避免无谓占用）；
        // force=true 仅用于无障碍被关闭等关键诊断，不受开关影响。
        if (!enabled && !force) {
            runCatching { Log.println(if (level == "ERROR") Log.ERROR else Log.INFO, "BK-$tag", message) }
            return
        }
        synchronized(lock) {
            runCatching {
                val line = buildString {
                    append(timeFormat.format(Date()))
                    append(' ').append(level)
                    append('[').append(tag).append("] ")
                    append(message)
                    t?.let {
                        append(" | ").append(it.javaClass.simpleName).append(": ").append(it.message)
                        val firstFrame = it.stackTrace.firstOrNull()
                        if (firstFrame != null) append(" @").append(firstFrame)
                    }
                    append('\n')
                }
                val file = file(context)
                file.appendText(line)
                if (file.length() > MAX_BYTES) trim(file)
            }.onFailure { Log.w(TAG, "write failed", it) }
        }
    }

    /** 超限裁剪：保留最后 KEEP_LINES_ON_TRIM 行。 */
    private fun trim(file: File) {
        runCatching {
            val kept = file.readLines().takeLast(KEEP_LINES_ON_TRIM)
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
