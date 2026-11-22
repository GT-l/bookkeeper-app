package com.azu.bookkeeper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.azu.bookkeeper.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bookExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var weekText by remember(state.weekStartDay) { mutableStateOf(state.weekStartDay.toString()) }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("默认账本", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { bookExpanded = true }) {
                Text(state.books.firstOrNull { it.bookId == state.defaultBookId }?.name ?: "未设置")
            }
            DropdownMenu(expanded = bookExpanded, onDismissRequest = { bookExpanded = false }) {
                state.books.forEach { book ->
                    DropdownMenuItem(
                        text = { Text(book.name) },
                        onClick = { viewModel.setDefaultBook(book.bookId); bookExpanded = false }
                    )
                }
            }

            Text("默认账户", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            OutlinedButton(onClick = { accountExpanded = true }) {
                Text(state.accounts.firstOrNull { it.accountId == state.defaultAccountId }?.name ?: "未设置")
            }
            DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                state.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.name) },
                        onClick = { viewModel.setDefaultAccount(account.accountId); accountExpanded = false }
                    )
                }
            }

            OutlinedTextField(
                value = state.currency,
                onValueChange = viewModel::setCurrency,
                label = { Text("货币符号（仅改显示）") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            OutlinedTextField(
                value = state.dateFormat,
                onValueChange = viewModel::setDateFormat,
                label = { Text("日期格式") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = weekText,
                onValueChange = { weekText = it },
                label = { Text("周起始日（1=周一，7=周日）") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedButton(
                onClick = { weekText.toIntOrNull()?.let { viewModel.setWeekStart(it) } },
                modifier = Modifier.padding(top = 6.dp)
            ) { Text("应用周起始日") }

            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = state.appLockEnabled, onCheckedChange = viewModel::setAppLock)
                Text("启用应用锁（退出 5 分钟后自动上锁）")
            }
            if (state.appLockEnabled) {
                OutlinedTextField(
                    value = state.pinDraft,
                    onValueChange = viewModel::setPinDraft,
                    label = { Text("数字密码（至少 4 位）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(onClick = { viewModel.savePin() }, modifier = Modifier.padding(top = 6.dp)) {
                    Text("保存密码")
                }
            }

            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = state.autoBackupEnabled, onCheckedChange = viewModel::setAutoBackup)
                Text("每日自动本地备份")
            }

            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Text("管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            listOf(
                "分类管理" to Screen.Categories.route,
                "标签管理" to Screen.Tags.route,
                "账本管理" to Screen.Books.route,
                "自动化设置（T5）" to Screen.AutomationSettings.route,
                "导入（T4）" to Screen.Import.route,
                "统计（T4）" to Screen.Statistics.route,
                "备份与恢复（T4）" to Screen.Backup.route
            ).forEach { (label, route) ->
                OutlinedButton(
                    onClick = { navController.navigate(route) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Text(label) }
            }
        }
    }
}
