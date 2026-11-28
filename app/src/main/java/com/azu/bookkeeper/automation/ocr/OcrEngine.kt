package com.azu.bookkeeper.automation.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.azu.bookkeeper.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ML Kit 离线文字识别（主架构 §5.3）。
 *
 * 关键点：
 * 1. 用**中文模型**（默认 latin 模型识别不了中文支付页，是识屏一直失败的根因之一）；
 * 2. 不再依赖 EncryptedFileDataSource——之前 OcrEngine 构造要拉起 Keystore，
 *    在前台服务里一旦构造失败，整个「识屏组件」就取不到（用户看到的「识屏组件失败」）；
 * 3. 文件读取兼容历史加密截图：先尝试 fromFilePath，失败再直接解 JPEG；
 * 4. 所有异常按 Throwable 兜底（含 OOM），识别失败返回空结果而不是崩进程。
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别内存中的 Bitmap；失败返回置信度 0 的空结果，由上层触发校对而非崩溃。 */
    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(ioDispatcher) {
        runCatching {
            process(InputImage.fromBitmap(bitmap, 0))
        }.getOrElse { e ->
            // v1.3.16：异常不能再被吞成「0 字」——那和「页面真的没有文字」在日志里长得一模一样，
            // 用户看到的只有「识别到文字但没找到扣款金额」，真正原因（模型/引擎报错）被抹掉。
            Timber.w(e, "ocr bitmap failed")
            OcrResult(
                rawText = "",
                confidence = 0f,
                blockCount = 0,
                error = "${e.javaClass.simpleName}: ${e.message ?: "无详细消息"}"
            )
        }
    }

    /**
     * 读取截图文件后识别（兼容旧管线：加密截图 / 普通 JPEG 均可）。
     * 文件不存在或解密失败返回空结果，不抛异常。
     */
    suspend fun recognizeFromFile(path: String): OcrResult = withContext(ioDispatcher) {
        val byUri = runCatching {
            process(InputImage.fromFilePath(context, Uri.fromFile(File(path))))
        }.getOrNull()
        if (byUri != null && byUri.rawText.isNotBlank()) return@withContext byUri
        // 老版本是加密落盘的截图，fromFilePath 读不出 → 直接解码失败也没关系，返回空
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            ?: return@withContext byUri ?: OcrResult("", 0f, 0)
        try {
            recognize(bitmap)
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun process(image: InputImage): OcrResult {
        val text = Tasks.await(recognizer.process(image))
        val blocks = text.textBlocks
        // ML Kit 的 TextBlock 不暴露置信度，取各 Line 的置信度均值（范围 0~1）。
        val lines = blocks.flatMap { block: Text.TextBlock -> block.lines }
        val confidence = if (lines.isEmpty()) {
            0f
        } else {
            lines.map { line: Text.Line -> line.confidence }.average().toFloat().coerceIn(0f, 1f)
        }
        return OcrResult(rawText = text.text, confidence = confidence, blockCount = blocks.size)
    }

    /** 释放底层识别器（可选，进程退出时系统亦会回收）。 */
    fun close() {
        runCatching { recognizer.close() }
    }
}
