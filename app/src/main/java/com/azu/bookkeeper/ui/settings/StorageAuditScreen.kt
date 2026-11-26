package com.azu.bookkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.common.LoadingIndicator
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.theme.ExpenseRed

/**
 * 存储排查页。
 *
 * 目的只有一个：把「以前版本留在手机里的东西」明明白白列出来，让用户自己决定留还是删。
 * 默认一个都不勾，且在删除前把文件名再念一遍——这是不可恢复操作，不能靠一次误触完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAuditScreen(viewModel: StorageAuditViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    // 提示条 3 秒自动消失，不挡着继续操作
    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(3_000L)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            com.azu.bookkeeper.ui.common.AppTopBar(
                title = "存储排查",
                subtitle = "历史版本留下的数据库与文件",
                actions = {
                    TextButton(onClick = { viewModel.rescan() }) { Text("重新扫描") }
                }
            )
        },
        bottomBar = {
            if (state.hasSelection) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "已选 ${state.selectedItems.size} 项",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = state.selectedText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        CuteOutlinedButton(onClick = viewModel::keepAll) {
                            Text("全部保留", maxLines = 1, softWrap = false)
                        }
                        CutePrimaryButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("删除所选", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            state.loading -> Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingIndicator()
            }

            state.items.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState("很干净：没有发现历史遗留的数据文件")
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { SummaryCard(state) }
                item { TipCard() }

                state.grouped.forEach { (kind, list) ->
                    item(key = "header-${kind.name}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = kind.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${list.size} 项 · ${StorageAuditViewModel.formatSize(list.sumOf { it.sizeBytes })}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(items = list, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            checked = item.path in state.selected,
                            onToggle = { viewModel.toggle(item.path) }
                        )
                    }
                }

                item {
                    Text(
                        text = "扫描范围：App 私有目录（数据库 / 备份 / 日志 / 缓存 / 偏好设置）。不读取相册、下载或其它应用的数据。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)
                    )
                }
            }
        }

        state.message?.let { message ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            items = state.selectedItems,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelected()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun SummaryCard(state: StorageAuditViewModel.UiState) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.totalText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = " · 共 ${state.items.size} 项",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            Text(
                text = "其中 ${state.lockedCount} 项正在使用（数据库、密钥、当前设置），不会出现在可勾选范围内。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            val removable = state.items.count { !it.locked }
            if (removable > 0) {
                Text(
                    text = "可清理 $removable 项 · 勾选后底部会出现「删除所选」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TipCard() {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "怎么判断该不该删",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "• 旧版本数据库：确认现在账本数据正常，就可以删；\n" +
                    "• 备份包：想留个兜底就保留，删了只是少一条恢复路径；\n" +
                    "• 日志 / 缓存 / 临时截图：随时可删，不影响账本；\n" +
                    "• 锁定的项目（🔒）正在使用，删了会出问题，所以不让选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "删除不可恢复。不确定就先不勾，退出这一页不会改动任何文件。",
                style = MaterialTheme.typography.labelSmall,
                color = ExpenseRed,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun FileRow(
    item: StorageAuditViewModel.Item,
    checked: Boolean,
    onToggle: () -> Unit
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !item.locked) { onToggle() }
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = !item.locked,
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = "${item.note} · ${item.sizeText} · ${item.timeText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (item.locked) {
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "🔒 使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    items: List<StorageAuditViewModel.Item>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val preview = items.take(8).joinToString("\n") { "· ${it.name}（${it.sizeText}）" }
    val more = if (items.size > 8) "\n… 还有 ${items.size - 8} 项" else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("确认删除 ${items.size} 项？") },
        text = {
            Column {
                Text(
                    text = preview + more,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "删除后无法恢复，账本中已记的账单不受影响。",
                    style = MaterialTheme.typography.labelSmall,
                    color = ExpenseRed,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { CutePrimaryButton(onClick = onConfirm) { Text("确认删除") } },
        dismissButton = { CuteOutlinedButton(onClick = onDismiss) { Text("再想想") } }
    )
}
