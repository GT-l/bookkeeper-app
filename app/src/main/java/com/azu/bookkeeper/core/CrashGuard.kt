package com.azu.bookkeeper.core

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自愈（用户反馈：授权后闪退，且再次打开仍然闪退、无法进入 App）。
 *
 * 「打不开」的经典成因有两类：
 * 1. 进程在数据库写入中途被系统强杀（如授予悬浮窗权限时 ROM 会 force-stop），
 *    SQLCipher 的 WAL/主库损坏后，首页任何一条 Room 查询都会抛异常，
 *    而 HomeViewModel 的 collect 在协程里未捕获 → 每次启动必崩 → 永远进不去；
 * 2. 悬浮球 / 三指手势这类悬浮层服务在启动阶段崩溃，而 onResume 每次都会把它拉起来
 *    → 「打开就崩 → 系统重启 → 又拉起 → 又崩」的死循环。
 *
 * 方案（启动哨兵 + 安全模式 + 自愈 + 崩溃留档）：
 * 1. 每次启动写入「启动哨兵」计数；30 秒无崩溃则清零；
 * 2. 下次启动若发现计数 >= 1（上一次启动在 30 秒内崩了）→ 进入安全模式，
 *    不再自动拉起悬浮层服务；仅当崩溃堆栈指向数据库时才备份重建库（保留现场），
 *    让 Room 重建空库，FirstRunSeeder 随即补回默认账本/分类/账户；
 * 3. 悬浮层服务已隔离到 `:overlay` 进程，它崩掉不会拖死主进程；该进程的崩溃由
 *    [installHandler] 写入独立的 overlay_guard 标记，主进程下次启动据此进入安全模式；
 * 4. 把完整堆栈写进 filesDir/crash_last.txt，用户反馈时可直接把该文件发给我们定位。
 *
 * 注意：本类必须先于任何数据库/依赖注入动作执行（Application.onCreate 最前面），
 * 且只有主进程做「哨兵 / 安全模式 / 重建库」，附属进程只负责留档 + 标记，避免误搬库文件。
 */
object CrashGuard {

    private const val PREFS = "crash_guard"
    private const val KEY_STARTUP_CRASHES = "startup_crashes"
    private const val STABLE_WINDOW_MS = 30_000L
    private const val CRASH_LOG = "crash_last.txt"

    /** 附属进程（:overlay）崩溃标记；单独一个 prefs 文件，避免与主进程的哨兵互相覆盖。 */
    private const val OVERLAY_PREFS = "overlay_guard"
    private const val KEY_OVERLAY_CRASHED = "crashed"
    private const val OVERLAY_CRASH_LOG = "crash_overlay.txt"

    /** 只有这些关键字出现时才认为是数据库损坏，才做重建——避免无关崩溃清掉用户账单。 */
    private val DB_MARKERS = listOf(
        "SQLite", "sqlcipher", "SQLCipher", "android.database", "Room", "disk I/O", "corrupt"
    )

    /** 当前进程是否就是主进程（:overlay 等附属进程返回 false）。 */
    fun isMainProcess(context: Context): Boolean = runCatching {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // 低版本没有 Application.getProcessName()：进程名 = /proc/self/cmdline（NUL 结尾）
            File("/proc/self/cmdline").readBytes().toString(Charsets.UTF_8)
                .trimEnd('\u0000').trim()
        }
        name == context.packageName
    }.getOrDefault(true)

    fun install(app: Application) {
        val mainProcess = isMainProcess(app)
        // 所有进程都要留档；但只有主进程做启动哨兵与自愈，否则附属进程会把主进程正在用的库搬走
        installHandler(app, mainProcess)
        if (mainProcess) startupSentinel(app)
    }

    /**
     * 启动哨兵：判定「上次启动是否在稳定窗口内崩溃」，并据此进入安全模式 / 自愈。
     */
    private fun startupSentinel(app: Application) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 悬浮层进程崩溃：主进程毫发无伤，但悬浮球一定不可用，必须停掉自动拉起，
        // 否则每次回到前台都会再尝试一次 → 系统反复弹「少花点儿已停止」。
        val overlayCrashed = runCatching {
            val p = app.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
            val crashed = p.getBoolean(KEY_OVERLAY_CRASHED, false)
            if (crashed) p.edit().putBoolean(KEY_OVERLAY_CRASHED, false).commit()
            crashed
        }.getOrDefault(false)
        if (overlayCrashed) {
            runCatching { SafeMode.activate(app, "悬浮球/三指手势启动崩溃") }
            Timber.w("crash guard: overlay process crashed before, safeMode=on")
        }

        val previousCrashes = prefs.getInt(KEY_STARTUP_CRASHES, 0)
        if (previousCrashes >= 1) {
            // 上一次启动在稳定窗口内崩溃了 → 判定为启动崩溃循环：
            // 1) 进入安全模式，停止自动拉起悬浮球/三指手势（否则每次启动都会再崩一次，永远进不去）；
            // 2) 仅当崩溃堆栈指向数据库时才备份重建库，避免无关崩溃误清用户账单。
            runCatching { SafeMode.activate(app, "上次启动异常退出") }
            val dbRelated = runCatching {
                val log = File(app.filesDir, CRASH_LOG).takeIf { it.exists() }?.readText().orEmpty()
                DB_MARKERS.any { log.contains(it, ignoreCase = true) }
            }.getOrDefault(false)
            if (dbRelated) {
                runCatching { selfHeal(app) }
                    .onFailure { Timber.e(it, "crash guard self-heal failed") }
            }
            prefs.edit().putInt(KEY_STARTUP_CRASHES, 0).apply()
            Timber.w("crash guard: startup crash loop detected, safeMode=on, dbSelfHeal=%s", dbRelated)
        }

        // 标记本次启动（若 30 秒内崩溃，标记留存到下一次启动触发安全模式）
        prefs.edit().putInt(KEY_STARTUP_CRASHES, previousCrashes + 1).apply()
        Handler(Looper.getMainLooper()).postDelayed({
            prefs.edit().putInt(KEY_STARTUP_CRASHES, 0).apply()
        }, STABLE_WINDOW_MS)
    }

    /**
     * 崩溃留档：不拦截崩溃本身，只留证据。
     * 附属进程额外写一个 crash 标记（commit 同步落盘，进程即将结束，apply 可能来不及）。
     */
    private fun installHandler(app: Application, mainProcess: Boolean) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                val process = if (mainProcess) "main" else "overlay"
                File(app.filesDir, if (mainProcess) CRASH_LOG else OVERLAY_CRASH_LOG).writeText(
                    "time=$time process=$process thread=${thread.name}\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
                if (!mainProcess) {
                    app.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_OVERLAY_CRASHED, true)
                        .commit()
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 自愈：把疑似损坏的数据库文件整体挪到 crash_backup（不删除，保留现场），
     * Room 下次打开会重建空库，FirstRunSeeder 补回默认数据。
     */
    private fun selfHeal(app: Context) {
        val dbPath = app.getDatabasePath(Constants.DATABASE_NAME)
        val dbFile = dbPath.absoluteFile
        val parent = dbFile.parentFile ?: return
        val backupDir = File(parent, "crash_backup").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val candidates = listOf(dbFile) + listOf("-wal", "-shm", "-journal").map {
            File(parent, Constants.DATABASE_NAME + it)
        }
        candidates.filter { it.exists() && it.length() > 0L }.forEach { file ->
            val target = File(backupDir, "${file.name}.$stamp.bak")
            if (!file.renameTo(target)) {
                // rename 跨路径失败时退回复制 + 删除
                file.copyTo(target, overwrite = true)
                file.delete()
            }
            Timber.w("crash guard: moved %s -> %s", file.name, target.name)
        }
    }
}
