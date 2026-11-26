package com.azu.bookkeeper.ui.backup

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.ConfirmDialog
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.common.SoftCard
import java.io.File

/**
 * 备份与恢复。
 *
 * 顶部一张渐变卡负责「一键备份」，自动备份开关贴着说明；下面的备份列表每条一张卡，
 * 恢复 / 分享 / 删除三个动作在卡内一行，不再散落成 TextButton。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context: Context = LocalContext.current
    var pendingDeletePath by remember { mutableStateOf<String?>(null) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onRestorePicked(uri.toString())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "备份与恢复", subtitle = "本地加密备份，不上传云端") }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                        ),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        text = "把账本打包成一个加密文件",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = state.lastBackupAt.takeIf { it > 0 }?.let {
                            "上次备份：${DateTimeUtils.format(it)}"
                        } ?: "还没有备份过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CutePrimaryButton(
                            onClick = { viewModel.createBackup() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.busy
                        ) {
                            Text(if (state.busy) "备份中…" else "立即备份", maxLines = 1, softWrap = false)
                        }
                        CuteOutlinedButton(
                            onClick = { restorePicker.launch("*/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("从文件恢复", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "每日自动备份", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "每天凌晨自动打一份，只保留最近几份",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoBackupEnabled,
                        onCheckedChange = { viewModel.setAutoBackup(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "本地备份",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${state.backups.size} 份",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.backups.isEmpty()) {
                EmptyState("还没有本地备份，点上面的「立即备份」创建一份")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = state.backups, key = { it.path }) { backup ->
                        SoftCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(text = "🗂", fontSize = 13.sp)
                                    }
                                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(
                                            text = backup.fileName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${backup.sizeBytes / 1024} KB · ${DateTimeUtils.format(backup.createdAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { viewModel.onRestorePicked(backup.path) }) {
                                        Text("恢复", style = MaterialTheme.typography.labelMedium)
                                    }
                                    TextButton(onClick = { share(context, backup.path) }) {
                                        Text("分享", style = MaterialTheme.typography.labelMedium)
                                    }
                                    TextButton(onClick = { pendingDeletePath = backup.path }) {
                                        Text("删除", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingRestorePath?.let { path ->
        ConfirmDialog(
            title = "确认恢复备份",
            message = "恢复将覆盖当前全部账本、账单、分类与账户数据（文件名：${
                state.pendingRestoreName.ifBlank { path }
            }）。此操作不可撤销，建议先创建一份当前备份。",
            confirmText = "确认覆盖恢复",
            onConfirm = { viewModel.confirmRestore() },
            onDismiss = { viewModel.cancelRestore() }
        )
    }

    pendingDeletePath?.let { path ->
        ConfirmDialog(
            title = "删除备份",
            message = "删除后该备份文件不可恢复。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteBackup(path)
                pendingDeletePath = null
            },
            onDismiss = { pendingDeletePath = null }
        )
    }
}

private fun share(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享备份包"))
    }
}
