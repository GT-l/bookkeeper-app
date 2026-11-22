package com.azu.bookkeeper.domain.usecase.import

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.ImportFileKind
import com.azu.bookkeeper.domain.model.ImportReadOptions
import com.azu.bookkeeper.domain.model.TablePreview
import com.azu.bookkeeper.domain.repository.ImportRepository
import javax.inject.Inject

/**
 * 步骤 2：文件校验 + 表头 / 前 10 行预览。
 * 字符集切换直接以新的 [ImportReadOptions] 重新调用即可，不缓存整文件文本。
 */
class ParseFileUseCase @Inject constructor(
    private val importRepository: ImportRepository
) {

    suspend operator fun invoke(
        uriString: String,
        options: ImportReadOptions
    ): Result<TablePreview> {
        if (uriString.isBlank()) {
            return Result.Error(
                AppError.Import(
                    code = "E_IMPORT_001",
                    userMessage = "请先选择要导入的账单文件"
                )
            )
        }
        return importRepository.readTable(uriString, options)
    }

    /** 按文件名判断格式；不支持 .pdf / 图片（PRD §8）。 */
    fun kindOf(fileName: String): ImportFileKind? = ImportFileKind.fromFileName(fileName)

    /** 文件名不合法时的统一错误。 */
    fun unsupportedFileError(): AppError = AppError.Import(
        code = "E_IMPORT_001",
        userMessage = "仅支持 .csv / .xlsx 账单文件，暂不支持 PDF 与图片"
    )

    /** 空文件或只有表头：E_IMPORT_002。 */
    fun emptyFileError(): AppError = AppError.Import(
        code = "E_IMPORT_002",
        userMessage = "文件内容为空或没有可导入的数据行"
    )
}
