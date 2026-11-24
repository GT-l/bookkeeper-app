package com.azu.bookkeeper.ui.importflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.ui.importflow.step.ConfirmStep
import com.azu.bookkeeper.ui.importflow.step.MappingStep
import com.azu.bookkeeper.ui.importflow.step.PreviewStep
import com.azu.bookkeeper.ui.importflow.step.SourceStep
import com.azu.bookkeeper.ui.importflow.step.UploadStep

/** 导入向导外壳：固定 5 步 + 步骤条 + 上一步/下一步（最后一步在 ConfirmStep 内）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWizardScreen(viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("导入账单 · 步骤 ${state.stepIndex + 1}/5：${state.step.label}")
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LinearProgressIndicator(
                progress = { (state.stepIndex + 1) / 5f },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImportViewModel.Step.ALL.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}.${step.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == state.stepIndex) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize().weight(1f)) {
                when (state.step) {
                    ImportViewModel.Step.SOURCE -> SourceStep(viewModel)
                    ImportViewModel.Step.UPLOAD -> UploadStep(viewModel)
                    ImportViewModel.Step.MAPPING -> MappingStep(viewModel)
                    ImportViewModel.Step.PREVIEW -> PreviewStep(viewModel)
                    ImportViewModel.Step.CONFIRM -> ConfirmStep(viewModel)
                }
            }

            if (state.step != ImportViewModel.Step.CONFIRM) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.stepIndex > 0) {
                        OutlinedButton(onClick = { viewModel.goBack() }, modifier = Modifier.weight(1f)) {
                            Text("上一步")
                        }
                    }
                    Button(
                        onClick = { viewModel.goNext() },
                        enabled = state.canNext && !state.importing,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    ) { Text("下一步") }
                }
            }
        }
    }
}
