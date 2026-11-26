package com.azu.bookkeeper.ui.account

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen

/**
 * 资产账户。
 *
 * 上一版是一行行文字 + 「校准 / 编辑」两个按钮，看不出哪些是负债、总资产在哪。
 * 这一版把净资产做成顶部渐变卡，每个账户一张小卡：emoji + 名称类型 + 余额（负数标红）。
 */
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar("资产账户", subtitle = "余额由账单自动汇总") },
        floatingActionButton = {
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
                Icon(painter = painterResource(R.drawable.ic_fab_add), contentDescription = "新增账户")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AssetHero(state)
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            if (state.accounts.isEmpty()) {
                EmptyState(message = "还没有账户，点右下角 + 添加银行卡 / 微信 / 支付宝")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = state.accounts, key = { it.accountId }) { account ->
                        AccountCard(
                            account = account,
                            onCalibrate = {
                                calibrating = account
                                actualText = Money.format(account.balanceFen, withSymbol = false)
                            },
                            onEdit = { navController.navigate(Screen.accountEdit(account.accountId)) }
                        )
                    }
                }
            }
        }
    }

    calibrating?.let { account ->
        AlertDialog(
            onDismissRequest = { calibrating = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("校准「${account.name}」") },
            text = {
                Column {
                    Text(
                        "输入实际余额，系统会生成一条不计入收支的余额调整账单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CuteTextField(
                        value = actualText,
                        onValueChange = { actualText = it },
                        label = "实际余额（元）",
                        placeholder = "例如 1200.50",
                        leadingEmoji = "💰",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                }
            },
            confirmButton = {
                CutePrimaryButton(onClick = {
                    viewModel.calibrate(account.accountId, actualText)
                    calibrating = null
                }) { Text("确认") }
            },
            dismissButton = { CuteOutlinedButton(onClick = { calibrating = null }) { Text("取消") } }
        )
    }
}

/** 净资产主卡：净资产大字 + 负债（信用卡为负）单独一行。 */
@Composable
private fun AssetHero(state: AccountListViewModel.UiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                )
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            Text(
                text = "净资产",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
            )
            Text(
                text = state.netAssetText,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = "信用卡负债 ${state.liabilityText} · 共 ${state.accounts.size} 个账户",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun AccountCard(account: Account, onCalibrate: () -> Unit, onEdit: () -> Unit) {
    val negative = account.balanceFen < 0L
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
                    Text(text = account.typeEmoji(account.icon), fontSize = 17.sp)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = account.type.label +
                            if (account.enabled) "" else " · 已停用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = Money.format(account.balanceFen),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (negative) ExpenseRed else IncomeGreen
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CuteOutlinedButton(onClick = onCalibrate, modifier = Modifier.weight(1f)) {
                    Text("校准余额", style = MaterialTheme.typography.labelMedium)
                }
                CuteOutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text("编辑", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 账户类型 → emoji。用户自定义图标优先。 */
private fun Account.typeEmoji(custom: String): String {
    if (custom.isNotBlank()) return custom
    return when (this.type) {
        com.azu.bookkeeper.domain.model.AccountType.CASH -> "💵"
        com.azu.bookkeeper.domain.model.AccountType.WECHAT -> "💬"
        com.azu.bookkeeper.domain.model.AccountType.ALIPAY -> "🅰️"
        com.azu.bookkeeper.domain.model.AccountType.DEBIT_CARD -> "🏦"
        com.azu.bookkeeper.domain.model.AccountType.CREDIT_CARD -> "💳"
        com.azu.bookkeeper.domain.model.AccountType.WEALTH -> "📈"
        com.azu.bookkeeper.domain.model.AccountType.VIRTUAL_WALLET -> "👛"
        com.azu.bookkeeper.domain.model.AccountType.CUSTOM -> "🏷️"
    }
}
