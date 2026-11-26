package com.azu.bookkeeper.ui.quick

import android.content.Context
import android.widget.Toast
import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.automation.ocr.OcrEngine
import com.azu.bookkeeper.automation.ocr.PaymentTextAnalyzer
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
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

/**
 * 快捷入口专用的轻量识屏器。
 *
 * 为什么单独做：原来的 [com.azu.bookkeeper.ui.quick.QuickCaptureTrigger] 依赖
 * CaptureCoordinator 全家桶（OCR + 分类 + 去重 + 校验弹窗 + 多个 Repository + 数据库事务）。
 * 从前台服务里通过 Hilt EntryPoint 取它时，只要依赖图里任何一环构造失败（常见于
 * 权限刚变更、进程被系统重启、数据库尚未就绪），getOrNull() 就返回 null，
 * 用户看到的是「识屏组件不可用」而不是表单 —— 这个体验很糟。
 *
 * 这里只保留「截屏 → OCR → 智能提取 → 打开带预填的表单让用户确认」这条最短路径，
 * 依赖极轻（四个无状态单例 + 进度通知），不写库、不弹校对窗，因此不会因重型依赖构造失败而罢工。
 * 用户确认后保存仍走既有 AddBillUseCase，数据完整性不变。
 *
 * 进度反馈（灵动岛适配）：截屏/识别/提取各阶段通过 [CaptureProgressNotifier] 投递
 * ongoing 进度通知——小米焦点通知 / 华为荣耀实况窗 / OPPO 流体云会自动聚合成顶部胶囊，
 * 其余 ROM 显示通知栏进度；失败时同时自动打开表单让用户手动记一笔。
 */
@Singleton
class LightweightCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenCapture: ScreenCaptureManager,
    private val ocr: OcrEngine,
    private val analyzer: PaymentTextAnalyzer,
    private val extractor: FieldExtractor,
    private val progress: CaptureProgressNotifier,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    /** 没有 MediaProjection 会话：需要用户先授权截屏。 */
    fun needsAuthorization(): Boolean = !screenCapture.hasActiveSession()

    /**
     * 触发一次轻量识屏。
     * @param onOpenForm 拿到预填值（或空值）后在主线程打开表单；amountFen 为 null 表示没识别到
     */
    fun captureIntoForm(
        scope: CoroutineScope,
        onOpenForm: (amountFen: Long?, merchant: String) -> Unit
    ) {
        scope.launch {
            runCatching {
                progress.showStage(CaptureProgressNotifier.Stage.CAPTURE)
                val path = withContext(ioDispatcher) { screenCapture.capture() }
                if (path == null) {
                    Timber.w("lightweight capture: screen frame unavailable")
                    onFail(onOpenForm)
                    return@launch
                }
                progress.showStage(CaptureProgressNotifier.Stage.RECOGNIZE)
                val result = withContext(ioDispatcher) { ocr.recognizeFromFile(path) }
                progress.showStage(CaptureProgressNotifier.Stage.EXTRACT)
                // 智能分析器优先（多因子打分）；极端情况（识别到旧格式文案）退回早期提取器
                val analysis = analyzer.analyze(result.rawText)
                val amountFen = analysis.amountFen
                    ?: extractor.extract(result.rawText, System.currentTimeMillis()).amountFen
                val merchant = analysis.merchant.ifBlank {
                    extractor.extract(result.rawText, System.currentTimeMillis()).merchant
                }
                Timber.i(
                    "lightweight capture ok: ocrConf=%.2f extractConf=%.2f paid=%b amount=%s merchant=%s",
                    result.confidence, analysis.confidence, analysis.paidConfirmed,
                    amountFen?.toString() ?: "null", merchant.ifBlank { "-" }
                )
                progress.showResult(
                    amountFen,
                    merchant,
                    QuickCaptureLauncher.pendingIntent(
                        context,
                        QuickCaptureRequest(
                            entry = QuickEntry.SYSTEM_GESTURE,
                            amountFen = amountFen,
                            merchant = merchant
                        ),
                        REQUEST_CODE_RESULT
                    )
                )
                withContext(mainDispatcher) { onOpenForm(amountFen, merchant) }
            }.onFailure { e ->
                Timber.w(e, "lightweight capture failed")
                onFail(onOpenForm)
            }
        }
    }

    /** 失败路径：进度通知换成「点击手动记一笔」，同时自动打开空表单。 */
    private suspend fun onFail(onOpenForm: (amountFen: Long?, merchant: String) -> Unit) {
        progress.showFailed(
            QuickCaptureLauncher.pendingIntent(
                context,
                QuickCaptureRequest(entry = QuickEntry.SYSTEM_GESTURE),
                REQUEST_CODE_FAILED
            )
        )
        withContext(mainDispatcher) { onOpenForm(null, "") }
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

    fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
    }

    private companion object {
        const val REQUEST_CODE_RESULT = 2005
        const val REQUEST_CODE_FAILED = 2006
    }
}
