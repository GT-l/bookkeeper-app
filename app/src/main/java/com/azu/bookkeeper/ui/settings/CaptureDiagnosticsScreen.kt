package com.azu.bookkeeper.ui.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.onAuthResult(result.resultCode, result.data) }

    Scaffold(topBar = { TopAppBar(title = { Text("识屏自检") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("识屏链路：双通道截屏 → 中文 OCR → 智能提取", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "通道 1 无障碍截屏（Android 11+，免录屏授权，首选）；通道 2 媒体投影（需授权，兜底）。" +
                            "点「开始自检」会真实抓一帧屏幕并识别，逐项告诉你哪一环失败、怎么修；" +
                            "若是「页面禁止截屏（FLAG_SECURE）」，属系统限制，只能手动记或用通知/短信通道。" +
                            "如果点「开始自检」会闪退，请先点「仅环境检查」，并到下方查看崩溃记录。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Row(modifier = Modifier.padding(top = 12.dp)) {
                CutePrimaryButton(
                    onClick = { if (!state.running) viewModel.runSelfCheck() },
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.running) "自检中…" else "开始自检") }
                OutlinedButton(
                    onClick = { authLauncher.launch(viewModel.createCaptureIntent()) },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("授权截屏") }
            }

            // 安全模式：完全不截屏，只查权限与通道——「点自检就闪退」时先用它排除环境问题
            OutlinedButton(
                onClick = { if (!state.running) viewModel.runEnvironmentOnlyCheck() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("仅环境检查（安全模式，不截屏）") }

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
