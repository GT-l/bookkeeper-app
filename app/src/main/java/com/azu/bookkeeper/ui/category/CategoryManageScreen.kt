package com.azu.bookkeeper.ui.category

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
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
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManageScreen(viewModel: CategoryManageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf(0L) }
    var renaming by remember { mutableStateOf<Category?>(null) }

    Scaffold(topBar = { AppTopBar("分类管理") }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row {
                CategoryKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { viewModel.setKind(kind) },
                        label = { Text(if (kind == CategoryKind.EXPENSE) "支出分类" else "收入分类") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新分类名称（一级）") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { viewModel.add(name, parentId); name = "" }) { Text("新增") }
                Text(
                    text = if (parentId == 0L) "新增一级分类" else "新增二级分类（父 ID $parentId）",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp)
                )
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(items = state.categories, key = { it.categoryId }) { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (category.parentId == 0L) category.name else "  └ ${category.name}",
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { parentId = category.categoryId }) { Text("加子级") }
                        OutlinedButton(
                            onClick = { renaming = category; name = category.name },
                            modifier = Modifier.padding(start = 6.dp)
                        ) { Text("改名") }
                        OutlinedButton(
                            onClick = { viewModel.delete(category, 0L) },
                            modifier = Modifier.padding(start = 6.dp)
                        ) { Text("删除") }
                    }
                    Divider()
                }
            }
        }
    }

    renaming?.let { category ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名分类") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") })
            },
            confirmButton = {
                Button(onClick = { viewModel.rename(category, name); renaming = null }) { Text("保存") }
            },
            dismissButton = {
                OutlinedButton(onClick = { renaming = null }) { Text("取消") }
            }
        )
    }
}
