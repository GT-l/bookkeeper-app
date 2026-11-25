package com.azu.bookkeeper.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.azu.bookkeeper.R
import com.azu.bookkeeper.ui.account.AccountEditScreen
import com.azu.bookkeeper.ui.account.AccountListScreen
import com.azu.bookkeeper.ui.backup.BackupScreen
import com.azu.bookkeeper.ui.bill.BillDetailScreen
import com.azu.bookkeeper.ui.bill.BillEditScreen
import com.azu.bookkeeper.ui.book.BookManageScreen
import com.azu.bookkeeper.ui.budget.BudgetScreen
import com.azu.bookkeeper.ui.category.CategoryManageScreen
import com.azu.bookkeeper.ui.category.TagManageScreen
import com.azu.bookkeeper.ui.export.ExportScreen
import com.azu.bookkeeper.ui.home.HomeScreen
import com.azu.bookkeeper.ui.importflow.ImportWizardScreen
import com.azu.bookkeeper.ui.search.SearchScreen
import com.azu.bookkeeper.ui.stats.StatisticsScreen
import com.azu.bookkeeper.ui.settings.AutomationSettingsScreen
import com.azu.bookkeeper.ui.settings.SettingsScreen
import com.azu.bookkeeper.ui.unlock.AppLockScreen

/** 底部导航的四个主入口；仅指向既有路由，不新增业务逻辑。 */
private data class BottomTab(val route: String, val label: String, val iconRes: Int)

private val bottomTabs = listOf(
    BottomTab(Screen.Home.route, "首页", R.drawable.ic_tab_home),
    BottomTab(Screen.Budget.route, "预算", R.drawable.ic_tab_budget),
    BottomTab(Screen.Statistics.route, "统计", R.drawable.ic_tab_stats),
    BottomTab(Screen.Settings.route, "设置", R.drawable.ic_tab_settings)
)

/** 可爱风底部栏：奶油白容器 + 浅粉胶囊指示条 + 主色图标 tint。 */
@Composable
private fun CuteBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp
    ) {
        bottomTabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab.route) },
                icon = {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = tab.label,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * 全量路由接入（QA F-14）：19 条路由全部指向真实 Screen，
 * Import / Statistics / Export / Backup / AutomationSettings 已由 T4/T5/T7 落地，无占位。
 * BillEdit 的可选 quickPrefill JSON 由 Screen.billEdit(...) 生成，ViewModel 从 SavedStateHandle 读取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 各页面自身已有 Scaffold 处理状态栏 inset；此处归零避免顶部留白翻倍。
        // 底部导航栏自身仍会消费系统导航栏 inset。
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        bottomBar = {
            CuteBottomBar(currentRoute = currentRoute) { route ->
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) { HomeScreen(navController) }

            composable(
                route = Screen.BillEdit.route,
                arguments = listOf(
                    navArgument(Screen.ARG_BILL_ID) {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument(Screen.ARG_QUICK_PREFILL) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { BillEditScreen(navController) }

            composable(
                route = Screen.BillDetail.route,
                arguments = listOf(navArgument(Screen.ARG_BILL_ID) { type = NavType.LongType })
            ) { BillDetailScreen(navController) }

            composable(Screen.Books.route) { BookManageScreen() }
            composable(Screen.Categories.route) { CategoryManageScreen() }
            composable(Screen.Tags.route) { TagManageScreen() }
            composable(Screen.Accounts.route) { AccountListScreen(navController) }

            composable(
                route = Screen.AccountEdit.route,
                arguments = listOf(navArgument(Screen.ARG_ACCOUNT_ID) { type = NavType.LongType })
            ) { AccountEditScreen(navController) }

            composable(Screen.Budget.route) { BudgetScreen() }
            composable(Screen.Search.route) { SearchScreen(navController) }
            composable(Screen.Unlock.route) { AppLockScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen(navController) }

            composable(Screen.Import.route) { ImportWizardScreen() }
            composable(Screen.Statistics.route) { StatisticsScreen(navController) }
            composable(Screen.Export.route) { ExportScreen() }
            composable(Screen.Backup.route) { BackupScreen() }
            composable(Screen.AutomationSettings.route) { AutomationSettingsScreen(navController) }
        }
    }
}
