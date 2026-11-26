package com.azu.bookkeeper.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.automation.ocr.OcrEngine
import com.azu.bookkeeper.automation.ocr.PaymentTextAnalyzer
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import com.azu.bookkeeper.core.CaptureDiagnostics
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.CaptureReport
import com.azu.bookkeeper.core.DiagnosticStep
import com.azu.bookkeeper.core.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 自检项：ok=false 时 detail 是失败原因，advice 是修复建议。 */
data class CheckItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val advice: String = ""
)

/**
 * 识屏自检 ViewModel：把「授权 → 截屏 → OCR → 提取」逐项跑一遍，
 * 每项都给结论和修复建议；同时读取最近一次摇一摇识屏的失败留痕。
 */
@HiltViewModel
class CaptureDiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: com.azu.bookkeeper.automation.capture.CaptureChannels,
    private val screenCapture: ScreenCaptureManager,
    private val ocr: OcrEngine,
    private val analyzer: PaymentTextAnalyzer,
    private val permissionGuide: PermissionGuide,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {

    data class UiState(
        val items: List<CheckItem> = emptyList(),
        val running: Boolean = false,
        val last: CaptureReport? = null,
        val textPreview: String = "",
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    /** 刷新环境检查项（通知权限 / 截屏通道）与最近一次留痕。 */
    fun refresh() {
        val last = CaptureDiagnostics.last(context)
        _state.update {
            it.copy(
                last = last,
                textPreview = last?.textPreview.orEmpty(),
                items = listOf(notificationCheck(), accessibilityChannelCheck(), projectionChannelCheck())
            )
        }
    }

    private fun notificationCheck(): CheckItem {
        val granted = permissionGuide.hasNotificationPermission()
        return CheckItem(
            name = "通知权限",
            ok = granted,
            detail = if (granted) "已授予（进度通知可见）" else "未授予（识屏进度和结果通知不会显示）",
            advice = if (granted) "" else "到系统设置 → 通知，允许「少花点儿」通知"
        )
    }

    /** 通道 1：无障碍截屏（API 30+，免录屏授权，首选）。 */
    private fun accessibilityChannelCheck(): CheckItem {
        val connected = channels.accessibilityServiceConnected()
        val ready = channels.accessibilityReady()
        val lastConnected = permissionGuide.accessibilityLastConnectedAt()
        val lastDisconnected = permissionGuide.accessibilityLastDisconnectedAt()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val connectedText = if (lastConnected > 0L) fmt.format(java.util.Date(lastConnected)) else "从未连上"
        val killedAfterConnect = lastDisconnected > lastConnected && lastConnected > 0L

        val detail = when {
            ready -> "可用 · 最后连接 $connectedText"
            !connected && killedAfterConnect -> "服务未连接 · 上次 $connectedText 连上后被系统解绑"
            !connected -> "服务未连接 · 最后连接 $connectedText"
            else -> "服务已连但缺少截屏能力（canTakeScreenshot 未生效） · $connectedText"
        }
        val advice = when {
            ready -> ""
            !connected -> "设置 → 无障碍 → 少花点儿，打开服务；打开后回到本页应显示「可用」"
            else -> "关闭无障碍服务再重新打开一次，让截屏能力重新注册"
        }
        return CheckItem("无障碍截屏通道", ready, detail, advice)
    }

    /** 通道 2：媒体投影（需系统录屏授权，覆盖 Android 8~10 与无障碍不可用场景）。 */
    private fun projectionChannelCheck(): CheckItem {
        val active = channels.projectionReady()
        return CheckItem(
            name = "媒体投影通道",
            ok = active,
            detail = if (active) "会话有效" else "无会话（重启 App 后失效，Android 14+ 规则）",
            advice = if (active) "" else "点下方「授权截屏」完成一次系统录屏授权"
        )
    }

    fun createCaptureIntent(): Intent = screenCapture.createCaptureIntent()

    fun onAuthResult(resultCode: Int, data: Intent?) {
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            _state.update { it.copy(message = "已取消授权") }
            refresh()
            return
        }
        val ok = screenCapture.startSession(resultCode, data) {}
        _state.update { it.copy(message = if (ok) "截屏会话已建立，可点「开始自检」" else "授权失败：系统未返回可用会话") }
        refresh()
    }

    /** 主动自检：真实执行一次截屏 + OCR + 提取，把每一步结果写出来。 */
    fun runSelfCheck() = viewModelScope.launch {
        _state.update { it.copy(running = true, message = null) }
        // 整段兜底：OutOfMemoryError / 原生异常都不能冒到 viewModelScope（会直接退出 App）
        try {
            runCheck()
        } catch (t: Throwable) {
            val items = listOf(
                CheckItem(
                    "自检异常",
                    false,
                    "${t.javaClass.simpleName}: ${t.message}",
                    "多为内存不足或 ROM 拦截截屏，重启 App 后重试，仍失败请回到自动化设置页查看"
                )
            )
            runCatching {
                CaptureDiagnostics.record(
                    context,
                    CaptureReport(System.currentTimeMillis(), CaptureFailReason.UNKNOWN,
                        items.map { DiagnosticStep(it.name, it.ok, it.detail) })
                )
            }
            _state.update {
                it.copy(running = false, items = items, last = CaptureDiagnostics.last(context))
            }
        }
    }

    private suspend fun runCheck() {
        val items = mutableListOf<CheckItem>()
        items += notificationCheck()
        items += accessibilityChannelCheck()
        items += projectionChannelCheck()

        var reason: CaptureFailReason? = null
        var preview = ""

        if (channels.needsAuthorization()) {
            reason = CaptureFailReason.NO_CHANNEL
        } else {
            // 双通道依次尝试：无障碍截屏优先，媒体投影兜底
            val frame = withContext(io) { channels.capture() }
            val bitmap = frame.bitmap
            items += CheckItem(
                name = "截屏（${frame.channel}）",
                ok = bitmap != null,
                detail = if (bitmap != null) {
                    "取帧成功 ${frame.detail}"
                } else {
                    (frame.reason?.title ?: "失败") + "：" +
                        frame.attempts.joinToString("；").ifBlank { frame.detail }
                },
                advice = frame.reason?.advice.orEmpty()
            )
            if (bitmap == null) {
                reason = frame.reason ?: CaptureFailReason.UNKNOWN
            } else {
                val result = try {
                    withContext(io) { ocr.recognize(bitmap) }
                } finally {
                    runCatching { bitmap.recycle() }
                }
                preview = result.rawText
                val textOk = result.rawText.isNotBlank()
                items += CheckItem(
                    name = "OCR 文字识别",
                    ok = textOk,
                    detail = if (textOk) {
                        "${result.rawText.length} 字 / ${result.blockCount} 块 / 置信度 ${"%.2f".format(result.confidence)}"
                    } else {
                        "识别为空（中文模型可能未下载，或截图是空白页）"
                    },
                    advice = if (textOk) "" else CaptureFailReason.OCR_EMPTY.advice
                )
                if (!textOk) {
                    reason = CaptureFailReason.OCR_EMPTY
                } else {
                    val analysis = analyzer.analyze(result.rawText)
                    val amountOk = analysis.amountFen != null
                    items += CheckItem(
                        name = "扣款信息提取",
                        ok = amountOk,
                        detail = if (amountOk) {
                            "金额 ¥${analysis.amountFen!! / 100.0} · 商户 ${analysis.merchant.ifBlank { "未识别" }} · 置信度 ${"%.2f".format(analysis.confidence)}"
                        } else {
                            "识别到文字但没有金额候选（当前页可能不是支付结果页）"
                        },
                        advice = if (amountOk) "" else CaptureFailReason.EXTRACT_EMPTY.advice
                    )
                    if (!amountOk) reason = CaptureFailReason.EXTRACT_EMPTY
                }
            }
        }

        runCatching {
            CaptureDiagnostics.record(
                context,
                CaptureReport(
                    timeMillis = System.currentTimeMillis(),
                    reason = reason,
                    steps = items.map { DiagnosticStep(it.name, it.ok, it.detail) },
                    textPreview = preview
                )
            )
        }
        _state.update {
            it.copy(running = false, items = items, last = CaptureDiagnostics.last(context))
        }
    }

    fun clearReport() {
        CaptureDiagnostics.clear(context)
        _state.update { it.copy(last = null, textPreview = "") }
        refresh()
    }

    /** 汇总当前诊断信息为纯文本，供用户一键复制反馈（含机型/ROM/通道状态）。 */
    fun buildReportText(): String {
        val s = _state.value
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("【少花点儿 · 识屏诊断】")
        sb.appendLine("机型：${com.azu.bookkeeper.core.RomCompat.displayName()}")
        sb.appendLine("Android：${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine("无障碍服务连接：${channels.accessibilityServiceConnected()}")
        sb.appendLine("无障碍截屏可用：${channels.accessibilityReady()}")
        sb.appendLine("媒体投影会话：${channels.projectionReady()}")
        val lastConnected = permissionGuide.accessibilityLastConnectedAt()
        sb.appendLine("最后连接时间：${if (lastConnected > 0) fmt.format(java.util.Date(lastConnected)) else "从未连上"}")
        sb.appendLine("上次截屏能力：${permissionGuide.accessibilityCanTakeScreenshot()}")
        s.items.forEach { sb.appendLine("${if (it.ok) "✓" else "✗"} ${it.name}：${it.detail}") }
        s.last?.let { report ->
            sb.appendLine("--- 最近一次识屏 ---")
            sb.appendLine("时间：${fmt.format(java.util.Date(report.timeMillis))}")
            sb.appendLine("结果：${report.reason?.title ?: "成功"}")
            report.steps.forEach { sb.appendLine("${if (it.ok) "✓" else "✗"} ${it.name}：${it.detail}") }
        }
        return sb.toString()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
