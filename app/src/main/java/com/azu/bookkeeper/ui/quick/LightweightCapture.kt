package com.azu.bookkeeper.ui.quick

import android.content.Context
import android.widget.Toast
import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.automation.ocr.OcrEngine
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
 * 这里只保留「截屏 → OCR → 正则提取 → 打开带预填的表单让用户确认」这条最短路径，
 * 依赖极轻（三个无状态单例），不写库、不弹校对窗，因此不会因重型依赖构造失败而罢工。
 * 用户确认后保存仍走既有 AddBillUseCase，数据完整性不变。
 */
@Singleton
class LightweightCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenCapture: ScreenCaptureManager,
    private val ocr: OcrEngine,
    private val extractor: FieldExtractor,
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
                val path = withContext(ioDispatcher) { screenCapture.capture() }
                if (path == null) {
                    withContext(mainDispatcher) { onOpenForm(null, "") }
                    return@launch
                }
                val result = withContext(ioDispatcher) { ocr.recognizeFromFile(path) }
                val fields = extractor.extract(result.rawText, System.currentTimeMillis())
                withContext(mainDispatcher) {
                    onOpenForm(fields.amountFen, fields.merchant)
                }
            }.onFailure { e ->
                Timber.w(e, "lightweight capture failed")
                withContext(mainDispatcher) { onOpenForm(null, "") }
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

    fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
    }
}
