package com.azu.bookkeeper.automation.permission

import android.content.Context
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
 * OrangeOS / ColorOS 等 ROM 会在以下场景把第三方无障碍服务关掉：
 * - 服务内部异常或主线程 ANR 后，系统判定服务不可用并自动取消；
 * - 一键清理 / 电池优化把进程杀掉，系统开关还亮着但服务已解绑；
 * - 系统升级 / 重启后无障碍授权被重置。
 *
 * 应用层无法阻止系统改这个开关（需要系统签名 / Shizuku / root），
 * 但可以做到：**发现异常 → 写一条强制日志 → 弹窗让用户一键复制 log → 给出保活步骤**。
 */
object AccessibilityHealth {

    private const val PREFS = "accessibility_health"
    private const val KEY_LAST_ENABLED = "last_enabled"
    private const val KEY_LAST_REPORTED = "last_reported"
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

    /**
     * 检测异常；同一现场只返回一次（通过 [KEY_LAST_REPORTED] 去重）。
     * 调用方每次页面恢复时调用即可。
     */
    fun detect(context: Context, guide: PermissionGuide): AccessibilityAnomaly? = runCatching {
        val app = context.applicationContext
        val enabled = guide.isAccessibilityEnabled()
        val everConnected = guide.accessibilityServiceEverConnected()
        val lastConnected = guide.accessibilityLastConnectedAt()
        val lastDisconnected = guide.accessibilityLastDisconnectedAt()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean(KEY_LAST_ENABLED, false)
        prefs.edit().putBoolean(KEY_LAST_ENABLED, enabled).apply()

        if (!everConnected) return@runCatching null

        // 开关被关掉；或开关还亮着但服务已经被 ROM 解绑（OrangeOS 最常见的形态）
        val closed = wasEnabled && !enabled
        val unbound = enabled && lastDisconnected > lastConnected
        if (!closed && !unbound) return@runCatching null

        val signature = "${if (closed) "closed" else "unbound"}:$enabled:$lastConnected:$lastDisconnected"
        if (signature == prefs.getString(KEY_LAST_REPORTED, "")) return@runCatching null
        prefs.edit().putString(KEY_LAST_REPORTED, signature).apply()

        val connectedText = if (lastConnected > 0L) timeFormat.format(Date(lastConnected)) else "从未连上"
        val disconnectedText = if (lastDisconnected > 0L) timeFormat.format(Date(lastDisconnected)) else "无记录"
        val detail = buildString {
            append("检测到无障碍服务异常：")
            append(if (closed) "系统开关已关闭" else "开关还在，但服务已被系统解绑")
            append("。\n最后连接：$connectedText")
            append("\n最后断开：$disconnectedText")
            append("\n机型：${RomCompat.displayName()}")
        }
        val advice = if (closed) {
            "请重新打开「设置 → 无障碍 → 少花点儿」。如果过一会儿又被关掉，按下面的稳定运行步骤锁定后台；" +
                "OrangeOS / ColorOS 可用 Shevery、Shizuku 等工具的「无障碍服务锁定」锁住本服务。"
        } else {
            "请到「设置 → 无障碍」把少花点儿关闭再打开一次，并在最近任务里锁定本 App；" +
                "OrangeOS / ColorOS 可用 Shevery、Shizuku 等工具的「无障碍服务锁定」锁住本服务。"
        }
        // 强制落盘：即使识屏日志开关没开，用户也能在调试页复制到这条现场
        CaptureLog.force(
            app, "A11Y",
            "无障碍异常 kind=${if (closed) "closed" else "unbound"} enabled=$enabled " +
                "wasEnabled=$wasEnabled lastConnected=$lastConnected lastDisconnected=$lastDisconnected " +
                "device=${RomCompat.displayName()}"
        )
        AccessibilityAnomaly(
            title = "无障碍服务被系统关闭了",
            detail = detail,
            advice = advice,
            signature = signature
        )
    }.getOrNull()

    /** 稳定运行步骤：与咔皮记账「稳定运行设置 / 锁定与电量」同一思路，按 OrangeOS 的实际入口写。 */
    fun stableRunGuide(): List<String> = listOf(
        "最近任务卡片下拉 → 锁定「少花点儿」，避免一键清理把读屏服务带走。",
        "设置 → 应用管理 → 少花点儿 → 自启动 / 关联启动：允许。",
        "设置 → 电池 → 少花点儿：允许后台运行 / 不限制；关闭智能省电对它的限制。",
        "设置 → 无障碍 → 少花点儿：保持开启。OrangeOS / ColorOS 若会自动关闭，" +
            "用 Shevery、Shizuku 等工具的「无障碍服务锁定」锁定本服务（系统本身不提供第三方 App 的锁定入口）。",
        "系统升级或重启后打开一次「少花点儿」，确认自动化设置里「服务状态：运行中」。"
    )

    fun stableRunGuideText(): String = buildString {
        appendLine("少花点儿 · 稳定运行设置")
        stableRunGuide().forEachIndexed { index, line -> appendLine("${index + 1}. $line") }
        appendLine()
        appendLine("说明：应用无法绕过系统权限修改无障碍开关，只能检测异常并提示。")
    }

    /** 复制给开发者的完整诊断：状态 + 时间线 + 最近日志。 */
    fun reportText(context: Context, guide: PermissionGuide): String = runCatching {
        val app = context.applicationContext
        val connected = guide.accessibilityLastConnectedAt()
        val disconnected = guide.accessibilityLastDisconnectedAt()
        buildString {
            appendLine("=== 无障碍诊断 ===")
            appendLine("机型：${RomCompat.displayName()}")
            appendLine("系统开关：${if (guide.isAccessibilityEnabled()) "开" else "关"}")
            appendLine("服务曾连接：${guide.accessibilityServiceEverConnected()}")
            appendLine("最后连接：${if (connected > 0L) timeFormat.format(Date(connected)) else "从未连上"}")
            appendLine("最后断开：${if (disconnected > 0L) timeFormat.format(Date(disconnected)) else "无记录"}")
            appendLine("截屏能力：${guide.accessibilityCanTakeScreenshot()}")
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
