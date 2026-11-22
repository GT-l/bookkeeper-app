package com.azu.bookkeeper.ui.bill

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.ui.common.ConfirmDialog
import com.azu.bookkeeper.ui.common.LoadingIndicator
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen
import com.azu.bookkeeper.ui.theme.TransferGray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailScreen(
    navController: NavController,
    viewModel: BillDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("账单详情") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            val bill = state.bill
            if (bill == null) {
                LoadingIndicator()
            } else {
                val color = when (bill.billType) {
                    BillType.EXPENSE -> ExpenseRed
                    BillType.INCOME -> IncomeGreen
                    BillType.TRANSFER -> TransferGray
                }
                Text(
                    text = "${Money.format(bill.amountFen)}",
                    color = color,
                    style = MaterialTheme.typography.displaySmall
                )
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                InfoRow("类型", bill.billType.label)
                InfoRow("分类 ID", bill.categoryId.toString())
                InfoRow("账户", "转出 ${bill.accountFrom}" + if (bill.isTransfer) " → 转入 ${bill.accountTo}" else "")
                InfoRow("时间", DateTimeUtils.format(bill.billTime, "yyyy-MM-dd HH:mm:ss"))
                InfoRow("备注", bill.remark.ifBlank { "—" })
                InfoRow("来源", bill.source.label)
                InfoRow("标签", bill.tagIds.joinToString().ifBlank { "—" })
                InfoRow("图片", "${bill.imagePaths.size} 张")
                InfoRow("周期", bill.cycleType.label)
                if (bill.isTransfer.not() && bill.ocrRawText.isNotBlank()) {
                    InfoRow("识别原文", bill.ocrRawText)
                }
                InfoRow("校对状态", bill.verifyStatus.label)

                Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Button(onClick = { navController.navigate(Screen.billEdit(bill.billId)) }) {
                        Text("编辑")
                    }
                    OutlinedButton(
                        onClick = { viewModel.copy { id -> navController.navigate(Screen.billDetail(id)) } },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("复制") }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("删除") }
                }
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除账单",
            message = "删除后将回滚账户余额，此操作不可恢复。确认删除？",
            confirmText = "确认删除",
            onConfirm = {
                confirmDelete = false
                viewModel.delete { navController.popBackStack() }
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
