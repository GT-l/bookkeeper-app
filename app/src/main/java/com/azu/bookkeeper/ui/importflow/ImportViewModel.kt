package com.azu.bookkeeper.ui.importflow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.FieldMapping
import com.azu.bookkeeper.domain.model.ImportField
import com.azu.bookkeeper.domain.model.ImportFileKind
import com.azu.bookkeeper.domain.model.ImportFlags
import com.azu.bookkeeper.domain.model.ImportPreviewSummary
import com.azu.bookkeeper.domain.model.ImportPresets
import com.azu.bookkeeper.domain.model.ImportReadOptions
import com.azu.bookkeeper.domain.model.ImportReport
import com.azu.bookkeeper.domain.model.ImportRow
import com.azu.bookkeeper.domain.model.ImportRowEdit
import com.azu.bookkeeper.domain.model.ImportTemplate
import com.azu.bookkeeper.domain.model.PreviewOptions
import com.azu.bookkeeper.domain.model.TablePreview
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.ImportRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.importflow.CommitImportInput
import com.azu.bookkeeper.domain.usecase.importflow.CommitImportUseCase
import com.azu.bookkeeper.domain.usecase.importflow.MapFieldsUseCase
import com.azu.bookkeeper.domain.usecase.importflow.ParseFileUseCase
import com.azu.bookkeeper.domain.usecase.importflow.PreviewImportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 5 步导入向导的唯一状态源。 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: ImportRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository,
    private val parseFile: ParseFileUseCase,
    private val mapFields: MapFieldsUseCase,
    private val previewImport: PreviewImportUseCase,
    private val commitImport: CommitImportUseCase
) : ViewModel() {

    enum class Step(val label: String) {
        SOURCE("选择来源"),
        UPLOAD("上传文件"),
        MAPPING("字段映射"),
        PREVIEW("预览校对"),
        CONFIRM("确认导入");

        companion object {
            val ALL: List<Step> = entries.toList()
        }
    }

    enum class RowFilter(val label: String) {
        ALL("全部"),
        FAILED("失败"),
        DUPLICATE("重复"),
        SKIPPED("已跳过"),
        VALID("有效");

        companion object {
            val ALL_VALUES: List<RowFilter> = entries.toList()
        }
    }

    data class UiState(
        val step: Step = Step.SOURCE,
        val bookId: Long = 0L,
        val templates: List<ImportTemplate> = emptyList(),
        val selectedTemplateId: Long = 0L,
        val selectedSourceKey: String = ImportPresets.CUSTOM_KEY,
        val sourceName: String = "自定义文件",
        val fileUri: String = "",
        val fileName: String = "",
        val fileKind: ImportFileKind = ImportFileKind.CSV,
        val charset: String = "AUTO",
        val detectedCharset: String = "",
        val preview: TablePreview? = null,
        val mapping: FieldMapping = FieldMapping(emptyMap()),
        val signRule: Boolean = false,
        val recognizeTransfer: Boolean = false,
        val autoCreateMissing: Boolean = true,
        val dedupEnabled: Boolean = true,
        val rows: List<ImportRow> = emptyList(),
        val summary: ImportPreviewSummary = ImportPreviewSummary(),
        val rowFilter: RowFilter = RowFilter.ALL,
        val selectedRowIndices: Set<Int> = emptySet(),
        val categories: List<Category> = emptyList(),
        val accounts: List<Account> = emptyList(),
        val defaultCategoryId: Long = 0L,
        val defaultAccountId: Long = 0L,
        val templateNameInput: String = "",
        val importing: Boolean = false,
        val progress: Int = 0,
        val report: ImportReport? = null,
        val message: String? = null,
        val error: AppError? = null
    ) {
        val stepIndex: Int get() = step.ordinal

        val filteredRows: List<ImportRow>
            get() = when (rowFilter) {
                RowFilter.ALL -> rows
                RowFilter.FAILED -> rows.filter { it.isFailed }
                RowFilter.DUPLICATE -> rows.filter { it.duplicate }
                RowFilter.SKIPPED -> rows.filter { it.skipped }
                RowFilter.VALID -> rows.filter { it.isImportable }
            }

        val canNext: Boolean
            get() = when (step) {
                Step.SOURCE -> selectedSourceKey.isNotBlank()
                Step.UPLOAD -> preview != null && (preview?.totalRows ?: 0) > 0
                Step.MAPPING -> mapping.columnFor(ImportField.BILL_TIME).isNotBlank() &&
                    mapping.columnFor(ImportField.AMOUNT).isNotBlank()
                Step.PREVIEW -> summary.failedRows == 0 && summary.validRows > 0
                Step.CONFIRM -> report != null
            }

        val options: PreviewOptions
            get() = PreviewOptions(
                bookId = bookId,
                signRule = signRule,
                recognizeTransfer = recognizeTransfer,
                autoCreateMissing = autoCreateMissing,
                dedupEnabled = dedupEnabled,
                defaultCategoryId = defaultCategoryId,
                defaultAccountId = defaultAccountId
            )
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = importRepository.ensurePresetTemplates()) {
                is Result.Error -> _uiState.update { it.copy(error = result.error) }
                else -> Unit
            }
            importRepository.observeTemplates().collect { templates ->
                _uiState.update { it.copy(templates = templates) }
            }
        }
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId ?: 0L
            categoryRepository.ensureDefaults(bookId)
            val categories = categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first() +
                categoryRepository.observeByBook(bookId, CategoryKind.INCOME).first()
            val accounts = accountRepository.observeAll(bookId).first()
            val defaultExpense = categories.firstOrNull { it.kind == CategoryKind.EXPENSE && it.name == "其他支出" }
                ?: categories.firstOrNull { it.kind == CategoryKind.EXPENSE }
            _uiState.update { state ->
                state.copy(
                    bookId = bookId,
                    categories = categories,
                    accounts = accounts,
                    defaultCategoryId = defaultExpense?.categoryId ?: state.defaultCategoryId,
                    defaultAccountId = accounts.firstOrNull()?.accountId ?: state.defaultAccountId
                )
            }
        }
    }

    // ---------- 步骤 1：来源 / 模板 ----------

    fun selectTemplate(template: ImportTemplate) {
        _uiState.update { state ->
            state.copy(
                selectedTemplateId = template.templateId,
                selectedSourceKey = template.sourceKey,
                sourceName = template.name,
                signRule = ImportFlags.has(template.flags, ImportFlags.SIGN_RULE),
                recognizeTransfer = ImportFlags.has(template.flags, ImportFlags.TRANSFER_SUPPORT),
                dedupEnabled = ImportFlags.has(template.flags, ImportFlags.AUTO_DEDUP),
                mapping = state.preview?.header?.let { mapFields(it, template) } ?: FieldMapping(
                    template.mapping,
                    template.sourceKey
                )
            )
        }
    }

    fun selectCustom() {
        _uiState.update { state ->
            state.copy(
                selectedTemplateId = 0L,
                selectedSourceKey = ImportPresets.CUSTOM_KEY,
                sourceName = "自定义文件",
                mapping = state.preview?.header?.let { mapFields(it, null) } ?: FieldMapping(),
                signRule = false,
                recognizeTransfer = false,
                dedupEnabled = true
            )
        }
    }

    fun deleteTemplate(templateId: Long) {
        viewModelScope.launch {
            when (val result = importRepository.deleteTemplate(templateId)) {
                is Result.Error -> _uiState.update { it.copy(error = result.error) }
                else -> {
                    if (_uiState.value.selectedTemplateId == templateId) selectCustom()
                    _uiState.update { it.copy(message = "模板已删除") }
                }
            }
        }
    }

    // ---------- 步骤 2：文件 / 字符集 ----------

    fun onFilePicked(uriString: String) {
        val name = displayName(uriString)
        val kind = parseFile.kindOf(name)
        if (kind == null) {
            _uiState.update { it.copy(error = parseFile.unsupportedFileError()) }
            return
        }
        _uiState.update {
            it.copy(
                fileUri = uriString,
                fileName = name,
                fileKind = kind,
                error = null,
                message = null,
                preview = null,
                rows = emptyList(),
                report = null
            )
        }
        reloadPreview()
    }

    fun setCharset(charset: String) {
        _uiState.update { it.copy(charset = charset) }
        reloadPreview()
    }

    private fun reloadPreview() {
        val state = _uiState.value
        if (state.fileUri.isBlank()) return
        viewModelScope.launch {
            val options = ImportReadOptions(
                kind = state.fileKind,
                charset = state.charset,
                hasHeader = true,
                maxPreviewRows = PREVIEW_ROWS
            )
            when (val result = parseFile(state.fileUri, options)) {
                is Result.Success -> {
                    val preview = result.data
                    if (preview.totalRows <= 0) {
                        _uiState.update {
                            it.copy(preview = preview, error = parseFile.emptyFileError())
                        }
                        return@launch
                    }
                    val selectedTemplate = state.templates.firstOrNull { it.templateId == state.selectedTemplateId }
                    val mapping = mapFields(preview.header, selectedTemplate)
                    _uiState.update {
                        it.copy(
                            preview = preview,
                            detectedCharset = preview.charset,
                            mapping = mapping,
                            error = null,
                            message = "识别为 ${preview.charset}，共 ${preview.totalRows} 行数据"
                        )
                    }
                }
                is Result.Error -> _uiState.update { it.copy(error = result.error) }
                Result.Loading -> Unit
            }
        }
    }

    // ---------- 步骤 3：字段映射 / 模板保存 ----------

    fun setMapping(fieldKey: String, column: String) {
        _uiState.update { state ->
            val values = state.mapping.values + (fieldKey to column)
            state.copy(mapping = state.mapping.copy(values = values))
        }
    }

    fun setSignRule(enabled: Boolean) = _uiState.update { it.copy(signRule = enabled) }

    fun setRecognizeTransfer(enabled: Boolean) = _uiState.update { it.copy(recognizeTransfer = enabled) }

    fun setAutoCreateMissing(enabled: Boolean) = _uiState.update { it.copy(autoCreateMissing = enabled) }

    fun setDedup(enabled: Boolean) = _uiState.update { it.copy(dedupEnabled = enabled) }

    fun setTemplateName(name: String) = _uiState.update { it.copy(templateNameInput = name) }

    fun saveTemplate() {
        val state = _uiState.value
        val name = state.templateNameInput.trim().ifBlank { "自定义模板_${DateTimeUtils.format(System.currentTimeMillis(), "MMdd_HHmm")}" }
        val sourceKey = "custom_${System.currentTimeMillis()}"
        var flags = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP
        if (state.signRule) flags = flags or ImportFlags.SIGN_RULE
        if (state.recognizeTransfer) flags = flags or ImportFlags.TRANSFER_SUPPORT
        val template = ImportTemplate(
            name = name,
            sourceKey = sourceKey,
            mapping = state.mapping.values,
            flags = flags
        )
        viewModelScope.launch {
            when (val result = importRepository.saveTemplate(template)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            selectedTemplateId = result.data,
                            selectedSourceKey = sourceKey,
                            sourceName = name,
                            templateNameInput = "",
                            message = "模板「$name」已保存"
                        )
                    }
                }
                is Result.Error -> _uiState.update { it.copy(error = result.error) }
                Result.Loading -> Unit
            }
        }
    }

    // ---------- 步骤 4：预览校对 ----------

    private fun buildRows() {
        val state = _uiState.value
        val preview = state.preview ?: return
        viewModelScope.launch {
            val cells = ArrayList<List<String>>()
            val readOptions = ImportReadOptions(
                kind = state.fileKind,
                charset = state.charset,
                hasHeader = true,
                maxPreviewRows = 0
            )
            when (val streamed = importRepository.streamRows(state.fileUri, readOptions) { _, row -> cells.add(row) }) {
                is Result.Error -> {
                    _uiState.update { it.copy(error = streamed.error) }
                    return@launch
                }
                else -> Unit
            }
            when (val result = previewImport(cells, preview.header, state.mapping, state.options)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        rows = result.data.rows,
                        summary = result.data.summary,
                        message = if (cells.size > LARGE_IMPORT_HINT) "共 ${cells.size} 行，将后台分批导入" else null
                    )
                }
                is Result.Error -> _uiState.update { it.copy(error = result.error) }
                Result.Loading -> Unit
            }
        }
    }

    fun updateRow(lineNo: Int, edit: ImportRowEdit) {
        _uiState.update { state ->
            val updated = state.rows.map { row ->
                if (row.lineNo != lineNo) return@map row
                val error = when {
                    edit.billTime <= 0L -> "时间无法识别"
                    edit.amountFen <= 0L -> "金额为空或非数字"
                    else -> null
                }
                row.copy(
                    billTime = edit.billTime,
                    amountFen = edit.amountFen,
                    billType = edit.billType,
                    categoryId = edit.categoryId,
                    categoryName = edit.categoryName,
                    accountFromId = edit.accountFromId,
                    accountFromName = edit.accountFromName,
                    remark = edit.remark,
                    errorMessage = error,
                    duplicate = if (error == null) false else row.duplicate,
                    edited = true
                )
            }
            state.copy(rows = updated, summary = summarize(updated))
        }
    }

    /** UI 编辑框输入的文本解析，失败返回 null 由调用方提示。 */
    fun parseEditedTime(text: String): Long? = DateTimeUtils.parseFlexible(text)

    fun parseEditedAmount(text: String): Long? = Money.parse(text)?.let { if (it < 0L) -it else it }

    fun setRowFilter(filter: RowFilter) = _uiState.update { it.copy(rowFilter = filter) }

    fun toggleRowSelection(lineNo: Int) = _uiState.update { state ->
        val selected = state.selectedRowIndices
        state.copy(
            selectedRowIndices = if (lineNo in selected) selected - lineNo else selected + lineNo
        )
    }

    fun selectAllVisible(checked: Boolean) = _uiState.update { state ->
        val visible = state.filteredRows.map { it.lineNo }.toSet()
        state.copy(
            selectedRowIndices = if (checked) state.selectedRowIndices + visible else state.selectedRowIndices - visible
        )
    }

    fun batchSetCategory(categoryId: Long, categoryName: String) = _uiState.update { state ->
        state.copy(
            rows = state.rows.map { row ->
                if (row.lineNo !in state.selectedRowIndices) row
                else row.copy(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    createCategory = false,
                    edited = true
                )
            }
        )
    }

    fun batchSetAccount(accountId: Long, accountName: String) = _uiState.update { state ->
        state.copy(
            rows = state.rows.map { row ->
                if (row.lineNo !in state.selectedRowIndices) row
                else row.copy(
                    accountFromId = accountId,
                    accountFromName = accountName,
                    createAccount = false,
                    edited = true
                )
            }
        )
    }

    fun batchSkip(skipped: Boolean) = _uiState.update { state ->
        val updated = state.rows.map { row ->
            if (row.lineNo !in state.selectedRowIndices) row else row.copy(skipped = skipped)
        }
        state.copy(rows = updated, summary = summarize(updated), selectedRowIndices = emptySet())
    }

    fun toggleSkip(lineNo: Int) = _uiState.update { state ->
        val updated = state.rows.map { row ->
            if (row.lineNo != lineNo) row else row.copy(skipped = !row.skipped)
        }
        state.copy(rows = updated, summary = summarize(updated))
    }

    fun toggleDuplicate(lineNo: Int) = _uiState.update { state ->
        val updated = state.rows.map { row ->
            if (row.lineNo != lineNo) row else row.copy(duplicate = !row.duplicate)
        }
        state.copy(rows = updated, summary = summarize(updated))
    }

    // ---------- 步骤 5：后台导入与报告 ----------

    fun confirmImport() {
        val state = _uiState.value
        if (state.importing) return
        _uiState.update { it.copy(importing = true, progress = 0, error = null, report = null) }
        val input = CommitImportInput(
            rows = state.rows,
            options = state.options,
            templateId = state.selectedTemplateId,
            sourceUri = state.fileUri,
            dedupEnabled = state.dedupEnabled
        )
        viewModelScope.launch {
            when (val result = commitImport(input) { done, total ->
                val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 100
                _uiState.update { it.copy(progress = percent) }
            }) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        importing = false,
                        progress = 100,
                        report = result.data,
                        message = "成功导入 ${result.data.successRows} 条账单"
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(importing = false, error = result.error)
                }
                Result.Loading -> Unit
            }
        }
    }

    fun resetWizard() {
        _uiState.value = UiState(
            templates = _uiState.value.templates,
            bookId = _uiState.value.bookId,
            categories = _uiState.value.categories,
            accounts = _uiState.value.accounts,
            defaultCategoryId = _uiState.value.defaultCategoryId,
            defaultAccountId = _uiState.value.defaultAccountId
        )
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null) }

    // ---------- 导航 ----------

    fun goNext() {
        val state = _uiState.value
        if (!state.canNext) return
        val next = Step.ALL.getOrNull(state.stepIndex + 1) ?: return
        _uiState.update { it.copy(step = next, error = null, message = null) }
        if (next == Step.PREVIEW) buildRows()
    }

    fun goBack() {
        val state = _uiState.value
        if (state.stepIndex <= 0) return
        val previous = Step.ALL[state.stepIndex - 1]
        _uiState.update { it.copy(step = previous, error = null, message = null) }
    }

    private fun summarize(rows: List<ImportRow>): ImportPreviewSummary = ImportPreviewSummary(
        totalRows = rows.size,
        validRows = rows.count { it.isImportable },
        failedRows = rows.count { it.isFailed && !it.skipped },
        skippedRows = rows.count { it.skipped },
        duplicateRows = rows.count { it.duplicate },
        balanceDeltaFen = rows.filter { it.isImportable && it.billType != BillType.TRANSFER }
            .sumOf { if (it.billType == BillType.INCOME) it.amountFen else -it.amountFen }
    )

    private fun displayName(uriString: String): String {
        val uri = Uri.parse(uriString)
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index).orEmpty() else ""
                } else {
                    ""
                }
            }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        } catch (_: SecurityException) {
            uri.lastPathSegment.orEmpty()
        }
    }

    private companion object {
        const val PREVIEW_ROWS = 10
        const val LARGE_IMPORT_HINT = 5000
    }
}
