package com.azu.bookkeeper.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.ImportFileKind
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.ImportRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 导出范围。 */
enum class ExportRange(val label: String) {
    TODAY("今日"),
    WEEK("本周"),
    MONTH("本月"),
    YEAR("本年"),
    ALL("全部"),
    CUSTOM("自定义");

    companion object {
        val ALL_VALUES: List<ExportRange> = entries.toList()
    }
}

/** 导出格式：CSV 为 UTF-8 带 BOM，XLSX 用 SXSSF 流式写。 */
enum class ExportFormat(val extension: String, val mimeType: String, val fileKind: ImportFileKind) {
    CSV("csv", "text/csv", ImportFileKind.CSV),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ImportFileKind.XLSX)
}

/** 可勾选的导出字段。 */
enum class ExportField(val key: String, val label: String) {
    TIME("time", "交易时间"),
    TYPE("type", "收支类型"),
    AMOUNT("amount", "金额(元)"),
    CATEGORY("category", "分类"),
    ACCOUNT_FROM("account_from", "账户"),
    ACCOUNT_TO("account_to", "转入账户"),
    REMARK("remark", "备注"),
    TAGS("tags", "标签"),
    SOURCE("source", "来源");

    companion object {
        val ALL_VALUES: List<ExportField> = entries.toList()
    }
}

/** 导出页状态与写入逻辑。 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val billRepository: BillRepository,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val tagRepository: TagRepository,
    private val settings: SettingsRepository,
    private val importRepository: ImportRepository
) : ViewModel() {

    data class UiState(
        val books: List<com.azu.bookkeeper.domain.model.Book> = emptyList(),
        val bookId: Long = 0L,
        val format: ExportFormat = ExportFormat.CSV,
        val range: ExportRange = ExportRange.MONTH,
        val customStart: Long = DateTimeUtils.startOfMonth(DateTimeUtils.nowMillis()),
        val customEnd: Long = DateTimeUtils.endOfMonth(DateTimeUtils.nowMillis()),
        val selectedFields: Set<String> = ExportField.ALL_VALUES.map { it.key }.toSet(),
        val exporting: Boolean = false,
        val message: String? = null
    ) {
        val selectedCount: Int get() = selectedFields.size

        val bounds: Pair<Long, Long>
            get() {
                val now = DateTimeUtils.nowMillis()
                return when (range) {
                    ExportRange.TODAY -> DateTimeUtils.startOfDay(now) to DateTimeUtils.endOfDay(now)
                    ExportRange.WEEK -> DateTimeUtils.startOfWeek(now) to DateTimeUtils.endOfDay(now)
                    ExportRange.MONTH -> DateTimeUtils.startOfMonth(now) to DateTimeUtils.endOfMonth(now)
                    ExportRange.YEAR -> {
                        val year = DateTimeUtils.localDateTime(now).year
                        val start = DateTimeUtils.parse("$year-01-01", "yyyy-MM-dd") ?: DateTimeUtils.startOfMonth(now)
                        val end = DateTimeUtils.parse("$year-12-31", "yyyy-MM-dd") ?: DateTimeUtils.endOfMonth(now)
                        start to DateTimeUtils.endOfDay(end)
                    }
                    ExportRange.ALL -> 0L to Long.MAX_VALUE
                    ExportRange.CUSTOM -> customStart to customEnd
                }
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val books = bookRepository.observeAll().first()
            val bookId = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId
                ?: books.firstOrNull()?.bookId ?: 0L
            _uiState.update { it.copy(books = books, bookId = bookId) }
        }
    }

    fun setBook(bookId: Long) = _uiState.update { it.copy(bookId = bookId) }

    fun setFormat(format: ExportFormat) = _uiState.update { it.copy(format = format) }

    fun setRange(range: ExportRange) = _uiState.update { it.copy(range = range) }

    fun setCustomRange(startMillis: Long, endMillis: Long) =
        _uiState.update { it.copy(range = ExportRange.CUSTOM, customStart = startMillis, customEnd = endMillis) }

    fun toggleField(field: ExportField) = _uiState.update { state ->
        val selected = state.selectedFields
        state.copy(
            selectedFields = if (field.key in selected) selected - field.key else selected + field.key
        )
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /** 用户通过 SAF 选定目标 URI 后写入；不落明文临时文件。 */
    fun exportTo(uriString: String) {
        val state = _uiState.value
        if (state.bookId <= 0L || state.selectedFields.isEmpty()) {
            _uiState.update { it.copy(message = "请选择账本和至少一个导出字段") }
            return
        }
        _uiState.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch {
            val (start, end) = state.bounds
            val bills = billRepository.findByTimeRange(state.bookId, start, end).getOrDefault(emptyList())
            val categoryNames = (
                categoryRepository.observeByBook(state.bookId, CategoryKind.EXPENSE).first() +
                    categoryRepository.observeByBook(state.bookId, CategoryKind.INCOME).first()
                ).associate { it.categoryId to it.name }
            val accountNames = accountRepository.observeAll(state.bookId).first().associate { it.accountId to it.name }
            val tagNames = tagRepository.observeByBook(state.bookId).first().associate { it.tagId to it.name }
            val fields = ExportField.ALL_VALUES.filter { it.key in state.selectedFields }
            val header = fields.map { it.label }
            val rows = bills.map { bill -> fields.map { field -> cellOf(bill, field, categoryNames, accountNames, tagNames) } }

            when (val result = importRepository.writeTable(uriString, state.format.fileKind, header, rows)) {
                is Result.Success -> _uiState.update {
                    it.copy(exporting = false, message = "已导出 ${rows.size} 条账单（${state.format.name}）")
                }
                is Result.Error -> _uiState.update {
                    it.copy(exporting = false, message = result.error.userMessage)
                }
                Result.Loading -> Unit
            }
        }
    }

    private fun cellOf(
        bill: Bill,
        field: ExportField,
        categoryNames: Map<Long, String>,
        accountNames: Map<Long, String>,
        tagNames: Map<Long, String>
    ): String = when (field) {
        ExportField.TIME -> DateTimeUtils.format(bill.billTime, "yyyy-MM-dd HH:mm:ss")
        ExportField.TYPE -> bill.billType.label
        ExportField.AMOUNT -> Money.toYuan(bill.amountFen).toPlainString()
        ExportField.CATEGORY -> categoryNames[bill.categoryId].orEmpty()
        ExportField.ACCOUNT_FROM -> accountNames[bill.accountFrom].orEmpty()
        ExportField.ACCOUNT_TO -> if (bill.billType == BillType.TRANSFER) accountNames[bill.accountTo].orEmpty() else ""
        ExportField.REMARK -> bill.remark
        ExportField.TAGS -> bill.tagIds.mapNotNull { tagNames[it] }.joinToString("|")
        ExportField.SOURCE -> bill.source.label
    }
}
