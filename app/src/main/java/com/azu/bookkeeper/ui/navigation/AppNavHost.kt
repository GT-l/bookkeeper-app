package com.azu.bookkeeper.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
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

/**
 * 全量路由接入（QA F-14）：19 条路由全部指向真实 Screen，
 * Import / Statistics / Export / Backup / AutomationSettings 已由 T4/T5/T7 落地，无占位。
 * BillEdit 的可选 quickPrefill JSON 由 Screen.billEdit(...) 生成，ViewModel 从 SavedStateHandle 读取。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route
) {
    NavHost(navController = navController, startDestination = startDestination) {
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
