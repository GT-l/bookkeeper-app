package com.azu.bookkeeper.automation.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ML Kit 离线文字识别（主架构 §5.3）。
 *
 * 纯端侧识别，断网可用；图像来自 EncryptedFile，识别结果只保留文本与置信度。
 * 使用 Tasks.await 而非额外引入 coroutines-play-services：调用方在 [IoDispatcher] 上执行。
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedFiles: EncryptedFileDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** 识别内存中的 Bitmap；失败返回置信度 0 的空结果，由上层触发校对而非崩溃。 */
    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(ioDispatcher) {
        runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = Tasks.await(recognizer.process(image))
            val blocks = text.textBlocks
            val confidence = if (blocks.isEmpty()) {
                0f
            } else {
                blocks.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
            }
            OcrResult(rawText = text.text, confidence = confidence, blockCount = blocks.size)
        }.getOrDefault(OcrResult(rawText = "", confidence = 0f, blockCount = 0))
    }

    /** 读取加密截图后识别；文件不存在或解密失败返回空结果。 */
    suspend fun recognizeFromFile(path: String): OcrResult = withContext(ioDispatcher) {
        val bitmap = encryptedFiles.readCapture(path)
            ?: return@withContext OcrResult("", 0f, 0)
        recognize(bitmap)
    }

    /** 释放底层识别器（可选，进程退出时系统亦会回收）。 */
    fun close() {
        runCatching { recognizer.close() }
    }
}
