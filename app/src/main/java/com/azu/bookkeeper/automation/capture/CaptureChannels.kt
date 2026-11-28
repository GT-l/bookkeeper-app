package com.azu.bookkeeper.automation.capture

import android.content.Context
import android.graphics.Bitmap
import com.azu.bookkeeper.automation.service.AccessibilityScreenshotSource
import com.azu.bookkeeper.automation.service.ScreenCaptureManager
import com.azu.bookkeeper.core.A11yShotOptIn
import com.azu.bookkeeper.core.CaptureBreakerStore
import com.azu.bookkeeper.core.CaptureChannel
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.CaptureGate
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 每一路都带「熔断」：调用前写 in-flight 标记，正常结束清除；
 * 若上次调用把进程干掉（native 崩溃），标记会残留，下次启动由
 * [CaptureBreakerStore.reconcile] 自动停用该通道——不会再让用户反复闪退。
 */
@Singleton
class CaptureChannels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessibility: AccessibilityScreenshotSource,
    private val projection: ScreenCaptureManager
) {

    fun accessibilityServiceConnected(): Boolean = accessibility.isServiceConnected()

    /** 无障碍截图实验开关是否开启（默认关闭，见 [A11yShotOptIn]）。 */
    fun accessibilityEnabledByUser(): Boolean = A11yShotOptIn.isEnabled(context)

    /**
     * 无障碍通道可用 = 服务具备截屏能力 + 未被熔断 + **用户显式开启实验开关**。
     * 默认关闭：部分 ROM 上该通道会 native 崩溃，不能让用户被动承受闪退。
     */
    fun accessibilityReady(): Boolean =
        A11yShotOptIn.isEnabled(context) &&
            accessibility.isReady() &&
            !CaptureBreakerStore.isOpen(context, CaptureChannel.ACCESSIBILITY)

    /** 无障碍有截屏能力（不考虑熔断），用于自检页区分「没能力」和「被停用」。 */
    fun accessibilityCapable(): Boolean = accessibility.isReady()

    fun projectionReady(): Boolean =
        projection.hasActiveSession() && !CaptureBreakerStore.isOpen(context, CaptureChannel.PROJECTION)

    fun isChannelDisabled(channel: CaptureChannel): Boolean =
        CaptureBreakerStore.isOpen(context, channel)

    fun anyChannelReady(): Boolean = accessibilityReady() || projectionReady()

    fun needsAuthorization(): Boolean = !anyChannelReady()

    /** 自检页「恢复截屏通道」：清掉熔断后重新评估。 */
    fun resetBreakers() = CaptureBreakerStore.reset(context)

    suspend fun capture(): ChannelCaptureResult {
        val attempts = mutableListOf<String>()
        com.azu.bookkeeper.core.CaptureLog.i(
            context, "CHANNEL",
            "开始抓帧 a11yCapable=${accessibility.isReady()} " +
                "a11yBreaker=${CaptureBreakerStore.isOpen(context, CaptureChannel.ACCESSIBILITY)} " +
                "projSession=${projection.hasActiveSession()} " +
                "projBreaker=${CaptureBreakerStore.isOpen(context, CaptureChannel.PROJECTION)}"
        )
        var accessibilityReason: CaptureFailReason? = null
        var accessibilityDetail = ""

        // ---- 通道 1：无障碍截屏（API 30+，免授权）----
        if (CaptureGate.shouldAttempt(
                accessibility.isReady() && A11yShotOptIn.isEnabled(context),
                CaptureBreakerStore.isOpen(context, CaptureChannel.ACCESSIBILITY)
            )
        ) {
            CaptureBreakerStore.markInflight(context, CaptureChannel.ACCESSIBILITY)
            com.azu.bookkeeper.core.CaptureLog.i(context, "CHANNEL", "尝试无障碍截屏")
            var shot = accessibility.capture()
            // 333ms 节流：稍等再来一次
            if (shot.errorCode == 3) {
                delay(400)
                shot = accessibility.capture()
            }
            // 走到这里说明调用返回了（无论成功失败），清除 in-flight 标记
            CaptureBreakerStore.clearInflight(context, CaptureChannel.ACCESSIBILITY)

            com.azu.bookkeeper.core.CaptureLog.i(
                context, "CHANNEL",
                "无障碍截屏返回 code=${shot.errorCode} bitmap=${shot.bitmap?.let { "${it.width}x${it.height}" } ?: "null"} detail=${shot.detail}"
            )
            val bitmap = shot.bitmap?.let { downscale(it) }
            // v1.3.16：帧体检。真实日志里出现过「截屏成功 1264x2780 → OCR 0 字」，
            // 说明系统给了张空白帧；旧的黑帧判定只看平均亮度，全白/全灰帧会漏过去。
            val a11yStats = bitmap?.let { runCatching { FrameSanity.analyze(it) }.getOrNull() }
            when {
                bitmap != null && (a11yStats == null || !a11yStats.uniform) -> {
                    if (a11yStats != null) {
                        com.azu.bookkeeper.core.CaptureLog.i(
                            context, "CHANNEL", "无障碍截屏帧体检 ${a11yStats.describe()}"
                        )
                    }
                    attempts += "无障碍截屏 ✓ ${shot.detail}"
                    return ChannelCaptureResult(
                        bitmap, "无障碍截屏", null,
                        shot.detail + (a11yStats?.let { " · ${it.describe()}" } ?: ""),
                        attempts
                    )
                }
                bitmap != null -> {
                    // 单色帧 = 系统没给到真实画面。别把空图丢给 OCR（那会报成「没找到金额」，
                    // 把真正的原因藏起来），直接让给媒体投影通道。
                    com.azu.bookkeeper.core.CaptureLog.w(
                        context, "CHANNEL",
                        "无障碍截屏整帧单色，按空白帧处理 ${a11yStats?.describe()}"
                    )
                    attempts += "无障碍截屏：空白帧（${a11yStats?.describe() ?: "统计失败"}）"
                    accessibilityReason = CaptureFailReason.BLACK_FRAME
                    accessibilityDetail =
                        "无障碍截屏返回空白帧（${a11yStats?.describe() ?: "无法统计"}）：系统没给到真实画面"
                    runCatching { bitmap.recycle() }
                }
                else -> {
                    accessibilityReason = shot.reason
                    accessibilityDetail = shot.detail
                    attempts += "无障碍截屏：${shot.reason?.title ?: "失败"}（${shot.detail}）"
                    if (shot.reason == CaptureFailReason.SECURE_WINDOW) {
                        return ChannelCaptureResult(
                            null, "无障碍截屏", CaptureFailReason.SECURE_WINDOW, shot.detail, attempts
                        )
                    }
                }
            }
        } else {
            attempts += when {
                CaptureBreakerStore.isOpen(context, CaptureChannel.ACCESSIBILITY) ->
                    "无障碍截屏：已自动停用（上次使用导致崩溃）"
                !A11yShotOptIn.isEnabled(context) ->
                    "无障碍截屏：未开启（实验功能，默认关闭）"
                else ->
                    "无障碍截屏：不可用（${if (accessibility.isServiceConnected()) "服务已连但无截屏能力" else "服务未连接"}）"
            }
        }

        // ---- 通道 2：MediaProjection（需授权，覆盖旧系统/无障碍不可用）----
        if (CaptureGate.shouldAttempt(
                projection.hasActiveSession(),
                CaptureBreakerStore.isOpen(context, CaptureChannel.PROJECTION)
            )
        ) {
            CaptureBreakerStore.markInflight(context, CaptureChannel.PROJECTION)
            com.azu.bookkeeper.core.CaptureLog.i(context, "CHANNEL", "尝试媒体投影截屏")
            val frame = projection.captureBitmapScaled()
            CaptureBreakerStore.clearInflight(context, CaptureChannel.PROJECTION)

            com.azu.bookkeeper.core.CaptureLog.i(
                context, "CHANNEL",
                "媒体投影返回 bitmap=${frame.bitmap?.let { "${it.width}x${it.height}" } ?: "null"} reason=${frame.reason?.name} detail=${frame.detail}"
            )
            val bitmap = frame.bitmap
            if (bitmap != null) {
                val stats = runCatching { FrameSanity.analyze(bitmap) }.getOrNull()
                if (stats != null) {
                    com.azu.bookkeeper.core.CaptureLog.i(
                        context, "CHANNEL", "媒体投影帧体检 ${stats.describe()}"
                    )
                }
                if (stats == null || stats.uniform) {
                    runCatching { bitmap.recycle() }
                    attempts += "媒体投影：空白帧（${stats?.describe() ?: "统计失败"}）"
                    return ChannelCaptureResult(
                        null, "媒体投影", CaptureFailReason.BLACK_FRAME,
                        "截到空白画面（${stats?.describe() ?: "无法统计"}）：多半是页面禁止截屏", attempts
                    )
                }
                attempts += "媒体投影 ✓ ${frame.detail}"
                return ChannelCaptureResult(
                    bitmap, "媒体投影", null,
                    frame.detail + " · ${stats.describe()}", attempts
                )
            }
            attempts += "媒体投影：${frame.reason?.title ?: "失败"}（${frame.detail}）"
            Timber.w("projection capture failed: %s", frame.detail)
        } else {
            attempts += if (CaptureBreakerStore.isOpen(context, CaptureChannel.PROJECTION)) {
                "媒体投影：已自动停用（上次使用导致崩溃）"
            } else {
                "媒体投影：无授权会话"
            }
        }

        val reason = when {
            accessibilityReason != null && accessibilityReason != CaptureFailReason.UNKNOWN -> accessibilityReason
            CaptureBreakerStore.isOpen(context, CaptureChannel.ACCESSIBILITY) &&
                CaptureBreakerStore.isOpen(context, CaptureChannel.PROJECTION) -> CaptureFailReason.CHANNEL_DISABLED
            else -> CaptureFailReason.NO_CHANNEL
        }
        com.azu.bookkeeper.core.CaptureLog.w(
            context, "CHANNEL", "抓帧全部失败 reason=${reason.name} attempts=${attempts.joinToString("；")}"
        )
        return ChannelCaptureResult(
            null, "无", reason,
            accessibilityDetail.ifBlank { attempts.joinToString("；") }, attempts
        )
    }

    /**
     * 统一缩放到最长边 1080：无障碍截屏返回的是全分辨率位图（2K 屏 ~18MB），
     * 直接送去 OCR 会翻倍占内存，低端机容易被系统杀掉。
     */
    private fun downscale(bitmap: Bitmap, maxDimension: Int = 1080): Bitmap = runCatching {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) runCatching { bitmap.recycle() }
        scaled
    }.getOrDefault(bitmap)

    /**
     * 黑帧 / 空白帧判定已升级为 [FrameSanity]（v1.3.16）：
     * 旧实现只算平均亮度 < 8，全白、全灰、半黑半空的帧都会漏过，
     * 结果把空白图丢给 OCR，最终报成「没找到金额」，掩盖了真正原因。
     */
}
