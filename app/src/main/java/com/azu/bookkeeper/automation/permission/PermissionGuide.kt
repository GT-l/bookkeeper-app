package com.azu.bookkeeper.automation.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.azu.bookkeeper.automation.service.PaymentAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 权限状态与设置页跳转（主架构 §5.5）：每个权限缺失都有明确引导，不静默失败。 */
@Singleton
class PermissionGuide @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun isAccessibilityEnabled(): Boolean {
        val enabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        } catch (_: Settings.SettingNotFoundException) {
            0
        }
        if (enabled == 0) return false
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return services.split(':').any {
            it.contains(context.packageName) &&
                it.contains(PaymentAccessibilityService::class.simpleName.orEmpty(), ignoreCase = true)
        }
    }

    /**
     * 服务是否真的连上过（心跳）。部分 ROM 会把系统开关显示成开、但服务已被杀掉，
     * 或反过来在用户返回后自动关掉服务。设置页用这个给用户更准确的提示。
     */
    fun accessibilityServiceEverConnected(): Boolean = runCatching {
        context.getSharedPreferences(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.PREFS,
            Context.MODE_PRIVATE
        ).getLong(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_LAST_CONNECTED,
            0L
        ) > 0L
    }.getOrDefault(false)

    fun isNotificationListenerEnabled(): Boolean {
        val listeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return listeners.contains(context.packageName)
    }

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 短信识别需 READ_SMS（读取）+ RECEIVE_SMS（接收广播）两项同时授予（QA F-13）。
     * Manifest 的 receiver 也声明了 RECEIVE_SMS 权限保护，缺一即视为未授权，避免开关打开却收不到广播。
     */
    fun hasSmsPermission(): Boolean = SMS_RUNTIME_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // 注意：这些 Intent 不再带 FLAG_ACTIVITY_NEW_TASK。
    // 之前带这个 flag 从 Activity 启动系统设置会新建 task，用户授权完按返回时
    // 常常直接回到桌面，表现为「每开一个权限 App 就退出一次」。
    // 由 Activity context（ActivityResultLauncher）启动即可留在原 task，返回原页面。

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun overlaySettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /** 短信权限撤销：跳应用详情页，文案说明「关闭后其他识别方式继续工作」。 */
    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    companion object {
        /** 设置页 ActivityResultContracts.RequestMultiplePermissions 使用，与 hasSmsPermission 同源。 */
        val SMS_RUNTIME_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )
    }
}
