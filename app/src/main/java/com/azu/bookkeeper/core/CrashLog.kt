package com.azu.bookkeeper.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局崩溃捕获。
 *
 * 为什么必须要有：自检/识屏链路上的崩溃可能是 native 崩溃（tombstone，Java 接不住），
 * 也可能发生在 UI 前置流程里（用户表现为「一点就闪退」，页面还没来得及更新）。
 * 没有栈就只能在黑暗里改代码。
 *
 * 做法：安装默认未捕获异常处理器，把进程名、线程、机型与完整栈写进 filesDir/last_crash.txt，
 * 同时用 Log.e 输出（可被抓 adb 的用户看到）。下次进入自检页即可读到并复制。
 * 只记录，不吞异常——仍交给系统原有处理器，行为不变。
 */
object CrashLog {

    private const val TAG = "BookkeeperCrash"
    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(app, thread.name, throwable) }
            Log.e(TAG, "未捕获异常 @${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun persist(context: Context, threadName: String, throwable: Throwable) {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        val text = buildString {
            appendLine("time=${System.currentTimeMillis()}")
            appendLine("process=${processName(context)}")
            appendLine("thread=$threadName")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            appendLine(writer.toString().take(12_000))
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** 读取最近一次崩溃记录（无则 null）。 */
    fun last(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME)
            .takeIf { it.exists() && it.length() > 0 }
            ?.readText()
    }.getOrNull()

    fun clear(context: Context) = runCatching {
        File(context.filesDir, FILE_NAME).delete()
    }

    /** 读取当前进程名：/proc/self/cmdline 最可靠，不依赖 API 版本。 */
    private fun processName(context: Context): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trim('\u0000', '\n', ' ')
    }.getOrDefault("")
}
