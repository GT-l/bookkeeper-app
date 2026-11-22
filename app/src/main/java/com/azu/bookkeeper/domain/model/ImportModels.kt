package com.azu.bookkeeper.domain.model

/**
 * T4 导入导出专用领域模型。
 * 约定：ImportTemplate.mapping / FieldMapping.values 的 key 恒等于 ImportField.key。
 */

/** 源文件列可映射到的系统标准字段。 */
enum class ImportField(val key: String, val label: String, val required: Boolean = false) {
    BILL_TIME("bill_time", "交易时间", required = true),
    AMOUNT("amount", "金额", required = true),
    BILL_TYPE("bill_type", "收支类型"),
    CATEGORY_NAME("category_name", "分类"),
    ACCOUNT_FROM("account_from", "账户 / 付款方式"),
    ACCOUNT_TO("account_to", "转入账户"),
    REMARK("remark", "备注"),
    TAG_NAMES("tag_names", "标签");

    companion object {
        /** 全部可映射字段（顺序即 UI 展示顺序）。 */
        val ALL: List<ImportField> = entries.toList()

        fun fromKey(key: String): ImportField? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 预设来源的唯一事实源：UI 展示 + 自动表头识别 + 模板播种共用。
 * aliases 为标准字段 key → 常见表头别名（优先级从高到低）。
 */
data class PresetImportSource(
    val sourceKey: String,
    val name: String,
    val description: String,
    val mapping: Map<String, String>,
    val aliases: Map<String, List<String>>,
    val flags: Int
)

object ImportPresets {

    const val CUSTOM_KEY = "custom"

    private val timeAliases = listOf("交易时间", "交易日期", "日期", "记账日期", "时间", "date", "time")
    private val amountAliases = listOf("金额(元)", "金额（元）", "交易金额", "金额", "发生额", "amount", "money")
    private val typeAliases = listOf("收支类型", "收/支", "收支", "类型", "交易类型", "type")
    private val categoryAliases = listOf("分类", "类别", "消费类型", "category")
    private val accountAliases = listOf("账户", "支付方式", "付款账户", "资金账户", "account")
    private val accountToAliases = listOf("转入账户", "收款账户", "对方账户", "转入账号")
    private val remarkAliases = listOf("备注", "交易对方", "商品说明", "交易摘要", "摘要", "说明", "remark", "memo")
    private val tagAliases = listOf("标签", "tags", "tag")

    private fun aliasesOf(
        time: List<String> = timeAliases,
        amount: List<String> = amountAliases,
        type: List<String> = typeAliases,
        category: List<String> = categoryAliases,
        from: List<String> = accountAliases,
        to: List<String> = accountToAliases,
        remark: List<String> = remarkAliases,
        tags: List<String> = tagAliases
    ): Map<String, List<String>> = mapOf(
        ImportField.BILL_TIME.key to time,
        ImportField.AMOUNT.key to amount,
        ImportField.BILL_TYPE.key to type,
        ImportField.CATEGORY_NAME.key to category,
        ImportField.ACCOUNT_FROM.key to from,
        ImportField.ACCOUNT_TO.key to to,
        ImportField.REMARK.key to remark,
        ImportField.TAG_NAMES.key to tags
    )

    val ALL: List<PresetImportSource> = listOf(
        PresetImportSource(
            sourceKey = "wechat",
            name = "微信支付",
            description = "微信账单 CSV：交易时间 / 金额(元) / 交易对方",
            mapping = mapOf(
                ImportField.BILL_TIME.key to "交易时间",
                ImportField.BILL_TYPE.key to "交易类型",
                ImportField.AMOUNT.key to "金额(元)",
                ImportField.REMARK.key to "交易对方",
                ImportField.ACCOUNT_FROM.key to "支付方式"
            ),
            aliases = aliasesOf(),
            flags = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP or ImportFlags.SIGN_RULE
        ),
        PresetImportSource(
            sourceKey = "alipay",
            name = "支付宝",
            description = "支付宝账单 CSV：交易时间 / 收/支 / 金额(元)",
            mapping = mapOf(
                ImportField.BILL_TIME.key to "交易时间",
                ImportField.BILL_TYPE.key to "收/支",
                ImportField.AMOUNT.key to "金额(元)",
                ImportField.REMARK.key to "交易对方",
                ImportField.ACCOUNT_FROM.key to "支付方式"
            ),
            aliases = aliasesOf(),
            flags = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP or
                ImportFlags.SIGN_RULE or ImportFlags.TRANSFER_SUPPORT
        ),
        PresetImportSource(
            sourceKey = "shark",
            name = "鲨鱼记账",
            description = "鲨鱼记账 CSV：日期 / 类型 / 金额 / 分类",
            mapping = mapOf(
                ImportField.BILL_TIME.key to "日期",
                ImportField.BILL_TYPE.key to "类型",
                ImportField.AMOUNT.key to "金额",
                ImportField.CATEGORY_NAME.key to "分类",
                ImportField.REMARK.key to "备注"
            ),
            aliases = aliasesOf(),
            flags = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP
        ),
        PresetImportSource(
            sourceKey = "suishouji",
            name = "随手记",
            description = "随手记 CSV：日期 / 收支 / 金额 / 分类 / 账户",
            mapping = mapOf(
                ImportField.BILL_TIME.key to "日期",
                ImportField.BILL_TYPE.key to "收支",
                ImportField.AMOUNT.key to "金额",
                ImportField.CATEGORY_NAME.key to "分类",
                ImportField.ACCOUNT_FROM.key to "账户",
                ImportField.REMARK.key to "备注"
            ),
            aliases = aliasesOf(),
            flags = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP
        ),
        PresetImportSource(
            sourceKey = "cmb",
            name = "招商银行",
            description = "招行流水 CSV/XLSX：交易日期 / 交易摘要 / 交易金额",
            mapping = mapOf(
                ImportField.BILL_TIME.key to "交易日期",
                ImportField.BILL_TYPE.key to "交易类型",
                ImportField.AMOUNT.key to "交易金额",
                ImportField.REMARK.key to "交易摘要",
                ImportField.ACCOUNT_FROM.key to "账户"
            ),
            aliases = aliasesOf(
                time = listOf("交易日期", "记账日期", "日期", "交易时间"),
                amount = listOf("交易金额", "金额(元)", "发生额", "金额"),
                remark = listOf("交易摘要", "摘要", "备注", "交易对方"),
                from = listOf("账户", "卡号", "付款账户")
            ),
            flags = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP or ImportFlags.SIGN_RULE
        ),
        PresetImportSource(
            sourceKey = "icbc",
            name = "工商银行",
            description = "工行流水 CSV/XLSX：交易日期 / 摘要 / 交易金额",
            mapping = mapOf(
                ImportField.BILL_TIME.key to "交易日期",
                ImportField.BILL_TYPE.key to "收支",
                ImportField.AMOUNT.key to "交易金额",
                ImportField.REMARK.key to "摘要",
                ImportField.ACCOUNT_FROM.key to "账户"
            ),
            aliases = aliasesOf(
                time = listOf("交易日期", "日期", "交易日"),
                amount = listOf("交易金额", "金额(元)", "发生额", "金额"),
                remark = listOf("摘要", "交易摘要", "备注"),
                from = listOf("账户", "卡号", "付款账户")
            ),
            flags = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP or ImportFlags.SIGN_RULE
        )
    )

    fun byKey(sourceKey: String): PresetImportSource? = ALL.firstOrNull { it.sourceKey == sourceKey }
}

/** 导入文件种类。 */
enum class ImportFileKind(val extensions: List<String>, val mime: String) {
    CSV(listOf("csv", "txt"), "text/csv"),
    XLSX(listOf("xlsx"), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    companion object {
        fun fromFileName(fileName: String): ImportFileKind? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/** 读取选项：字符集 AUTO 时由数据源自动识别 UTF-8 / GBK。 */
data class ImportReadOptions(
    val kind: ImportFileKind = ImportFileKind.CSV,
    val charset: String = "AUTO",
    val hasHeader: Boolean = true,
    val maxPreviewRows: Int = 10
)

/** 表头 + 前 N 行预览（流式读取，预览之外的行只计数不驻留内存）。 */
data class TablePreview(
    val header: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val totalRows: Int = 0,
    val charset: String = "UTF-8",
    val kind: ImportFileKind = ImportFileKind.CSV,
    val fileName: String = ""
)

/** 字段映射结果：标准字段 key → 源文件列名；空串表示跳过该列。 */
data class FieldMapping(
    val values: Map<String, String> = emptyMap(),
    val matchedPresetKey: String = ""
) {
    fun columnFor(field: ImportField): String = values[field.key].orEmpty()

    fun indexFor(field: ImportField, header: List<String>): Int {
        val column = columnFor(field)
        if (column.isBlank()) return -1
        return header.indexOfFirst { it.equals(column, ignoreCase = true) }
    }
}

/** 预览校对阶段的参数。 */
data class PreviewOptions(
    val bookId: Long = 0L,
    val signRule: Boolean = false,
    val recognizeTransfer: Boolean = false,
    val autoCreateMissing: Boolean = true,
    val dedupEnabled: Boolean = true,
    val defaultCategoryId: Long = 0L,
    val defaultAccountId: Long = 0L,
    val defaultCategoryName: String = "其他支出",
    val defaultAccountName: String = "默认账户"
)

/** 一条待导入行；原始单元格保留，供单行重新编辑时回退重解析。 */
data class ImportRow(
    val lineNo: Int = 0,
    val cells: List<String> = emptyList(),
    val billTime: Long = 0L,
    val amountFen: Long = 0L,
    val billType: BillType = BillType.EXPENSE,
    val categoryId: Long = 0L,
    val categoryName: String = "",
    val accountFromId: Long = 0L,
    val accountFromName: String = "",
    val accountToId: Long = 0L,
    val accountToName: String = "",
    val remark: String = "",
    val tagNames: List<String> = emptyList(),
    val errorMessage: String? = null,
    val skipped: Boolean = false,
    val duplicate: Boolean = false,
    val createCategory: Boolean = false,
    val createAccount: Boolean = false,
    val edited: Boolean = false
) {
    val isFailed: Boolean get() = errorMessage != null
    val isImportable: Boolean get() = !isFailed && !skipped && !duplicate && billTime > 0L && amountFen > 0L
}

/** 预览摘要（顶部统计卡片）。 */
data class ImportPreviewSummary(
    val totalRows: Int = 0,
    val validRows: Int = 0,
    val failedRows: Int = 0,
    val skippedRows: Int = 0,
    val duplicateRows: Int = 0,
    val balanceDeltaFen: Long = 0L
)

/** 预览计算结果。 */
data class ImportPreviewResult(
    val rows: List<ImportRow> = emptyList(),
    val summary: ImportPreviewSummary = ImportPreviewSummary()
)

/** 单行编辑提交值（时间/金额为已解析后的值，解析失败由 ViewModel 写回错误）。 */
data class ImportRowEdit(
    val billTime: Long,
    val amountFen: Long,
    val billType: BillType,
    val categoryId: Long,
    val categoryName: String,
    val accountFromId: Long,
    val accountFromName: String,
    val remark: String
)

/** 失败明细中的一条记录。 */
data class ImportFailure(
    val rowIndex: Int = 0,
    val reason: String = "",
    val raw: String = ""
)

/** 导入报告（步骤 5 展示 + 失败明细 CSV）。 */
data class ImportReport(
    val taskId: Long = 0L,
    val totalRows: Int = 0,
    val successRows: Int = 0,
    val expenseRows: Int = 0,
    val incomeRows: Int = 0,
    val transferRows: Int = 0,
    val skippedRows: Int = 0,
    val failedRows: Int = 0,
    val duplicateRows: Int = 0,
    val createdCategories: List<String> = emptyList(),
    val createdAccounts: List<String> = emptyList(),
    val reportPath: String = ""
)

/** 超过 10MB 上限：仓储层将其映射为 AppError.File(E_FILE_001)。 */
class ImportFileTooLargeException : RuntimeException("import file exceeds 10MB limit")
