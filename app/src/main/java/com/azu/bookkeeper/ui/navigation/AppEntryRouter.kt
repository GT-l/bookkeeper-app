package com.azu.bookkeeper.ui.navigation

import android.content.Context
import android.content.Intent
import com.azu.bookkeeper.MainActivity

/**
 * 系统入口（常驻通知等）→ MainActivity 的「落地页」协议。
 *
 * 与 [com.azu.bookkeeper.ui.quick.QuickCaptureLauncher] 并列：后者负责「打开记账表单」，
 * 这里负责「打开某个普通页面」。两者都用 extra 传路由字符串，MainActivity 冷启动时
 * 作为 startDestination、热启动（singleTop → onNewIntent）时通过事件转发给 NavHost，
 * 因此不需要在 manifest 里再声明一套 deep link。
 */
object AppEntryRouter {

    /** 目标路由 extra；值为 [Screen] 中已存在的 route 字符串。 */
    const val EXTRA_START_DESTINATION = "com.azu.bookkeeper.extra.START_DESTINATION"

    /** 允许外部入口跳转的白名单，避免任意字符串进入 NavHost。 */
    private val allowedRoutes: Set<String> = setOf(
        Screen.Home.route,
        Screen.Settings.route,
        Screen.AutomationSettings.route,
        Screen.Budget.route
    )

    /** 常驻通知点击落地页：自动化设置页（用户可以在这里关掉常驻通知的来源开关）。 */
    fun openAutomationSettingsIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            // 冷启动需要 NEW_TASK；SINGLE_TOP + CLEAR_TOP 让热启动走 onNewIntent 而不重建页面。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_START_DESTINATION, Screen.AutomationSettings.route)
        }

    /** 解析 Intent 中的落地页；非法或缺失返回 null（调用方回落到首页）。 */
    fun resolve(intent: Intent?): String? {
        val route = intent?.getStringExtra(EXTRA_START_DESTINATION) ?: return null
        return route.takeIf { it in allowedRoutes }
    }
}
