package com.azu.bookkeeper.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.stats.CompareResult
import com.azu.bookkeeper.domain.usecase.stats.DaySummary
import com.azu.bookkeeper.domain.usecase.stats.StatsEnhanceCalculator
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.SoftCard
import com.azu.bookkeeper.ui.theme.ExpenseRed
import com.azu.bookkeeper.ui.theme.IncomeGreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class CalendarStatsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val billRepository: BillRepository,
    private val calculator: StatsEnhanceCalculator
) : ViewModel() {
    data class UiState(
        val bookId: Long = 0L,
        val month: YearMonth = YearMonth.now(),
        val bills: List<Bill> = emptyList(),
        val days: List<DaySummary> = emptyList(),
        val heatmap: List<Pair<YearMonth, Long>> = emptyList(),
        val mom: CompareResult? = null,
        val yoy: CompareResult? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            _uiState.update { it.copy(bookId = bookId) }
            billRepository.observeBills(bookId).collect { bills -> recompute(bills, _uiState.value.month) }
        }
    }

    fun setMonth(month: YearMonth) {
        _uiState.update { it.copy(month = month) }
        recompute(_uiState.value.bills, month)
    }

    private fun recompute(bills: List<Bill>, month: YearMonth) {
        val firstDay = month.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val lastDay = month.atEndOfMonth().atTime(23, 59, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val inRange = bills.filter { it.billTime in firstDay..lastDay }
        _uiState.update {
            it.copy(
                bills = bills,
                days = calculator.monthCalendar(bills, month),
                heatmap = calculator.monthlyHeatmap(bills, month, 12),
                mom = calculator.monthOverMonth(bills, System.currentTimeMillis()),
                yoy = calculator.yearOverYear(bills, System.currentTimeMillis())
            )
        }
    }
}

@Composable
fun CalendarStatsScreen(vm: CalendarStatsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedDay by remember { mutableStateOf<Int?>(null) }

    Scaffold(topBar = { AppTopBar("日历与趋势", subtitle = "按天看花销 · 年度热力 · 同比环比") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextButton(
                                onClick = { vm.setMonth(state.month.minusMonths(1)); selectedDay = null }
                            ) { Text("‹ 上月") }
                            Text(
                                "${state.month.year} 年 ${state.month.monthValue} 月",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { vm.setMonth(state.month.plusMonths(1)); selectedDay = null }
                            ) { Text("下月 ›") }
                        }
                        CalendarGrid(state.days, selectedDay) { selectedDay = if (selectedDay == it) null else it }
                        selectedDay?.let { day ->
                            val summary = state.days.getOrNull(day - 1)
                            if (summary != null) {
                                Text(
                                    "${state.month.monthValue}月${day}日 · 支出 ${Money.format(summary.expenseFen)} · 收入 ${Money.format(summary.incomeFen)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("同比 / 环比", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        CompareRow("环比上月", state.mom)
                        CompareRow("同比去年", state.yoy)
                    }
                }
            }

            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("近 12 个月消费热力", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("颜色越深，当月支出越多", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.heatmap.forEach { (ym, fen) ->
                                HeatCell(ym, fen, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareRow(label: String, result: CompareResult?) {
    if (result == null) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${Money.format(result.currentFen)}", fontWeight = FontWeight.SemiBold)
        val ratioText = if (result.hasBase && result.ratio != null) {
            "${if (result.increased) "↑" else "↓"}${ kotlin.math.abs((result.ratio - 1f) * 100).toInt() }%"
        } else "—"
        Text(
            "  $ratioText",
            style = MaterialTheme.typography.labelMedium,
            color = if (!result.hasBase) MaterialTheme.colorScheme.onSurfaceVariant
            else if (result.increased) ExpenseRed else IncomeGreen
        )
    }
}

@Composable
private fun HeatCell(ym: YearMonth, fen: Long, modifier: Modifier) {
    // 以 12 个月里的最大值为基准做 5 档色阶，避免硬编码金额阈值
    val alpha = when {
        fen <= 0L -> 0.08f
        else -> (0.25f + (fen.coerceAtMost(10_000_00L).toFloat() / 10_000_00L) * 0.65f).coerceIn(0.25f, 0.9f)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        )
        Text("${ym.monthValue}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarGrid(
    days: List<DaySummary>,
    selected: Int?,
    onSelect: (Int) -> Unit
) {
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val firstDay = days.firstOrNull()?.date
    val leading = if (firstDay != null) (firstDay.dayOfWeek.value - 1) else 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekLabels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val cells = leading + days.size
        val rows = (cells + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val index = row * 7 + col - leading
                    if (index in days.indices) {
                        val summary = days[index]
                        val isSelected = selected == index + 1
                        val hasExpense = summary.expenseFen > 0L
                        Box(
                            Modifier
                                .weight(1f)
                                .height(52.dp)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .then(
                                    if (isSelected) Modifier.border(
                                        1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)
                                    ) else Modifier
                                )
                                .clickable { onSelect(index + 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                                if (hasExpense) {
                                    Text(
                                        Money.formatPlain(summary.expenseFen, withSymbol = false)
                                            .let { if (it.length > 6) it.take(5) + "…" else it },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ExpenseRed
                                    )
                                }
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).height(52.dp))
                    }
                }
            }
        }
    }
}
