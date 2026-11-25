package com.azu.bookkeeper.ui.budget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.theme.ExpenseRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(viewModel: BudgetViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("月度预算 · ${state.month}") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            // 总预算兜底说明（固定文案）：未设总预算时按分类预算之和计算
            Text(
                "未设置总预算时，将自动按各分类预算之和计算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "已支出 ${Money.format(state.spentFen)}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "剩余 ${Money.format(state.remainingFen)}" +
                    if (state.isOverBudget) " · 已超预算" else "",
                color = if (state.isOverBudget) ExpenseRed else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.totalLimitDerived) {
                Text(
                    "当前总预算按分类预算之和计算：${Money.format(state.effectiveTotalLimitFen)}" +
                        "（上方总预算留空即保持此模式）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = if (state.isOverBudget) ExpenseRed else MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = state.totalLimitText,
                onValueChange = viewModel::setTotalLimit,
                label = { Text("总预算（元）") },
                placeholder = { Text("留空 = 按分类预算之和") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            )

            Text(
                "分类预算（未设置总预算时，各项之和即为总预算）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            state.categories.forEach { category ->
                OutlinedTextField(
                    value = state.categoryLimitTexts[category.categoryId].orEmpty(),
                    onValueChange = { viewModel.setCategoryLimit(category.categoryId, it) },
                    label = { Text("${category.name}（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            }
            CutePrimaryButton(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("保存预算")
            }
            CuteOutlinedButton(onClick = { viewModel.resetMonth() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("本月重置")
            }
        }
    }
}
