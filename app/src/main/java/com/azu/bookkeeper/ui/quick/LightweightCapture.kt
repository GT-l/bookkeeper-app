package com.azu.bookkeeper.ui.quick

import android.content.Context
import android.widget.Toast
import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.automation.ocr.OcrIpcClient
import com.azu.bookkeeper.automation.ocr.PaymentTextAnalyzer
import com.azu.bookkeeper.core.CaptureDiagnostics
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.CaptureReport
import com.azu.bookkeeper.core.DiagnosticStep
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.di.MainDispatcher
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 识屏结果：失败时带可读原因，供 Toast / 通知 / 自检页展示。 */
data class CaptureOutcome(
    val amountFen: Long?,
    val merchant: String,
    val reason: CaptureFailReason? = null,
    val detail: String = ""
) {
    val success: Boolean get() = reason == null
}

/**
 * 快捷入口专用的轻量识屏器。
 *
 * 链路：截屏（带原因）→ 中文 OCR → 智能提取 → 打开带预填的表单让用户确认。
 * 任何一步失败都会：
 * 1) 记录完整步骤到 [CaptureDiagnostics]（自检页可查）；
 * 2) 进度通知改成「识屏失败：<具体原因>」；
 * 3) Toast 一句话原因；
 * 4) 自动打开记账表单让用户手动输入。
 * 这样用户不需要猜，能直接知道卡在哪一环。
 */
@Singleton
class LightweightCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: com.azu.bookkeeper.automation.capture.CaptureChannels,
    private val ocrIpc: OcrIpcClient,
    private val analyzer: PaymentTextAnalyzer,
    private val extractor: FieldExtractor,
    private val progress: CaptureProgressNotifier,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    /** 两条截屏通道（无障碍截屏 / 媒体投影）都不可用时才需要引导授权。 */
    fun needsAuthorization(): Boolean = channels.needsAuthorization()

    fun captureIntoForm(scope: CoroutineScope, onResult: (CaptureOutcome) -> Unit) {
        scope.launch {
            val steps = mutableListOf<DiagnosticStep>()
            val startedAt = System.currentTimeMillis()

            fun fail(reason: CaptureFailReason, detail: String) {
                steps += DiagnosticStep(reason.title, false, detail)
                Timber.w("识屏失败: %s | %s", reason.name, detail)
                runCatching {
                    CaptureDiagnostics.record(
                        context,
                        CaptureReport(startedAt, reason, steps.toList())
                    )
                }
                runCatching { progress.showFailed(manualEntryIntent(), reason) }
                runCatching { toast("识屏失败：${reason.title}") }
                onResult(CaptureOutcome(null, "", reason, detail))
            }

            steps += DiagnosticStep(
                "截屏通道",
                !needsAuthorization(),
                "无障碍截屏=${if (channels.accessibilityReady()) "可用" else "不可用"}，" +
                    "媒体投影=${if (channels.projectionReady()) "可用" else "不可用"}"
            )
            if (needsAuthorization()) {
                fail(CaptureFailReason.NO_CHANNEL, "无障碍截屏与媒体投影均不可用")
                return@launch
            }

            runCatching {
                progress.showStage(CaptureProgressNotifier.Stage.CAPTURE)
                // 双通道：无障碍截屏（免授权）优先，媒体投影兜底；内部已做黑帧/FLAG_SECURE 判定
                val frame = withContext(ioDispatcher) { channels.capture() }
                val bitmap = frame.bitmap
                if (bitmap == null) {
                    val detail = frame.attempts.joinToString("；").ifBlank { frame.detail }
                    fail(frame.reason ?: CaptureFailReason.UNKNOWN, detail)
                    return@launch
                }
                steps += DiagnosticStep("截屏（${frame.channel}）", true, frame.detail)

                progress.showStage(CaptureProgressNotifier.Stage.RECOGNIZE)
                // OCR 放到 :ocr 独立进程执行：ML Kit 原生库崩溃不会带走主进程
                val ipc = withContext(ioDispatcher) { ocrIpc.recognize(bitmap) }
                runCatching { bitmap.recycle() }
                val ocrPayload = ipc.payload
                if (ocrPayload == null) {
                    fail(CaptureFailReason.OCR_PROCESS_FAILED, ipc.error ?: "unknown")
                    return@launch
                }
                if (ocrPayload.text.isBlank()) {
                    fail(CaptureFailReason.OCR_EMPTY, "empty text, confidence=${ocrPayload.confidence}")
                    return@launch
                }
                val ocrText = ocrPayload.text
                steps += DiagnosticStep(
                    "OCR（独立进程）", true,
                    "${ocrText.length} 字 / ${ocrPayload.blocks} 块 / conf=${"%.2f".format(ocrPayload.confidence)}"
                )

                progress.showStage(CaptureProgressNotifier.Stage.EXTRACT)
                val analysis = analyzer.analyze(ocrText)
                val fallback = extractor.extract(ocrText, System.currentTimeMillis())
                val amountFen = analysis.amountFen ?: fallback.amountFen
                val merchant = analysis.merchant.ifBlank { fallback.merchant }

                if (amountFen == null) {
                    steps += DiagnosticStep("提取金额", false, "无候选金额，文本前 40 字：${ocrText.take(40)}")
                    runCatching {
                        CaptureDiagnostics.record(
                            context,
                            CaptureReport(
                                startedAt, CaptureFailReason.EXTRACT_EMPTY, steps.toList(),
                                textPreview = ocrText
                            )
                        )
                    }
                    runCatching { progress.showFailed(manualEntryIntent(), CaptureFailReason.EXTRACT_EMPTY) }
                    runCatching { toast("没识别到金额，已打开表单请手动输入") }
                    onResult(CaptureOutcome(null, merchant, CaptureFailReason.EXTRACT_EMPTY, "no amount candidate"))
                    return@launch
                }

                steps += DiagnosticStep(
                    "提取金额", merchant.isNotBlank(),
                    "¥${amountFen / 100.0} / 商户=${merchant.ifBlank { "未识别" }} / conf=${"%.2f".format(analysis.confidence)}"
                )
                steps += DiagnosticStep("进度通知", true, "已投递")
                runCatching {
                    CaptureDiagnostics.record(
                        context,
                        CaptureReport(startedAt, null, steps.toList(), ocrText)
                    )
                }
                progress.showResult(amountFen, merchant, resultIntent(amountFen, merchant))
                withContext(mainDispatcher) { onResult(CaptureOutcome(amountFen, merchant)) }
            }.onFailure { e ->
                Timber.w(e, "lightweight capture failed")
                fail(CaptureFailReason.UNKNOWN, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** 构造打开表单的 Intent（带 OCR 预填）。 */
    fun formIntent(amountFen: Long?, merchant: String): android.content.Intent {
        val request = QuickCaptureRequest(
            entry = QuickEntry.SYSTEM_GESTURE,
            amountFen = amountFen,
            merchant = merchant
        )
        return QuickCaptureLauncher.intent(context, request)
    }

    private fun resultIntent(amountFen: Long?, merchant: String): android.app.PendingIntent =
        QuickCaptureLauncher.pendingIntent(
            context,
            QuickCaptureRequest(
                entry = QuickEntry.SYSTEM_GESTURE,
                amountFen = amountFen,
                merchant = merchant
            ),
            REQUEST_CODE_RESULT
        )

    private fun manualEntryIntent(): android.app.PendingIntent =
        QuickCaptureLauncher.pendingIntent(
            context,
            QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE),
            REQUEST_CODE_FAILED
        )

    fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
    }

    private companion object {
        const val REQUEST_CODE_RESULT = 2005
        const val REQUEST_CODE_FAILED = 2006
    }
}
