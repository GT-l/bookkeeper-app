package com.azu.bookkeeper.ui.home

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.ui.common.ConfirmDialog
import com.azu.bookkeeper.ui.common.CutePill
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.theme.AmountStyle
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen

/** 首页：账本切换 + 账单概览 + 快捷入口 + 账单列表（右滑/长按删除需确认）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bookMenuExpanded by remember { mutableStateOf(false) }
    // 待删除账单 ID：滑动删除 / 长按删除都会先记录到这里，经确认对话框后再真正删除
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    val currentBook = state.books.firstOrNull { it.bookId == state.bookId }
    val groups = state.dayGroups
    val totalIncome = groups.sumOf { it.incomeFen }
    val totalExpense = groups.sumOf { it.expenseFen }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Button(
                        onClick = { bookMenuExpanded = true },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Text(text = currentBook?.name ?: "选择账本")
                    }
                    DropdownMenu(
                        expanded = bookMenuExpanded,
                        onDismissRequest = { bookMenuExpanded = false },
                        modifier = Modifier.width(200.dp)
                    ) {
                        state.books.forEach { book ->
                            DropdownMenuItem(
                                text = { Text(book.name) },
                                onClick = {
                                    viewModel.switchBook(book.bookId)
                                    bookMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // 极简记账：一键默认建单，成功后直接进详情页让用户长按就地补全
                    viewModel.createInstantBill { id ->
                        navController.navigate(Screen.billDetail(id))
                    }
                },
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
                    contentDescription = "记一笔",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 概览主卡：支出做成视觉主体（渐变底 + 大字），收入/结余退为次级信息。
            // 之前三个数字同等大小，用户一眼看不出「这个月花了多少」。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "本月支出",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                    )
                    Text(
                        text = Money.format(totalExpense),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "收入",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                            )
                            Text(
                                text = Money.format(totalIncome),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "结余",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                            )
                            Text(
                                text = Money.format(totalIncome - totalExpense),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickEntryPill(label = "资产") { navController.navigate(Screen.Accounts.route) }
                QuickEntryPill(label = "预算") { navController.navigate(Screen.Budget.route) }
                QuickEntryPill(label = "搜索") { navController.navigate(Screen.Search.route) }
                QuickEntryPill(label = "账本") { navController.navigate(Screen.Books.route) }
                QuickEntryPill(label = "设置") { navController.navigate(Screen.Settings.route) }
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            if (state.bills.isEmpty()) {
                EmptyState(message = "还没有账单，点右下角 + 一秒记一笔")
            } else {
                BillList(
                    groups = state.dayGroups,
                    onBillClick = { id -> navController.navigate(Screen.billDetail(id)) },
                    onSwipeDelete = { id -> pendingDelete = id },
                    onLoadMore = { viewModel.loadMore() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    pendingDelete?.let { billId ->
        ConfirmDialog(
            title = "删除账单",
            message = "删除后将回滚账户余额，此操作不可恢复。确认删除？",
            confirmText = "确认删除",
            onConfirm = {
                viewModel.deleteBill(billId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** 快捷入口胶囊：浅粉底 + 主色文字，圆形涟漪，替代原来生硬的 TextButton。 */
@Composable
private fun QuickEntryPill(label: String, onClick: () -> Unit) {
    CutePill(
        text = label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
    )
}
