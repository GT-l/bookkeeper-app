package com.azu.bookkeeper.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.ui.navigation.Screen
import kotlin.math.roundToInt

/**
 * 自动化设置页（T5/T6）：总开关、权限引导、短信隐私开关、OCR 阈值、三档校对、能力说明固定文案。
 * 能力说明直接引用 strings.xml 固定文案，禁止出现「无感检测已实现」措辞（AC-13）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationSettingsScreen(
    navController: NavController,
    viewModel: AutomationSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var verifyExpanded by remember { mutableStateOf(false) }

    // 从系统「悬浮窗权限」页返回本页时自动补拉起悬浮球：
    // 否则用户授权完返回，开关虽已记录为开启，球却永远不出现。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.retryStartFloatingBallIfNeeded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统权限页统一用 ActivityResultLauncher：留在当前 task，返回时直接刷新权限状态，
    // 不再出现「授权完按返回直接回桌面、像退出了一次 App」的问题。
    val systemSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissions() }

    // MediaProjection 每次会话需重新授权（Android 14+）；拒绝则降级读屏/通知
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            viewModel.startScreenCaptureSession(result.resultCode, result.data!!)
        } else {
            Toast.makeText(context, "已取消授权，截屏备选未启用", Toast.LENGTH_SHORT).show()
        }
    }

    // 短信 READ_SMS + RECEIVE_SMS 运行时申请（QA F-13）：settings 页前置用途说明后由用户主动触发
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        viewModel.onSmsPermissionResult(grants.isNotEmpty() && grants.values.all { it })
    }

    // Android 13+ POST_NOTIFICATIONS 运行时申请（常驻通知不出现的主因之一：系统从不自动弹这个权限）。
    // pendingEnable 记录本次申请是否由「启用自动记账」总开关发起：授权 / 拒绝后都要把开关落到用户点下的状态。
    var pendingEnable by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val enableRequested = pendingEnable
        pendingEnable = false
        viewModel.onNotificationPermissionResult(granted, enableRequested)
    }
    // 运行时申请入口：Android 13+ 用权限弹窗，低版本无需该权限（hasNotificationPermission 恒为 true）。
    val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.openAppDetails()
        }
    }

    Scaffold(topBar = { AppTopBar("自动化设置") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            // 能力说明固定文案（AC-13 / AC-8）
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.automation_capability_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.automation_capability_body),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        stringResource(R.string.automation_capability_footer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // OrangeOS / ColorOS 专项提示：国产 ROM 上「权限给了也不生效」绝大多数是这三项没开
            if (com.azu.bookkeeper.core.RomCompat.needsOppoGuidance()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("OrangeOS / ColorOS 用户请确认", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "① 系统设置 → 应用管理 → 少花点儿 → 自启动：允许；" +
                                "② 通知管理：允许通知；③ 电池：允许后台运行（不限制）。" +
                                "缺任何一项，常驻通知与手势都可能被系统拦截。" +
                                "权限弹窗后若服务没启动，回到本页会自动重试。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // 总开关：与「通知权限」是两件事。用户只开通知权限时服务不会启动，通知栏自然什么都没有，
            // 所以开启总开关时顺手把 POST_NOTIFICATIONS 要下来（Android 13+），拿到权限后立即启动前台服务。
            SettingSwitch("启用自动记账", state.enabled) { enabled ->
                if (enabled && !state.notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pendingEnable = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.setEnabled(enabled)
                }
            }
            Text(
                "常驻通知随总开关出现。" +
                    if (state.enabled && !state.notificationGranted) "当前未授予通知权限，通知栏不会显示。" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingSwitch("悬浮球快捷记账（单击记一笔 / 长按识屏 / 双击收起）", state.floatingBallEnabled) {
                viewModel.setFloatingBallEnabled(it)
            }
            OutlinedButton(
                onClick = { navController.navigate(Screen.QuickGestureSettings.route) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) { Text("系统手势：指纹 / 摇一摇") }
            OutlinedButton(
                onClick = { navController.navigate(Screen.NotificationSettings.route) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) { Text("通知栏显示内容") }
            // 识屏失败时用户能直接看到原因与修复建议（不用再猜「识屏不可用」）
            OutlinedButton(
                onClick = { navController.navigate(Screen.CaptureDiagnostics.route) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) { Text("识屏诊断与日志（复制日志给开发者）") }

            // 无障碍截屏属于实验能力：部分 ROM 会 native 崩溃，必须由用户显式开启
            var a11yShotOptIn by remember {
                mutableStateOf(com.azu.bookkeeper.core.A11yShotOptIn.isEnabled(context))
            }
            SettingSwitch(
                "无障碍截屏（实验，部分机型会闪退）",
                a11yShotOptIn
            ) { enabled ->
                a11yShotOptIn = enabled
                com.azu.bookkeeper.core.A11yShotOptIn.setEnabled(context, enabled)
                com.azu.bookkeeper.core.CaptureBreakerStore.reset(context)
                Toast.makeText(
                    context,
                    if (enabled) "已开启无障碍截屏；若出现闪退会自动停用" else "已关闭无障碍截屏，改用媒体投影",
                    Toast.LENGTH_SHORT
                ).show()
            }
            Text(
                "默认关闭：无障碍截屏无需录屏授权，但在部分国产 ROM 上会直接崩溃，故改为实验开关。" +
                    "关闭时识屏走「媒体投影」（需一次性录屏授权，稳定）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.safeModeActive) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("已进入安全模式", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "悬浮球上次启动异常，已暂停自动启动以保证 App 能打开。可手动重试。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        // 开关此时仍是「开」的状态，用户想重试不会去拨开关；
                        // 给一个明确的动作：清安全模式 + 重新拉起悬浮球（崩了下次启动会再次进入安全模式）
                        OutlinedButton(
                            onClick = { viewModel.setFloatingBallEnabled(true) },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("重新尝试开启悬浮球") }
                    }
                }
            }
            Text(
                "快捷入口：桌面图标 / 小组件 / 常驻通知（全机型）；悬浮球与系统手势需按提示授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "服务状态：${if (state.serviceRunning) "运行中" else "未运行"}",
                style = MaterialTheme.typography.bodySmall
            )

            Text("权限与引导", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            if (state.accessibilityGranted && !state.accessibilityServiceConnected) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        "系统显示已开启读屏服务，但服务尚未成功连接（部分机型会自动关闭）。" +
                            "请在系统无障碍设置里关闭再重新打开一次「少花点儿」。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            PermissionStatusCard(
                title = "无障碍读屏",
                description = "读取支付完成页金额/商家/时间，主识别路径",
                granted = state.accessibilityGranted,
                actionLabel = "去开启",
                onAction = { systemSettingsLauncher.launch(viewModel.accessibilitySettingsIntent()) }
            )
            PermissionStatusCard(
                title = "通知使用权",
                description = "读取锁屏上的支付通知，读屏未授权时的降级路径",
                granted = state.notificationListenerGranted,
                actionLabel = "去开启",
                onAction = { systemSettingsLauncher.launch(viewModel.notificationListenerSettingsIntent()) }
            )
            PermissionStatusCard(
                title = "通知权限",
                description = "常驻通知与校对提醒",
                granted = state.notificationGranted,
                actionLabel = if (state.notificationGranted) "已开启" else "去开启",
                onAction = {
                    pendingEnable = false
                    requestNotificationPermission()
                }
            )
            // 勾选过「不再询问」后运行时弹窗不再出现，保留应用详情作为兜底入口
            if (!state.notificationGranted) {
                ActionCard(
                    title = "弹窗不再出现时，到应用详情开启通知",
                    buttonLabel = "应用详情",
                    onClick = { systemSettingsLauncher.launch(viewModel.appDetailsIntent()) }
                )
            }
            PermissionStatusCard(
                title = "悬浮窗",
                description = "后台校对弹窗；未授权自动降级为通知",
                granted = state.overlayGranted,
                actionLabel = "去开启",
                onAction = { systemSettingsLauncher.launch(viewModel.overlaySettingsIntent()) }
            )
            PermissionStatusCard(
                title = "电池优化白名单",
                description = "减少 Doze 限制；强杀自启做不到，需手动打开一次 App",
                granted = state.batteryWhitelisted,
                actionLabel = "去设置",
                onAction = {
                    viewModel.batteryWhitelistIntent()?.let { systemSettingsLauncher.launch(it) }
                        ?: runCatching { viewModel.requestBatteryWhitelist() }
                }
            )

            Text("识别与去重", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            SettingSwitch("截屏备选（需每次授权）", state.screenCaptureEnabled) { enabled ->
                if (enabled) {
                    viewModel.createCaptureIntent()?.let { captureLauncher.launch(it) }
                        ?: Toast.makeText(context, "当前设备不支持截屏授权", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.setScreenCaptureEnabled(false)
                }
            }

            Text(
                "OCR 阈值：${String.format("%.2f", state.ocrThreshold)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            Slider(
                value = state.ocrThreshold,
                onValueChange = { viewModel.setOcrThreshold(it) },
                valueRange = Constants.MIN_OCR_THRESHOLD..Constants.MAX_OCR_THRESHOLD,
                steps = 8,
                modifier = Modifier.fillMaxWidth()
            )

            Text("校对方式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            OutlinedButton(onClick = { verifyExpanded = true }) {
                Text(when (state.verifyMode) {
                    0 -> "自动（前台弹窗优先）"
                    1 -> "悬浮窗"
                    2 -> "通知"
                    else -> "仅前台弹窗"
                })
            }
            DropdownMenu(expanded = verifyExpanded, onDismissRequest = { verifyExpanded = false }) {
                listOf("自动" to 0, "悬浮窗" to 1, "通知" to 2, "仅前台弹窗" to 3).forEach { (label, mode) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { viewModel.setVerifyMode(mode); verifyExpanded = false }
                    )
                }
            }

            SettingSwitch("启用去重", state.dedupEnabled) { viewModel.setDedupEnabled(it) }

            Text("短信识别（银行扣款）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            // 前置用途说明（PRD 23.3.2）：先讲清用途与隐私边界，再由用户主动触发运行时授权
            Text(
                "本地识别银行扣款短信，正文不上传、不存原文；需两项短信权限，随时可关。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingSwitch("短信识别", state.smsEnabled) { enabled ->
                if (enabled && !state.smsPermissionGranted) {
                    smsPermissionLauncher.launch(PermissionGuide.SMS_RUNTIME_PERMISSIONS)
                } else {
                    viewModel.setSmsEnabled(enabled)
                }
            }
            PermissionStatusCard(
                title = "短信读取权限",
                description = "本地识别扣款短信，正文不上传不留存",
                granted = state.smsPermissionGranted,
                actionLabel = "去授权",
                onAction = { smsPermissionLauncher.launch(PermissionGuide.SMS_RUNTIME_PERMISSIONS) }
            )
            // 用户在系统弹窗勾选「不再询问」后运行时申请不再弹出，保留应用详情作为兜底入口
            ActionCard(
                title = "弹窗不再出现时，到应用详情开启短信权限",
                buttonLabel = "应用详情",
                onClick = { systemSettingsLauncher.launch(viewModel.appDetailsIntent()) }
            )
            Text(
                stringResource(R.string.sms_dedup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "容差：${state.smsDedupToleranceSec} 秒",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Slider(
                value = state.smsDedupToleranceSec.toFloat(),
                onValueChange = { viewModel.setSmsDedupTolerance(it.roundToInt().toLong()) },
                valueRange = 0f..600f,
                steps = 20,
                modifier = Modifier.fillMaxWidth()
            )

            ActionCard(
                title = "清除所有捕获截图（本地加密文件）",
                buttonLabel = "清除",
                onClick = viewModel::clearCaptures,
                modifier = Modifier.padding(top = 16.dp)
            )

            state.message?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
                LaunchedEffectMessage(msg, viewModel::clearMessage)
            }
        }
    }
}

/** 阈值滑块只读展示；如需可配置，可在 ViewModel 暴露 setOcrThreshold 后放开。 */
@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 一次性 SnackBar 式提示清理：显示数秒后清除 message。 */
@Composable
private fun LaunchedEffectMessage(message: String, onClear: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(message) {
        kotlinx.coroutines.delay(3000L)
        onClear()
    }
}
