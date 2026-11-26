package com.azu.bookkeeper.automation.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import com.azu.bookkeeper.core.CaptureFailReason
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抓帧结果：成功时 path 非空；失败时 reason 给出可读原因（诊断链路使用）。
 */
data class FrameCaptureResult(
    val path: String?,
    val reason: CaptureFailReason?,
    val detail: String
) {
    val success: Boolean get() = path != null
}

/**
 * MediaProjection 截屏备选（主架构 §5.2 / AC-5）：
 * 用户授权后在 FGS 中拿投影实例，抓一帧 → 加密落盘 → 交 OCR。
 * Android 14+ 每次会话需重授权；onStop 回调释放投影并清空引用，自动降级读屏/通知路。
 */
@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedFiles: EncryptedFileDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val projectionManager: MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var onSessionStopped: (() -> Unit)? = null

    /** 系统授权弹窗 intent（明确告知将捕获屏幕内容）。 */
    fun createCaptureIntent(): Intent {
        // Android 14+：getMediaProjection / createVirtualDisplay 都要求已有
        // mediaProjection 类型的前台服务在跑，否则 Authorization 成功也会 SecurityException。
        // 在拉起授权弹窗之前就把服务拉起来，给系统留出启动时间。
        AutoCaptureForegroundService.startForProjection(context)
        return projectionManager.createScreenCaptureIntent()
    }

    /** 用户授权后建立投影会话；onStop 在授权失效时回调，供 UI 降级提示。 */
    fun startSession(resultCode: Int, data: Intent, onStopped: () -> Unit): Boolean = try {
        release()
        onSessionStopped = onStopped
        // 双保险：授权回调里再确保一次（弹窗期间服务可能被 ROM 杀掉）
        AutoCaptureForegroundService.startForProjection(context)
        projection = projectionManager.getMediaProjection(resultCode, data).also { mediaProjection ->
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // 授权会话失效：释放并清空引用，避免泄漏与后续崩溃
                    release()
                    onSessionStopped?.invoke()
                }
            }
            mediaProjection.registerCallback(callback, null)
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 抓一帧并加密保存，返回绝对路径；无会话 / 授权失效 / 超时均返回 null（上层降级）。
     */
    suspend fun capture(): String? = captureWithReason().path

    /**
     * 带失败原因的抓帧（诊断用）：每一步都给出可读原因，供 Toast / 通知 / 自检页展示。
     */
    suspend fun captureWithReason(): FrameCaptureResult = withContext(ioDispatcher) {
        val mediaProjection = projection
            ?: return@withContext FrameCaptureResult(null, CaptureFailReason.NO_SESSION, "projection == null")
        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        try {
            // Android 14+ 要求：调用 createVirtualDisplay 前必须有 mediaProjection 类型的前台服务在跑，
            // 否则 SecurityException。之前只靠设置页授权、没有这个前置，截屏会直接失败。
            AutoCaptureForegroundService.startForProjection(context)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = try {
                createVirtualDisplay(mediaProjection, width, height, density, imageReader.surface)
            } catch (e: Exception) {
                val reason = if (e is SecurityException) {
                    CaptureFailReason.PROJECTION_INIT_FAILED
                } else {
                    CaptureFailReason.VIRTUAL_DISPLAY_FAILED
                }
                return@withContext FrameCaptureResult(null, reason, "${e.javaClass.simpleName}: ${e.message}")
            }

            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                awaitBitmap(imageReader!!, width, height)
            } ?: return@withContext FrameCaptureResult(
                null, CaptureFailReason.FRAME_TIMEOUT, "${CAPTURE_TIMEOUT_MS}ms 内未收到画面帧"
            )

            val path = runCatching { encryptedFiles.saveCapture(bitmap) }.getOrNull()
            bitmap.recycle()
            if (path == null) {
                FrameCaptureResult(null, CaptureFailReason.SAVE_FAILED, "saveCapture 返回 null")
            } else {
                FrameCaptureResult(path, null, "${width}x$height")
            }
        } catch (e: Exception) {
            FrameCaptureResult(null, CaptureFailReason.UNKNOWN, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
        }
    }

    private fun createVirtualDisplay(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        density: Int,
        surface: android.view.Surface
    ): VirtualDisplay {
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection.createVirtualDisplay(
                "bookkeeper_capture", width, height, density, flags, surface, null, null
            )
        } else {
            @Suppress("DEPRECATION")
            mediaProjection.createVirtualDisplay(
                "bookkeeper_capture", width, height, density, flags, surface, null, null
            )
        }
    }

    private suspend fun awaitBitmap(reader: ImageReader, width: Int, height: Int): Bitmap? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val listener = ImageReader.OnImageAvailableListener { r ->
                val image = r.acquireLatestImage() ?: return@OnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(cropped) {}
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null) {}
                } finally {
                    image.close()
                }
            }
            reader.setOnImageAvailableListener(listener, HANDLER)
            cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
        }

    /** 释放投影会话（设置页关闭 / 服务销毁时调用）。 */
    fun release() {
        runCatching { projection?.stop() }
        projection = null
        onSessionStopped = null
    }

    fun hasActiveSession(): Boolean = projection != null

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 3_000L

        /**
         * 识屏从未成功的根因：setOnImageAvailableListener 传 null handler 时
         * 要求调用线程自带 Looper，而 capture() 跑在 IO 线程池（无 Looper），
         * 监听器要么抛异常被吞、要么永不回调 → 截屏永远超时返回 null。
         * 统一挂到主线程 Looper，回调只做取帧转 Bitmap，不卡主线程。
         */
        val HANDLER = Handler(Looper.getMainLooper())
    }
}
