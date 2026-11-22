package com.azu.bookkeeper.ui.book

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
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.model.BookStatus
import com.azu.bookkeeper.ui.common.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookManageScreen(viewModel: BookManageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("账本管理") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新账本名称（日常 / 家庭 / 旅行 / 生意）") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.add(newName); newName = "" },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("创建账本") }

            state.message?.let {
                Text(text = it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(items = state.books, key = { it.bookId }) { book ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            text = "${book.name}${if (book.isDefault) "（默认）" else ""}" +
                                if (book.status == BookStatus.ARCHIVED) " · 已归档" else "",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(text = book.description, style = MaterialTheme.typography.labelSmall)
                        Row {
                            OutlinedButton(onClick = { viewModel.setDefault(book.bookId) }) { Text("默认") }
                            OutlinedButton(
                                onClick = { viewModel.toggleArchive(book) },
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(if (book.status == BookStatus.ACTIVE) "归档" else "恢复")
                            }
                            OutlinedButton(
                                onClick = { pendingDelete = book },
                                modifier = Modifier.padding(start = 6.dp)
                            ) { Text("删除") }
                        }
                    }
                    Divider()
                }
            }
        }
    }

    pendingDelete?.let { book ->
        ConfirmDialog(
            title = "删除账本",
            message = "将永久删除「${book.name}」下全部账单、账户、分类与预算，且不可恢复。确认删除？",
            confirmText = "确认删除",
            onConfirm = { viewModel.delete(book.bookId); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}
