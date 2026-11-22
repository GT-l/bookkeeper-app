package com.azu.bookkeeper.data.local.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.domain.model.ImportFileKind
import com.azu.bookkeeper.domain.model.ImportFileTooLargeException
import com.azu.bookkeeper.domain.model.ImportReadOptions
import com.azu.bookkeeper.domain.model.TablePreview
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * XLSX 读取 / 写入（PRD §8.1、§14）。
 *
 * 写入：SXSSFWorkbook 滑动窗口（100 行），导出上万条不爆内存，是真流式写入。
 * 读取：当前采用 XSSFWorkbook DOM 对象模型 + 行迭代器（不整表复制为字符串矩阵），
 *       **不是 SAX 真流式**。内存上界由 counting() 的 10MB 文件硬上限（E_FILE_001）约束，
 *       因此不会出现无界膨胀；但对 10MB 以内的万行级 .xlsx，DOM 模型仍比 SAX 更吃内存。
 *
 * 已知限制（QA F-12）：真流式读取（XSSFReader + XSSFSheetXMLHandler + SAX）列入二期优化。
 * 本版未落地理由：① 当前依赖为 poi-ooxml-lite，eventusermodel/SAX 需连同共享字符串
 * （ReadOnlySharedStringsTable）、日期样式（StylesTable）、公式缓存值一起重写 cellsOf() 语义；
 * ② 本机无 Android SDK / Gradle，无法对这套 API 做编译与真机验证。
 * 在「个人自用 + 10MB 上限」约束下，维持可静态自洽的 DOM 实现更安全；不谎报已流式化。
 */
@Singleton
class XlsxDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /** 表头 + 前 N 行；totalRows 为数据行数（不含表头）。 */
    suspend fun readPreview(
        uriString: String,
        options: ImportReadOptions
    ): Result<TablePreview> = guarded {
        withContext(ioDispatcher) {
            val uri = Uri.parse(uriString)
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open file: $uriString")
            val header = mutableListOf<String>()
            val previewRows = mutableListOf<List<String>>()
            var dataRows = 0
            raw.use { input ->
                counting(input).use { counted ->
                    val workbook = XSSFWorkbook(OPCPackage.open(counted))
                    try {
                        val sheet = if (workbook.numberOfSheets > 0) workbook.getSheetAt(0) else null
                        val formatter = DataFormatter(Locale.CHINA)
                        val evaluator = workbook.creationHelper.createFormulaEvaluator()
                        val rows = sheet?.rowIterator()
                        var first = true
                        while (rows != null && rows.hasNext()) {
                            val row = rows.next()
                            val cells = cellsOf(row, formatter, evaluator)
                            if (cells.all { it.isBlank() }) continue
                            if (first) {
                                if (options.hasHeader) {
                                    header.addAll(cells)
                                } else {
                                    header.addAll(cells.mapIndexed { index, _ -> "第${index + 1}列" })
                                    previewRows.add(cells)
                                    dataRows++
                                }
                                first = false
                                continue
                            }
                            if (previewRows.size < options.maxPreviewRows.coerceAtLeast(0)) {
                                previewRows.add(cells)
                            }
                            dataRows++
                        }
                        if (header.isEmpty() && previewRows.isNotEmpty()) {
                            header.addAll(previewRows.first().mapIndexed { index, _ -> "第${index + 1}列" })
                        }
                    } finally {
                        workbook.close()
                    }
                }
            }
            TablePreview(
                header = header,
                rows = previewRows,
                totalRows = dataRows,
                charset = "UTF-8",
                kind = ImportFileKind.XLSX,
                fileName = fileNameOf(uriString)
            )
        }
    }

    /** 逐行回调；returns 数据行数。 */
    suspend fun forEachRow(
        uriString: String,
        options: ImportReadOptions,
        onRow: suspend (Int, List<String>) -> Unit
    ): Result<Int> = guarded {
        withContext(ioDispatcher) {
            val uri = Uri.parse(uriString)
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open file: $uriString")
            var dataRows = 0
            raw.use { input ->
                counting(input).use { counted ->
                    val workbook = XSSFWorkbook(OPCPackage.open(counted))
                    try {
                        val sheet = if (workbook.numberOfSheets > 0) workbook.getSheetAt(0) else null
                        val formatter = DataFormatter(Locale.CHINA)
                        val evaluator = workbook.creationHelper.createFormulaEvaluator()
                        val rows = sheet?.rowIterator()
                        var first = true
                        while (rows != null && rows.hasNext()) {
                            val row = rows.next()
                            val cells = cellsOf(row, formatter, evaluator)
                            if (cells.all { it.isBlank() }) continue
                            if (first && options.hasHeader) {
                                first = false
                                continue
                            }
                            first = false
                            dataRows++
                            onRow(dataRows, cells)
                        }
                    } finally {
                        workbook.close()
                    }
                }
            }
            dataRows
        }
    }

    /** 写 XLSX 到用户 URI（SXSSF 滑动窗口 100 行，导出上万条不爆内存）。 */
    suspend fun write(
        uriString: String,
        header: List<String>,
        rows: List<List<String>>
    ): Result<Unit> = guarded {
        withContext(ioDispatcher) {
            val out = context.contentResolver.openOutputStream(Uri.parse(uriString))
                ?: throw IOException("cannot open output: $uriString")
            val workbook = SXSSFWorkbook(100)
            try {
                val sheet = workbook.createSheet("账单")
                val headerRow = sheet.createRow(0)
                header.forEachIndexed { column, value -> headerRow.createCell(column).setCellValue(value) }
                rows.forEachIndexed { index, cells ->
                    val row = sheet.createRow(index + 1)
                    cells.forEachIndexed { column, value -> row.createCell(column).setCellValue(value) }
                }
                out.use { stream -> workbook.write(stream) }
            } finally {
                workbook.dispose()
                workbook.close()
            }
        }
    }

    private fun cellsOf(
        row: Row,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator
    ): List<String> {
        val last = row.lastCellNum.coerceAtLeast(0)
        return (0 until last).map { column ->
            val cell: Cell? = row.getCell(column)
            when {
                cell == null -> ""
                cell.cellType == CellType.FORMULA -> runCatching {
                    formatter.formatCellValue(cell, evaluator)
                }.getOrDefault(formatter.formatCellValue(cell)).trim()
                cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) ->
                    DateTimeUtils.format(cell.dateCellValue.time, "yyyy-MM-dd HH:mm:ss")
                else -> formatter.formatCellValue(cell).trim()
            }
        }
    }

    /** 读取时统计字节数，超过 10MB 立刻中断。 */
    private fun counting(source: InputStream): InputStream = object : InputStream() {
        private var bytesRead = 0L

        private fun count(size: Int) {
            if (size <= 0) return
            bytesRead += size
            if (bytesRead > com.azu.bookkeeper.core.Constants.MAX_IMPORT_FILE_BYTES) {
                throw ImportFileTooLargeException()
            }
        }

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val value = source.read(b, off, len)
            count(value)
            return value
        }

        override fun close() {
            source.close()
        }
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

    /** 超限异常映射为 E_FILE_001，其余异常走全局归类。 */
    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): Result<T> = try {
        Result.Success(block())
    } catch (tooLarge: ImportFileTooLargeException) {
        Result.Error(AppError.File("E_FILE_001", "文件超过 10MB 上限，请拆分后再导入", tooLarge))
    } catch (throwable: Throwable) {
        Result.Error(AppError.from(throwable))
    }

}
