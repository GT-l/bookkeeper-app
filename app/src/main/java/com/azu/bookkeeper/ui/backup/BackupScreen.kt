package com.azu.bookkeeper.ui.backup

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.ui.common.ConfirmDialog
import java.io.File

/** 备份与恢复页面：加密备份 + 恢复二次确认 + 自动备份。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context: Context = LocalContext.current
    var pendingDeletePath by remember { mutableStateOf<String?>(null) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onRestorePicked(uri.toString())
    }

    Scaffold(topBar = { AppTopBar("备份与恢复") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("自动备份（每天 00:10）", modifier = Modifier.weight(1f))
                Switch(checked = state.autoBackupEnabled, onCheckedChange = { viewModel.setAutoBackup(it) })
            }
            state.lastBackupAt.takeIf { it > 0 }?.let {
                Text("上次备份：${DateTimeUtils.format(it)}", style = MaterialTheme.typography.labelMedium)
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Button(onClick = { viewModel.createBackup() }, modifier = Modifier.weight(1f)) { Text("立即备份") }
                OutlinedButton(
                    onClick = { restorePicker.launch("*/*") },
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) { Text("选择备份恢复") }
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
            }

            Text("本地备份（${state.backups.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
            LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
                items(items = state.backups, key = { it.path }) { backup ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(backup.fileName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${backup.sizeBytes / 1024} KB · ${DateTimeUtils.format(backup.createdAt)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { viewModel.onRestorePicked(backup.path) }) { Text("从此备份恢复") }
                                TextButton(onClick = { share(context, backup.path) }) { Text("分享") }
                                TextButton(onClick = { pendingDeletePath = backup.path }) { Text("删除") }
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
            message = "恢复将覆盖当前全部账本、账单、分类与账户数据（文件名：${state.pendingRestoreName.ifBlank { path }}）。此操作不可撤销，建议先创建一份当前备份。",
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
