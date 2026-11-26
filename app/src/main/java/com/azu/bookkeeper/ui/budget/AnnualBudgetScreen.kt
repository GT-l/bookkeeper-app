package com.azu.bookkeeper.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.AnnualBudget
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.finance.SaveAnnualBudgetUseCase
import com.azu.bookkeeper.domain.usecase.stats.StatsEnhanceCalculator
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.theme.ExpenseRed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Year
import javax.inject.Inject

@HiltViewModel
class AnnualBudgetViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val billRepository: BillRepository,
    private val observer: com.azu.bookkeeper.domain.usecase.finance.ObserveAnnualBudgetUseCase,
    private val save: SaveAnnualBudgetUseCase,
    private val calculator: StatsEnhanceCalculator
) : ViewModel() {
    data class UiState(
        val bookId: Long = 0L,
        val year: Int = Year.now().value,
        val limitFen: Long = 0L,
        val spentFen: Long = 0L,
        val limitText: String = "",
        val message: String? = null
    ) {
        val remainingFen: Long get() = limitFen - spentFen
        val usedPercent: Int get() = if (limitFen > 0L) ((spentFen * 100) / limitFen).toInt() else 0
        val progress: Float get() = if (limitFen > 0L) (spentFen.toFloat() / limitFen.toFloat()).coerceIn(0f, 1f) else 0f
        val over: Boolean get() = limitFen > 0L && spentFen > limitFen
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            val year = Year.now().value
            _uiState.update { it.copy(bookId = bookId, year = year) }
            observer(bookId, year).collect { entity ->
                val limit = entity?.totalLimitFen ?: 0L
                val start = java.time.LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end = java.time.LocalDate.of(year, 12, 31).atTime(23, 59, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val bills = billRepository.findByTimeRange(bookId, start, end).getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        limitFen = limit,
                        limitText = if (limit > 0L) Money.formatPlain(limit, withSymbol = false) else "",
                        spentFen = calculator.yearExpense(bills, year)
                    )
                }
            }
        }
    }

    fun setLimit(text: String) = _uiState.update { it.copy(limitText = text) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun save() {
        val s = _uiState.value
        val amount = Money.parse(s.limitText) ?: 0L
        viewModelScope.launch {
            val r = save(AnnualBudget(bookId = s.bookId, year = s.year, totalLimitFen = amount))
            _uiState.update {
                it.copy(message = if (r is Result.Success) "年度预算已保存" else
                    (r as? Result.Error)?.error?.userMessage ?: "保存失败")
            }
        }
    }
}

@Composable
fun AnnualBudgetScreen(vm: AnnualBudgetViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppTopBar("年度预算", subtitle = "${state.year} 年全年花销上限") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${state.year} 年已花", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            Money.format(state.spentFen),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (state.over) ExpenseRed else MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = state.progress,
                            modifier = Modifier.fillMaxWidth().height(8.dp)
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                if (state.limitFen > 0L) "预算 ${Money.format(state.limitFen)} · 已用 ${state.usedPercent}%"
                                else "还没设置年度预算",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.limitFen > 0L) {
                                Text(
                                    if (state.over) "已超 ${Money.format(-state.remainingFen)}"
                                    else "剩 ${Money.format(state.remainingFen)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (state.over) ExpenseRed else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("设置年度上限", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        CuteTextField(
                            value = state.limitText,
                            onValueChange = vm::setLimit,
                            label = "全年预算（元）",
                            placeholder = "例如 60000，留空表示取消",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            modifier = Modifier.fillMaxWidth()
                        )
                        CutePrimaryButton(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("保存年度预算") }
                        state.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("失败")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Text(
                    "年度预算与月度预算相互独立：月度预算管当月现金流，年度预算看全年总量。余额校准流水不计入年度已花。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}
