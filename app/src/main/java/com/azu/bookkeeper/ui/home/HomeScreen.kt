package com.azu.bookkeeper.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.azu.bookkeeper.ui.navigation.Screen
import com.azu.bookkeeper.ui.common.EmptyState

/** 首页：账本切换 + 账单列表 + 快捷入口导航。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bookMenuExpanded by remember { mutableStateOf(false) }
    val currentBook = state.books.firstOrNull { it.bookId == state.bookId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Button(onClick = { bookMenuExpanded = true }) {
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
            FloatingActionButton(onClick = { navController.navigate(Screen.billEdit()) }) {
                Text("+")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TextButton(onClick = { navController.navigate(Screen.Accounts.route) }) { Text("资产") }
                TextButton(onClick = { navController.navigate(Screen.Budget.route) }) { Text("预算") }
                TextButton(onClick = { navController.navigate(Screen.Search.route) }) { Text("搜索") }
                TextButton(onClick = { navController.navigate(Screen.Books.route) }) { Text("账本") }
                TextButton(onClick = { navController.navigate(Screen.Settings.route) }) { Text("设置") }
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
