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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 抓帧结果：成功时 path 非空；失败时 reason 给出可读原因（诊断链路使用）。 */
data class FrameCaptureResult(
    val path: String?,
    val reason: CaptureFailReason?,
    val detail: String
) {
    val success: Boolean get() = path != null
}

/** 内存帧结果：识屏主链路直接用 Bitmap 交给 OCR，不再落盘（更快、更省内存）。 */
data class FrameBitmapResult(
    val bitmap: Bitmap?,
    val reason: CaptureFailReason?,
    val detail: String
) {
    val success: Boolean get() = bitmap != null
}

/**
 * MediaProjection 截屏备选（主架构 §5.2 / AC-5）。
 *
 * 关键实现要点（都是踩过的坑）：
 * 1. Android 14+：getMediaProjection / createVirtualDisplay 之前必须有 mediaProjection 类型的
 *    前台服务在跑，否则授权成功也会 SecurityException → 会话建不起来；
 * 2. ImageReader 监听器必须挂主线程 Looper（null handler 在无 Looper 的 IO 线程上不回调）；
 * 3. 抓帧按最长边 1280 缩放：全分辨率 ARGB_8888 在 2K 屏上一次就是 70MB+，
 *    直接 OOM 把整个 App 杀掉（用户看到的「点自检就退出」）；
 * 4. 所有异常都按 Throwable 兜（OutOfMemoryError 是 Error，catch Exception 接不住）。
 */
@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    // 个别 ROM/受限环境下服务可能为 null；强转会直接崩，改为安全获取 + 使用处判空
    private val projectionManager: MediaProjectionManager? by lazy {
        runCatching {
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        }.getOrNull()
    }

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var onSessionStopped: (() -> Unit)? = null

    /**
     * 系统授权弹窗 intent（明确告知将捕获屏幕内容）。
     * 拉起前先把 mediaProjection 类型的前台服务起来，给系统留启动时间。
     */
    fun createCaptureIntent(): Intent? {
        AutoCaptureForegroundService.startForProjection(context)
        return runCatching { projectionManager?.createScreenCaptureIntent() }.getOrNull()
    }

    /** 用户授权后建立投影会话；onStop 在授权失效时回调，供 UI 降级提示。 */
    fun startSession(resultCode: Int, data: Intent, onStopped: () -> Unit): Boolean = try {
        release()
        onSessionStopped = onStopped
        // 双保险：授权回调里再确保一次（弹窗期间服务可能被 ROM 杀掉）
        AutoCaptureForegroundService.startForProjection(context)
        val manager = projectionManager ?: return false
        projection = manager.getMediaProjection(resultCode, data).also { mediaProjection ->
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // 授权会话失效：释放并清空引用，避免泄漏与后续崩溃
                    release()
                    onSessionStopped?.invoke()
                }
            }
            mediaProjection.registerCallback(callback, null)
        }
        com.azu.bookkeeper.core.CaptureLog.i(context, "PROJ", "MediaProjection 会话已建立")
        true
    } catch (t: Throwable) {
        Timber.w(t, "start projection session failed")
        com.azu.bookkeeper.core.CaptureLog.e(context, "PROJ", "MediaProjection 会话建立失败", t)
        false
    }

    /** 抓一帧并保存为缓存 JPEG，返回绝对路径；兼容旧管线（CaptureCoordinator）。 */
    suspend fun capture(): String? = captureWithReason().path

    /** 带失败原因的抓帧（落盘版，诊断/旧管线使用）。 */
    suspend fun captureWithReason(): FrameCaptureResult = withContext(ioDispatcher) {
        val frame = captureBitmapScaled()
        val bitmap = frame.bitmap
            ?: return@withContext FrameCaptureResult(null, frame.reason, frame.detail)
        try {
            val path = saveJpegToCache(bitmap)
            if (path == null) {
                FrameCaptureResult(null, CaptureFailReason.SAVE_FAILED, "JPEG 写入缓存失败")
            } else {
                FrameCaptureResult(path, null, frame.detail)
            }
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * 抓一帧到内存（最长边缩放到 [maxDimension]），识屏主链路与自检都走这里。
     * 调用方负责 recycle 返回的 Bitmap。
     */
    suspend fun captureBitmapScaled(maxDimension: Int = 1080): FrameBitmapResult =
        withContext(ioDispatcher) {
            val mediaProjection = projection
                ?: return@withContext FrameBitmapResult(null, CaptureFailReason.NO_SESSION, "projection == null")
            var imageReader: ImageReader? = null
            var virtualDisplay: VirtualDisplay? = null
            try {
                // Android 14+：createVirtualDisplay 前必须有 mediaProjection 类型的前台服务
                AutoCaptureForegroundService.startForProjection(context)

                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.getMetrics(metrics)
                val fullWidth = metrics.widthPixels.coerceAtLeast(1)
                val fullHeight = metrics.heightPixels.coerceAtLeast(1)
                val scale = minOf(1f, maxDimension.toFloat() / maxOf(fullWidth, fullHeight))
                val width = (fullWidth * scale).toInt().coerceAtLeast(1)
                val height = (fullHeight * scale).toInt().coerceAtLeast(1)

                // 单缓冲即可（AUTO_MIRROR 会持续产帧）；多缓冲会翻倍占内存，低端机易 OOM
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
                virtualDisplay = try {
                    createVirtualDisplay(mediaProjection, width, height, metrics.densityDpi, imageReader.surface)
                } catch (t: Throwable) {
                    val reason = if (t is SecurityException) {
                        CaptureFailReason.PROJECTION_INIT_FAILED
                    } else {
                        CaptureFailReason.VIRTUAL_DISPLAY_FAILED
                    }
                    com.azu.bookkeeper.core.CaptureLog.e(
                        context, "PROJ", "createVirtualDisplay 失败 ${t.javaClass.simpleName}: ${t.message}", t
                    )
                    return@withContext FrameBitmapResult(null, reason, "${t.javaClass.simpleName}: ${t.message}")
                }

                val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    awaitBitmap(imageReader!!, width, height)
                } ?: run {
                    com.azu.bookkeeper.core.CaptureLog.w(
                        context, "PROJ", "取帧超时（${CAPTURE_TIMEOUT_MS}ms）${width}x${height}"
                    )
                    return@withContext FrameBitmapResult(
                        null, CaptureFailReason.FRAME_TIMEOUT, "${CAPTURE_TIMEOUT_MS}ms 内未收到画面帧"
                    )
                }

                FrameBitmapResult(bitmap, null, "${width}x$height")
            } catch (t: Throwable) {
                // Throwable：OutOfMemoryError 也在内，绝不让它冒到上层杀进程
                FrameBitmapResult(null, CaptureFailReason.UNKNOWN, "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { virtualDisplay?.release() }
                runCatching { imageReader?.close() }
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
                val image = runCatching { r.acquireLatestImage() }.getOrNull()
                    ?: return@OnImageAvailableListener
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
                } catch (t: Throwable) {
                    Timber.w(t, "decode frame failed")
                    if (cont.isActive) cont.resume(null) {}
                } finally {
                    runCatching { image.close() }
                }
            }
            reader.setOnImageAvailableListener(listener, HANDLER)
            cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
        }

    /** 写缓存 JPEG（不加密、不依赖 Keystore，识屏瞬时截图用完即删）。 */
    private fun saveJpegToCache(bitmap: Bitmap): String? = runCatching {
        val dir = File(context.cacheDir, "captures").apply { if (!exists()) mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)) return null
        }
        file.absolutePath
    }.getOrNull()

    /** 清理解码/OCR 后的临时截图。 */
    fun deleteTemp(absolutePath: String?) = runCatching {
        absolutePath?.let { File(it).takeIf { f -> f.exists() }?.delete() }
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
         * setOnImageAvailableListener 传 null handler 时要求调用线程自带 Looper，
         * 而抓帧跑在 IO 线程池（无 Looper）→ 监听器不回调 / 抛异常 → 截屏永远超时。
         */
        val HANDLER = Handler(Looper.getMainLooper())
    }
}
