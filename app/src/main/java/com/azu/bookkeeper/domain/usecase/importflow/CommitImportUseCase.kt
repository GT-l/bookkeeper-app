package com.azu.bookkeeper.domain.usecase.importflow

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.AccountType
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.CycleType
import com.azu.bookkeeper.domain.model.ImportFailure
import com.azu.bookkeeper.domain.model.ImportReport
import com.azu.bookkeeper.domain.model.ImportRow
import com.azu.bookkeeper.domain.model.ImportTask
import com.azu.bookkeeper.domain.model.ImportTaskStatus
import com.azu.bookkeeper.domain.model.PreviewOptions
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.ImportRepository
import com.azu.bookkeeper.domain.repository.TagRepository
import com.azu.bookkeeper.domain.usecase.bill.BulkInsertBillsUseCase
import javax.inject.Inject

/** 步骤 5 提交参数。 */
data class CommitImportInput(
    val rows: List<ImportRow>,
    val failures: List<ImportFailure> = emptyList(),
    val options: PreviewOptions = PreviewOptions(),
    val templateId: Long = 0L,
    val sourceUri: String = "",
    val dedupEnabled: Boolean = true
)

/**
 * 步骤 5：后台批量入账 + 进度 + 导入报告。
 * 所有写入复用 BulkInsertBillsUseCase → BillRepository.bulkInsert（每 500 条一个 @Transaction），
 * 余额由 T2 的 addWithBalanceTx 同族事务入口更新，绝不直接写 DAO。
 */
class CommitImportUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val tagRepository: TagRepository,
    private val bulkInsertBills: BulkInsertBillsUseCase,
    private val importRepository: ImportRepository
) {

    suspend operator fun invoke(
        input: CommitImportInput,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<ImportReport> = safeResult {
        val rows = input.rows.filter { it.isImportable }
        val totalRows = input.rows.size + input.failures.size
        val taskId = importRepository.createTask(input.sourceUri, input.templateId, totalRows).getOrNull() ?: 0L
        importRepository.updateTask(
            ImportTask(
                taskId = taskId,
                templateId = input.templateId,
                filePath = input.sourceUri,
                status = ImportTaskStatus.RUNNING,
                totalRows = totalRows
            )
        )

        categoryRepository.ensureDefaults(input.options.bookId)
        val createdCategories = LinkedHashMap<String, Long>()
        val createdAccounts = LinkedHashMap<String, Long>()
        val categoryKindCache = HashMap<String, CategoryKind>()

        val bills = rows.mapNotNull { row ->
            val categoryId = resolveCategoryId(row, input.options, createdCategories, categoryKindCache)
            val accountFromId = resolveAccountId(row.accountFromName, row.accountFromId, input.options, createdAccounts)
            val accountToId = if (row.billType == BillType.TRANSFER) {
                resolveAccountId(row.accountToName, row.accountToId, input.options, createdAccounts)
            } else {
                0L
            }
            if (categoryId <= 0L || accountFromId <= 0L) return@mapNotNull null
            val tagIds = row.tagNames.mapNotNull { name -> tagRepository.getOrCreate(input.options.bookId, name).getOrNull() }
            Bill(
                bookId = input.options.bookId,
                billType = row.billType,
                amountFen = row.amountFen,
                categoryId = categoryId,
                tagIds = tagIds,
                accountFrom = accountFromId,
                accountTo = accountToId,
                billTime = row.billTime,
                remark = row.remark,
                source = BillSource.IMPORT,
                cycleType = CycleType.NONE,
                createAt = DateTimeUtils.nowMillis(),
                updateAt = DateTimeUtils.nowMillis()
            )
        }

        // 同一批内部的重复项也要拦住（findDuplicate 只能查库里已有数据）。
        val seen = HashSet<String>()
        val candidates = if (input.dedupEnabled) {
            bills.filter { seen.add(it.dedupKey().asString()) }
        } else {
            bills
        }

        val insertResult = bulkInsertBills(candidates, input.dedupEnabled, onProgress)
        val inserted = insertResult.getOrNull() ?: 0
        if (insertResult is Result.Error) {
            importRepository.updateTask(
                ImportTask(
                    taskId = taskId,
                    templateId = input.templateId,
                    filePath = input.sourceUri,
                    status = ImportTaskStatus.FAILED,
                    totalRows = totalRows,
                    failedRows = totalRows
                )
            )
            return@safeResult ImportReport(
                taskId = taskId,
                totalRows = totalRows,
                failedRows = totalRows,
                createdCategories = createdCategories.keys.toList(),
                createdAccounts = createdAccounts.keys.toList()
            )
        }

        val failureRows = buildFailureRows(input.rows, input.failures)
        val reportPath = importRepository.writeReport(
            fileName = "import_report_${taskId}_${DateTimeUtils.nowMillis()}.csv",
            header = listOf("行号", "状态", "原始内容", "说明"),
            rows = failureRows.map {
                listOf(it.rowIndex.toString(), it.reason, it.raw, "请修正源文件后重新导入，或在预览页编辑/跳过")
            }
        ).getOrNull().orEmpty()

        val skippedCount = input.rows.count { it.skipped }
        val failedCount = input.rows.count { it.isFailed } + input.failures.size
        val duplicateCount = input.rows.count { it.duplicate }
        importRepository.updateTask(
            ImportTask(
                taskId = taskId,
                templateId = input.templateId,
                filePath = input.sourceUri,
                status = if (failureRows.isEmpty()) ImportTaskStatus.SUCCESS else ImportTaskStatus.PARTIAL,
                totalRows = totalRows,
                successRows = inserted,
                failedRows = failedCount,
                reportPath = reportPath
            )
        )
        onProgress(inserted, candidates.size)

        ImportReport(
            taskId = taskId,
            totalRows = totalRows,
            successRows = inserted,
            expenseRows = candidates.count { it.billType == BillType.EXPENSE },
            incomeRows = candidates.count { it.billType == BillType.INCOME },
            transferRows = candidates.count { it.billType == BillType.TRANSFER },
            skippedRows = skippedCount,
            failedRows = failedCount,
            duplicateRows = duplicateCount,
            createdCategories = createdCategories.keys.toList(),
            createdAccounts = createdAccounts.keys.toList(),
            reportPath = reportPath
        )
    }

    private suspend fun resolveCategoryId(
        row: ImportRow,
        options: PreviewOptions,
        created: MutableMap<String, Long>,
        kinds: MutableMap<String, CategoryKind>
    ): Long {
        if (row.categoryId > 0L) return row.categoryId
        if (!row.createCategory || row.categoryName.isBlank()) return options.defaultCategoryId
        val name = row.categoryName.trim()
        val kind = if (row.billType == BillType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        if (name in created) return created[name] ?: 0L
        val category = Category(
            bookId = options.bookId,
            kind = kind,
            name = name,
            parentId = 0L,
            sortOrder = 0
        )
        val id = categoryRepository.insert(category).getOrNull() ?: return options.defaultCategoryId
        created[name] = id
        kinds[name] = kind
        return id
    }

    private suspend fun resolveAccountId(
        name: String,
        existingId: Long,
        options: PreviewOptions,
        created: MutableMap<String, Long>
    ): Long {
        if (existingId > 0L) return existingId
        if (name.isBlank()) return options.defaultAccountId
        val trimmed = name.trim()
        if (trimmed in created) return created[trimmed] ?: 0L
        val account = Account(
            bookId = options.bookId,
            name = trimmed,
            type = AccountType.CUSTOM,
            initialBalanceFen = 0L,
            balanceFen = 0L
        )
        val id = accountRepository.insert(account).getOrNull() ?: return options.defaultAccountId
        created[trimmed] = id
        return id
    }

    private fun buildFailureRows(
        rows: List<ImportRow>,
        failures: List<ImportFailure>
    ): List<ImportFailure> {
        val fromRows = rows.filter { it.isFailed || it.skipped || it.duplicate }.map { row ->
            ImportFailure(
                rowIndex = row.lineNo,
                reason = when {
                    row.isFailed -> row.errorMessage.orEmpty()
                    row.skipped -> "用户在预览页跳过"
                    else -> "命中重复账单"
                },
                raw = row.cells.joinToString(" | ")
            )
        }
        return (failures + fromRows).sortedBy { it.rowIndex }
    }
}
