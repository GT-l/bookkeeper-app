package com.azu.bookkeeper.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.navigation.NavController
import com.azu.bookkeeper.domain.model.AccountType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    navController: NavController,
    viewModel: AccountEditViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var typeExpanded by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(if (state.accountId > 0L) "编辑账户" else "新增账户") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("账户名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { typeExpanded = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("类型：${state.type.label}")
            }
            DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                AccountType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label + if (type == AccountType.CREDIT_CARD) "（负债）" else "") },
                        onClick = { viewModel.setType(type); typeExpanded = false }
                    )
                }
            }
            OutlinedTextField(
                value = state.initialBalanceText,
                onValueChange = viewModel::setInitialBalance,
                label = { Text("初始余额（元，信用卡欠款填负数）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            OutlinedTextField(
                value = state.icon,
                onValueChange = viewModel::setIcon,
                label = { Text("图标（可选）") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            OutlinedTextField(
                value = state.remark,
                onValueChange = viewModel::setRemark,
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Checkbox(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                Text("启用（停用后不可选，历史账单保留）")
            }

            // 信用卡账单日 / 还款日：只对该类型有意义，其它类型隐藏，避免干扰
            if (state.type == AccountType.CREDIT_CARD) {
                Text(
                    "账单日与还款日",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                OutlinedTextField(
                    value = state.billDayText,
                    onValueChange = viewModel::setBillDay,
                    label = { Text("账单日（1~28）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = state.dueDayText,
                    onValueChange = viewModel::setDueDay,
                    label = { Text("还款日（1~28）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Checkbox(
                        checked = state.reminderEnabled,
                        onCheckedChange = viewModel::setReminderEnabled
                    )
                    Text("临近还款日时提醒我")
                }
            }
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = { viewModel.save { navController.popBackStack() } },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            ) { Text("保存") }
        }
    }
}
