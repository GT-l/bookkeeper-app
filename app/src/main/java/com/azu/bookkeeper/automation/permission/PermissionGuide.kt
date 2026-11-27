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
    fun accessibilityServiceEverConnected(): Boolean = accessibilityLastConnectedAt() > 0L

    /** 最后一次「服务连上」的时间戳（0 表示从未连上）。 */
    fun accessibilityLastConnectedAt(): Long = runCatching {
        context.getSharedPreferences(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.PREFS,
            Context.MODE_PRIVATE
        ).getLong(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_LAST_CONNECTED,
            0L
        )
    }.getOrDefault(0L)

    /** 最后一次「服务被解绑/销毁」的时间戳（判断是否连上后又被 ROM 干掉）。 */
    fun accessibilityLastDisconnectedAt(): Long = runCatching {
        context.getSharedPreferences(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.PREFS,
            Context.MODE_PRIVATE
        ).getLong(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_LAST_DISCONNECTED,
            0L
        )
    }.getOrDefault(0L)

    /** 上次连接时无障碍截屏能力是否已注册（canTakeScreenshot）。 */
    fun accessibilityCanTakeScreenshot(): Boolean = runCatching {
        context.getSharedPreferences(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.PREFS,
            Context.MODE_PRIVATE
        ).getBoolean(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_CAN_SHOT,
            false
        )
    }.getOrDefault(false)

    /** 本次「连上 → 被撤销」的存活毫秒数；0 表示没有记录。 */
    fun accessibilityLastLifespanMs(): Long = runCatching {
        a11yPrefs().getLong(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_LAST_LIFESPAN,
            0L
        )
    }.getOrDefault(0L)

    /** 服务累计连上次数（进程重启后仍累加，用来看是不是「反复秒关」）。 */
    fun accessibilityConnectCount(): Int = runCatching {
        a11yPrefs().getInt(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_CONNECT_COUNT,
            0
        )
    }.getOrDefault(0)

    /** 上次连上时的进程 pid：连接 / 断开 pid 相同说明是解绑，不同说明进程被重建过。 */
    fun accessibilityConnectPid(): Int = runCatching {
        a11yPrefs().getInt(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_CONNECT_PID,
            0
        )
    }.getOrDefault(0)

    /** 上次连上时该进程已运行的毫秒数。 */
    fun accessibilityProcessAgeAtConnectMs(): Long = runCatching {
        a11yPrefs().getLong(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_PROCESS_AGE,
            0L
        )
    }.getOrDefault(0L)

    /**
     * 多次连接期间 pid 是否始终相同。true 表示进程从未被杀，
     * 于是「后台保活 / 自启动 / 电池白名单」这条排查线可以直接排除。
     */
    fun accessibilityPidStable(): Boolean = runCatching {
        val p = a11yPrefs()
        p.getInt(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_CONNECT_COUNT, 0
        ) >= 2 && !p.getBoolean(
            com.azu.bookkeeper.automation.service.PaymentAccessibilityService.KEY_PID_CHANGED, false
        )
    }.getOrDefault(false)

    /**
     * 安装来源。GitHub 下载的 APK 属于侧载，Android 13+ 的「受限设置」会把
     * 无障碍开关置灰或开了又自动关掉；把来源写进诊断报告能一眼确认这一点。
     */
    fun installSourceText(): String = runCatching {
        val pm = context.packageManager
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(context.packageName)
            listOfNotNull(info.installingPackageName, info.initiatingPackageName)
                .distinct().joinToString(", ")
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName).orEmpty()
        }
        source.ifBlank { "未知 / 侧载或 ADB 安装（非应用商店）" }
    }.getOrDefault("读取失败")

    private fun a11yPrefs() = context.getSharedPreferences(
        com.azu.bookkeeper.automation.service.PaymentAccessibilityService.PREFS,
        Context.MODE_PRIVATE
    )

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
