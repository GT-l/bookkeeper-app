package com.azu.bookkeeper.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃探针（始终开启，极轻量）。
 *
 * 为什么在 [CrashLog] 之外还要它：CrashLog 只能抓「Java 未捕获异常」。
 * 但用户遇到的「一点就闪退」有三种可能：
 * 1. Java 异常 → CrashLog 能抓；
 * 2. native 崩溃（.so，SIGSEGV） → Java 层完全接不住，CrashLog 抓不到；
 * 3. 被系统/ROM 直接杀进程（内存、权限、策略） → 也没有 Java 栈。
 *
 * 后两种情况下，唯一能拿到的事实是「崩溃前最后执行到哪一行」。
 * 所以这里在每个关键入口前后各写一行：崩了以后最后一行就是断点位置，
 * 等价于给进程装了行车记录仪。
 *
 * 约束（自身绝不影响主流程）：
 * - 单文件 64KB，超限只保留最后 300 行；
 * - 全程 runCatching，写失败只打 logcat；
 * - 不记录任何账单内容，只记步骤名。
 */
object BootTrace {

    private const val TAG = "BootTrace"
    private const val FILE_NAME = "boot_trace.txt"
    private const val MAX_BYTES = 64 * 1024L
    private const val KEEP_LINES = 300

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    /** Application.onCreate 里调一次，缓存 filesDir 路径，之后打点不再依赖 Context。 */
    fun init(context: Context) {
        appContext = runCatching { context.applicationContext }.getOrNull()
        step("APP", "onCreate 开始")
    }

    fun step(tag: String, name: String) = append(tag, name, "")

    fun step(tag: String, name: String, extra: String) = append(tag, name, extra)

    private fun append(tag: String, name: String, extra: String) {
        Log.println(Log.INFO, "BK-TRACE", "$tag/$name $extra")
        val context = appContext ?: return
        synchronized(lock) {
            runCatching {
                val line = buildString {
                    append(timeFormat.format(Date()))
                    append(' ')
                    append(tag)
                    append('/')
                    append(name)
                    if (extra.isNotBlank()) {
                        append(' ')
                        append(extra.take(160))
                    }
                    append('\n')
                }
                val file = File(context.filesDir, FILE_NAME)
                file.appendText(line)
                if (file.length() > MAX_BYTES) {
                    val kept = file.readLines().takeLast(KEEP_LINES)
                    file.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    /** 导出探针记录（默认最近 120 行）。 */
    fun dump(context: Context, maxLines: Int = 120): String = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return@runCatching "（暂无探针记录）"
        file.readLines().takeLast(maxLines).joinToString("\n")
    }.getOrElse { "（读取失败：${it.javaClass.simpleName}）" }

    fun clear(context: Context) = runCatching {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}
