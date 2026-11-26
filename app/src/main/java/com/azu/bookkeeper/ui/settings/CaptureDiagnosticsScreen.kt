package com.azu.bookkeeper.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                    Text("识屏链路：授权 → 截屏 → 中文 OCR → 智能提取", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "点「开始自检」会真实抓一帧屏幕并识别，逐项告诉你哪一环失败、怎么修。" +
                            "摇一摇识屏失败时，下面也会显示最近一次的失败原因。",
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

            state.message?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (state.items.isNotEmpty()) {
                Text("自检结果", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
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
