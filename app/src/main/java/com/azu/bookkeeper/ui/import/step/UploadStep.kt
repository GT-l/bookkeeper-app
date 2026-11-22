package com.azu.bookkeeper.ui.import.step

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.ui.import.ImportViewModel
import com.azu.bookkeeper.ui.theme.ErrorRed

/** 步骤 2：选择 ≤10MB 的 CSV/XLSX，展示表头与前 10 行，字符集可手动切换。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadStep(viewModel: ImportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var charsetMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Button(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("选择账单文件（.csv / .xlsx，≤10MB）")
        }
        if (state.fileName.isNotBlank()) {
            Text("文件：${state.fileName} · ${state.fileKind.name}", modifier = Modifier.padding(top = 10.dp))
            Text("行数：${state.preview?.totalRows ?: 0} · 自动识别：${state.detectedCharset.ifBlank { "—" }}")
        }

        Text("字符编码", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        ExposedDropdownMenuBox(
            expanded = charsetMenu,
            onExpandedChange = { charsetMenu = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = when (state.charset) {
                    "UTF-8" -> "UTF-8"
                    "GBK" -> "GBK"
                    else -> "自动识别（UTF-8 / GBK）"
                },
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = charsetMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = charsetMenu, onDismissRequest = { charsetMenu = false }) {
                listOf("AUTO", "UTF-8", "GBK").forEach { charset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (charset) {
                                    "AUTO" -> "自动识别（UTF-8 / GBK）"
                                    else -> charset
                                }
                            )
                        },
                        onClick = {
                            viewModel.setCharset(charset)
                            charsetMenu = false
                        }
                    )
                }
            }
        }

        val preview = state.preview
        if (preview != null) {
            Text("表头与前 ${preview.rows.size} 行", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(preview.header.joinToString(" | "), style = MaterialTheme.typography.labelMedium)
                preview.rows.forEachIndexed { index, row ->
                    Text("${index + 1}. ${row.joinToString(" | ")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
        state.error?.let { Text(it.userMessage, color = ErrorRed, modifier = Modifier.padding(top = 8.dp)) }
    }
}
