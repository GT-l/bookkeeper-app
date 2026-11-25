package com.azu.bookkeeper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.data.local.datastore.NotificationDisplayStyles

/** 通知栏显示内容设置：选样式 + 勾选要显示的项目，改动即时生效。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs

    Scaffold(topBar = { TopAppBar(title = { Text("通知栏显示内容") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("通知样式", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                NotificationDisplayStyles.ALL.forEach { style ->
                    OutlinedButton(
                        onClick = { viewModel.setStyle(style) },
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    ) {
                        Text(
                            NotificationDisplayStyles.label(style),
                            color = if (prefs.style == style) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }

            Text("显示项目", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            SettingSwitch("今日支出", prefs.showToday, viewModel::setToday)
            SettingSwitch("本月支出", prefs.showMonth, viewModel::setMonth)
            SettingSwitch("预算剩余", prefs.showBudget, viewModel::setBudget)
            SettingSwitch("最近一笔", prefs.showLastBill, viewModel::setLastBill)
            SettingSwitch("今日笔数", prefs.showCount, viewModel::setCount)
            SettingSwitch("「记一笔」按钮", prefs.showQuickAdd, viewModel::setQuickAdd)
            SettingSwitch("「显示/隐藏悬浮球」按钮", prefs.showBallToggle, viewModel::setBallToggle)

            Text(
                "需打开「启用自动记账」才会出现常驻通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
