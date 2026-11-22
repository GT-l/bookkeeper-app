package com.azu.bookkeeper.ui.bill

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberLauncherForActivityResult
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CycleType
import com.azu.bookkeeper.ui.widget.QuickCaptureWidget
import kotlinx.coroutines.launch

/** 记账 / 编辑表单；quickPrefill 已在 ViewModel 中回填。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillEditScreen(
    navController: NavController,
    viewModel: BillEditViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var categoryExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var toAccountExpanded by remember { mutableStateOf(false) }
    var bookExpanded by remember { mutableStateOf(false) }
    var cycleExpanded by remember { mutableStateOf(false) }
    var timeText by remember(state.billTime) { mutableStateOf(DateTimeUtils.format(state.billTime)) }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.addImage(uri.toString()) }

    // 任何离开路径都解除补记提醒抑制；不写库。
    DisposableEffect(Unit) {
        onDispose { viewModel.onCancel() }
    }

    // 幂等短路：来源事件已终态时不写库，直接提示并返回。
    LaunchedEffect(state.duplicateHandled) {
        if (state.duplicateHandled) {
            Toast.makeText(context, "该笔已补记，无需重复添加", Toast.LENGTH_SHORT).show()
            navController.popBackStack()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (state.billId > 0L) "编辑账单" else "记一笔") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            // 类型
            Row(modifier = Modifier.selectableGroup()) {
                BillType.entries.forEach { type ->
                    FilterChip(
                        selected = state.billType == type,
                        onClick = { viewModel.setBillType(type) },
                        label = { Text(type.label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::setAmount,
                label = { Text("金额（元）") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            // 账本
            OutlinedButton(onClick = { bookExpanded = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("账本：${state.books.firstOrNull { it.bookId == state.bookId }?.name ?: "默认"}")
            }
            DropdownMenu(expanded = bookExpanded, onDismissRequest = { bookExpanded = false }) {
                state.books.forEach { book ->
                    DropdownMenuItem(
                        text = { Text(book.name) },
                        onClick = { viewModel.setBook(book.bookId); bookExpanded = false }
                    )
                }
            }

            // 分类
            OutlinedButton(onClick = { categoryExpanded = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("分类：${state.categories.firstOrNull { it.categoryId == state.categoryId }?.name ?: "未选择"}")
            }
            DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                state.categories.forEach { category ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (category.parentId == 0L) category.name else "  └ ${category.name}"
                            )
                        },
                        onClick = { viewModel.setCategory(category.categoryId); categoryExpanded = false }
                    )
                }
            }

            // 账户（转账时显示转入账户）
            OutlinedButton(onClick = { accountExpanded = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("${if (state.billType == BillType.TRANSFER) "转出" else "账户"}：${state.accounts.firstOrNull { it.accountId == state.accountFrom }?.name ?: "未选择"}")
            }
            DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                state.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text("${account.name}（余额 ${com.azu.bookkeeper.core.Money.format(account.balanceFen)}）") },
                        onClick = { viewModel.setAccountFrom(account.accountId); accountExpanded = false }
                    )
                }
            }
            if (state.billType == BillType.TRANSFER) {
                OutlinedButton(onClick = { toAccountExpanded = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("转入：${state.accounts.firstOrNull { it.accountId == state.accountTo }?.name ?: "未选择"}")
                }
                DropdownMenu(expanded = toAccountExpanded, onDismissRequest = { toAccountExpanded = false }) {
                    state.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = { viewModel.setAccountTo(account.accountId); toAccountExpanded = false }
                        )
                    }
                }
            }

            // 时间（支持多格式文本解析）
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("时间（yyyy-MM-dd HH:mm）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { timeText = DateTimeUtils.format(DateTimeUtils.nowMillis()) }) {
                    Text("现在")
                }
                OutlinedButton(
                    onClick = {
                        val parsed = DateTimeUtils.parseFlexible(timeText)
                        if (parsed != null) viewModel.setBillTime(parsed)
                        else Toast.makeText(context, "时间格式无法识别", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("应用时间") }
            }

            // 标签（最多 10 个）
            Text("标签", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                state.tags.forEach { tag ->
                    FilterChip(
                        selected = tag.tagId in state.tagIds,
                        onClick = { viewModel.toggleTag(tag.tagId) },
                        label = { Text(tag.name) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            // 图片（URI 字符串作为索引，真实加密落盘由 T4/相册选择器完善）
            Row(modifier = Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { imagePicker.launch("image/*") }) { Text("添加图片") }
                Text(
                    text = "已选 ${state.imagePaths.size}/9",
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            state.imagePaths.forEach { path ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = path.take(48), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.removeImage(path) }) { Text("移除") }
                }
            }

            // 周期
            OutlinedButton(onClick = { cycleExpanded = true }, modifier = Modifier.padding(top = 12.dp)) {
                Text("周期：${state.cycleType.label}")
            }
            DropdownMenu(expanded = cycleExpanded, onDismissRequest = { cycleExpanded = false }) {
                CycleType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = { viewModel.setCycle(type); cycleExpanded = false }
                    )
                }
            }

            OutlinedTextField(
                value = state.remark,
                onValueChange = viewModel::setRemark,
                label = { Text("备注 / 商家") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    enabled = !state.isSaving,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = {
                        DateTimeUtils.parseFlexible(timeText)?.let(viewModel::setBillTime)
                        viewModel.save { _ ->
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                            scope.launch { runCatching { QuickCaptureWidget.refresh(context) } }
                            navController.popBackStack()
                        }
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) { Text(if (state.isSaving) "保存中…" else "保存") }
            }
        }
    }
}
