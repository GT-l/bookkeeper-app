package com.azu.bookkeeper.ui.quick

import android.content.Context
import android.widget.Toast
import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.automation.ocr.OcrIpcClient
import com.azu.bookkeeper.automation.ocr.PaymentTextAnalyzer
import com.azu.bookkeeper.automation.service.PaymentAccessibilityService
import com.azu.bookkeeper.core.CaptureDiagnostics
import com.azu.bookkeeper.core.composeRemark
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.CaptureReport
import com.azu.bookkeeper.core.DiagnosticStep
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.di.MainDispatcher
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.model.QuickEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 识屏结果。
 *
 * [confidence] 是「抽取置信度」（金额 0.5 / 商户 +0.3 / 支付成功 +0.2），不是 OCR 引擎置信度。
 * 上层用它决定后续动作：高 → 直接记账，中 → 弹小弹窗确认，低 → 打开完整表单。
 * [goods] 是购买内容（商品名）；识别不出来就是空串，不提示、不确认，直接当成没有。
 */
data class CaptureOutcome(
    val amountFen: Long?,
    val merchant: String,
    val reason: CaptureFailReason? = null,
    val detail: String = "",
    val confidence: Float = 0f,
    val captureType: CaptureType = CaptureType.NONE,
    val rawText: String = "",
    val goods: String = ""
) {
    val success: Boolean get() = reason == null

    /** 写进账单备注的文本：「商户 · 购买内容」，只有一项时就是那一项。 */
    val remark: String get() = composeRemark(merchant, goods)
}

/**
 * 快捷入口识屏器。
 *
 * **主链路已改为「无障碍节点读屏」**（这也是钱迹等主流记账 App 在 Android 上的做法）：
 * 摇一摇/悬浮球命中 → 直接读当前页面节点树里的 text/contentDescription →
 * 用 [PaymentTextAnalyzer] 提取金额与商户 → 打开带预填的表单让用户确认。
 *
 * 为什么换掉「截屏 + OCR」当主路径：
 * - 截屏要录屏授权、Android 14 还不能复用会话；
 * - 支付宝/微信账单页普遍 FLAG_SECURE，截出来是全黑；
 * - 部分 ROM 的 takeScreenshot 会 native 崩溃，Java 层接不住。
 * 而读节点是纯 Binder 数据，不需要授权、不怕 FLAG_SECURE、不会崩。
 *
 * 截屏 OCR 降级为**兜底**：只有在节点读不到金额、且截图通道确实可用时才尝试。
 * 任何一步失败都会：
 * 1) 记录完整步骤到 [CaptureDiagnostics]（自检页可查）；
 * 2) 进度通知改成「识屏失败：<具体原因>」；
 * 3) Toast 一句话原因；
 * 4) 自动打开记账表单让用户手动输入。
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
    /**
     * 是否需要先引导授权。
     *
     * 现在主链路是节点读屏：只要无障碍服务连着就能识屏，**不需要任何截屏授权**。
     * 只有「无障碍服务没连 + 截图通道也不可用」时才需要引导。
     */
    fun needsAuthorization(): Boolean =
        !channels.accessibilityServiceConnected() && channels.needsAuthorization()

    /**
     * v1.3.12：截图兜底通道是否真的可用（无障碍截屏或媒体投影会话任一）。
     *
     * 摇一摇失败后用它判断要不要拉起一次录屏授权：
     * 微信账单详情这类自绘页面节点为空，必须靠截图 OCR；而媒体投影会话
     * 在 App 重启后必然失效，不主动引导就永远是「两条通道都不可用」。
     */
    fun captureChannelReady(): Boolean = channels.anyChannelReady()

    /** 媒体投影是否已有会话（用于区分「没授权」和「授权了但截失败」）。 */
    fun projectionSessionActive(): Boolean = channels.projectionReady()

    fun captureIntoForm(scope: CoroutineScope, onResult: (CaptureOutcome) -> Unit) {
        scope.launch {
            val steps = mutableListOf<DiagnosticStep>()
            val startedAt = System.currentTimeMillis()

            fun record(reason: CaptureFailReason?, rawText: String) {
                runCatching {
                    CaptureDiagnostics.record(
                        context,
                        CaptureReport(startedAt, reason, steps.toList(), rawText)
                    )
                }
            }

            fun fail(reason: CaptureFailReason, detail: String, rawText: String = "") {
                com.azu.bookkeeper.core.CaptureLog.w(context, "CAPTURE", "失败 ${reason.name}：$detail")
                steps += DiagnosticStep(reason.title, false, detail)
                Timber.w("识屏失败: %s | %s", reason.name, detail)
                record(reason, rawText)
                runCatching { progress.showFailed(manualEntryIntent(), reason) }
                runCatching { toast("识屏失败：${reason.title}") }
                onResult(CaptureOutcome(null, "", reason, detail))
            }

            suspend fun succeed(
                amountFen: Long,
                merchant: String,
                goods: String,
                confidence: Float,
                rawText: String,
                captureType: CaptureType
            ) {
                val remark = composeRemark(merchant, goods)
                steps += DiagnosticStep(
                    "提取金额", merchant.isNotBlank(),
                    "¥${amountFen / 100.0} / 商户=${merchant.ifBlank { "未识别" }}" +
                        " / 内容=${goods.ifBlank { "无" }} / conf=${"%.2f".format(confidence)}"
                )
                com.azu.bookkeeper.core.CaptureLog.i(
                    context, "CAPTURE",
                    "识别成功 amount=$amountFen merchant=${merchant.ifBlank { "-" }}" +
                        " goods=${goods.ifBlank { "-" }} conf=$confidence"
                )
                record(null, rawText)
                // v1.3.1：不再用「识屏完成 · 点击确认记账」常驻通知收尾，
                // 结果统一由底部轻卡片呈现（自动入账成功 / 低置信度轻确认）
                runCatching { progress.clear() }
                withContext(mainDispatcher) {
                    onResult(
                        CaptureOutcome(
                            amountFen = amountFen,
                            merchant = merchant,
                            confidence = confidence,
                            captureType = captureType,
                            rawText = rawText,
                            goods = goods
                        )
                    )
                }
            }

            try {
                var rawText = ""

                // ---------- 阶段 1：无障碍节点读屏（主路径，不截屏、不授权、不崩） ----------
                if (channels.accessibilityServiceConnected()) {
                    runCatching { progress.showStage(CaptureProgressNotifier.Stage.RECOGNIZE) }
                    com.azu.bookkeeper.core.CaptureLog.i(context, "CAPTURE", "阶段=节点读屏")
                    val snapshot = withContext(ioDispatcher) {
                        PaymentAccessibilityService.readScreenSnapshot()
                    }
                    if (snapshot != null && snapshot.text.isNotBlank()) {
                        rawText = snapshot.text
                        steps += DiagnosticStep(
                            "读屏节点", true,
                            "${snapshot.nodeCount} 个文本节点 / ${snapshot.text.length} 字 / ${snapshot.packageName}"
                        )
                        runCatching { progress.showStage(CaptureProgressNotifier.Stage.EXTRACT) }
                        val fields = extract(rawText)
                        val amountFen = fields.amountFen
                        if (amountFen != null) {
                            succeed(
                                amountFen, fields.merchant, fields.goods, fields.confidence, rawText,
                                CaptureType.ACCESSIBILITY_TEXT
                            )
                            return@launch
                        }
                        steps += DiagnosticStep(
                            "读屏提取", false,
                            "节点文本里没有金额候选，前 40 字：${rawText.take(40)}"
                        )
                    } else {
                        steps += DiagnosticStep("读屏节点", false, "当前页面没有可读文本（自绘页面/WebView 常见）")
                    }
                } else {
                    steps += DiagnosticStep(
                        "读屏节点", false,
                        "无障碍服务未连接（设置 → 无障碍 → 少花点儿 打开后即可免截屏识屏）"
                    )
                }

                // ---------- 阶段 2：截图 OCR 兜底（需通道可用，可选） ----------
                if (channels.anyChannelReady()) {
                    runCatching { progress.showStage(CaptureProgressNotifier.Stage.CAPTURE) }
                    com.azu.bookkeeper.core.CaptureLog.i(context, "CAPTURE", "阶段=截屏兜底")
                    val frame = withContext(ioDispatcher) { channels.capture() }
                    val bitmap = frame.bitmap
                    if (bitmap == null) {
                        val detail = frame.attempts.joinToString("；").ifBlank { frame.detail }
                        if (rawText.isNotBlank()) {
                            fail(CaptureFailReason.EXTRACT_EMPTY, "读屏与截屏都没提取到金额：$detail", rawText)
                        } else {
                            fail(frame.reason ?: CaptureFailReason.UNKNOWN, detail)
                        }
                        return@launch
                    }
                    steps += DiagnosticStep("截屏（${frame.channel}）", true, frame.detail)

                    runCatching { progress.showStage(CaptureProgressNotifier.Stage.RECOGNIZE) }
                    val ipc = withContext(ioDispatcher) { ocrIpc.recognize(bitmap) }
                    runCatching { bitmap.recycle() }
                    val payload = ipc.payload
                    if (payload == null) {
                        fail(CaptureFailReason.OCR_PROCESS_FAILED, ipc.error ?: "unknown", rawText)
                        return@launch
                    }
                    rawText = listOf(rawText, payload.text).filter { it.isNotBlank() }.joinToString("\n")
                    val ocrFields = extract(rawText)
                    val amountFen = ocrFields.amountFen
                    if (amountFen != null) {
                        // 统一用抽取置信度做分流依据（OCR 引擎置信度只反映字符清晰度，不代表字段抽对）
                        succeed(
                            amountFen, ocrFields.merchant, ocrFields.goods, ocrFields.confidence, rawText,
                            CaptureType.PROJECTION_IMAGE
                        )
                        return@launch
                    }
                    fail(
                        CaptureFailReason.EXTRACT_EMPTY,
                        "截图 OCR 完成但没找到金额（当前页可能不是支付结果页）",
                        rawText
                    )
                    return@launch
                }

                // ---------- 两条路都不可用 ----------
                if (rawText.isNotBlank()) {
                    fail(CaptureFailReason.EXTRACT_EMPTY, "读到页面文字但没有金额候选", rawText)
                } else {
                    fail(CaptureFailReason.NO_CHANNEL, "无障碍节点读屏与截图通道都不可用")
                }
            } catch (t: Throwable) {
                // 任何异常都不许逃出：抛出会让前台服务/进程崩掉
                fail(
                    CaptureFailReason.UNKNOWN,
                    "${t.javaClass.simpleName}: ${t.message}",
                    ""
                )
            }
        }
    }

    /** 一次分析同时拿到金额 / 商户 / 购买内容 / 置信度，避免同一段文本重复跑三遍正则。 */
    private fun extract(text: String): ScreenFields {
        if (text.isBlank()) return ScreenFields()
        val analysis = runCatching { analyzer.analyze(text) }.getOrNull()
        val fallback = runCatching {
            extractor.extract(text, System.currentTimeMillis())
        }.getOrNull()
        return ScreenFields(
            amountFen = analysis?.amountFen ?: fallback?.amountFen,
            merchant = analysis?.merchant?.takeIf { it.isNotBlank() }
                ?: fallback?.merchant.orEmpty(),
            goods = analysis?.goods.orEmpty(),
            confidence = analysis?.confidence ?: 0f
        )
    }

    /** 构造打开表单的 Intent（带预填备注：商户 · 购买内容）。 */
    fun formIntent(amountFen: Long?, remark: String): android.content.Intent {
        val request = QuickCaptureRequest(
            entry = QuickEntry.SYSTEM_GESTURE,
            amountFen = amountFen,
            merchant = remark
        )
        return QuickCaptureLauncher.intent(context, request)
    }

    private fun resultIntent(amountFen: Long?, remark: String): android.app.PendingIntent =
        QuickCaptureLauncher.pendingIntent(
            context,
            QuickCaptureRequest(
                entry = QuickEntry.SYSTEM_GESTURE,
                amountFen = amountFen,
                merchant = remark
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

/** 识屏抽出来的四个字段（内部用，不外传）。 */
private data class ScreenFields(
    val amountFen: Long? = null,
    val merchant: String = "",
    val goods: String = "",
    val confidence: Float = 0f
)
