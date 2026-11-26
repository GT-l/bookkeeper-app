package com.azu.bookkeeper.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.domain.model.Tag
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.SoftCard

/** 标签管理：把标签做成可点的胶囊卡片，改名/删除收在小按钮里。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageScreen(viewModel: TagManageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Tag?>(null) }
    var name by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "标签",
                subtitle = "一笔账单最多 10 个标签",
                actions = {
                    TextButton(onClick = { name = ""; creating = true }) { Text("+ 新增") }
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = state.tags, key = { it.tagId }) { tag ->
                    SoftCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "# ${tag.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Box(modifier = Modifier.weight(1f))
                            TextButton(onClick = { name = tag.name; renaming = tag }) {
                                Text("改名", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { viewModel.delete(tag.tagId) }) {
                                Text("删除", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "标签可以跨分类使用，比如「出差」「报销」「给家里」。删标签不会删账单。",
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
            title = { Text("新增标签") },
            text = {
                CuteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "标签名",
                    placeholder = "例如 出差 / 报销 / 请客",
                    leadingEmoji = "#️⃣",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                CutePrimaryButton(
                    onClick = { viewModel.add(name); creating = false },
                    enabled = name.isNotBlank()
                ) { Text("新增") }
            },
            dismissButton = { CuteOutlinedButton(onClick = { creating = false }) { Text("取消") } }
        )
    }

    renaming?.let { tag ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("重命名「${tag.name}」") },
            text = {
                CuteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "新名称",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                CutePrimaryButton(
                    onClick = { viewModel.rename(tag, name); renaming = null },
                    enabled = name.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { CuteOutlinedButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }
}
