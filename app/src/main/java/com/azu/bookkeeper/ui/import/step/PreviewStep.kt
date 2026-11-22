package com.azu.bookkeeper.ui.import.step

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.ui.import.ImportViewModel
import com.azu.bookkeeper.ui.import.component.BillPreviewRow
import com.azu.bookkeeper.ui.theme.ErrorRed
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen

/** 步骤 4：顶部摘要 + 失败/重复筛选 + 单行编辑 + 批量修改分类账户。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewStep(viewModel: ImportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var categoryMenu by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var batchCategoryId by remember { mutableStateOf(0L) }
    var batchAccountId by remember { mutableStateOf(0L) }
    val visibleLines = state.filteredRows.map { it.lineNo }.toSet()
    val allVisibleSelected = visibleLines.isNotEmpty() && visibleLines.all { it in state.selectedRowIndices }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "总 ${state.summary.totalRows} · 有效 ${state.summary.validRows} · 未处理失败 ${state.summary.failedRows} · " +
                "跳过 ${state.summary.skippedRows} · 重复 ${state.summary.duplicateRows}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("预估余额变化：", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = Money.format(state.summary.balanceDeltaFen),
                color = if (state.summary.balanceDeltaFen >= 0) IncomeGreen else ExpenseRed,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (state.summary.failedRows > 0) {
            Text(
                text = "有 ${state.summary.failedRows} 行解析失败，必须编辑修正或点击跳过才能继续。",
                color = ErrorRed,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            ImportViewModel.RowFilter.ALL_VALUES.forEach { filter ->
                FilterChip(
                    selected = state.rowFilter == filter,
                    onClick = { viewModel.setRowFilter(filter) },
                    label = { Text(filter.label) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = allVisibleSelected,
                onCheckedChange = { viewModel.selectAllVisible(it) }
            )
            Text("全选当前筛选结果（${visibleLines.size}）", style = MaterialTheme.typography.labelMedium)

            androidx.compose.material3.DropdownMenu(
                expanded = categoryMenu,
                onDismissRequest = { categoryMenu = false }
            ) {
                state.categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name) },
                        onClick = {
                            batchCategoryId = category.categoryId
                            viewModel.batchSetCategory(category.categoryId, category.name)
                            categoryMenu = false
                        }
                    )
                }
            }
            Button(onClick = { categoryMenu = true }, modifier = Modifier.padding(start = 8.dp)) { Text("批量分类") }

            androidx.compose.material3.DropdownMenu(
                expanded = accountMenu,
                onDismissRequest = { accountMenu = false }
            ) {
                state.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.name) },
                        onClick = {
                            batchAccountId = account.accountId
                            viewModel.batchSetAccount(account.accountId, account.name)
                            accountMenu = false
                        }
                    )
                }
            }
            Button(onClick = { accountMenu = true }, modifier = Modifier.padding(start = 8.dp)) { Text("批量账户") }
            OutlinedButton(onClick = { viewModel.batchSkip(true) }, modifier = Modifier.padding(start = 8.dp)) {
                Text("批量跳过")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(items = state.filteredRows, key = { it.lineNo }) { row ->
                BillPreviewRow(
                    row = row,
                    categories = state.categories,
                    accounts = state.accounts,
                    selected = row.lineNo in state.selectedRowIndices,
                    onToggleSelected = { viewModel.toggleRowSelection(row.lineNo) },
                    onToggleSkip = { viewModel.toggleSkip(row.lineNo) },
                    onForceImportDuplicate = { viewModel.toggleDuplicate(row.lineNo) },
                    onSubmit = { viewModel.updateRow(it.lineNo, it.edit) }
                )
            }
        }

        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        }
        state.error?.let {
            Text(it.userMessage, color = ErrorRed, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
