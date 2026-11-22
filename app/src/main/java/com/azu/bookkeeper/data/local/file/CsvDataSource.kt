package com.azu.bookkeeper.data.local.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.domain.model.ImportFileTooLargeException
import com.azu.bookkeeper.domain.model.ImportFileKind
import com.azu.bookkeeper.domain.model.ImportReadOptions
import com.azu.bookkeeper.domain.model.TablePreview
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CSV 流式读取 / 写入（PRD §8.1 步骤 2、§14 导出）。
 * 逐条记录解析，预览只保留前 N 行；超过 10MB 立即中断。
 * 仅解析纯文本，不执行任何脚本或公式。
 */
@Singleton
class CsvDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /** 严格 UTF-8 解码失败时回退 GBK；带 BOM 的 UTF-8 直接识别。 */
    suspend fun detectCharset(uriString: String): String = withContext(ioDispatcher) {
        val uri = Uri.parse(uriString)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open file: $uriString")
        input.use { raw ->
            val buffered = BufferedInputStream(raw, DETECT_BYTES + 1)
            buffered.mark(DETECT_BYTES + 1)
            val bytes = ByteArray(DETECT_BYTES)
            var read = 0
            while (read < DETECT_BYTES) {
                val count = buffered.read(bytes, read, DETECT_BYTES - read)
                if (count <= 0) break
                read += count
            }
            buffered.reset()
            val head = bytes.copyOf(read)
            val bom = head.size >= 3 &&
                (head[0].toInt() and 0xFF) == 0xEF &&
                (head[1].toInt() and 0xFF) == 0xBB &&
                (head[2].toInt() and 0xFF) == 0xBF
            if (bom) return@use "UTF-8"
            val strictUtf8 = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head))
                true
            } catch (_: CharacterCodingException) {
                false
            }
            if (strictUtf8) "UTF-8" else "GBK"
        }
    }

    /** 表头 + 前 N 行预览；其余行只计数。 */
    suspend fun readPreview(
        uriString: String,
        options: ImportReadOptions
    ): Result<TablePreview> = guarded {
        withContext(ioDispatcher) {
            val resolved = resolveCharset(uriString, options.charset)
            val charset = Charset.forName(resolved)
            val uri = Uri.parse(uriString)
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open file: $uriString")
            val header = mutableListOf<String>()
            val previewRows = mutableListOf<List<String>>()
            var dataRows = 0
            LimitedInputStream(raw).use { limited ->
                BufferedReader(InputStreamReader(limited, charset)).use { reader ->
                    // 先用原始首行推断分隔符再解析（QA F-05）：TSV 首行按逗号解析只有 1 列，
                    // 旧逻辑「record.size > 1」永远为 false，制表符文件被当成单列。
                    val delimiter = detectDelimiter(reader)
                    var record = nextRecord(reader, delimiter)?.let(::stripBom)
                    if (options.hasHeader) {
                        header.addAll(record.orEmpty())
                        record = nextRecord(reader, delimiter)
                    } else {
                        header.addAll(record.orEmpty().mapIndexed { index, _ -> "第${index + 1}列" })
                    }
                    while (record != null) {
                        if (previewRows.size < options.maxPreviewRows.coerceAtLeast(0)) {
                            previewRows.add(record)
                        }
                        dataRows++
                        record = nextRecord(reader, delimiter)
                    }
                }
            }
            TablePreview(
                header = header,
                rows = previewRows,
                totalRows = dataRows,
                charset = resolved,
                kind = ImportFileKind.CSV,
                fileName = fileNameOf(uriString)
            )
        }
    }

    /** 逐行回调；returns 数据行数。回调抛出的异常原样上抛。 */
    suspend fun forEachRow(
        uriString: String,
        options: ImportReadOptions,
        onRow: suspend (Int, List<String>) -> Unit
    ): Result<Int> = guarded {
        withContext(ioDispatcher) {
            val charset = Charset.forName(resolveCharset(uriString, options.charset))
            val uri = Uri.parse(uriString)
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open file: $uriString")
            var dataRows = 0
            LimitedInputStream(raw).use { limited ->
                BufferedReader(InputStreamReader(limited, charset)).use { reader ->
                    // 与 readPreview 共用同一分隔符推断（peek 原始首行）与 BOM 剥离逻辑。
                    val delimiter = detectDelimiter(reader)
                    val first = nextRecord(reader, delimiter)?.let(::stripBom)
                    var record = if (options.hasHeader) nextRecord(reader, delimiter) else first
                    while (record != null) {
                        dataRows++
                        onRow(dataRows, record)
                        record = nextRecord(reader, delimiter)
                    }
                }
            }
            dataRows
        }
    }

    /** 写 UTF-8（带 BOM，便于 Excel 直接打开）CSV 到用户选择的 URI。 */
    suspend fun write(
        uriString: String,
        header: List<String>,
        rows: List<List<String>>
    ): Result<Unit> = guarded {
        withContext(ioDispatcher) {
            val out = context.contentResolver.openOutputStream(Uri.parse(uriString))
                ?: throw IOException("cannot open output: $uriString")
            out.use { stream ->
                val writer = BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8))
                writer.write("\uFEFF")
                writer.write(joinRow(header))
                writer.newLine()
                rows.forEach { row ->
                    writer.write(joinRow(row))
                    writer.newLine()
                }
                writer.flush()
            }
        }
    }

    /** 写导入报告到 App 私有 imports 目录，返回绝对路径（FileProvider 可分享）。 */
    suspend fun writePrivateReport(fileName: String, header: List<String>, rows: List<List<String>>): Result<String> =
        guarded {
            withContext(ioDispatcher) {
                val dir = File(context.filesDir, Constants.DIR_IMPORTS).apply { if (!exists()) mkdirs() }
                val target = File(dir, fileName)
                target.outputStream().use { stream: OutputStream ->
                    val writer = BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8))
                    writer.write("\uFEFF")
                    writer.write(joinRow(header))
                    writer.newLine()
                    rows.forEach { row ->
                        writer.write(joinRow(row))
                        writer.newLine()
                    }
                    writer.flush()
                }
                target.absolutePath
            }
        }

    private suspend fun resolveCharset(uriString: String, requested: String): String {
        if (requested.equals("UTF-8", ignoreCase = true)) return "UTF-8"
        if (requested.equals("GBK", ignoreCase = true)) return "GBK"
        return detectCharset(uriString)
    }

    private fun fileNameOf(uriString: String): String {
        val uri = Uri.parse(uriString)
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index).orEmpty() else ""
                    } else {
                        ""
                    }
                }.takeUnless { it.isNullOrBlank() } ?: uri.lastPathSegment.orEmpty()
        } catch (_: SecurityException) {
            uri.lastPathSegment.orEmpty()
        }
    }

    /**
     * 从原始首行推断分隔符（QA F-05）：mark 后 peek 首行文本，比较制表符与逗号数量，
     * 再 reset 回行首，交由 nextRecord 用真实 delimiter 解析。
     * 只看第一行、且忽略引号内的分隔符（数量级比较，个别误差不影响判定）。
     */
    private fun detectDelimiter(reader: BufferedReader): Char {
        reader.mark(PEEK_LIMIT)
        val buffer = CharArray(PEEK_LIMIT)
        val read = reader.read(buffer)
        reader.reset()
        if (read <= 0) return ','
        val firstLine = String(buffer, 0, read).substringBefore('\n').substringBefore('\r')
        val tabs = firstLine.count { it == '\t' }
        val commas = firstLine.count { it == ',' }
        return if (tabs > 0 && tabs > commas) '\t' else ','
    }

    /** 剥离 UTF-8 BOM，避免表头 \uFEFF交易时间 匹配不上「交易时间」（QA F-06）。 */
    private fun stripBom(record: List<String>): List<String> =
        if (record.isEmpty()) {
            record
        } else {
            record.toMutableList().also { it[0] = it[0].removePrefix("\uFEFF") }
        }

    /** RFC4180 风格记录解析：支持引号包裹的逗号 / 换行 / 双引号转义。 */
    private fun nextRecord(reader: BufferedReader, delimiter: Char): List<String>? {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var consumed = false
        var c = reader.read()
        if (c == -1) return null
        while (c != -1) {
            val ch = c.toChar()
            if (inQuotes) {
                when (ch) {
                    '"' -> {
                        val next = reader.read()
                        if (next == '"'.code) {
                            field.append('"')
                            c = reader.read()
                        } else {
                            inQuotes = false
                            c = next
                        }
                    }
                    else -> {
                        field.append(ch)
                        c = reader.read()
                    }
                }
            } else {
                when (ch) {
                    '"' -> {
                        inQuotes = true
                        consumed = true
                        c = reader.read()
                    }
                    delimiter -> {
                        fields.add(field.toString().trim())
                        field.setLength(0)
                        consumed = true
                        c = reader.read()
                    }
                    '\r' -> {
                        c = reader.read()
                        if (c == '\n'.code) c = reader.read()
                        fields.add(field.toString().trim())
                        return fields.takeIf { consumed || field.isNotEmpty() }
                    }
                    '\n' -> {
                        c = reader.read()
                        fields.add(field.toString().trim())
                        return fields.takeIf { consumed || field.isNotEmpty() }
                    }
                    else -> {
                        field.append(ch)
                        consumed = true
                        c = reader.read()
                    }
                }
            }
        }
        if (consumed || field.isNotEmpty()) {
            fields.add(field.toString().trim())
            return fields
        }
        return null
    }

    private fun joinRow(cells: List<String>): String = cells.joinToString(",") { cell ->
        val safe = cell.replace("\"", "\"\"")
        if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$safe\"" else safe
    }

    /** 读取字节数硬上限，超限立刻抛错，避免大文件把内存打满。 */
    private class LimitedInputStream(source: InputStream) : InputStream() {
        private val delegate = BufferedInputStream(source, BUFFER_SIZE)
        private var bytesRead = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = delegate.read(b, off, len)
            if (count > 0) count(count)
            return count
        }

        private fun count(size: Int) {
            bytesRead += size
            if (bytesRead > Constants.MAX_IMPORT_FILE_BYTES) throw ImportFileTooLargeException()
        }

        override fun close() {
            delegate.close()
        }

        private companion object {
            const val BUFFER_SIZE = 16 * 1024
        }
    }


    /** 超限异常映射为 E_FILE_001，其余异常走全局归类。 */
    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): Result<T> = try {
        Result.Success(block())
    } catch (tooLarge: ImportFileTooLargeException) {
        Result.Error(AppError.File("E_FILE_001", "文件超过 10MB 上限，请拆分后再导入", tooLarge))
    } catch (throwable: Throwable) {
        Result.Error(AppError.from(throwable))
    }

    private companion object {
        const val DETECT_BYTES = 64 * 1024
        /** 分隔符推断时 peek 的最大字符数（一行表头通常远小于此）。 */
        const val PEEK_LIMIT = 8 * 1024
    }
}
