package com.azu.bookkeeper.ui.navigation

import android.net.Uri
import com.azu.bookkeeper.domain.model.BillQuickPrefill
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 所有路由集中定义（sealed class），参数占位符与常量统一在此维护。 */
sealed class Screen(val route: String) {

    data object Home : Screen("home")

    /** billId=0 表示新增；可选的 JSON 查询参数承载快捷入口预填。 */
    data object BillEdit : Screen("bill_edit/{${Screen.ARG_BILL_ID}}?${Screen.ARG_QUICK_PREFILL}={${Screen.ARG_QUICK_PREFILL}}")

    data object BillDetail : Screen("bill_detail/{${Screen.ARG_BILL_ID}}")
    data object Books : Screen("books")
    data object Categories : Screen("categories")
    data object Tags : Screen("tags")
    data object Accounts : Screen("accounts")
    data object AccountEdit : Screen("account_edit/{${Screen.ARG_ACCOUNT_ID}}")
    data object Budget : Screen("budget")
    data object Search : Screen("search")
    data object Unlock : Screen("unlock")
    data object Import : Screen("import")
    data object Statistics : Screen("statistics")
    data object Export : Screen("export")
    data object Backup : Screen("backup")
    /** 「我的」：记账总结 + 各功能入口，底部第三个 tab。 */
    data object Profile : Screen("profile")

    data object Settings : Screen("settings")

    /** 存储排查：扫描历史遗留的数据库 / 备份 / 日志，交给用户决定保留还是删除。 */
    data object StorageAudit : Screen("storage_audit")

    /** 关于与更新介绍。 */
    data object About : Screen("about")
    data object AutomationSettings : Screen("automation_settings")
    data object NotificationSettings : Screen("notification_settings")
    data object QuickGestureSettings : Screen("quick_gesture_settings")

    /** 识屏自检与失败原因诊断。 */
    data object CaptureDiagnostics : Screen("capture_diagnostics")

    companion object {
        const val ARG_BILL_ID = "billId"
        const val ARG_ACCOUNT_ID = "accountId"
        const val ARG_QUICK_PREFILL = "quickPrefill"

        private val routeJson = Json { ignoreUnknownKeys = true }

        fun billEdit(billId: Long = 0L, prefill: BillQuickPrefill? = null): String {
            val base = "bill_edit/$billId"
            return if (prefill == null) {
                base
            } else {
                "$base?$ARG_QUICK_PREFILL=${Uri.encode(routeJson.encodeToString(prefill))}"
            }
        }

        fun billDetail(billId: Long): String = "bill_detail/$billId"

        fun accountEdit(accountId: Long): String = "account_edit/$accountId"

        fun parsePrefill(raw: String?): BillQuickPrefill? {
            if (raw.isNullOrBlank() || raw == "null") return null
            return runCatching {
                routeJson.decodeFromString<BillQuickPrefill>(Uri.decode(raw))
            }.getOrNull()
        }
    }
}
