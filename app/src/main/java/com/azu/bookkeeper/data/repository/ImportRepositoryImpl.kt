package com.azu.bookkeeper.data.repository

import android.content.Context
import android.net.Uri
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.dao.ImportDao
import com.azu.bookkeeper.data.local.file.CsvDataSource
import com.azu.bookkeeper.data.local.file.XlsxDataSource
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.ImportFileKind
import com.azu.bookkeeper.domain.model.ImportFlags
import com.azu.bookkeeper.domain.model.ImportPresets
import com.azu.bookkeeper.domain.model.ImportReadOptions
import com.azu.bookkeeper.domain.model.ImportTask
import com.azu.bookkeeper.domain.model.ImportTemplate
import com.azu.bookkeeper.domain.model.TablePreview
import com.azu.bookkeeper.domain.repository.FilePreview
import com.azu.bookkeeper.domain.repository.ImportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importDao: ImportDao,
    private val csvDataSource: CsvDataSource,
    private val xlsxDataSource: XlsxDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ImportRepository {

    override fun observeTemplates(): Flow<List<ImportTemplate>> =
        importDao.observeTemplates().map { list -> list.map { it.toDomain() } }

    override suspend fun getTemplate(templateId: Long): Result<ImportTemplate?> = safeResult {
        withContext(ioDispatcher) { importDao.findTemplateById(templateId)?.toDomain() }
    }

    override suspend fun getTemplateBySourceKey(sourceKey: String): Result<ImportTemplate?> =
        safeResult {
            withContext(ioDispatcher) { importDao.findTemplateBySourceKey(sourceKey)?.toDomain() }
        }

    override suspend fun saveTemplate(template: ImportTemplate): Result<Long> = safeResult {
        withContext(ioDispatcher) { importDao.insertTemplate(template.toEntity()) }
    }

    override suspend fun deleteTemplate(templateId: Long): Result<Unit> = safeResult {
        withContext(ioDispatcher) { importDao.deleteTemplateById(templateId) }
    }

    /**
     * 幂等播种 6 个预设（微信 / 支付宝 / 鲨鱼 / 随手记 / 招行 / 工行）。
     * ID 稳定：按 sourceKey 判断是否已存在，老用户升级后也能补齐新增预设。
     */
    override suspend fun ensurePresetTemplates(): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val existingKeys = importDao.getAllTemplates().map { it.sourceKey }.toSet()
            val missing = ImportPresets.ALL
                .filter { it.sourceKey !in existingKeys }
                .map { preset ->
                    ImportTemplate(
                        name = preset.name,
                        sourceKey = preset.sourceKey,
                        mapping = preset.mapping,
                        flags = preset.flags
                    ).toEntity()
                }
            if (missing.isNotEmpty()) importDao.insertTemplates(missing)
        }
    }

    override fun observeTasks(): Flow<List<ImportTask>> =
        importDao.observeTasks().map { list -> list.map { it.toDomain() } }

    override suspend fun createTask(
        filePath: String,
        templateId: Long,
        totalRows: Int
    ): Result<Long> = safeResult {
        withContext(ioDispatcher) {
            importDao.insertTask(
                ImportTask(
                    filePath = filePath,
                    templateId = templateId,
                    totalRows = totalRows
                ).toEntity()
            )
        }
    }

    override suspend fun updateTask(task: ImportTask): Result<Unit> = safeResult {
        withContext(ioDispatcher) { importDao.updateTask(task.toEntity()) }
    }

    override suspend fun readTextPreview(uriString: String, maxLines: Int): Result<FilePreview> =
        safeResult {
            withContext(ioDispatcher) {
                val uri = Uri.parse(uriString)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: throw IllegalArgumentException("cannot open file: $uriString")
                if (bytes.size > Constants.MAX_IMPORT_FILE_BYTES) {
                    throw IllegalArgumentException("file exceeds 10MB limit")
                }
                val (text, charsetName) = decode(bytes)
                val allLines = text.split(Regex("\\r?\\n"))
                val header = allLines.firstOrNull().orEmpty()
                val preview = allLines.drop(1).take(maxLines.coerceAtLeast(0))
                FilePreview(header = header, lines = preview, charsetName = charsetName)
            }
        }

    override suspend fun countLines(uriString: String): Result<Int> = safeResult {
        withContext(ioDispatcher) {
            val uri = Uri.parse(uriString)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("cannot open file: $uriString")
            input.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    var count = 0
                    while (reader.readLine() != null) count++
                    count
                }
            }
        }
    }

    override suspend fun readTable(
        uriString: String,
        options: ImportReadOptions
    ): Result<TablePreview> = when (options.kind) {
        ImportFileKind.CSV -> csvDataSource.readPreview(uriString, options)
        ImportFileKind.XLSX -> xlsxDataSource.readPreview(uriString, options)
    }

    override suspend fun streamRows(
        uriString: String,
        options: ImportReadOptions,
        onRow: suspend (Int, List<String>) -> Unit
    ): Result<Int> = when (options.kind) {
        ImportFileKind.CSV -> csvDataSource.forEachRow(uriString, options, onRow)
        ImportFileKind.XLSX -> xlsxDataSource.forEachRow(uriString, options, onRow)
    }

    override suspend fun writeTable(
        uriString: String,
        kind: ImportFileKind,
        header: List<String>,
        rows: List<List<String>>
    ): Result<Unit> = when (kind) {
        ImportFileKind.CSV -> csvDataSource.write(uriString, header, rows)
        ImportFileKind.XLSX -> xlsxDataSource.write(uriString, header, rows)
    }

    override suspend fun writeReport(
        fileName: String,
        header: List<String>,
        rows: List<List<String>>
    ): Result<String> = csvDataSource.writePrivateReport(fileName, header, rows)

    /** 先按严格 UTF-8 解码，失败则回退 GBK（PRD §8.4 乱码处理）。 */
    private fun decode(bytes: ByteArray): Pair<String, String> = try {
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        decoded to "UTF-8"
    } catch (_: CharacterCodingException) {
        val gbk: Charset = Charset.forName("GBK")
        String(bytes, gbk) to "GBK"
    }
}
