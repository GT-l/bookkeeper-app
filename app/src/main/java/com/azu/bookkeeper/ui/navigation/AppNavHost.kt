@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package com.azu.bookkeeper.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.NavBackStackEntry
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
import com.azu.bookkeeper.ui.settings.CaptureDiagnosticsScreen
import com.azu.bookkeeper.ui.settings.NotificationSettingsScreen
import com.azu.bookkeeper.ui.settings.QuickGestureSettingsScreen
import com.azu.bookkeeper.ui.profile.ProfileScreen
import com.azu.bookkeeper.ui.settings.AboutScreen
import com.azu.bookkeeper.ui.settings.SettingsScreen
import com.azu.bookkeeper.ui.settings.StorageAuditScreen
import com.azu.bookkeeper.ui.unlock.AppLockScreen

/** 底部导航的四个主入口；仅指向既有路由，不新增业务逻辑。 */
private data class BottomTab(val route: String, val label: String, val iconRes: Int)

private val bottomTabs = listOf(
    BottomTab(Screen.Home.route, "首页", R.drawable.ic_tab_home),
    BottomTab(Screen.Budget.route, "预算", R.drawable.ic_tab_budget),
    BottomTab(Screen.Statistics.route, "统计", R.drawable.ic_tab_stats),
    BottomTab(Screen.Profile.route, "我的", R.drawable.ic_tab_settings)
)

/**
 * 页面切换动效。
 *
 * Navigation Compose 的默认值是一整屏 700ms 淡入淡出 —— 点一下要等半秒多才「换完」，
 * 中间那段既没位移也没层次，看起来就像 App 卡了一下。这里按两种场景分别处理：
 *
 * - **二级页面（进 / 退）**：200ms 左右的「小幅横移 + 淡入」，方向与用户手势一致，
 *   位移只有屏幕宽度的 1/8，够表达层级但不拖时间；
 * - **四个主 tab 之间**：只做 120ms 交叉淡入，不横移——tab 是平级切换，横向滑会显得绕路。
 *
 * 所有时长都控制在 220ms 以内，点击到画面开始变化几乎是即时的。
 */
private const val PUSH_MS = 220
private const val POP_MS = 180
private const val TAB_MS = 120

private val pushEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(140)) + slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { it / 8 }
}

private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(140)) + slideInHorizontally(tween(POP_MS, easing = FastOutSlowInEasing)) { -it / 8 }
}

private val pageExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(110))
}

private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(110)) + slideOutHorizontally(tween(POP_MS, easing = FastOutSlowInEasing)) { it / 10 }
}

/** tab 之间：纯交叉淡入，最快也最不打扰。 */
private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(TAB_MS))
}

private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(TAB_MS))
}

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
    // 只有四个主入口显示底部导航。二级页面（详情/编辑/表单/管理）全屏展示，
    // 否则底部三个操作按钮要跟导航栏抢空间，内容也会少一大截。
    val showBottomBar = bottomTabs.any { it.route == currentRoute }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 各页面自身已有 Scaffold 处理状态栏 inset；此处归零避免顶部留白翻倍。
        // 底部导航栏自身仍会消费系统导航栏 inset。
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        bottomBar = {
            // 测试结论：用 AnimatedVisibility 包底栏时，Scaffold 在动画期间仍按底栏高度给
            // NavHost 留 padding，动画结束的那一帧 padding 突然归零，页面高度「二次跳变」，
            // 观感比直接切换更差。转场本身已压到 220ms 内，这里回归即时切换最稳。
            if (showBottomBar) {
                CuteBottomBar(currentRoute = currentRoute) { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                        // 切 tab 时不要堆栈：回到该 tab 已有实例，返回键直接退出而不是逐个回退
                        popUpTo(Screen.Home.route) { saveState = true }
                        restoreState = true
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
            enterTransition = pushEnter,
            exitTransition = pageExit,
            popEnterTransition = popEnter,
            popExitTransition = popExit
        ) {
            // 四个主 tab 覆盖成纯淡入，避免平级切换也横移
            composable(
                route = Screen.Home.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
                popEnterTransition = tabEnter,
                popExitTransition = tabExit
            ) { HomeScreen(navController) }

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

            composable(
                route = Screen.Budget.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
                popEnterTransition = tabEnter,
                popExitTransition = tabExit
            ) { BudgetScreen() }
            composable(Screen.Search.route) { SearchScreen(navController) }
            composable(Screen.Unlock.route) { AppLockScreen(navController) }
            composable(
                route = Screen.Profile.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
                popEnterTransition = tabEnter,
                popExitTransition = tabExit
            ) { ProfileScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen(navController) }
            composable(Screen.StorageAudit.route) { StorageAuditScreen() }
            composable(Screen.About.route) { AboutScreen() }

            composable(Screen.Import.route) { ImportWizardScreen() }
            composable(
                route = Screen.Statistics.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
                popEnterTransition = tabEnter,
                popExitTransition = tabExit
            ) { StatisticsScreen(navController) }
            composable(Screen.Export.route) { ExportScreen() }
            composable(Screen.Backup.route) { BackupScreen() }
            composable(Screen.AutomationSettings.route) { AutomationSettingsScreen(navController) }
            composable(Screen.NotificationSettings.route) { NotificationSettingsScreen() }
            composable(Screen.QuickGestureSettings.route) { QuickGestureSettingsScreen() }
            composable(Screen.CaptureDiagnostics.route) { CaptureDiagnosticsScreen() }
        }
    }
}
