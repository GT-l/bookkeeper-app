package com.azu.bookkeeper.automation.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 电池白名单 + 厂商自启引导（主架构 §5.4）。
 * 全部需用户手动同意，代码不能自授；强杀自启做不到，设置页固定文案如实告知。
 */
@Singleton
class BatteryOptimizationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun isIgnoringBatteryOptimizations(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** 系统电池白名单申请（不可代码自授，需弹系统对话框用户同意）。 */
    fun requestIgnoreOptimizationsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    /** 按厂商跳自启管理页，机型路径差异大；失败由调用方兜底到应用详情页。 */
    fun manufacturerAutoStartIntent(): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                Intent("miui.intent.action.APP_AUTOSTART_MANAGER").setPackage("com.miui.securitycenter")
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            manufacturer.contains("oppo") ->
                Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            manufacturer.contains("vivo") ->
                Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            else -> return null
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
