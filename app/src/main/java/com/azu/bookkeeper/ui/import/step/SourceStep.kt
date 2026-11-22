package com.azu.bookkeeper.ui.import.step

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.domain.model.ImportPresets
import com.azu.bookkeeper.ui.common.ConfirmDialog
import com.azu.bookkeeper.ui.import.ImportViewModel
import com.azu.bookkeeper.ui.import.template.PresetTemplates

/** 步骤 1：预设模板 / 自定义来源，并管理已保存的自定义模板。 */
@Composable
fun SourceStep(viewModel: ImportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    val presetTemplates = state.templates.filter { ImportPresets.byKey(it.sourceKey) != null }
    val customTemplates = state.templates.filter { template ->
        ImportPresets.byKey(template.sourceKey) == null && template.sourceKey != ImportPresets.CUSTOM_KEY
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("预设模板", style = MaterialTheme.typography.titleMedium)
        presetTemplates.forEach { template ->
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(
                    onClick = { viewModel.selectTemplate(template) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.selectedTemplateId == template.templateId) "✓ ${template.name}" else template.name)
                }
                Text(
                    text = ImportPresets.byKey(template.sourceKey)?.description.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Text("自定义映射方案", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        OutlinedButton(
            onClick = { viewModel.selectCustom() },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(if (state.selectedSourceKey == ImportPresets.CUSTOM_KEY) "✓ ${PresetTemplates.CUSTOM.name}" else PresetTemplates.CUSTOM.name)
        }
        customTemplates.forEach { template ->
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(
                    onClick = { viewModel.selectTemplate(template) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.selectedTemplateId == template.templateId) "✓ ${template.name}" else template.name)
                }
                TextButton(onClick = { pendingDeleteId = template.templateId }) { Text("删除模板") }
            }
        }

        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
        state.error?.let { Text(it.userMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }

    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = "删除自定义模板",
            message = "删除后无法恢复，已导入的账单不受影响。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteTemplate(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null }
        )
    }
}
