package com.azu.bookkeeper.data.mapper

import com.azu.bookkeeper.data.local.db.entity.AccountEntity
import com.azu.bookkeeper.data.local.db.entity.AnnualBudgetEntity
import com.azu.bookkeeper.data.local.db.entity.BillEntity
import com.azu.bookkeeper.data.local.db.entity.BillImageEntity
import com.azu.bookkeeper.data.local.db.entity.BillTagCrossRef
import com.azu.bookkeeper.data.local.db.entity.BookEntity
import com.azu.bookkeeper.data.local.db.entity.BudgetEntity
import com.azu.bookkeeper.data.local.db.entity.CaptureEventEntity
import com.azu.bookkeeper.data.local.db.entity.CategoryEntity
import com.azu.bookkeeper.data.local.db.entity.CategoryRuleEntity
import com.azu.bookkeeper.data.local.db.entity.DebtEntity
import com.azu.bookkeeper.data.local.db.entity.DebtRepaymentEntity
import com.azu.bookkeeper.data.local.db.entity.ImportTaskEntity
import com.azu.bookkeeper.data.local.db.entity.InstallmentEntity
import com.azu.bookkeeper.data.local.db.entity.InstallmentPaymentEntity
import com.azu.bookkeeper.data.local.db.entity.ImportTemplateEntity
import com.azu.bookkeeper.data.local.db.entity.TagEntity
import com.azu.bookkeeper.data.local.db.entity.RefundEntity
import com.azu.bookkeeper.data.local.db.entity.ReimbursementEntity
import com.azu.bookkeeper.domain.model.AnnualBudget
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.AccountType
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.model.BookStatus
import com.azu.bookkeeper.domain.model.Debt
import com.azu.bookkeeper.domain.model.DebtDirection
import com.azu.bookkeeper.domain.model.DebtRepayment
import com.azu.bookkeeper.domain.model.DebtStatus
import com.azu.bookkeeper.domain.model.Budget
import com.azu.bookkeeper.domain.model.CaptureCandidate
import com.azu.bookkeeper.domain.model.CaptureType
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.CategoryRule
import com.azu.bookkeeper.domain.model.CycleType
import com.azu.bookkeeper.domain.model.ImportTask
import com.azu.bookkeeper.domain.model.Installment
import com.azu.bookkeeper.domain.model.InstallmentPayment
import com.azu.bookkeeper.domain.model.ImportTemplate
import com.azu.bookkeeper.domain.model.Refund
import com.azu.bookkeeper.domain.model.ReimburseStatus
import com.azu.bookkeeper.domain.model.Reimbursement
import com.azu.bookkeeper.domain.model.Tag
import com.azu.bookkeeper.domain.model.VerifyStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room Entity ↔ Domain Model 单向映射（Entity 绝不越过 Data 层）。 */
private val mapperJson = Json { ignoreUnknownKeys = true }

private inline fun <reified T : Any> decodeList(raw: String): List<T> = runCatching {
    mapperJson.decodeFromString<List<T>>(raw)
}.getOrDefault(emptyList())

private fun decodeLongMap(raw: String): Map<Long, Long> = runCatching {
    mapperJson.decodeFromString<Map<Long, Long>>(raw)
}.getOrDefault(emptyMap())

private fun decodeStringMap(raw: String): Map<String, String> = runCatching {
    mapperJson.decodeFromString<Map<String, String>>(raw)
}.getOrDefault(emptyMap())

// ---------- Book ----------
fun BookEntity.toDomain(): Book = Book(
    bookId = bookId,
    name = name,
    icon = icon,
    description = description,
    createdAt = createdAt,
    isDefault = isDefault,
    status = BookStatus.fromCode(status)
)

fun Book.toEntity(): BookEntity = BookEntity(
    bookId = bookId,
    name = name,
    icon = icon,
    description = description,
    createdAt = createdAt,
    isDefault = isDefault,
    status = status.code
)

// ---------- Bill ----------
fun BillEntity.toDomain(): Bill = Bill(
    billId = billId,
    bookId = bookId,
    billType = BillType.fromCode(billType),
    amountFen = amountFen,
    categoryId = categoryId,
    tagIds = decodeList<Long>(tagIdsJson),
    accountFrom = accountFrom,
    accountTo = accountTo,
    billTime = billTime,
    remark = remark,
    imagePaths = decodeList<String>(imagePathsJson),
    cycleType = CycleType.fromCode(cycleType),
    cycleEndTime = cycleEndTime,
    source = BillSource.fromCode(source),
    captureType = CaptureType.fromCode(captureType),
    captureImagePath = captureImagePath,
    ocrConfidence = ocrConfidence,
    ocrRawText = ocrRawText,
    verifyStatus = VerifyStatus.fromCode(verifyStatus),
    createAt = createAt,
    updateAt = updateAt
)

fun Bill.toEntity(): BillEntity = BillEntity(
    billId = billId,
    bookId = bookId,
    billType = billType.code,
    amountFen = amountFen,
    categoryId = categoryId,
    tagIdsJson = mapperJson.encodeToString(tagIds),
    accountFrom = accountFrom,
    accountTo = accountTo,
    billTime = billTime,
    remark = remark,
    imagePathsJson = mapperJson.encodeToString(imagePaths),
    cycleType = cycleType.code,
    cycleEndTime = cycleEndTime,
    source = source.code,
    captureType = captureType.code,
    captureImagePath = captureImagePath,
    ocrConfidence = ocrConfidence,
    ocrRawText = ocrRawText,
    verifyStatus = verifyStatus.code,
    createAt = createAt,
    updateAt = updateAt
)

fun List<BillEntity>.toDomainBills(): List<Bill> = map { it.toDomain() }

// ---------- Category ----------
fun CategoryEntity.toDomain(): Category = Category(
    categoryId = categoryId,
    bookId = bookId,
    kind = CategoryKind.fromCode(kind),
    name = name,
    parentId = parentId,
    icon = icon,
    sortOrder = sortOrder
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    categoryId = categoryId,
    bookId = bookId,
    kind = kind.code,
    name = name,
    parentId = parentId,
    icon = icon,
    sortOrder = sortOrder
)

// ---------- Tag ----------
fun TagEntity.toDomain(): Tag = Tag(tagId = tagId, bookId = bookId, name = name)

fun Tag.toEntity(): TagEntity = TagEntity(tagId = tagId, bookId = bookId, name = name)

fun BillTagCrossRef.toDomainPair(): Pair<Long, Long> = billId to tagId

// ---------- Account ----------
fun AccountEntity.toDomain(): Account = Account(
    accountId = accountId,
    bookId = bookId,
    name = name,
    type = AccountType.fromCode(type),
    initialBalanceFen = initialBalanceFen,
    balanceFen = balanceFen,
    icon = icon,
    remark = remark,
    enabled = enabled,
    billDay = billDay,
    dueDay = dueDay,
    reminderEnabled = reminderEnabled
)

fun Account.toEntity(): AccountEntity = AccountEntity(
    accountId = accountId,
    bookId = bookId,
    name = name,
    type = type.code,
    initialBalanceFen = initialBalanceFen,
    balanceFen = balanceFen,
    icon = icon,
    remark = remark,
    enabled = enabled,
    billDay = billDay,
    dueDay = dueDay,
    reminderEnabled = reminderEnabled
)

// ---------- Budget ----------
fun BudgetEntity.toDomain(): Budget = Budget(
    budgetId = budgetId,
    bookId = bookId,
    month = month,
    totalLimitFen = totalLimitFen,
    categoryLimits = decodeLongMap(categoryLimitsJson)
)

fun Budget.toEntity(): BudgetEntity = BudgetEntity(
    budgetId = budgetId,
    bookId = bookId,
    month = month,
    totalLimitFen = totalLimitFen,
    categoryLimitsJson = mapperJson.encodeToString(categoryLimits)
)

// ---------- Import ----------
fun ImportTemplateEntity.toDomain(): ImportTemplate = ImportTemplate(
    templateId = templateId,
    name = name,
    sourceKey = sourceKey,
    mapping = decodeStringMap(mappingJson),
    flags = flags
)

fun ImportTemplate.toEntity(): ImportTemplateEntity = ImportTemplateEntity(
    templateId = templateId,
    name = name,
    sourceKey = sourceKey,
    mappingJson = mapperJson.encodeToString(mapping),
    flags = flags
)

fun ImportTaskEntity.toDomain(): ImportTask = ImportTask(
    taskId = taskId,
    templateId = templateId,
    filePath = filePath,
    status = ImportTaskStatus.fromCode(status),
    totalRows = totalRows,
    successRows = successRows,
    failedRows = failedRows,
    reportPath = reportPath
)

fun ImportTask.toEntity(): ImportTaskEntity = ImportTaskEntity(
    taskId = taskId,
    templateId = templateId,
    filePath = filePath,
    status = status.code,
    totalRows = totalRows,
    successRows = successRows,
    failedRows = failedRows,
    reportPath = reportPath
)

// ---------- 自动化 ----------
fun CategoryRuleEntity.toDomain(): CategoryRule = CategoryRule(
    ruleId = ruleId,
    keyword = keyword,
    categoryId = categoryId,
    priority = priority,
    enabled = enabled
)

fun CategoryRule.toEntity(): CategoryRuleEntity = CategoryRuleEntity(
    ruleId = ruleId,
    keyword = keyword,
    categoryId = categoryId,
    priority = priority,
    enabled = enabled
)

fun CaptureEventEntity.toCandidate(): CaptureCandidate = CaptureCandidate(
    eventId = eventId,
    captureType = CaptureType.fromCode(captureType),
    rawText = rawText,
    imagePath = imagePath,
    ocrConfidence = ocrConfidence,
    billTime = billTime.takeIf { it > 0L },
    finalScore = ocrConfidence
)

fun BillImageEntity.toPath(): String = encryptedPath

// ---------- 1.2.0 新增表 ----------
fun RefundEntity.toDomain(): Refund = Refund(
    refundId = refundId, bookId = bookId, sourceBillId = sourceBillId,
    amountFen = amountFen, refundTime = refundTime, reason = reason, createAt = createAt
)

fun Refund.toEntity(): RefundEntity = RefundEntity(
    refundId = refundId, bookId = bookId, sourceBillId = sourceBillId,
    amountFen = amountFen, refundTime = refundTime, reason = reason, createAt = createAt
)

fun ReimbursementEntity.toDomain(): Reimbursement = Reimbursement(
    reimbursementId = reimbursementId, bookId = bookId, title = title,
    amountFen = amountFen, status = ReimburseStatus.fromCode(status),
    sourceBillId = sourceBillId, incomeBillId = incomeBillId, accountId = accountId,
    createAt = createAt, updateAt = updateAt
)

fun Reimbursement.toEntity(): ReimbursementEntity = ReimbursementEntity(
    reimbursementId = reimbursementId, bookId = bookId, title = title,
    amountFen = amountFen, status = status.code,
    sourceBillId = sourceBillId, incomeBillId = incomeBillId, accountId = accountId,
    createAt = createAt, updateAt = updateAt
)

fun DebtEntity.toDomain(): Debt = Debt(
    debtId = debtId, bookId = bookId, direction = DebtDirection.fromCode(direction),
    counterparty = counterparty, totalFen = totalFen, settledFen = settledFen,
    accountId = accountId, dueDate = dueDate, note = note,
    status = DebtStatus.fromCode(status), createAt = createAt, updateAt = updateAt
)

fun Debt.toEntity(): DebtEntity = DebtEntity(
    debtId = debtId, bookId = bookId, direction = direction.code,
    counterparty = counterparty, totalFen = totalFen, settledFen = settledFen,
    accountId = accountId, dueDate = dueDate, note = note,
    status = status.code, createAt = createAt, updateAt = updateAt
)

fun DebtRepaymentEntity.toDomain(): DebtRepayment = DebtRepayment(
    repaymentId = repaymentId, debtId = debtId, amountFen = amountFen,
    repayTime = repayTime, billId = billId, note = note
)

fun DebtRepayment.toEntity(): DebtRepaymentEntity = DebtRepaymentEntity(
    repaymentId = repaymentId, debtId = debtId, amountFen = amountFen,
    repayTime = repayTime, billId = billId, note = note
)

fun InstallmentEntity.toDomain(): Installment = Installment(
    installmentId = installmentId, bookId = bookId, title = title,
    totalFen = totalFen, periods = periods, paidPeriods = paidPeriods,
    perPeriodFen = perPeriodFen, accountId = accountId, categoryId = categoryId,
    note = note, createAt = createAt
)

fun Installment.toEntity(): InstallmentEntity = InstallmentEntity(
    installmentId = installmentId, bookId = bookId, title = title,
    totalFen = totalFen, periods = periods, paidPeriods = paidPeriods,
    perPeriodFen = perPeriodFen, accountId = accountId, categoryId = categoryId,
    note = note, createAt = createAt
)

fun InstallmentPaymentEntity.toDomain(): InstallmentPayment = InstallmentPayment(
    paymentId = paymentId, installmentId = installmentId, periodIndex = periodIndex,
    amountFen = amountFen, payTime = payTime, billId = billId
)

fun InstallmentPayment.toEntity(): InstallmentPaymentEntity = InstallmentPaymentEntity(
    paymentId = paymentId, installmentId = installmentId, periodIndex = periodIndex,
    amountFen = amountFen, payTime = payTime, billId = billId
)

fun AnnualBudgetEntity.toDomain(): AnnualBudget = AnnualBudget(
    budgetId = budgetId, bookId = bookId, year = year, totalLimitFen = totalLimitFen
)

fun AnnualBudget.toEntity(): AnnualBudgetEntity = AnnualBudgetEntity(
    budgetId = budgetId, bookId = bookId, year = year, totalLimitFen = totalLimitFen
)
