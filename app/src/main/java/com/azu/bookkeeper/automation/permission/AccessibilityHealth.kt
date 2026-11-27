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
 * v1.2.2 依据 vivo V2505A / Android 17 真机日志修正判断：
 * 真机上「开关开了 1 秒 / 27 秒又被关掉」，日志表现为 onUnbind + onDestroy 成对出现。
 * 这不是 App 崩溃、不是 ANR、也不是后台省电杀进程——**是系统主动撤销了无障碍授权**；
 * 后台被杀时开关通常还亮着，而这里 Settings.Secure 里已经是「关」。
 * onDestroy 只是解绑后的正常销毁，早期文案写成「被系统销毁 / 进程退出」会把排查方向带偏。
 *
 * 常见成因：Android 13+ 对侧载 APK 的「受限设置」拦截、vivo / OPPO 系安全与省电策略复核后重置授权。
 * 应用层无法阻止系统改这个开关（需要系统签名 / Shizuku / root），
 * 所以本类只做：**发现异常 → 写出可判因的现场 → 弹窗一键复制 log → 给出系统侧解法**。
 */
object AccessibilityHealth {

    private const val PREFS = "accessibility_health"
    private const val KEY_LAST_ENABLED = "last_enabled"
    private const val KEY_LAST_REPORTED = "last_reported"
    private const val KEY_REVOKE_COUNT = "revoke_count"
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 服务真正连上时记一笔「曾经开启」。
     * 这样即使用户从没进过设置页，之后服务被 ROM 关掉，调试页也能识别出「之前是开的」。
     */
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

        // 开关被关掉；或开关还亮着但服务已经被 ROM 解绑
        val closed = wasEnabled && !enabled
        val unbound = enabled && lastDisconnected > lastConnected
        if (!closed && !unbound) return@runCatching null

        val signature = "${if (closed) "closed" else "unbound"}:$enabled:$lastConnected:$lastDisconnected"
        if (signature == prefs.getString(KEY_LAST_REPORTED, "")) return@runCatching null
        prefs.edit().putString(KEY_LAST_REPORTED, signature).apply()

        // 去重之后再计数，保证「同一现场反复进页面」不会虚增次数
        val revokeCount = prefs.getInt(KEY_REVOKE_COUNT, 0) + 1
        prefs.edit().putInt(KEY_REVOKE_COUNT, revokeCount).apply()
        val verdict = diagnose(enabled, everConnected, lifespanMs, revokeCount, source)

        val connectedText = if (lastConnected > 0L) timeFormat.format(Date(lastConnected)) else "从未连上"
        val disconnectedText = if (lastDisconnected > 0L) timeFormat.format(Date(lastDisconnected)) else "无记录"
        val detail = buildString {
            append("检测到无障碍服务异常：")
            append(if (closed) "系统开关已关闭（授权被撤销）" else "开关还在，但服务已被系统解绑")
            append("。\n最后连接：$connectedText")
            append("\n最后断开：$disconnectedText")
            append("\n本次服务存活：${formatDuration(lifespanMs)}")
            append("\n累计被撤销：$revokeCount 次 / 累计连接 ${guide.accessibilityConnectCount()} 次")
            append("\n安装来源：$source")
            append("\n机型：${RomCompat.displayName()}")
            append("\n\n诊断结论：$verdict")
        }
        val advice = if (closed) {
            "这不是 App 崩溃，是系统撤销了授权。按稳定运行设置第 1 步解除「受限设置」" +
                (if (RomCompat.isVivoFamily()) "，再做 vivo 的「后台高耗电 + 一键加速白名单」" else "，再做自启动 / 电池白名单") +
                "，最后重新打开无障碍开关。若仍秒关，只能用 Shevery / Shizuku 的系统级无障碍服务锁定。" +
                "点「复制 log」可把完整时间线发我。"
        } else {
            "开关还亮着但服务已解绑，更像是进程被回收：请到系统无障碍把少花点儿关掉再打开一次，" +
                "并在最近任务里锁定本 App。OrangeOS / ColorOS / OriginOS 可用 Shevery、Shizuku 等工具锁定本服务。"
        }
        // 强制落盘：即使识屏日志开关没开，用户也能在调试页复制到这条现场
        CaptureLog.force(
            app, "A11Y",
            "无障碍异常 kind=${if (closed) "closed" else "unbound"} enabled=$enabled " +
                "wasEnabled=$wasEnabled lastConnected=$lastConnected lastDisconnected=$lastDisconnected " +
                "lifespan=${lifespanMs}ms revokeCount=$revokeCount source=$source " +
                "device=${RomCompat.displayName()}"
        )
        AccessibilityAnomaly(
            title = if (closed) "系统撤销了无障碍授权" else "无障碍服务被解绑",
            detail = detail,
            advice = advice,
            signature = signature
        )
    }.getOrNull()

    /**
     * 把现场翻译成人话结论。重点是先排除「崩溃 / ANR / 省电杀进程」这三个常见误判：
     * 系统开关已经是关 → 授权被撤销；服务秒级存活 → 开启后立刻被系统复核重置。
     */
    private fun diagnose(
        enabled: Boolean,
        everConnected: Boolean,
        lifespanMs: Long,
        revokeCount: Int,
        source: String
    ): String {
        if (!everConnected) return "服务从未连上过，请先在系统无障碍里开启少花点儿。"
        if (enabled) {
            return "系统开关仍显示开启，但服务已被解绑。这更接近进程被回收或服务被系统重启，" +
                "请关闭再打开一次无障碍开关，并锁定后台。"
        }
        return buildString {
            append("系统关闭了无障碍开关（撤销授权），不是 App 崩溃、不是 ANR、也不是识屏逻辑报错。")
            if (lifespanMs > 0L && lifespanMs <= 60_000L) {
                append("服务连上后只存活 ${formatDuration(lifespanMs)} 就被撤销，属于「开启后立刻被系统重置」；")
            } else {
                append("服务上次存活 ${formatDuration(lifespanMs)}；")
            }
            append("这种情况下锁后台 / 允许自启动只能减少回收，改不动系统开关。")
            if (revokeCount >= 2) {
                append("已累计发生 $revokeCount 次，说明是稳定复现的系统策略，反复重试开关没有意义。")
            }
            if (source.isBlank() || source.contains("未知") || source.contains("侧载")) {
                append("安装来源显示为侧载，Android 13+ 的「受限设置」会拦截侧载应用的无障碍授权，请优先做第 1 步。")
            }
            if (RomCompat.isVivoFamily()) {
                append("本机是 vivo / iQOO（OriginOS），vivo 官方对「无障碍开关自动关闭」要求把应用加入「后台高耗电」与「一键加速白名单」。")
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
     * 稳定运行步骤。
     *
     * 与咔皮记账「稳定运行设置 / 锁定与电量」同一思路，但按真机补了两件原来漏掉的事：
     * ① Android 13+ 侧载 APK 必须先「允许受限设置」，否则开关会被系统置灰或开了立刻关掉；
     * ② vivo / iQOO 的官方解法是「后台高耗电 + 一键加速白名单」，不是通用的电池不限制。
     */
    fun stableRunGuide(): List<String> = buildList {
        add("【Android 13+ 侧载必做】设置 → 应用管理 → 少花点儿 → 右上角 ⋮ / 更多 → 「允许受限设置」，" +
            "按提示验证锁屏密码。从 GitHub / 浏览器下载的 APK 属于侧载，不解除这项限制，" +
            "无障碍开关会置灰或开了立刻被系统关掉。")
        add("最近任务卡片下拉 → 锁定「少花点儿」，避免一键清理把读屏服务带走。")
        add("设置 → 应用管理 → 少花点儿 → 自启动 / 关联启动：允许。")
        if (RomCompat.isVivoFamily()) {
            add("【vivo / iQOO 关键】i管家 → 应用管理 / 权限管理 → 少花点儿：允许「后台高耗电」；" +
                "再把 App 加入「一键加速白名单」。vivo 官方对「无障碍开关自动关闭」给的就是这两项，" +
                "名字反直觉但不能跳过。")
            add("设置 → 快捷与辅助 → 无障碍 → 已下载的服务 → 少花点儿：重新打开开关。")
        } else {
            add("设置 → 电池 → 少花点儿：允许后台运行 / 不限制；关闭智能省电对它的限制。")
            add("设置 → 无障碍 → 少花点儿：保持开启。")
        }
        add("若开关仍会被系统自动关掉：用 Shevery、Shizuku 等系统级工具的「无障碍服务锁定」锁住本服务" +
            "（系统不向第三方 App 提供锁定入口，App 自己无法做到）。")
        add("系统升级或重启后打开一次「少花点儿」，确认自动化设置里「服务状态：运行中」。")
    }

    fun stableRunGuideText(): String = buildString {
        appendLine("少花点儿 · ${RomCompat.keepAliveLabel()}")
        stableRunGuide().forEachIndexed { index, line -> appendLine("${index + 1}. $line") }
        appendLine()
        appendLine("说明：应用无法绕过系统权限修改无障碍开关，只能检测异常并提示。")
    }

    /** 复制给开发者的完整诊断：状态 + 时间线 + 自动结论 + 解法 + 最近日志。 */
    fun reportText(context: Context, guide: PermissionGuide): String = runCatching {
        val app = context.applicationContext
        val connected = guide.accessibilityLastConnectedAt()
        val disconnected = guide.accessibilityLastDisconnectedAt()
        val lifespanMs = guide.accessibilityLastLifespanMs()
        val source = guide.installSourceText()
        val count = revokeCount(app)
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
            appendLine("连接时 pid：${guide.accessibilityConnectPid()} · 进程已运行 ${formatDuration(guide.accessibilityProcessAgeAtConnectMs())}")
            appendLine("截屏能力：${guide.accessibilityCanTakeScreenshot()}")
            appendLine("安装来源：$source")
            appendLine()
            appendLine("=== 诊断结论 ===")
            appendLine(diagnose(guide.isAccessibilityEnabled(), guide.accessibilityServiceEverConnected(),
                lifespanMs, count, source))
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
