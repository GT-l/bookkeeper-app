package com.azu.bookkeeper.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.core.DateTimeUtils

/** 账单导出：账本 + 时间范围 + 格式 + 字段勾选，写入用户选择的 URI。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bookMenu by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(state.format.mimeType)
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri.toString())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("导出账单") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("账本", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = bookMenu,
                onExpandedChange = { bookMenu = it },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                val bookName = state.books.firstOrNull { it.bookId == state.bookId }?.name.orEmpty()
                OutlinedTextField(
                    value = bookName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bookMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                    state.books.forEach { book ->
                        DropdownMenuItem(
                            text = { Text(book.name) },
                            onClick = {
                                viewModel.setBook(book.bookId)
                                bookMenu = false
                            }
                        )
                    }
                }
            }

            Text("时间范围", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                ExportRange.ALL_VALUES.forEach { range ->
                    OutlinedButton(
                        onClick = { viewModel.setRange(range) },
                        modifier = Modifier.padding(end = 6.dp)
                    ) { Text(if (state.range == range) "✓${range.label}" else range.label) }
                }
            }

            Text("格式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEach { format ->
                    OutlinedButton(
                        onClick = { viewModel.setFormat(format) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(if (state.format == format) "✓${format.name}" else format.name) }
                }
            }

            Text(
                "导出字段（已选 ${state.selectedCount} 项）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 18.dp)
            )
            ExportField.ALL_VALUES.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { field ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = field.key in state.selectedFields,
                                onCheckedChange = { viewModel.toggleField(field) }
                            )
                            Text(field.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            }
            Button(
                onClick = {
                    val name = "账单_${state.range.label}_${DateTimeUtils.format(DateTimeUtils.nowMillis(), "yyyyMMdd_HHmm")}.${state.format.extension}"
                    saver.launch(name)
                },
                enabled = !state.exporting && state.selectedCount > 0 && state.bookId > 0L,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            ) { Text(if (state.exporting) "正在导出…" else "选择保存位置并导出") }
        }
    }
}
