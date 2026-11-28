package com.azu.bookkeeper.automation.ocr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** OCR 文本载荷（跨进程写文件传递，避免 Binder 大小限制）。 */
@Serializable
data class OcrIpcPayload(
    val text: String = "",
    val confidence: Float = 0f,
    val blocks: Int = 0,
    /** v1.3.16：识别失败原因。空文本 + error 非空 = 引擎异常；两者都空 = 图里确实没字。 */
    val error: String? = null
)

data class OcrIpcResult(val payload: OcrIpcPayload?, val error: String?)

/**
 * OCR 跨进程客户端。
 *
 * 为什么要跨进程：ML Kit 的识别是 **native 库**（libmlkit_google_ocr_pipeline），
 * 在某些机型/异常图像上会 native 崩溃，Java 层 try-catch 接不住，进程直接消失。
 * 把 OCR 放到 `:ocr` 独立进程执行，最坏情况只崩这个附属进程，主 App 与自检页面
 * 都活着，并能在 12 秒超时后明确告诉用户「OCR 进程崩溃/无响应」。
 */
@Singleton
class OcrIpcClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 直接识别内存位图：先编码成缓存 JPEG，再走跨进程识别，用完即删。
     * 调用方无需自行 recycle（本方法不动原 Bitmap 的生命周期）。
     */
    suspend fun recognize(
        bitmap: android.graphics.Bitmap,
        timeoutMs: Long = 12_000L
    ): OcrIpcResult {
        val file = File(context.cacheDir, "ocr_in_${System.currentTimeMillis()}.jpg")
        val encoded = runCatching {
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
        }.isSuccess
        if (!encoded) return OcrIpcResult(null, "图片编码失败（内存不足）")
        return try {
            recognize(file.absolutePath, timeoutMs)
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * 在 :ocr 进程识别图片，结果通过临时 JSON 文件回传。
     * @return payload 成功；error 非空表示进程失败/超时（主进程未受影响）
     */
    suspend fun recognize(imagePath: String, timeoutMs: Long = 12_000L): OcrIpcResult =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = File(context.cacheDir, "ocr_out_${System.currentTimeMillis()}.json")
            val intent = Intent(context, OcrWorkerService::class.java).apply {
                putExtra(OcrWorkerService.EXTRA_IMAGE, imagePath)
                putExtra(OcrWorkerService.EXTRA_OUT, out.absolutePath)
            }
            val started = runCatching { context.startService(intent) }.isSuccess
            com.azu.bookkeeper.core.CaptureLog.i(context, "OCR", "启动 :ocr 进程 started=$started")
            if (!started) {
                com.azu.bookkeeper.core.CaptureLog.e(context, "OCR", "无法启动 OCR 进程（服务被 ROM 拦截）")
                return@withContext OcrIpcResult(null, "无法启动 OCR 进程（服务被 ROM 拦截）")
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (out.exists() && out.length() > 0) {
                    val payload = runCatching {
                        json.decodeFromString<OcrIpcPayload>(out.readText())
                    }.getOrNull()
                    out.delete()
                    if (payload != null) {
                        if (payload.error != null) {
                            com.azu.bookkeeper.core.CaptureLog.w(
                                context, "OCR",
                                "识别异常 0 字 blocks=${payload.blocks} 原因=${payload.error}"
                            )
                        } else {
                            com.azu.bookkeeper.core.CaptureLog.i(
                                context, "OCR", "识别完成 ${payload.text.length} 字 conf=${payload.confidence} blocks=${payload.blocks}"
                            )
                        }
                        return@withContext OcrIpcResult(payload, payload.error)
                    }
                }
                delay(120)
            }
            runCatching { out.delete() }
            Timber.w("ocr ipc timeout after %dms", timeoutMs)
            com.azu.bookkeeper.core.CaptureLog.e(context, "OCR", "OCR 进程 ${timeoutMs}ms 无响应（可能 native 崩溃）")
            OcrIpcResult(null, "OCR 进程 ${timeoutMs}ms 内无响应（可能已 native 崩溃）")
        }
}

/**
 * :ocr 进程里的识别服务：读图片 → ML Kit 识别 → 写结果文件 → 自停。
 * 崩了也只崩这个进程。
 */
class OcrWorkerService : Service() {

    private val scope = CoroutineScope(SupervisorJob())

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, OcrWorkerEntryPoint::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val imagePath = intent?.getStringExtra(EXTRA_IMAGE)
        val outPath = intent?.getStringExtra(EXTRA_OUT)
        if (imagePath.isNullOrBlank() || outPath.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            runCatching {
                val bitmap = BitmapFactory.decodeFile(imagePath) ?: error("decode failed: $imagePath")
                try {
                    val result = entryPoint.ocrEngine().recognize(bitmap)
                    val payload = OcrIpcPayload(
                        text = result.rawText,
                        confidence = result.confidence,
                        blocks = result.blockCount,
                        error = result.error
                    )
                    File(outPath).writeText(Json.encodeToString(OcrIpcPayload.serializer(), payload))
                } finally {
                    runCatching { bitmap.recycle() }
                }
            }.onFailure { t ->
                Timber.w(t, "ocr worker failed")
                // v1.3.16：进程内抛异常也要回传原因，否则主进程只能看到「12 秒无响应」
                // 或一个 0 字结果，分不清是崩溃、解码失败还是引擎报错。
                runCatching {
                    val payload = OcrIpcPayload(
                        error = "${t.javaClass.simpleName}: ${t.message ?: "无详细消息"}"
                    )
                    File(outPath).writeText(Json.encodeToString(OcrIpcPayload.serializer(), payload))
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_IMAGE = "extra_image"
        const val EXTRA_OUT = "extra_out"
    }
}

/** :ocr 进程内的惰性依赖（只有 OCR 引擎，不碰数据库/Keystore）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OcrWorkerEntryPoint {
    fun ocrEngine(): OcrEngine
}
