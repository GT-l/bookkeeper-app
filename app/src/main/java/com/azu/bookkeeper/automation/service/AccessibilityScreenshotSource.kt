package com.azu.bookkeeper.automation.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.azu.bookkeeper.core.CaptureFailReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 无障碍截屏结果：errorCode 保留系统原始错误码，便于日志定位。 */
data class AccessibilityShotResult(
    val bitmap: Bitmap?,
    val errorCode: Int,
    val reason: CaptureFailReason?,
    val detail: String
) {
    val success: Boolean get() = bitmap != null
}

/**
 * 无障碍截屏通道（Android 11 / API 30+ 的 AccessibilityService.takeScreenshot）。
 *
 * 相比 MediaProjection 的优势：不需要用户授权录屏、不依赖投影会话生命周期、
 * 重启 App 也不会失效——这是识屏最应该先走的一条路。
 *
 * 前提（缺一不可，任何一条缺失都会静默失败）：
 * 1. 系统里无障碍服务真的连上（PaymentAccessibilityService.current() != null）；
 * 2. 服务能力含 CAPABILITY_CAN_TAKE_SCREENSHOT（XML canTakeScreenshot="true"）；
 * 3. 目标窗口未开启 FLAG_SECURE（否则返回 INVALID_WINDOW，截出来也是黑的）。
 *
 * 所有错误码都会打日志并映射成可读原因。
 */
@Singleton
class AccessibilityScreenshotSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 通道是否可用：服务已连接 + API 30+ + 具备截屏能力。 */
    fun isReady(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && PaymentAccessibilityService.canCaptureScreen()

    /** 服务是否连接（用于区分「没开无障碍」和「开了但没截屏能力」）。 */
    fun isServiceConnected(): Boolean = PaymentAccessibilityService.current() != null

    suspend fun capture(): AccessibilityShotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AccessibilityShotResult(
                null, -100, CaptureFailReason.ACCESSIBILITY_NOT_CONNECTED,
                "Android ${Build.VERSION.RELEASE} 不支持无障碍截屏（需 Android 11+）"
            )
        }
        val service = PaymentAccessibilityService.current()
        if (service == null) {
            com.azu.bookkeeper.core.CaptureLog.w(context, "A11Y", "takeScreenshot 跳过：无障碍服务未连接")
            return AccessibilityShotResult(
                null, -101, CaptureFailReason.ACCESSIBILITY_NOT_CONNECTED,
                "无障碍服务未连接（设置 → 无障碍 → 少花点儿 未开启或已被系统关闭）"
            )
        }
        com.azu.bookkeeper.core.CaptureLog.i(context, "A11Y", "takeScreenshot 发起")
        return suspendCancellableCoroutine { cont ->
            // takeScreenshot 走系统 binder，且服务实例的生命周期在主线程序列——
            // 统一在主线程发起，避免线程约束导致的系统级异常。
            context.mainExecutor.execute {
                runCatching { requestShot(service) { result ->
                    if (cont.isActive) cont.resume(result)
                } }.onFailure { t ->
                    Timber.w(t, "takeScreenshot invoke failed")
                    if (cont.isActive) {
                        cont.resume(
                            AccessibilityShotResult(
                                null, -102, CaptureFailReason.UNKNOWN,
                                "${t.javaClass.simpleName}: ${t.message}"
                            )
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestShot(
        service: AccessibilityService,
        onResult: (AccessibilityShotResult) -> Unit
    ) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            context.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bitmap = runCatching {
                        val buffer = result.hardwareBuffer
                        try {
                            // HardwareBuffer 带 rowStride，必须 wrap 后再拷成 ARGB_8888，
                            // 否则会出现花屏/黑屏（用户清单里的第 3 条）
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            runCatching { buffer.close() }
                        }
                    }.getOrNull()
                    Timber.i(
                        "accessibility screenshot ok: %s",
                        bitmap?.let { "${it.width}x${it.height}" } ?: "convert failed"
                    )
                    com.azu.bookkeeper.core.CaptureLog.i(
                        context, "A11Y",
                        "takeScreenshot 成功 " + (bitmap?.let { "${it.width}x${it.height}" } ?: "HardwareBuffer 转换失败")
                    )
                    onResult(
                        if (bitmap == null) {
                            AccessibilityShotResult(
                                null, 0, CaptureFailReason.BLACK_FRAME, "HardwareBuffer 转 Bitmap 失败"
                            )
                        } else {
                            AccessibilityShotResult(bitmap, 0, null, "${bitmap.width}x${bitmap.height}")
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    val (reason, text) = mapError(errorCode)
                    Timber.w("accessibility screenshot failed: code=%d %s", errorCode, text)
                    com.azu.bookkeeper.core.CaptureLog.w(
                        context, "A11Y", "takeScreenshot 失败 code=$errorCode reason=${reason.name} $text"
                    )
                    onResult(AccessibilityShotResult(null, errorCode, reason, text))
                }
            }
        )
    }

    /** 系统错误码 → 可读原因（AOSP AccessibilityService.ERROR_TAKE_SCREENSHOT_*）。 */
    private fun mapError(code: Int): Pair<CaptureFailReason, String> = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            CaptureFailReason.SCREENSHOT_THROTTLED to "INTERVAL_TIME_SHORT(3)：333ms 内重复截屏被拒"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            CaptureFailReason.SECURE_WINDOW to "INVALID_WINDOW(5)：窗口禁止截屏（FLAG_SECURE）"
        // API 34 新增：窗口含安全内容（FLAG_SECURE 的显式错误码）
        6 -> CaptureFailReason.SECURE_WINDOW to "SECURE_WINDOW(6)：窗口含安全内容，禁止截屏"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            CaptureFailReason.ACCESSIBILITY_NOT_CONNECTED to "NO_ACCESSIBILITY_ACCESS(2)：无障碍权限丢失"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            CaptureFailReason.UNKNOWN to "INVALID_DISPLAY(4)：显示器无效"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            CaptureFailReason.UNKNOWN to "INTERNAL_ERROR(1)：系统内部错误"
        else -> CaptureFailReason.UNKNOWN to "errorCode=$code"
    }
}
