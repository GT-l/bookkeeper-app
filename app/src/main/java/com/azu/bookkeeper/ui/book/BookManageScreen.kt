package com.azu.bookkeeper.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.model.BookStatus
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.ConfirmDialog
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePill
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.SoftCard

/**
 * 账本管理。
 *
 * 造页时是「顶部输入框 + 灰色列表 + 三个 OutlinedButton」，视觉噪音很大。
 * 现在改成：右上角「+ 新建」→ 弹窗，列表每个账本一张卡，默认/归档用彩色徽标而不是括号文字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookManageScreen(viewModel: BookManageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "账本",
                subtitle = "不同场景分开记，数据互不影响",
                actions = {
                    TextButton(onClick = { creating = true; newName = "" }) { Text("+ 新建") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = state.books, key = { it.bookId }) { book ->
                    BookCard(
                        book = book,
                        onDefault = { viewModel.setDefault(book.bookId) },
                        onArchive = { viewModel.toggleArchive(book) },
                        onDelete = { pendingDelete = book }
                    )
                }
                item {
                    Text(
                        text = "账本之间完全隔离：账单、账户、分类、预算都按账本独立统计。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("新建账本") },
            text = {
                Column {
                    CuteTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = "账本名称",
                        placeholder = "日常 / 家庭 / 旅行 / 生意",
                        leadingEmoji = "📚",
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "新账本会自带一套默认分类，不用手动建。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                CutePrimaryButton(
                    onClick = {
                        viewModel.add(newName)
                        creating = false
                    },
                    enabled = newName.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = { CuteOutlinedButton(onClick = { creating = false }) { Text("取消") } }
        )
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

@Composable
private fun BookCard(
    book: Book,
    onDefault: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    val archived = book.status == BookStatus.ARCHIVED
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = book.icon.ifBlank { "📒" }, fontSize = 18.sp)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = book.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (book.description.isNotBlank()) {
                        Text(
                            text = book.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (book.isDefault) CutePill(text = "默认", color = MaterialTheme.colorScheme.primary)
                    if (archived) CutePill(text = "已归档", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!book.isDefault) {
                    CuteOutlinedButton(onClick = onDefault, modifier = Modifier.weight(1f)) {
                        Text("设为默认", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                }
                CuteOutlinedButton(onClick = onArchive, modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (archived) "恢复" else "归档",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                CuteOutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("删除", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
