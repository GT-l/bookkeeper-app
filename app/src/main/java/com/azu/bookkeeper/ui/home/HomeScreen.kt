package com.azu.bookkeeper.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.ui.common.ConfirmDialog
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.navigation.Screen

/**
 * 首页。
 *
 * 操作逻辑这轮理顺了三处：
 * 1. 顶栏：左边账本名（点击就切换，带 ▾ 暗示可点），右边搜索——原来账本是个粉色大按钮，占地方还像主操作；
 * 2. 概览卡：整张卡可点，进统计报表；原来点它没反应，用户只能去底部 tab 找；
 * 3. 记一笔：FAB 直接打开表单（带金额键盘），不再先落一条 0 元占位再让你去详情页补——
 *    那条路径会留下空账单，还得靠返回时清理。极简记账仍保留给快捷方式/通知用。
 */
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

    // 今日小结：直接从已加载的分组里取今天那一组，不额外查库
    val todayStart = DateTimeUtils.startOfDay(DateTimeUtils.nowMillis())
    val todayGroup = groups.firstOrNull { it.dayStart == todayStart }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { bookMenuExpanded = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentBook?.name ?: "选择账本",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = " ▾",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = bookMenuExpanded,
                            onDismissRequest = { bookMenuExpanded = false },
                            modifier = Modifier.width(200.dp)
                        ) {
                            state.books.forEach { book ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (book.bookId == state.bookId) "✓ ${book.name}" else book.name,
                                            color = if (book.bookId == state.bookId) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    },
                                    onClick = {
                                        viewModel.switchBook(book.bookId)
                                        bookMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { navController.navigate(Screen.Search.route) }) {
                        Text(text = "🔍", fontSize = 17.sp)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // 直接进表单：金额框自动聚焦，写完就存；不再产生 0 元占位账单
                    navController.navigate(Screen.billEdit(0L))
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
            OverviewCard(
                expenseFen = totalExpense,
                incomeFen = totalIncome,
                todayExpenseFen = todayGroup?.expenseFen ?: 0L,
                todayCount = todayGroup?.bills?.size ?: 0,
                onClick = { navController.navigate(Screen.Statistics.route) }
            )

            ShortcutRow(
                onAccounts = { navController.navigate(Screen.Accounts.route) },
                onBudget = { navController.navigate(Screen.Budget.route) },
                onBooks = { navController.navigate(Screen.Books.route) },
                onImport = { navController.navigate(Screen.Import.route) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "账单明细",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (state.bills.isNotEmpty()) {
                    Text(
                        text = "已显示 ${groups.sumOf { it.bills.size }} 笔",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }

            if (state.bills.isEmpty()) {
                EmptyState(message = "还没有账单，点右下角 + 记第一笔")
            } else {
                BillList(
                    groups = state.dayGroups,
                    onBillClick = { id -> navController.navigate(Screen.billDetail(id)) },
                    onSwipeDelete = { id -> pendingDelete = id },
                    onLoadMore = { viewModel.loadMore() },
                    hasMore = state.visibleCount < state.bills.size,
                    modifier = Modifier.weight(1f)
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

/**
 * 概览卡：支出是主角（渐变底 + 大字），收入/结余退到第二行，最底下一行「今天」。
 * 整卡可点进统计——这是原来最容易被误认为只是展示的一块。
 */
@Composable
private fun OverviewCard(
    expenseFen: Long,
    incomeFen: Long,
    todayExpenseFen: Long,
    todayCount: Int,
    onClick: () -> Unit
) {
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
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本月支出",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "看统计 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
            }
            Text(
                text = Money.format(expenseFen),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "收入",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                    )
                    Text(
                        text = Money.format(incomeFen),
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
                        text = Money.format(incomeFen - expenseFen),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Text(
                text = if (todayCount > 0) {
                    "今天 · 支出 ${Money.format(todayExpenseFen)} · $todayCount 笔"
                } else {
                    "今天还没有记账"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** 四个快捷入口：图标 + 文字，方块圆角，一眼能认。 */
@Composable
private fun ShortcutRow(
    onAccounts: () -> Unit,
    onBudget: () -> Unit,
    onBooks: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShortcutItem("💳", "资产", onAccounts, Modifier.weight(1f))
        ShortcutItem("🎯", "预算", onBudget, Modifier.weight(1f))
        ShortcutItem("📚", "账本", onBooks, Modifier.weight(1f))
        ShortcutItem("📥", "导入", onImport, Modifier.weight(1f))
    }
}

@Composable
private fun ShortcutItem(
    emoji: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 14.sp)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
