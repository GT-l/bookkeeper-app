package com.azu.bookkeeper.ui.quick

import android.content.Context
import android.widget.Toast
import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.capture.CaptureEvent
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.CaptureType
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
 * 悬浮球长按共用的「主动识屏记账」触发器。
 *
 * 识屏流程不重复 OCR：CaptureCoordinator.onCapture 内部已包含
 * 幂等 → 落库 → OCR → 分类 → 置信度 → 去重 → 自动入账/进校对 的完整管线，
 * 这里只负责截屏并投递 [CaptureEvent]，终态反馈由 DB status 反推。
 */
@Singleton
class QuickCaptureTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenCaptureManager: ScreenCaptureManager,
    private val captureCoordinator: CaptureCoordinator,
    private val captureDao: CaptureDao,
    @com.azu.bookkeeper.core.di.MainDispatcher private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    @com.azu.bookkeeper.core.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 触发一次识屏记账。
     * @param feedback 主线程回调，参数为 (提示文案, Toast 时长)
     * @param onFailed 识屏最终失败（截屏失败 / 异常 / 识别未完成）时的主线程回调，
     *                 调用方据此打开记账表单让用户手动输入；低置信度不算失败（已有校对窗）
     * @return true 表示已发起识屏流程；false 表示没有可用截屏会话（调用方可自行降级）
     */
    fun trigger(
        scope: CoroutineScope,
        feedback: (String, Int) -> Unit,
        onFailed: (() -> Unit)? = null
    ): Boolean {
        if (!screenCaptureManager.hasActiveSession()) {
            return false
        }
        scope.launch {
            runCatching {
                val path = withContext(ioDispatcher) { screenCaptureManager.capture() }
                if (path == null) {
                    withContext(mainDispatcher) {
                        feedback("识屏失败，请重新完成授权", Toast.LENGTH_LONG)
                        onFailed?.invoke()
                    }
                    return@launch
                }
                val event = CaptureEvent(
                    // eventId 只需保证幂等键唯一；手动触发场景以毫秒时间戳派生即可
                    eventId = System.currentTimeMillis(),
                    captureType = CaptureType.PROJECTION_IMAGE,
                    imagePath = path,
                    rawText = "",
                    billTime = System.currentTimeMillis()
                )
                captureCoordinator.onCapture(event)
                // 管线终态由 DB status 反推，区分三种反馈口径
                val status = withContext(ioDispatcher) {
                    captureDao.findByEventId(event.eventId)?.status
                }
                withContext(mainDispatcher) {
                    when (status) {
                        CaptureEventStatus.LOW_CONFIDENCE.code -> feedback("已进入校对", Toast.LENGTH_SHORT)
                        // PENDING：管线未走完（如缺默认账本），直接让用户手动输入
                        CaptureEventStatus.PENDING.code -> {
                            feedback("识别未完成，请手动输入", Toast.LENGTH_LONG)
                            onFailed?.invoke()
                        }
                        else -> feedback("已识别并记账", Toast.LENGTH_SHORT)
                    }
                }
            }.onFailure { e ->
                Timber.w(e, "overlay screen capture failed")
                withContext(mainDispatcher) {
                    feedback("识屏失败，请稍后再试", Toast.LENGTH_SHORT)
                    onFailed?.invoke()
                }
            }
        }
        return true
    }

    /** 没有截屏会话时的降级 Intent：调用方（Service）用它在主线程 startActivity。 */
    fun formIntent(): android.content.Intent =
        QuickCaptureLauncher.intent(context, QuickCaptureRequest(entry = QuickEntry.FLOATING_BALL))
}
