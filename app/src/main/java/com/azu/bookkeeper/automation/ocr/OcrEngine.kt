package com.azu.bookkeeper.automation.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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

    /**
     * 中文识别模型（识屏从未成功的根因之一：此前用的是拉丁模型，
     * 中文支付页面整页识别不出，提取器自然拿不到金额/商户）。
     * chinese 模型同时覆盖中文、数字与字母，纯端侧离线运行，首次使用会自动下载模型。
     */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别内存中的 Bitmap；失败返回置信度 0 的空结果，由上层触发校对而非崩溃。 */
    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(ioDispatcher) {
        runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = Tasks.await(recognizer.process(image))
            val blocks = text.textBlocks
            // ML Kit 的 TextBlock 不暴露置信度，取各 Line 的置信度均值（范围 0~1）。
            val lines = blocks.flatMap { block: Text.TextBlock -> block.lines }
            val confidence = if (lines.isEmpty()) {
                0f
            } else {
                lines.map { line: Text.Line -> line.confidence }.average().toFloat().coerceIn(0f, 1f)
            }
            OcrResult(rawText = text.text, confidence = confidence, blockCount = blocks.size)
        }.getOrDefault(OcrResult(rawText = "", confidence = 0f, blockCount = 0))
    }

    /**
     * 读取加密截图后识别；文件不存在或解密失败返回空结果。
     * Bitmap 在识别结束（含异常路径）后立即 recycle，识别过程中产生的大图内存
     * 不留到下一次调用，避免连续识屏时的内存占用堆积。
     */
    suspend fun recognizeFromFile(path: String): OcrResult = withContext(ioDispatcher) {
        val bitmap = encryptedFiles.readCapture(path)
            ?: return@withContext OcrResult("", 0f, 0)
        try {
            recognize(bitmap)
        } finally {
            // Tasks.await 已在 recognize 内同步完成，ML Kit 此时不再持有像素，可安全回收
            runCatching { bitmap.recycle() }
        }
    }

    /** 释放底层识别器（可选，进程退出时系统亦会回收）。 */
    fun close() {
        runCatching { recognizer.close() }
    }
}
