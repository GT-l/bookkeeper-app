package com.azu.bookkeeper.ui.bill

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.ui.common.ConfirmDialog
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.LoadingIndicator
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen
import com.azu.bookkeeper.ui.theme.TransferGray

/** 账单详情：点一下金额/备注/分类/账户/时间任一行即可就地编辑或跳转管理页；保留查看、复制、删除。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BillDetailScreen(
    navController: NavController,
    viewModel: BillDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    // 统一离开动作：先让 VM 清理「进去什么都没改」的 0 值占位账单，再回退栈。
    // 一键建单会先落一条 0 分占位账单，用户直接返回时必须把它删掉，否则首页残留空账单。
    val leave: () -> Unit = remember(viewModel, navController) {
        {
            viewModel.onLeave()
            navController.popBackStack()
        }
    }

    // 系统返回键 / 手势返回与顶部返回按钮走同一套清理逻辑
    BackHandler(enabled = true) { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单详情") },
                navigationIcon = {
                    // material-icons 未在 app/build.gradle.kts 声明，按约定用文本箭头兜底（不新增依赖）
                    TextButton(onClick = leave) {
                        Text(text = "←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { padding ->
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
                    color = if (bill.amountFen == 0L) MaterialTheme.colorScheme.error else color,
                    style = MaterialTheme.typography.displaySmall
                )
                if (bill.amountFen == 0L) {
                    Text(
                        text = "点一下金额就能改数字",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    text = "点一下下面条目即可修改；账户 / 分类还能跳去管理页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Divider(modifier = Modifier.padding(vertical = 12.dp))

                val categoryName = state.categories
                    .firstOrNull { it.categoryId == bill.categoryId }?.name
                    ?: if (bill.categoryId == 0L) "未设置分类" else bill.categoryId.toString()
                // 账户非必须：accountFrom=0 表示「还没选账户」，不能显示成 "0"
                val accountName = state.accounts
                    .firstOrNull { it.accountId == bill.accountFrom }?.name
                    ?: if (bill.accountFrom <= 0L) "未设置账户" else bill.accountFrom.toString()

                EditableInfoRow(
                    label = "金额",
                    value = Money.format(bill.amountFen),
                    hint = if (bill.amountFen == 0L) "还没写金额" else null,
                    onClick = { viewModel.startEdit(EditField.Amount) }
                )
                EditableInfoRow(
                    label = "分类",
                    value = categoryName,
                    onClick = { viewModel.startEdit(EditField.Category) }
                )
                EditableInfoRow(
                    label = "账户",
                    value = accountName,
                    hint = if (bill.accountFrom <= 0L) "可以先不选，之后再补" else null,
                    onClick = { viewModel.startEdit(EditField.Account) }
                )
                EditableInfoRow(
                    label = "时间",
                    value = DateTimeUtils.format(bill.billTime, "yyyy-MM-dd HH:mm:ss"),
                    onClick = { viewModel.startEdit(EditField.Time) }
                )
                EditableInfoRow(
                    label = "备注",
                    value = bill.remark.ifBlank { "—" },
                    onClick = { viewModel.startEdit(EditField.Remark) }
                )

                InfoRow("类型", bill.billType.label)
                InfoRow("来源", bill.source.label)
                InfoRow("标签", bill.tagIds.joinToString().ifBlank { "—" })
                InfoRow("图片", "${bill.imagePaths.size} 张")
                InfoRow("周期", bill.cycleType.label)
                if (bill.isTransfer.not() && bill.ocrRawText.isNotBlank()) {
                    InfoRow("识别原文", bill.ocrRawText)
                }
                InfoRow("校对状态", bill.verifyStatus.label)

                Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    CutePrimaryButton(onClick = { navController.navigate(Screen.billEdit(bill.billId)) }) {
                        Text("编辑")
                    }
                    CuteOutlinedButton(
                        onClick = { viewModel.copy { id -> navController.navigate(Screen.billDetail(id)) } },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("复制") }
                    CuteOutlinedButton(
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

    // ---------- 单字段就地编辑 BottomSheet ----------

    when (val field = state.editingField) {
        EditField.Amount -> state.bill?.let { bill ->
            AmountEditSheet(
                initialText = Money.formatPlain(bill.amountFen, withSymbol = false),
                onConfirm = viewModel::updateAmount,
                onDismiss = viewModel::cancelEdit
            )
        }
        EditField.Remark -> state.bill?.let { bill ->
            RemarkEditSheet(
                initialText = bill.remark,
                onConfirm = viewModel::updateRemark,
                onDismiss = viewModel::cancelEdit
            )
        }
        EditField.Category -> CategoryPickerSheet(
            categories = state.categories,
            selectedId = state.bill?.categoryId ?: 0L,
            onSelect = viewModel::updateCategory,
            onDismiss = viewModel::cancelEdit,
            onManage = {
                viewModel.cancelEdit()
                navController.navigate(Screen.Categories.route)
            }
        )
        EditField.Account -> AccountPickerSheet(
            accounts = state.accounts,
            selectedId = state.bill?.accountFrom ?: 0L,
            onSelect = viewModel::updateAccount,
            onDismiss = viewModel::cancelEdit,
            onManage = {
                viewModel.cancelEdit()
                navController.navigate(Screen.Accounts.route)
            }
        )
        EditField.Time -> state.bill?.let { bill ->
            TimeEditSheet(
                initialText = DateTimeUtils.format(bill.billTime, "yyyy-MM-dd HH:mm"),
                onConfirm = viewModel::updateBillTimeText,
                onSetNow = viewModel::updateBillTimeToNow,
                onDismiss = viewModel::cancelEdit
            )
        }
        null -> Unit
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

/** 支持单击就地编辑/跳转的条目行；保留长按同款操作作为习惯兜底。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableInfoRow(
    label: String,
    value: String,
    hint: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------- 各字段编辑器 Sheet ----------

/** 金额编辑：数字键盘文本框，保存经 Money.parse 校验（金额必须大于 0）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmountEditSheet(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "改金额", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("金额（元）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Button(
                onClick = { onConfirm(text) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("保存") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 备注编辑：单行文本框，允许为空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemarkEditSheet(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "改备注", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Button(
                onClick = { onConfirm(text) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("保存") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 分类选择：该账本支出分类列表，点选即保存并关闭；底部可跳分类管理新增。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    categories: List<com.azu.bookkeeper.domain.model.Category>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(text = "改分类", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp).padding(top = 8.dp)) {
                items(categories, key = { it.categoryId }) { category ->
                    TextButton(
                        onClick = { onSelect(category.categoryId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = category.name,
                            color = if (category.categoryId == selectedId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Text("找不到想要的？去分类管理新增 →", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 账户选择：该账本账户列表，点选即保存并关闭；底部可跳资产页新增账户。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPickerSheet(
    accounts: List<com.azu.bookkeeper.domain.model.Account>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(text = "改账户", style = MaterialTheme.typography.titleMedium)
            if (accounts.isEmpty()) {
                // 账户非必须：账本里一个账户都没有时给出明确提示，并保留下方「去资产页新增」入口
                Text(
                    text = "还没有账户",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp).padding(top = 8.dp)) {
                    items(accounts, key = { it.accountId }) { account ->
                        TextButton(
                            onClick = { onSelect(account.accountId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = account.name,
                                color = if (account.accountId == selectedId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Text("去资产页新增 →", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 时间编辑：文本框支持多种常见格式（经 DateTimeUtils.parseFlexible 容错解析），
 * 另提供「改为现在」一键快捷入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEditSheet(
    initialText: String,
    onConfirm: (String) -> Unit,
    onSetNow: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "改时间", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("时间（如 2026-01-01 12:00）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSetNow) { Text("改为现在") }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onConfirm(text) }) { Text("保存") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
