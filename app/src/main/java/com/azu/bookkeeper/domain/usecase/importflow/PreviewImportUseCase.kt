package com.azu.bookkeeper.domain.usecase.importflow

import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.normalizeRemark
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.DedupKey
import com.azu.bookkeeper.domain.model.FieldMapping
import com.azu.bookkeeper.domain.model.ImportField
import com.azu.bookkeeper.domain.model.ImportPreviewResult
import com.azu.bookkeeper.domain.model.ImportPreviewSummary
import com.azu.bookkeeper.domain.model.ImportRow
import com.azu.bookkeeper.domain.model.PreviewOptions
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.math.abs

/**
 * 步骤 4：逐行清洗 + 分类 / 账户模糊匹配 + 去重标记 + 顶部摘要。
 * 失败行只标红不静默丢弃，必须经用户编辑或跳过才能进入步骤 5。
 */
class PreviewImportUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val billRepository: BillRepository
) {

    suspend operator fun invoke(
        rows: List<List<String>>,
        header: List<String>,
        mapping: FieldMapping,
        options: PreviewOptions
    ): Result<ImportPreviewResult> = safeResult {
        val categories = (
            categoryRepository.observeByBook(options.bookId, CategoryKind.EXPENSE).first() +
                categoryRepository.observeByBook(options.bookId, CategoryKind.INCOME).first()
            )
        val accounts = accountRepository.observeAll(options.bookId).first()
        val seenKeys = HashSet<String>()
        val previewRows = rows.mapIndexed { index, cells ->
            buildRow(index + 1, cells, header, mapping, options, categories, accounts, seenKeys)
        }
        ImportPreviewResult(previewRows, buildSummary(previewRows))
    }

    private suspend fun buildRow(
        lineNo: Int,
        cells: List<String>,
        header: List<String>,
        mapping: FieldMapping,
        options: PreviewOptions,
        categories: List<Category>,
        accounts: List<Account>,
        seenKeys: MutableSet<String>
    ): ImportRow {
        fun valueOf(field: ImportField): String {
            val index = mapping.indexFor(field, header)
            return cells.getOrNull(index).orEmpty().trim()
        }

        val rawTime = valueOf(ImportField.BILL_TIME)
        val rawAmount = valueOf(ImportField.AMOUNT)
        val rawType = valueOf(ImportField.BILL_TYPE)
        val rawCategory = valueOf(ImportField.CATEGORY_NAME)
        val rawAccountFrom = valueOf(ImportField.ACCOUNT_FROM)
        val rawAccountTo = valueOf(ImportField.ACCOUNT_TO)
        val remark = valueOf(ImportField.REMARK)
        val tagNames = valueOf(ImportField.TAG_NAMES)
            .split(Regex("[,，;；|]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val time = parseTime(rawTime)
        val signedFen = Money.parse(rawAmount)
        var error: String? = null
        if (time == null) error = "时间无法识别"
        if (signedFen == null) error = error ?: "金额为空或非数字"
        if (signedFen == 0L) error = error ?: "金额不能为 0"

        val billType = parseType(
            raw = rawType,
            signRule = options.signRule,
            signedFen = signedFen ?: 0L,
            recognizeTransfer = options.recognizeTransfer
        )
        val amountFen = abs(signedFen ?: 0L)

        val (categoryId, categoryName, needCreateCategory) = matchCategory(
            rawName = rawCategory,
            billType = billType,
            categories = categories,
            options = options
        )
        val (accountId, accountName, needCreateAccount) = matchAccount(rawAccountFrom, accounts, options)
        val (accountToId, accountToName, needCreateAccountTo) = if (billType == BillType.TRANSFER) {
            if (rawAccountTo.isBlank()) {
                Triple(accountId, accountName, false)
            } else {
                matchAccount(rawAccountTo, accounts, options)
            }
        } else {
            Triple(0L, "", false)
        }

        var duplicate = false
        if (error == null) {
            val dedupAccountId = if (accountId > 0L) accountId else accountName.hashCode().toLong()
            val key = DedupKey(
                bookId = options.bookId,
                billTime = DateTimeUtils.truncateToSecond(time ?: 0L),
                amountFen = amountFen,
                accountFrom = dedupAccountId,
                remark = remark.normalizeRemark()
            )
            if (!seenKeys.add(key.asString())) {
                duplicate = true
            } else if (options.dedupEnabled) {
                duplicate = when (val found = billRepository.findDuplicate(key)) {
                    is Result.Success -> found.data != null
                    is Result.Error -> false // 去重查询失败不阻断导入，写入事务仍保证原子性
                    Result.Loading -> false
                }
            }
        }

        return ImportRow(
            lineNo = lineNo,
            cells = cells,
            billTime = time ?: 0L,
            amountFen = amountFen,
            billType = billType,
            categoryId = categoryId,
            categoryName = categoryName,
            accountFromId = accountId,
            accountFromName = accountName,
            accountToId = accountToId,
            accountToName = accountToName,
            remark = remark,
            tagNames = tagNames,
            errorMessage = error,
            duplicate = duplicate,
            createCategory = needCreateCategory,
            createAccount = needCreateAccount || needCreateAccountTo
        )
    }

    private fun parseTime(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val numeric = text.toLongOrNull()
        if (numeric != null && text.length >= 10 && numeric > 0L) {
            return if (text.length >= 13) numeric else numeric * 1000L
        }
        return DateTimeUtils.parseFlexible(text)
    }

    private fun parseType(
        raw: String,
        signRule: Boolean,
        signedFen: Long,
        recognizeTransfer: Boolean
    ): BillType {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) {
            return if (signRule) {
                if (signedFen < 0L) BillType.EXPENSE else BillType.INCOME
            } else {
                BillType.EXPENSE
            }
        }
        if (recognizeTransfer && TRANSFER_WORDS.any { text.contains(it) }) return BillType.TRANSFER
        if (INCOME_WORDS.any { text.contains(it) }) return BillType.INCOME
        if (EXPENSE_WORDS.any { text.contains(it) }) return BillType.EXPENSE
        return if (signRule) {
            if (signedFen < 0L) BillType.EXPENSE else BillType.INCOME
        } else {
            BillType.EXPENSE
        }
    }

    private fun matchCategory(
        rawName: String,
        billType: BillType,
        categories: List<Category>,
        options: PreviewOptions
    ): Triple<Long, String, Boolean> {
        val name = rawName.trim()
        if (name.isEmpty()) {
            return Triple(options.defaultCategoryId, options.defaultCategoryName, false)
        }
        val kind = if (billType == BillType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val pool = categories.filter { it.kind == kind }
        val matched = pool.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: pool.firstOrNull { it.name.contains(name) || name.contains(it.name) }
        if (matched != null) return Triple(matched.categoryId, matched.name, false)
        if (options.autoCreateMissing) return Triple(0L, name, true)
        return Triple(options.defaultCategoryId, options.defaultCategoryName, false)
    }

    private fun matchAccount(
        rawName: String,
        accounts: List<Account>,
        options: PreviewOptions
    ): Triple<Long, String, Boolean> {
        val name = rawName.trim()
        if (name.isEmpty()) {
            return Triple(options.defaultAccountId, options.defaultAccountName, false)
        }
        val matched = accounts.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: accounts.firstOrNull { it.name.contains(name) || name.contains(it.name) }
        if (matched != null) return Triple(matched.accountId, matched.name, false)
        if (options.autoCreateMissing) return Triple(0L, name, true)
        return Triple(options.defaultAccountId, options.defaultAccountName, false)
    }

    private fun buildSummary(rows: List<ImportRow>): ImportPreviewSummary = ImportPreviewSummary(
        totalRows = rows.size,
        validRows = rows.count { it.isImportable },
        failedRows = rows.count { it.isFailed && !it.skipped },
        skippedRows = rows.count { it.skipped },
        duplicateRows = rows.count { it.duplicate },
        balanceDeltaFen = rows.filter { it.isImportable && it.billType != BillType.TRANSFER }
            .sumOf { row ->
                if (row.billType == BillType.INCOME) row.amountFen else -row.amountFen
            }
    )

    private companion object {
        val INCOME_WORDS = listOf("收入", "收款", "存入", "入账", "转入", "income", "credit", "贷")
        val EXPENSE_WORDS = listOf("支出", "付款", "消费", "转出", "expense", "debit", "借", "支")
        val TRANSFER_WORDS = listOf("转账", "转出", "转入", "transfer")
    }
}
