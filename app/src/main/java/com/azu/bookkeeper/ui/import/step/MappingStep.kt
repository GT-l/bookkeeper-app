package com.azu.bookkeeper.ui.import.step

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.domain.model.ImportField
import com.azu.bookkeeper.ui.import.ImportViewModel
import com.azu.bookkeeper.ui.import.component.MappingRow
import com.azu.bookkeeper.ui.theme.ErrorRed

/** 步骤 3：自动映射 + 手动下拉映射 + 收支/转账/去重/分类账户策略。 */
@Composable
fun MappingStep(viewModel: ImportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val header = state.preview?.header.orEmpty()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("文件表头（${header.size} 列）", style = MaterialTheme.typography.titleMedium)
        Text(header.joinToString(" | "), style = MaterialTheme.typography.bodySmall)

        Text("字段映射", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        ImportField.ALL.forEach { field ->
            MappingRow(
                field = field,
                columns = header,
                selected = state.mapping.columnFor(field),
                onSelect = { viewModel.setMapping(field.key, it) }
            )
        }

        Text("解析规则", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        SwitchRow("负数 = 支出，正数 = 收入", state.signRule) { viewModel.setSignRule(it) }
        SwitchRow("识别转账（仅来源模板标记支持时建议开启）", state.recognizeTransfer) {
            viewModel.setRecognizeTransfer(it)
        }
        SwitchRow("分类 / 账户匹配不到时自动新建", state.autoCreateMissing) {
            viewModel.setAutoCreateMissing(it)
        }
        SwitchRow("开启重复账单检测（账本 + 秒级时间 + 金额 + 账户 + 备注）", state.dedupEnabled) {
            viewModel.setDedup(it)
        }

        Text("保存本次映射方案", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.templateNameInput,
                onValueChange = viewModel::setTemplateName,
                label = { Text("模板名称") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { viewModel.saveTemplate() }, modifier = Modifier.padding(start = 8.dp)) {
                Text("保存")
            }
        }

        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
        state.error?.let { Text(it.userMessage, color = ErrorRed, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
