package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.ImportFileKind
import com.azu.bookkeeper.domain.model.ImportReadOptions
import com.azu.bookkeeper.domain.model.ImportTask
import com.azu.bookkeeper.domain.model.ImportTemplate
import com.azu.bookkeeper.domain.model.TablePreview
import kotlinx.coroutines.flow.Flow

/** 文本预览结果：表头 + 前 N 行 + 识别出的字符集。 */
data class FilePreview(
    val header: String,
    val lines: List<String>,
    val charsetName: String
)

interface ImportRepository {

    fun observeTemplates(): Flow<List<ImportTemplate>>

    suspend fun getTemplate(templateId: Long): Result<ImportTemplate?>

    suspend fun getTemplateBySourceKey(sourceKey: String): Result<ImportTemplate?>

    suspend fun saveTemplate(template: ImportTemplate): Result<Long>

    suspend fun deleteTemplate(templateId: Long): Result<Unit>

    /** 首次进入导入向导时写入微信/支付宝/鲨鱼记账等预设映射。 */
    suspend fun ensurePresetTemplates(): Result<Unit>

    fun observeTasks(): Flow<List<ImportTask>>

    suspend fun createTask(filePath: String, templateId: Long, totalRows: Int): Result<Long>

    suspend fun updateTask(task: ImportTask): Result<Unit>

    /** 按 URI 字符串流式读取文本预览（UTF-8 失败自动切换 GBK）。 */
    suspend fun readTextPreview(uriString: String, maxLines: Int): Result<FilePreview>

    suspend fun countLines(uriString: String): Result<Int>

    /** T4：表头 + 前 N 行预览（CSV 逐条读取 / XLSX 行迭代器），字符集可按选项切换。 */
    suspend fun readTable(uriString: String, options: ImportReadOptions): Result<TablePreview>

    /** T4：逐行流式遍历，回调返回 false 可提前结束。 */
    suspend fun streamRows(
        uriString: String,
        options: ImportReadOptions,
        onRow: suspend (Int, List<String>) -> Unit
    ): Result<Int>

    /** T4：按 URI 写出 CSV / XLSX（UTF-8 带表头）。 */
    suspend fun writeTable(
        uriString: String,
        kind: ImportFileKind,
        header: List<String>,
        rows: List<List<String>>
    ): Result<Unit>

    /** T4：把导入报告写到 App 私有目录并返回绝对路径。 */
    suspend fun writeReport(fileName: String, header: List<String>, rows: List<List<String>>): Result<String>
}
