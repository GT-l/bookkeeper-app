package com.azu.bookkeeper.ui.home

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.theme.AmountStyle
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen

/** 首页：账本切换 + 账单概览 + 快捷入口 + 账单列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bookMenuExpanded by remember { mutableStateOf(false) }
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
                        shape = MaterialTheme.shapes.small,
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
                onClick = { navController.navigate(Screen.billEdit()) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "账单概览", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "收入",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = Money.format(totalIncome),
                                style = AmountStyle,
                                color = IncomeGreen
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "支出",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = Money.format(totalExpense),
                                style = AmountStyle,
                                color = ExpenseRed
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TextButton(onClick = { navController.navigate(Screen.Accounts.route) }) {
                    Text("资产", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { navController.navigate(Screen.Budget.route) }) {
                    Text("预算", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { navController.navigate(Screen.Search.route) }) {
                    Text("搜索", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { navController.navigate(Screen.Books.route) }) {
                    Text("账本", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                    Text("设置", color = MaterialTheme.colorScheme.primary)
                }
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            if (state.bills.isEmpty()) {
                EmptyState(message = "还没有账单，点右下角 + 记一笔")
            } else {
                BillList(
                    groups = state.dayGroups,
                    onBillClick = { id -> navController.navigate(Screen.billDetail(id)) },
                    onLoadMore = { viewModel.loadMore() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
