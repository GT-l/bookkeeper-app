package com.azu.bookkeeper.ui.import.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.ImportRow
import com.azu.bookkeeper.domain.model.ImportRowEdit
import com.azu.bookkeeper.ui.theme.ExpenseRed

/** 单行编辑提交流：解析失败的时间/金额由 ViewModel 标红。 */
data class BillPreviewEditSubmit(
    val lineNo: Int,
    val edit: ImportRowEdit
)

/** 预览校对行：支持勾选、跳过、重复放行、展开编辑六项核心字段。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillPreviewRow(
    row: ImportRow,
    categories: List<Category>,
    accounts: List<Account>,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onToggleSkip: () -> Unit,
    onForceImportDuplicate: () -> Unit,
    onSubmit: (BillPreviewEditSubmit) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var timeText by remember(row.lineNo) {
        mutableStateOf(if (row.billTime > 0) DateTimeUtils.format(row.billTime) else "")
    }
    var amountText by remember(row.lineNo) {
        mutableStateOf(if (row.amountFen > 0) Money.formatPlain(row.amountFen, withSymbol = false) else "")
    }
    var type by remember(row.lineNo) { mutableStateOf(row.billType) }
    var categoryId by remember(row.lineNo) { mutableStateOf(row.categoryId) }
    var accountId by remember(row.lineNo) { mutableStateOf(row.accountFromId) }
    var remark by remember(row.lineNo) { mutableStateOf(row.remark) }
    var typeMenu by remember { mutableStateOf(false) }

    val rowColor = if (row.isFailed && !row.skipped) ExpenseRed else MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    text = "第 ${row.lineNo} 行 · ${row.billType.label} · ${Money.format(row.amountFen)}",
                    color = rowColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = buildSummary(row),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                if (row.isFailed && !row.skipped) {
                    Text(text = row.errorMessage.orEmpty(), color = ExpenseRed, style = MaterialTheme.typography.labelSmall)
                }
                if (row.duplicate) {
                    Text(text = "疑似重复，默认不导入", color = ExpenseRed, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButtonSmall(text = if (expanded) "收起" else "编辑", onClick = { expanded = !expanded })
        }

        if (expanded) {
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("时间（2025-10-01 12:30）") },
                singleLine = true,
                isError = row.isFailed && timeText.isBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("金额（元）") },
                singleLine = true,
                isError = row.isFailed && amountText.isBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                ExposedDropdownMenuBox(
                    expanded = typeMenu,
                    onExpandedChange = { typeMenu = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = type.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        BillType.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    type = option
                                    typeMenu = false
                                }
                            )
                        }
                    }
                }
            }
            CategoryDropdown(categories, categoryId) { categoryId = it }
            AccountDropdown(accounts, accountId) { accountId = it }
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(
                    onClick = {
                        val category = categories.firstOrNull { it.categoryId == categoryId }
                        val account = accounts.firstOrNull { it.accountId == accountId }
                        onSubmit(
                            BillPreviewEditSubmit(
                                lineNo = row.lineNo,
                                edit = ImportRowEdit(
                                    billTime = DateTimeUtils.parseFlexible(timeText) ?: 0L,
                                    amountFen = Money.parse(amountText)?.let { if (it < 0) -it else it } ?: 0L,
                                    billType = type,
                                    categoryId = category?.categoryId ?: row.categoryId,
                                    categoryName = category?.name ?: row.categoryName,
                                    accountFromId = account?.accountId ?: row.accountFromId,
                                    accountFromName = account?.name ?: row.accountFromName,
                                    remark = remark.trim()
                                )
                            )
                        )
                        expanded = false
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存修改") }
                OutlinedButton(
                    onClick = onToggleSkip,
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text(if (row.skipped) "取消跳过" else "跳过本行") }
            }
            if (row.duplicate) {
                OutlinedButton(onClick = onForceImportDuplicate, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("仍然导入这一行")
                }
            }
        }
        Divider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(categories: List<Category>, selectedId: Long, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.firstOrNull { it.categoryId == selectedId }?.name.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("分类") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text("${category.name}（${category.kind.label()}）") },
                    onClick = {
                        onSelect(category.categoryId)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDropdown(accounts: List<Account>, selectedId: Long, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = accounts.firstOrNull { it.accountId == selectedId }?.name.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("账户") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onSelect(account.accountId)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TextButtonSmall(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text) }
}

private fun buildSummary(row: ImportRow): String {
    val time = if (row.billTime > 0) DateTimeUtils.format(row.billTime) else row.cells.firstOrNull().orEmpty()
    val category = row.categoryName.ifBlank { "默认分类" }
    val account = row.accountFromName.ifBlank { "默认账户" }
    return "$time · $category · $account · ${row.remark}"
}

private fun com.azu.bookkeeper.domain.model.CategoryKind.label(): String = when (this) {
    com.azu.bookkeeper.domain.model.CategoryKind.EXPENSE -> "支出"
    com.azu.bookkeeper.domain.model.CategoryKind.INCOME -> "收入"
}
