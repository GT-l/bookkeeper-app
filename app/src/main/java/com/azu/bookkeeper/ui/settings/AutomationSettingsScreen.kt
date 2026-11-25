package com.azu.bookkeeper.ui.settings

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
import com.azu.bookkeeper.R
import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.core.Constants
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

    Scaffold(topBar = { TopAppBar(title = { Text("自动化设置") }) }) { padding ->
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

            // 总开关
            SettingSwitch("启用自动记账", state.enabled) { viewModel.setEnabled(it) }
            SettingSwitch("悬浮球快捷记账（单击记一笔 / 长按识屏 / 双击收起）", state.floatingBallEnabled) {
                viewModel.setFloatingBallEnabled(it)
            }
            Text(
                "复用上方「悬浮窗」权限；默认关闭。四个快捷入口：桌面图标、桌面组件、悬浮球、常驻通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "服务状态：${if (state.serviceRunning) "运行中" else "未运行"}",
                style = MaterialTheme.typography.bodySmall
            )

            Text("权限与引导", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            PermissionStatusCard(
                title = "无障碍读屏",
                description = "读取支付完成页金额/商家/时间，主识别路径",
                granted = state.accessibilityGranted,
                actionLabel = "去开启",
                onAction = viewModel::openAccessibilitySettings
            )
            PermissionStatusCard(
                title = "通知使用权",
                description = "读取锁屏上的支付通知，读屏未授权时的降级路径",
                granted = state.notificationListenerGranted,
                actionLabel = "去开启",
                onAction = viewModel::openNotificationListenerSettings
            )
            PermissionStatusCard(
                title = "通知权限",
                description = "常驻服务通知与校对提醒",
                granted = state.notificationGranted,
                actionLabel = "去开启",
                onAction = viewModel::openAppDetails
            )
            PermissionStatusCard(
                title = "悬浮窗",
                description = "后台校对弹窗；未授权自动降级为通知",
                granted = state.overlayGranted,
                actionLabel = "去开启",
                onAction = viewModel::openOverlaySettings
            )
            PermissionStatusCard(
                title = "电池优化白名单",
                description = "减少 Doze 限制；强杀自启做不到，需手动打开一次 App",
                granted = state.batteryWhitelisted,
                actionLabel = "去设置",
                onAction = viewModel::requestBatteryWhitelist
            )

            Text("识别与去重", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            SettingSwitch("截屏备选（需每次授权）", state.screenCaptureEnabled) { enabled ->
                if (enabled) {
                    runCatching { captureLauncher.launch(viewModel.createCaptureIntent()) }
                        .onFailure { Toast.makeText(context, "设备不支持截屏捕获", Toast.LENGTH_SHORT).show() }
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
                "开启后仅在本地识别银行扣款短信的金额 / 时间 / 商户，短信正文不上传、不存原文、不写入备份；" +
                    "需要「读取短信」与「接收短信」两项权限，可随时关闭，关闭后读屏 / 通知 / 截屏识别继续工作。",
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
                description = "仅用于本地识别银行扣款短信：正文不上传、不存原文，可随时关闭；关闭后其他识别方式继续工作",
                granted = state.smsPermissionGranted,
                actionLabel = "去授权",
                onAction = { smsPermissionLauncher.launch(PermissionGuide.SMS_RUNTIME_PERMISSIONS) }
            )
            // 用户在系统弹窗勾选「不再询问」后运行时申请不再弹出，保留应用详情作为兜底入口
            ActionCard(
                title = "若授权弹窗不再出现，请到应用详情手动开启短信权限",
                buttonLabel = "应用详情",
                onClick = viewModel::openAppDetails
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
