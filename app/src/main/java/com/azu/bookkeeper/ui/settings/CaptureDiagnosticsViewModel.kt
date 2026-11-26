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

    /** 刷新环境检查项（通知权限 / 截屏授权）与最近一次留痕。 */
    fun refresh() {
        val last = CaptureDiagnostics.last(context)
        _state.update {
            it.copy(
                last = last,
                textPreview = last?.textPreview.orEmpty(),
                items = listOf(notificationCheck(), sessionCheck())
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

    private fun sessionCheck(): CheckItem {
        val active = screenCapture.hasActiveSession()
        return CheckItem(
            name = "截屏授权会话",
            ok = active,
            detail = if (active) "会话有效" else "无会话（重启 App 后会话必失效，Android 14+ 规则）",
            advice = if (active) "" else "点下方「授权截屏」完成一次系统授权"
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
        val items = mutableListOf<CheckItem>()
        items += notificationCheck()
        items += sessionCheck()

        var reason: CaptureFailReason? = null
        var preview = ""

        if (!screenCapture.hasActiveSession()) {
            reason = CaptureFailReason.NO_SESSION
        } else {
            val frame = withContext(io) { screenCapture.captureWithReason() }
            items += CheckItem(
                name = "截屏",
                ok = frame.success,
                detail = if (frame.success) "取帧成功 ${frame.detail}" else (frame.reason?.title ?: "失败") + "：" + frame.detail,
                advice = frame.reason?.advice.orEmpty()
            )
            if (!frame.success) {
                reason = frame.reason ?: CaptureFailReason.UNKNOWN
            } else {
                val result = withContext(io) { ocr.recognizeFromFile(frame.path!!) }
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

    fun clearMessage() = _state.update { it.copy(message = null) }
}
