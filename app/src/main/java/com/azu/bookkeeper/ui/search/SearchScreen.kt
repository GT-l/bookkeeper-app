package com.azu.bookkeeper.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.ui.home.BillRow
import com.azu.bookkeeper.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryIdText by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("搜索与筛选") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = viewModel::setKeyword,
                label = { Text("备注 / 分类 / 原文 / 标签") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = state.typeFilter == null,
                    onClick = { viewModel.setTypeFilter(null) },
                    label = { Text("全部类型") }
                )
                BillType.entries.forEach { type ->
                    FilterChip(
                        selected = state.typeFilter == type,
                        onClick = { viewModel.setTypeFilter(type) },
                        label = { Text(type.label) },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = state.sourceFilter == null,
                    onClick = { viewModel.setSourceFilter(null) },
                    label = { Text("全部来源") }
                )
                listOf(BillSource.MANUAL, BillSource.IMPORT, BillSource.AUTO_CAPTURE, BillSource.QUICK_MANUAL)
                    .forEach { source ->
                        FilterChip(
                            selected = state.sourceFilter == source,
                            onClick = { viewModel.setSourceFilter(source) },
                            label = { Text(source.label) },
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = { viewModel.selectAll() }) {
                    Text(if (state.selectedIds.size == state.results.size && state.results.isNotEmpty()) "取消全选" else "全选")
                }
                Text(
                    text = "已选 ${state.selectedIds.size} / ${state.results.size}",
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { showCategoryDialog = true }) { Text("批量改分类") }
                OutlinedButton(
                    onClick = { viewModel.batchDelete() },
                    modifier = Modifier.padding(start = 6.dp)
                ) { Text("批量删除") }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(items = state.results, key = { it.billId }) { bill ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = bill.billId in state.selectedIds,
                            onCheckedChange = { viewModel.toggleSelect(bill.billId) }
                        )
                        BillRow(
                            bill = bill,
                            onClick = { navController.navigate(Screen.billDetail(bill.billId)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (showCategoryDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("批量修改分类") },
            text = {
                OutlinedTextField(
                    value = categoryIdText,
                    onValueChange = { categoryIdText = it },
                    label = { Text("目标分类 ID（在分类管理页查看）") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    categoryIdText.toLongOrNull()?.let { viewModel.batchSetCategory(it) }
                    showCategoryDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCategoryDialog = false }) { Text("取消") }
            }
        )
    }
}
