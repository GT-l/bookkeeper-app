package com.azu.bookkeeper.automation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机 / 升级恢复（主架构 §5.4）：
 * 收到 BOOT_COMPLETED / MY_PACKAGE_REPLACED 且自动化开关开启时拉起前台服务。
 *
 * 限制（如实告知）：Android 3.1 起被系统「强行停止」(force-stop) 后，
 * 静态广播不再触发，本恢复方式无效；需用户手动打开一次 App。设置页固定文案说明。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var automationSettings: AutomationSettingsDataStore

    private val scope = CoroutineScope(SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        scope.launch {
            try {
                if (automationSettings.enabled.first()) {
                    AutoCaptureForegroundService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
