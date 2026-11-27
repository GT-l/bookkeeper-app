package com.azu.bookkeeper.automation.permission

import android.content.Context
import android.os.Build
import com.azu.bookkeeper.core.CaptureLog
import com.azu.bookkeeper.core.RomCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次「无障碍被系统关闭 / 被解绑」的异常快照。
 *
 * @param signature 同一现场只弹一次，避免用户每次回到页面都被同一个弹窗打断。
 */
data class AccessibilityAnomaly(
    val title: String,
    val detail: String,
    val advice: String,
    val signature: String
)

/**
 * 无障碍健康检查。
 *
 * v1.2.3 依据第二轮真机日志（vivo V2505A / Android 17 API 37）修正：
 * - 连接 6 次、被撤销 4 次，**pid 始终 23522、进程已运行 3.4 分钟** → 进程从未被杀；
 * - 安装来源 `com.android.packageinstaller` → 手动装 APK 的侧载；
 * - 服务存活 0.6s / 1.4s / 3.3s / 67s 后开关变「关」。
 *
 * 结论：这是系统对侧载应用的授权复核 / 撤销（Android 13+「受限设置」、Android 17
 * 「高级保护模式」、厂商支付风控），**不是崩溃、不是 ANR、也不是后台省电清理**。
 * 上一版把重心放在后台保活与电池白名单上，方向错了：进程都没死，保活无从谈起。
 *
 * 应用无法写 Settings.Secure 改这个开关（需系统签名 / Shizuku / root），
 * 所以本类负责：判因 → 写出可判因现场 → 给出系统侧可执行解法（含 ADB 一次性授权）。
 */
object AccessibilityHealth {

    private const val PREFS = "accessibility_health"
    private const val KEY_LAST_ENABLED = "last_enabled"
    private const val KEY_LAST_REPORTED = "last_reported"
    private const val KEY_REVOKE_COUNT = "revoke_count"
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 服务真正连上时记一笔「曾经开启」。 */
    fun onServiceConnected(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAST_ENABLED, true).apply()
        }
    }

    /** 检测异常；同一现场只返回一次（通过 [KEY_LAST_REPORTED] 去重）。 */
    fun detect(context: Context, guide: PermissionGuide): AccessibilityAnomaly? = runCatching {
        val app = context.applicationContext
        val enabled = guide.isAccessibilityEnabled()
        val everConnected = guide.accessibilityServiceEverConnected()
        val lastConnected = guide.accessibilityLastConnectedAt()
        val lastDisconnected = guide.accessibilityLastDisconnectedAt()
        val lifespanMs = guide.accessibilityLastLifespanMs()
        val source = guide.installSourceText()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean(KEY_LAST_ENABLED, false)
        prefs.edit().putBoolean(KEY_LAST_ENABLED, enabled).apply()

        if (!everConnected) return@runCatching null

        val closed = wasEnabled && !enabled
        val unbound = enabled && lastDisconnected > lastConnected
        if (!closed && !unbound) return@runCatching null

        val signature = "${if (closed) "closed" else "unbound"}:$enabled:$lastConnected:$lastDisconnected"
        if (signature == prefs.getString(KEY_LAST_REPORTED, "")) return@runCatching null
        prefs.edit().putString(KEY_LAST_REPORTED, signature).apply()

        val revokeCount = prefs.getInt(KEY_REVOKE_COUNT, 0) + 1
        prefs.edit().putInt(KEY_REVOKE_COUNT, revokeCount).apply()
        val verdict = diagnose(
            enabled, everConnected, lifespanMs, revokeCount, source,
            guide.accessibilityPidStable(), guide.accessibilityConnectCount(), Build.VERSION.SDK_INT
        )

        val connectedText = if (lastConnected > 0L) timeFormat.format(Date(lastConnected)) else "从未连上"
        val disconnectedText = if (lastDisconnected > 0L) timeFormat.format(Date(lastDisconnected)) else "无记录"
        val detail = buildString {
            append("检测到无障碍服务异常：")
            append(if (closed) "系统开关已关闭（授权被撤销）" else "开关还在，但服务已被系统解绑")
            append("。\n最后连接：$connectedText")
            append("\n最后断开：$disconnectedText")
            append("\n本次服务存活：${formatDuration(lifespanMs)}")
            append("\n累计被撤销：$revokeCount 次 / 累计连接 ${guide.accessibilityConnectCount()} 次")
            append("\n进程 pid 是否稳定：${if (guide.accessibilityPidStable()) "是（进程未被杀）" else "否 / 数据不足"}")
            append("\n安装来源：$source")
            append("\n机型：${RomCompat.displayName()}")
            append("\n\n诊断结论：$verdict")
        }
        val advice = if (closed) {
            "系统撤销了授权，不是 App 崩溃，也不是后台被杀。按稳定运行设置顺序做：" +
                "① 关掉「高级保护模式」；② 允许受限设置（菜单找不到就用复制文本里的 ADB 命令）；" +
                "③ 重新打开无障碍开关。若仍秒关，只能用 Shevery / Shizuku 的系统级锁定。" +
                "点「复制 log」可把完整时间线与 ADB 命令一起带走。"
        } else {
            "开关还亮着但服务已解绑，更像是进程被回收：请到系统无障碍把少花点儿关掉再打开一次。" +
                "若这台机器是侧载安装且 Android 13+，也请一并检查「允许受限设置」。"
        }
        CaptureLog.force(
            app, "A11Y",
            "无障碍异常 kind=${if (closed) "closed" else "unbound"} enabled=$enabled " +
                "wasEnabled=$wasEnabled lastConnected=$lastConnected lastDisconnected=$lastDisconnected " +
                "lifespan=${lifespanMs}ms revokeCount=$revokeCount pidStable=${guide.accessibilityPidStable()} " +
                "source=$source api=${Build.VERSION.SDK_INT} device=${RomCompat.displayName()}"
        )
        AccessibilityAnomaly(
            title = if (closed) "系统撤销了无障碍授权" else "无障碍服务被解绑",
            detail = detail,
            advice = advice,
            signature = signature
        )
    }.getOrNull()

    /** 安装来源是否属于侧载。com.android.packageinstaller = 用户手动装 APK，最常见也最容易被漏判。 */
    private fun isSideloaded(source: String): Boolean {
        if (source.isBlank()) return true
        val s = source.lowercase(Locale.getDefault())
        if (s.contains("读取失败") || s.contains("未知") || s.contains("侧载")) return true
        if (s.contains("packageinstaller") || s.contains("com.android.shell") ||
            s.contains("adb") || (s.contains("installer") && !s.contains("store"))) return true
        // 明确的应用商店来源不算侧载
        if (s.contains("play.google") || s.contains("vivo") || s.contains("bbk") ||
            s.contains("market") || s.contains("appstore") || s.contains("huawei") ||
            s.contains("xiaomi") || s.contains("oppo") || s.contains("tencent")) return false
        return true
    }

    /**
     * 把现场翻译成人话结论。
     * v1.2.3 重点：pid 稳定就直接排除后台保活这条错误方向，把侧载受限设置 /
     * Android 17 高级保护模式列为首要嫌疑，并给出可执行的 ADB 解法。
     */
    private fun diagnose(
        enabled: Boolean,
        everConnected: Boolean,
        lifespanMs: Long,
        revokeCount: Int,
        source: String,
        pidStable: Boolean,
        connectCount: Int,
        apiLevel: Int
    ): String {
        if (!everConnected) return "服务从未连上过，请先在系统无障碍里开启少花点儿。"
        if (enabled) {
            return "系统开关仍显示开启，但服务已被解绑。这更接近进程被回收或服务被系统重启，" +
                "请关闭再打开一次无障碍开关；侧载设备也请一并检查「允许受限设置」。"
        }
        return buildString {
            append("系统关闭了无障碍开关（撤销授权），不是 App 崩溃、不是 ANR、也不是识屏逻辑报错。")
            if (pidStable) {
                append("多次连接 / 断开期间进程 pid 始终相同（共 $connectCount 次连接），进程根本没被杀；")
                append("因此后台保活、自启动、电池白名单、一键加速对本案无效，不要再往这个方向排查。")
            }
            if (lifespanMs > 0L && lifespanMs <= 60_000L) {
                append("服务连上后只存活 ${formatDuration(lifespanMs)} 就被撤销，属于「开启后立刻被系统复核重置」；")
            } else {
                append("服务上次存活 ${formatDuration(lifespanMs)}；")
            }
            if (revokeCount >= 2) {
                append("已累计 $revokeCount 次，说明是稳定复现的系统策略，反复手动重试开关没有意义。")
            }
            if (isSideloaded(source)) {
                append("安装来源 $source 属于侧载：")
                if (apiLevel >= 33) append("Android 13+ 的「受限设置」会拦截侧载应用的无障碍授权；")
                if (apiLevel >= 37) {
                    append("本机 Android 17 还新增「高级保护模式」，开启时会强制撤销非辅助类应用的无障碍权限。")
                }
                append("优先做：① 关闭高级保护模式；② 允许受限设置（或用下方 ADB 命令一次性解除）；")
                append("③ 必要时用会话安装器覆盖安装去掉侧载标记。")
            }
            append("以上都做完仍会被关，只能用 Shevery / Shizuku 等系统级工具的「无障碍服务锁定」。")
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms <= 0L -> "无记录"
        ms < 1000L -> "${ms}ms"
        ms < 60_000L -> String.format(Locale.getDefault(), "%.1f 秒", ms / 1000.0)
        else -> String.format(Locale.getDefault(), "%.1f 分钟", ms / 60_000.0)
    }

    private fun revokeCount(context: Context): Int = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_REVOKE_COUNT, 0)
    }.getOrDefault(0)

    /**
     * 稳定运行步骤（v1.2.3 重排）。
     * 上一版把后台保活放前面，但真机 pid 不变证明进程没死，方向错了；
     * 现在按「系统策略 → 授权解除 → 安装来源 → 才轮到保活」排序。
     */
    fun stableRunGuide(): List<String> = buildList {
        add("【先排除 Android 17 高级保护】设置 → 安全 → 高级保护模式（或设置顶栏搜「高级保护」）：确认关闭。" +
            "该模式开启时系统会强制撤销非辅助类应用的无障碍权限，应用内任何设置都改不了它。")
        add("【侧载必做 · 允许受限设置】设置 → 应用管理 → 少花点儿 → 右上角 ⋮ / 更多 → 「允许受限设置」并验证锁屏密码。" +
            "安装来源显示 com.android.packageinstaller 就是侧载，Android 13+ 会拦截侧载应用的无障碍授权；" +
            "vivo 若隐藏该菜单，直接用第 3 步的 ADB 命令。")
        add("【ADB 一次性解除，菜单找不到就用这个】电脑连手机执行：" +
            "adb shell appops set com.azu.bookkeeper ACCESS_RESTRICTED_SETTINGS allow 。" +
            "完整命令见下方「ADB 授权命令」，仓库内也有一键脚本 scripts/android_unlock_accessibility.sh unlock。无需 root。")
        if (RomCompat.isVivoFamily()) {
            add("【vivo / iQOO 次要项】i管家 → 允许「后台高耗电」，加入「一键加速白名单」；" +
                "设置 → 快捷与辅助 → 无障碍 → 已下载的服务 → 少花点儿 重新打开开关。" +
                "注意：本案 pid 不变、进程没被杀，这些只在确实被清后台时才有用。")
        } else {
            add("【次要项】最近任务锁定卡片、允许自启动、电池不限制。" +
                "注意：本案 pid 不变、进程没被杀，这些只在确实被清后台时才有用。")
        }
        add("【仍是侧载标记可试】用 SAI / 系统会话安装器覆盖安装同一个 APK，让安装来源不再是「手动装 APK」；" +
            "同签名覆盖安装不丢账单数据。部分 ROM 会因此不再拦截无障碍授权。")
        add("【系统级锁定】以上都做完仍被自动撤销时，用 Shevery、Shizuku 等工具的「无障碍服务锁定」锁住本服务" +
            "（系统不向第三方 App 提供锁定入口，App 自己实现不了）。")
        add("系统升级或重启后打开一次「少花点儿」，确认自动化设置里「服务状态：运行中」。")
    }

    /** 可复制的 ADB 一次性授权命令，用户点「复制稳定运行说明」即可带走。 */
    fun adbUnlockText(): String = """
少花点儿 · ADB 一次性解除「受限设置」（无需 root / 无需解锁）

前提：手机开启「开发者选项 → USB 调试」，电脑装 adb 并授权；Android 11+ 也可用无线调试 adb connect。
这是一次性部署授权，App 运行时不依赖 adb。

1. 解除受限设置：
   adb shell appops set com.azu.bookkeeper ACCESS_RESTRICTED_SETTINGS allow
   若提示未知 op，改用完整名：
   adb shell appops set com.azu.bookkeeper android:access_restricted_settings allow
2. 复核结果应为 allow：
   adb shell appops get com.azu.bookkeeper ACCESS_RESTRICTED_SETTINGS
3. 可选：把服务登记进系统列表（先 get，再用冒号追加，别覆盖已有的其它无障碍服务）：
   adb shell settings get secure enabled_accessibility_services
   adb shell settings put secure enabled_accessibility_services "<上一步结果>:com.azu.bookkeeper/com.azu.bookkeeper.automation.service.PaymentAccessibilityService"
4. 回到 设置 → 快捷与辅助 → 无障碍 → 已下载的服务，打开少花点儿，静置 5 分钟确认不再被关。

仓库内一键脚本：scripts/android_unlock_accessibility.sh {status|unlock|check}
""".trimIndent()

    fun stableRunGuideText(): String = buildString {
        appendLine("少花点儿 · ${RomCompat.keepAliveLabel()}")
        stableRunGuide().forEachIndexed { index, line -> appendLine("${index + 1}. $line") }
        appendLine()
        appendLine(adbUnlockText())
        appendLine()
        appendLine("说明：应用无法绕过系统权限修改无障碍开关，只能检测异常并给出系统侧解法。")
    }

    /** 复制给开发者的完整诊断：状态 + 时间线 + 自动结论 + 解法 + ADB 命令 + 最近日志。 */
    fun reportText(context: Context, guide: PermissionGuide): String = runCatching {
        val app = context.applicationContext
        val connected = guide.accessibilityLastConnectedAt()
        val disconnected = guide.accessibilityLastDisconnectedAt()
        val lifespanMs = guide.accessibilityLastLifespanMs()
        val source = guide.installSourceText()
        val count = revokeCount(app)
        val pidStable = guide.accessibilityPidStable()
        buildString {
            appendLine("=== 无障碍诊断 ===")
            appendLine("机型：${RomCompat.displayName()}")
            appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("系统开关：${if (guide.isAccessibilityEnabled()) "开" else "关"}")
            appendLine("服务曾连接：${guide.accessibilityServiceEverConnected()}")
            appendLine("最后连接：${if (connected > 0L) timeFormat.format(Date(connected)) else "从未连上"}")
            appendLine("最后断开：${if (disconnected > 0L) timeFormat.format(Date(disconnected)) else "无记录"}")
            appendLine("本次服务存活：${formatDuration(lifespanMs)}")
            appendLine("累计连接 / 被撤销：${guide.accessibilityConnectCount()} 次 / $count 次")
            appendLine("进程 pid 是否稳定：${if (pidStable) "是（多次连接 pid 相同，进程未被杀）" else "否 / 数据不足"}")
            appendLine("连接时 pid：${guide.accessibilityConnectPid()} · 进程已运行 ${formatDuration(guide.accessibilityProcessAgeAtConnectMs())}")
            appendLine("截屏能力：${guide.accessibilityCanTakeScreenshot()}（v1.2.3 起移除高风险声明，false 属预期）")
            appendLine("安装来源：$source${if (isSideloaded(source)) "（侧载）" else ""}")
            appendLine()
            appendLine("=== 诊断结论 ===")
            appendLine(diagnose(guide.isAccessibilityEnabled(), guide.accessibilityServiceEverConnected(),
                lifespanMs, count, source, pidStable, guide.accessibilityConnectCount(), Build.VERSION.SDK_INT))
            appendLine()
            appendLine(stableRunGuideText())
            appendLine("=== 最近识屏日志（400 行）===")
            appendLine(CaptureLog.export(app, 400))
        }
    }.getOrElse { t ->
        "无障碍诊断生成失败：${t.javaClass.simpleName}: ${t.message}\n\n" +
            CaptureLog.export(context.applicationContext, 200)
    }
}
