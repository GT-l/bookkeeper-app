package com.azu.bookkeeper.automation.capture

import android.graphics.Bitmap
import com.azu.bookkeeper.automation.service.AccessibilityScreenshotSource
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import com.azu.bookkeeper.core.CaptureFailReason
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 通道抓帧结果：channel 记录实际走通的通道，attempts 记录每条通道的失败经过。 */
data class ChannelCaptureResult(
    val bitmap: Bitmap?,
    val channel: String,
    val reason: CaptureFailReason?,
    val detail: String,
    val attempts: List<String> = emptyList()
) {
    val success: Boolean get() = bitmap != null
}

/**
 * 双通道截屏调度：无障碍截屏（API 30+，免授权）优先，MediaProjection 兜底。
 *
 * 为什么双通道：
 * - 无障碍截屏不需要录屏授权、不受投影会话失效影响，是识屏最稳的一条路；
 * - MediaProjection 覆盖 Android 8~10（无无障碍截屏）与无障碍被系统关闭的场景；
 * - 两条都失败时，把两条通道的具体失败原因一起交给诊断页，用户一眼看出是
 *   「没开无障碍」「没授权录屏」还是「页面禁止截屏（FLAG_SECURE）」。
 *
 * 附加：黑帧检测（FLAG_SECURE / 视频层保护截出来是全黑），命中后直接给
 * 「页面禁止截屏」的明确结论，而不是让用户看到「OCR 没识别到文字」。
 */
@Singleton
class CaptureChannels @Inject constructor(
    private val accessibility: AccessibilityScreenshotSource,
    private val projection: ScreenCaptureManager
) {

    /** 任一通道可用即可识屏；都不可用时才需要引导用户去授权。 */
    fun anyChannelReady(): Boolean = accessibility.isReady() || projection.hasActiveSession()

    fun needsAuthorization(): Boolean = !anyChannelReady()

    fun accessibilityReady(): Boolean = accessibility.isReady()

    fun accessibilityServiceConnected(): Boolean = accessibility.isServiceConnected()

    fun projectionReady(): Boolean = projection.hasActiveSession()

    suspend fun capture(): ChannelCaptureResult {
        val attempts = mutableListOf<String>()
        var accessibilityReason: CaptureFailReason? = null
        var accessibilityDetail = ""

        // ---- 通道 1：无障碍截屏（API 30+，免授权）----
        if (accessibility.isReady()) {
            var shot = accessibility.capture()
            // 333ms 节流：稍等再来一次
            if (shot.errorCode == 3) {
                delay(400)
                shot = accessibility.capture()
            }
            val bitmap = shot.bitmap
            when {
                bitmap != null && !isBlackFrame(bitmap) -> {
                    attempts += "无障碍截屏 ✓ ${shot.detail}"
                    return ChannelCaptureResult(bitmap, "无障碍截屏", null, shot.detail, attempts)
                }
                bitmap != null -> {
                    attempts += "无障碍截屏：截到黑帧"
                    runCatching { bitmap.recycle() }
                    // 黑帧基本就是 FLAG_SECURE，投影通道同样会黑，不必再等
                    return ChannelCaptureResult(
                        null, "无障碍截屏", CaptureFailReason.BLACK_FRAME,
                        "截到全黑画面（多半是页面禁止截屏）", attempts
                    )
                }
                else -> {
                    accessibilityReason = shot.reason
                    accessibilityDetail = shot.detail
                    attempts += "无障碍截屏：${shot.reason?.title ?: "失败"}（${shot.detail}）"
                    // 明确是禁止截屏：投影也不会有画面，直接给结论
                    if (shot.reason == CaptureFailReason.SECURE_WINDOW) {
                        return ChannelCaptureResult(
                            null, "无障碍截屏", CaptureFailReason.SECURE_WINDOW, shot.detail, attempts
                        )
                    }
                }
            }
        } else {
            attempts += "无障碍截屏：不可用（${if (accessibility.isServiceConnected()) "服务已连但无截屏能力" else "服务未连接"}）"
        }

        // ---- 通道 2：MediaProjection（需授权，覆盖旧系统/无障碍不可用）----
        if (projection.hasActiveSession()) {
            val frame = projection.captureBitmapScaled()
            val bitmap = frame.bitmap
            if (bitmap != null) {
                if (isBlackFrame(bitmap)) {
                    runCatching { bitmap.recycle() }
                    attempts += "媒体投影：截到黑帧"
                    return ChannelCaptureResult(
                        null, "媒体投影", CaptureFailReason.BLACK_FRAME,
                        "截到全黑画面（多半是页面禁止截屏）", attempts
                    )
                }
                attempts += "媒体投影 ✓ ${frame.detail}"
                return ChannelCaptureResult(bitmap, "媒体投影", null, frame.detail, attempts)
            }
            attempts += "媒体投影：${frame.reason?.title ?: "失败"}（${frame.detail}）"
            Timber.w("projection capture failed: %s", frame.detail)
        } else {
            attempts += "媒体投影：无授权会话"
        }

        val reason = when {
            accessibilityReason != null && accessibilityReason != CaptureFailReason.UNKNOWN -> accessibilityReason
            else -> CaptureFailReason.NO_CHANNEL
        }
        return ChannelCaptureResult(
            null, "无", reason,
            accessibilityDetail.ifBlank { attempts.joinToString("；") }, attempts
        )
    }

    /**
     * 黑帧检测：等距采样 32x64 个点算平均亮度，过低判定为全黑。
     * 支付/银行页开 FLAG_SECURE 时就是这种结果。
     */
    private fun isBlackFrame(bitmap: Bitmap): Boolean = runCatching {
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 64).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val luma = ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
                sum += luma
                count++
                x += stepX
            }
            y += stepY
        }
        count > 0 && (sum / count) < 8
    }.getOrDefault(false)
}
