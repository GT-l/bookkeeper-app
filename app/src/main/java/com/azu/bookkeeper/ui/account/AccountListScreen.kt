package com.azu.bookkeeper.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.theme.ExpenseRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(
    navController: NavController,
    viewModel: AccountListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var calibrating by remember { mutableStateOf<Account?>(null) }
    var actualText by remember { mutableStateOf("") }

    Scaffold(
        topBar = { AppTopBar("资产账户") },
        floatingActionButton = {
            // 醒目的可爱风「+」悬浮按钮：创建账户的唯一入口，避免被误认为普通小按钮而找不到
            FloatingActionButton(
                onClick = { navController.navigate(Screen.accountEdit(0L)) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fab_add),
                    contentDescription = "新增账户"
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("净资产：${state.netAssetText}", style = MaterialTheme.typography.titleLarge)
            Text(
                "信用卡负债：${state.liabilityText}",
                color = ExpenseRed,
                style = MaterialTheme.typography.bodyMedium
            )
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (state.accounts.isEmpty()) {
                // 空态引导：告诉用户账户从这里创建
                EmptyState(message = "还没有账户，点右下角 + 添加你的银行卡 / 微信 / 支付宝钱包")
            } else {
                LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(items = state.accounts, key = { it.accountId }) { account ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${account.name} · ${account.type.label}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "初始 ${Money.format(account.initialBalanceFen)} · 当前 ${Money.format(account.balanceFen)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        CuteOutlinedButton(onClick = {
                            calibrating = account
                            actualText = Money.format(account.balanceFen, withSymbol = false)
                        }) { Text("校准") }
                        CuteOutlinedButton(
                            onClick = { navController.navigate(Screen.accountEdit(account.accountId)) },
                            modifier = Modifier.padding(start = 6.dp)
                        ) { Text("编辑") }
                    }
                    Divider()
                }
                }
            }
        }
    }

    calibrating?.let { account ->
        AlertDialog(
            onDismissRequest = { calibrating = null },
            title = { Text("校准「${account.name}」余额") },
            text = {
                Column {
                    Text("输入实际余额，系统自动生成一条不计入收支的余额调整账单。")
                    OutlinedTextField(
                        value = actualText,
                        onValueChange = { actualText = it },
                        label = { Text("实际余额（元）") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.calibrate(account.accountId, actualText)
                    calibrating = null
                }) { Text("确认") }
            },
            dismissButton = { OutlinedButton(onClick = { calibrating = null }) { Text("取消") } }
        )
    }
}
