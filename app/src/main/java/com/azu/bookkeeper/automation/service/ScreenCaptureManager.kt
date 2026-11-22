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
import android.util.DisplayMetrics
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaProjection 截屏备选（主架构 §5.2 / AC-5）：
 * 用户授权后在 FGS 中拿投影实例，抓一帧 → 加密落盘 → 交 OCR。
 * Android 14+ 每次会话需重授权；onStopped 回调释放投影并清空引用，自动降级读屏/通知路。
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
    fun createCaptureIntent(): Intent = projectionManager.createScreenCaptureIntent()

    /** 用户授权后建立投影会话；onStopped 在授权失效时回调，供 UI 降级提示。 */
    fun startSession(resultCode: Int, data: Intent, onStopped: () -> Unit): Boolean = try {
        release()
        onSessionStopped = onStopped
        projection = projectionManager.getMediaProjection(resultCode, data).also { mediaProjection ->
            val callback = object : MediaProjection.Callback() {
                override fun onStopped() {
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
    suspend fun capture(): String? = withContext(ioDispatcher) {
        val mediaProjection = projection ?: return@withContext null
        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = createVirtualDisplay(mediaProjection, width, height, density, imageReader.surface)

            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                awaitBitmap(imageReader!!, width, height)
            } ?: return@withContext null

            val path = encryptedFiles.saveCapture(bitmap)
            bitmap.recycle()
            path
        } catch (_: Exception) {
            null
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
                "bookkeeper_capture", width, height, density, flags, surface, null
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
            reader.setOnImageAvailableListener(listener, null)
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
    }
}
