package com.azu.bookkeeper.domain.usecase.auto

import android.graphics.Bitmap
import com.azu.bookkeeper.automation.ocr.FieldExtractor
import com.azu.bookkeeper.automation.ocr.OcrEngine
import com.azu.bookkeeper.automation.ocr.OcrResult
import javax.inject.Inject

/** 字段抽取用例：图像走 OCR，文本源直读正则（领域层门面，纯委托）。 */
class ExtractFieldsUseCase @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val extractor: FieldExtractor
) {
    suspend operator fun invoke(bitmap: Bitmap): Pair<OcrResult, com.azu.bookkeeper.automation.ocr.ExtractedFields> {
        val result = ocrEngine.recognize(bitmap)
        return result to extractor.extract(result.rawText)
    }

    operator fun invoke(text: String, fallbackTime: Long? = null): com.azu.bookkeeper.automation.ocr.ExtractedFields =
        extractor.extract(text, fallbackTime)
}
