package com.azu.bookkeeper.ui.import.step

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.core.content.FileProvider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.ui.import.ImportViewModel
import com.azu.bookkeeper.ui.theme.ErrorRed
import java.io.File

/** 步骤 5：后台导入进度 + 成功/失败/新建分类账户报告 + 失败明细 CSV 分享。 */
@Composable
fun ConfirmStep(viewModel: ImportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context: Context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("确认导入", style = MaterialTheme.typography.titleMedium)
        Text("模板：${state.sourceName} · 文件：${state.fileName}")
        Text("有效 ${state.summary.validRows} 条 · 跳过 ${state.summary.skippedRows} 条 · 重复 ${state.summary.duplicateRows} 条")
        Text("去重检测：${if (state.dedupEnabled) "开启" else "关闭"}")

        if (state.importing) {
            Text("正在后台导入 ${state.progress}%", modifier = Modifier.padding(top = 12.dp))
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }

        val report = state.report
        if (report != null) {
            Text("导入报告", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            Text("成功 ${report.successRows} 条（支出 ${report.expenseRows} / 收入 ${report.incomeRows} / 转账 ${report.transferRows}）")
            Text("新建分类 ${report.createdCategories.size} 个 · 新建账户 ${report.createdAccounts.size} 个")
            Text("失败 ${report.failedRows} 条 · 跳过 ${report.skippedRows} 条 · 重复 ${report.duplicateRows} 条")
            if (report.createdCategories.isNotEmpty()) {
                Text("新建分类：${report.createdCategories.joinToString("、")}")
            }
            if (report.createdAccounts.isNotEmpty()) {
                Text("新建账户：${report.createdAccounts.joinToString("、")}")
            }
            if (report.reportPath.isNotBlank()) {
                Button(
                    onClick = { share(context, report.reportPath) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text("下载 / 分享失败明细 CSV") }
            }
            OutlinedButton(
                onClick = { viewModel.resetWizard() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("完成，开始新的导入") }
        } else if (!state.importing) {
            Button(
                onClick = { viewModel.confirmImport() },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                enabled = state.summary.validRows > 0
            ) { Text("开始导入 ${state.summary.validRows} 条账单") }
        }

        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
        state.error?.let { Text(it.userMessage, color = ErrorRed, modifier = Modifier.padding(top = 8.dp)) }
    }
}

private fun share(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享导入报告"))
    }
}
