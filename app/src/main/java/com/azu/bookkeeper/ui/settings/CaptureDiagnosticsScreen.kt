package com.azu.bookkeeper.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.SoftCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识屏自检页：把「授权 → 截屏 → OCR → 提取」逐项跑通并显示结论，
 * 同时展示最近一次摇一摇识屏失败的完整步骤，用户能直接看出卡在哪。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureDiagnosticsScreen(
    viewModel: CaptureDiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 识屏日志预览：初始为空，读取放到 LaunchedEffect（组合期不碰磁盘，避免首帧卡顿/主线程异常）
    var logText by remember { mutableStateOf("") }
    var logSummary by remember { mutableStateOf("读取中…") }
    LaunchedEffect(state.running, state.last, state.message) {
        com.azu.bookkeeper.core.BootTrace.step("PAGE", "自检页组合/刷新")
        logText = runCatching { viewModel.logPreview(20) }.getOrDefault("")
        logSummary = runCatching { viewModel.logSummary() }.getOrDefault("读取失败")
    }
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.onAuthResult(result.resultCode, result.data) }

    // 从系统无障碍设置返回时重新检测：服务被 ROM 关掉就立即弹窗
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 无障碍被系统关闭 / 解绑：在调试页第一屏弹窗，直接给「复制 log」和「去开启」
    state.accessibilityAnomaly?.let { anomaly ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAccessibilityAnomaly,
            title = { Text(anomaly.title) },
            text = {
                Column {
                    Text(anomaly.detail, style = MaterialTheme.typography.bodySmall)
                    Text(
                        anomaly.advice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        copyText(context, "无障碍诊断", viewModel.accessibilityReportText())
                        Toast.makeText(context, "无障碍 log 已复制，直接粘贴发送", Toast.LENGTH_LONG).show()
                        viewModel.dismissAccessibilityAnomaly()
                    }
                ) { Text("复制 log") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissAccessibilityAnomaly()
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) { Text("去开启") }
            }
        )
    }

    Scaffold(topBar = { AppTopBar("识屏自检") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("识屏链路：无障碍节点读屏 → 智能提取（截图 OCR 仅兜底）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "主路径和钱迹等主流记账 App 一致：摇一摇时直接读取当前页面节点树里的文字，" +
                            "从中提取金额与商户。不需要录屏授权，也不受「页面禁止截屏（FLAG_SECURE）」影响。" +
                            "只有节点读不到金额时，才会尝试截图 OCR 兜底。" +
                            "「环境检查（安全）」完全不截屏、不会闪退，其中的「读屏节点」一项就是主路径体检。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Row(modifier = Modifier.padding(top = 12.dp)) {
                CutePrimaryButton(
                    onClick = { if (!state.running) viewModel.runEnvironmentOnlyCheck() },
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.running) "检查中…" else "环境检查（安全）") }
                OutlinedButton(
                    onClick = {
                        viewModel.createCaptureIntent()?.let { authLauncher.launch(it) }
                            ?: Toast.makeText(context, "当前设备不支持截屏授权", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("授权截屏") }
            }

            // 真实截屏测试单独放：它可能触发本机 native 崩溃，必须由用户明确点击；
            // 但已加熔断保护——真崩了会自动停用该通道，下次不会再崩。
            OutlinedButton(
                onClick = { if (!state.running) viewModel.runSelfCheck() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("真实截屏测试（可能崩溃，已加熔断保护）") }

            OutlinedButton(
                onClick = { viewModel.resetBreakers() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("恢复截屏通道（清除熔断）") }

            // 一键复制诊断信息：不方便连 adb 时，把这段发给开发者即可定位
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE
                    ) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "识屏诊断", viewModel.buildReportText()
                        )
                    )
                    Toast.makeText(context, "诊断信息已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("复制诊断信息") }

            // 识屏日志：开启识屏后自动记录全链路，复制即发给开发者定位
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("识屏日志", style = MaterialTheme.typography.titleSmall)
                    Text(
                        logSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE
                                ) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "识屏日志", viewModel.logPreview(400)
                                    )
                                )
                                Toast.makeText(context, "识屏日志已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("复制识屏日志") }
                        OutlinedButton(
                            onClick = { viewModel.clearLog() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("清空日志") }
                    }
                    if (logText.isNotBlank()) {
                        Text(
                            logText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // 监督指示：自检进行中实时显示当前步骤（不是等全部跑完才反馈）
            if (state.running) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "正在${state.currentStep}…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
            }

            // 最显眼位置放「复制全部」：用户找不到日志入口是真实反馈，这里放第一屏
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE
                    ) as? android.content.ClipboardManager
                    val text = viewModel.buildReportText() + "\n\n--- 识屏日志（最近400行）---\n" + viewModel.logPreview(400)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("识屏诊断与日志", text)
                    )
                    Toast.makeText(context, "已复制：诊断信息 + 识屏日志，直接粘贴发送", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { Text("① 复制全部（诊断 + 日志）← 反馈问题点这里") }

            OutlinedButton(
                onClick = {
                    copyText(context, "稳定运行设置", viewModel.stableRunGuideText())
                    Toast.makeText(context, "稳定运行说明已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("复制稳定运行说明（OrangeOS / ColorOS 保活）") }

            state.message?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (state.items.isNotEmpty()) {
                Text("自检结果（实时）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                state.items.forEach { item -> CheckRow(item) }
            }

            state.last?.let { report ->
                Text(
                    "最近一次识屏记录",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.timeMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                report.reason?.let { reason ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("失败原因：${reason.title}", fontWeight = FontWeight.SemiBold)
                            Text(reason.advice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                } ?: Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        "上次识屏成功",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                report.steps.forEach { step ->
                    CheckRow(CheckItem(step.name, step.ok, step.detail))
                }
                if (state.textPreview.isNotBlank()) {
                    Text(
                        "识别到的文字（截取前 200 字）",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        state.textPreview.take(200),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.clearReport() },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("清除记录") }
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.ok) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${if (item.ok) "✓" else "✗"} ${item.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(item.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            if (!item.ok && item.advice.isNotBlank()) {
                Text(
                    "建议：${item.advice}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun copyText(context: Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
