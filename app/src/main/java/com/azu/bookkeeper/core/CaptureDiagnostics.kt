package com.azu.bookkeeper.core

import android.content.Context

/**
 * 识屏失败原因与诊断留痕。
 *
 * 为什么需要它：识屏是一条「授权 → 截屏 → OCR → 提取」的长链路，
 * 任何一环失败在 UI 上以前都只显示一句「识屏不可用」，用户和开发者都无法定位。
 * 这里把每个阶段的结果与原因记录下来：
 * 1) 摇一摇失败时的 Toast / 进度通知直接展示具体原因；
 * 2) 「识屏自检」页展示最近一次完整步骤与修复建议；
 * 3) 自检功能可主动逐项跑一遍，把卡点摆到明面上。
 */
enum class CaptureFailReason(val title: String, val advice: String) {
    NO_SESSION(
        "没有截屏授权会话",
        "到「自动化设置 → 截屏授权」重新授权一次；Android 14+ 每次重启 App 后会话会失效"
    ),
    NEED_AUTH_LAUNCH_FAILED(
        "无法自动弹出授权页",
        "系统拦截了后台弹窗，请回到 App → 自动化设置 → 点「授权截屏」"
    ),
    PROJECTION_INIT_FAILED(
        "投影初始化失败",
        "多为后台服务类型被 ROM 拒绝：请确认「启用自动记账」已开，并允许后台运行"
    ),
    VIRTUAL_DISPLAY_FAILED(
        "创建虚拟屏幕失败",
        "系统拒绝了屏幕捕获，请重新授权截屏；部分 ROM 需要保持常驻通知运行"
    ),
    FRAME_TIMEOUT(
        "3 秒内没有拿到屏幕画面",
        "屏幕可能处于锁屏/息屏，或 ROM 拦截了捕获；解锁后再试"
    ),
    SAVE_FAILED(
        "截图保存失败",
        "存储空间不足或文件被清理，清理空间后重试"
    ),
    OCR_EMPTY(
        "OCR 没有识别到任何文字",
        "中文识别模型可能未下载（首次使用需联网）或截图是空白页；联网后重试"
    ),
    EXTRACT_EMPTY(
        "识别到文字但没找到扣款金额",
        "当前页面可能不是支付结果页；打开支付成功页再摇一摇"
    ),
    UNKNOWN(
        "未知异常",
        "查看「识屏自检」页的最近一次记录"
    );

    companion object {
        fun fromName(name: String?): CaptureFailReason =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** 诊断里的单个步骤（截屏 / OCR / 提取 / 通知等）。 */
data class DiagnosticStep(
    val name: String,
    val ok: Boolean,
    val detail: String
)

/** 一次识屏（或自检）的完整记录。 */
data class CaptureReport(
    val timeMillis: Long,
    val reason: CaptureFailReason?,
    val steps: List<DiagnosticStep>,
    val textPreview: String = ""
) {
    val success: Boolean get() = reason == null
}

/**
 * 诊断记录存取（SharedPreferences，轻量、进程被杀也留得住）。
 * 只存最近一次，够用户自检和反馈问题用；不写数据库，零侵入。
 */
object CaptureDiagnostics {

    private const val PREFS = "capture_diagnostics"
    private const val KEY_TIME = "last_time"
    private const val KEY_REASON = "last_reason"
    private const val KEY_STEPS = "last_steps"
    private const val KEY_TEXT = "last_text"

    fun record(context: Context, report: CaptureReport) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIME, report.timeMillis)
            .putString(KEY_REASON, report.reason?.name.orEmpty())
            .putString(
                KEY_STEPS,
                report.steps.joinToString("\n") { "${if (it.ok) "✓" else "✗"} ${it.name}：${it.detail}" }
            )
            .putString(KEY_TEXT, report.textPreview.take(600))
            .apply()
    }

    fun last(context: Context): CaptureReport? = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time <= 0L) return null
        val reasonName = prefs.getString(KEY_REASON, null).orEmpty()
        val steps = prefs.getString(KEY_STEPS, "").orEmpty()
            .lines()
            .filter { it.isNotBlank() }
            .map { line ->
                DiagnosticStep(
                    name = line.substringAfter(' ').substringBefore('：').trim(),
                    ok = line.startsWith("✓"),
                    detail = line.substringAfter('：', "")
                )
            }
        CaptureReport(
            timeMillis = time,
            reason = if (reasonName.isBlank()) null else CaptureFailReason.fromName(reasonName),
            steps = steps,
            textPreview = prefs.getString(KEY_TEXT, "").orEmpty()
        )
    }.getOrNull()

    fun clear(context: Context) = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
