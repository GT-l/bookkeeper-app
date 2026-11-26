package com.azu.bookkeeper.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.data.local.db.dao.InstallmentDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.domain.model.Installment
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.finance.AddInstallmentUseCase
import com.azu.bookkeeper.domain.usecase.finance.DeleteInstallmentUseCase
import com.azu.bookkeeper.domain.usecase.finance.PayInstallmentUseCase
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.EmptyState
import com.azu.bookkeeper.ui.common.SoftCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InstallmentsViewModel @Inject constructor(
    private val dao: InstallmentDao,
    private val settings: SettingsRepository,
    private val add: AddInstallmentUseCase,
    private val pay: PayInstallmentUseCase,
    private val delete: DeleteInstallmentUseCase
) : ViewModel() {
    data class UiState(
        val plans: List<Installment> = emptyList(),
        val title: String = "",
        val amountText: String = "",
        val periodsText: String = "",
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            if (bookId > 0L) {
                dao.observeByBook(bookId).collect { list ->
                    _uiState.update { it.copy(plans = list.map { e -> e.toDomain() }) }
                }
            }
        }
    }

    fun setTitle(v: String) = _uiState.update { it.copy(title = v) }
    fun setAmount(v: String) = _uiState.update { it.copy(amountText = v) }
    fun setPeriods(v: String) = _uiState.update { it.copy(periodsText = v) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun submit() {
        val s = _uiState.value
        val amount = Money.parse(s.amountText)
        val periods = s.periodsText.trim().toIntOrNull()
        if (amount == null || amount <= 0L) {
            _uiState.update { it.copy(message = "请输入有效总额") }
            return
        }
        if (periods == null || periods !in 2..60) {
            _uiState.update { it.copy(message = "期数需在 2~60 之间") }
            return
        }
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            val result = add(
                Installment(
                    bookId = bookId, title = s.title.trim(), totalFen = amount,
                    periods = periods, perPeriodFen = amount / periods
                )
            )
            _uiState.update {
                it.copy(
                    message = if (result is Result.Success) "已创建，首期账单已生成" else
                        (result as? Result.Error)?.error?.userMessage ?: "创建失败",
                    title = "", amountText = "", periodsText = ""
                )
            }
        }
    }

    fun payNext(plan: Installment) = viewModelScope.launch {
        val r = pay(plan.installmentId)
        _uiState.update {
            it.copy(message = if (r is Result.Success) "已确认第 ${plan.paidPeriods + 1} 期" else
                (r as? Result.Error)?.error?.userMessage ?: "操作失败")
        }
    }

    fun delete(id: Long) = viewModelScope.launch {
        val r = delete(id)
        _uiState.update { it.copy(message = if (r.isSuccess) "已删除分期计划" else "删除失败") }
    }
}

@Composable
fun InstallmentsScreen(vm: InstallmentsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppTopBar("分期计划", subtitle = "大额消费拆成每期，尾差自动补齐") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CuteTextField(value = state.title, onValueChange = vm::setTitle,
                            label = "分期名称", placeholder = "例如：新手机", modifier = Modifier.fillMaxWidth())
                        CuteTextField(value = state.amountText, onValueChange = vm::setAmount,
                            label = "总额", placeholder = "例如 5999.00",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            modifier = Modifier.fillMaxWidth())
                        CuteTextField(value = state.periodsText, onValueChange = vm::setPeriods,
                            label = "期数", placeholder = "例如 12",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.fillMaxWidth())
                        CutePrimaryButton(onClick = vm::submit, modifier = Modifier.fillMaxWidth()) { Text("创建分期") }
                        state.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("失败")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (state.plans.isEmpty()) {
                item { EmptyState("还没有分期计划") }
            } else {
                items(state.plans) { plan ->
                    SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(plan.title, fontWeight = FontWeight.SemiBold)
                                    Text("每期约 ${Money.format(plan.perPeriodFen)} · 共 ${plan.periods} 期",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(Money.format(plan.totalFen), fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            LinearProgressIndicator(
                                progress = { if (plan.periods > 0) plan.paidPeriods.toFloat() / plan.periods else 0f },
                                modifier = Modifier.fillMaxWidth().height(6.dp)
                            )
                            Text("已还 ${plan.paidPeriods}/${plan.periods} 期${if (plan.finished) " · 已结清" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth()) {
                                if (!plan.finished) {
                                    TextButton(onClick = { vm.payNext(plan) }) {
                                        Text("确认第 ${plan.paidPeriods + 1} 期 · ${Money.format(plan.amountForNextPeriod())}")
                                    }
                                }
                                TextButton(onClick = { vm.delete(plan.installmentId) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
}
