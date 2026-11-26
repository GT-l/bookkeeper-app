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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.azu.bookkeeper.data.local.db.dao.DebtDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.domain.model.Debt
import com.azu.bookkeeper.domain.model.DebtDirection
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.finance.AddDebtUseCase
import com.azu.bookkeeper.domain.usecase.finance.DeleteDebtUseCase
import com.azu.bookkeeper.domain.usecase.finance.RepayDebtUseCase
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CuteOutlinedButton
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
class DebtsViewModel @Inject constructor(
    private val dao: DebtDao,
    private val settings: SettingsRepository,
    private val add: AddDebtUseCase,
    private val repay: RepayDebtUseCase,
    private val delete: DeleteDebtUseCase
) : ViewModel() {
    data class UiState(
        val debts: List<Debt> = emptyList(),
        val direction: Int = DebtDirection.BORROW.code,
        val counterparty: String = "",
        val amountText: String = "",
        val dueDate: String = "",
        val note: String = "",
        val repayAmounts: Map<Long, String> = emptyMap(),
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            if (bookId > 0L) {
                dao.observeByBook(bookId).collect { list ->
                    _uiState.update { it.copy(debts = list.map { e -> e.toDomain() }) }
                }
            }
        }
    }

    fun setDirection(v: Int) = _uiState.update { it.copy(direction = v) }
    fun setCounterparty(v: String) = _uiState.update { it.copy(counterparty = v) }
    fun setAmount(v: String) = _uiState.update { it.copy(amountText = v) }
    fun setDueDate(v: String) = _uiState.update { it.copy(dueDate = v) }
    fun setNote(v: String) = _uiState.update { it.copy(note = v) }
    fun setRepayAmount(id: Long, v: String) = _uiState.update { it.copy(repayAmounts = it.repayAmounts + (id to v)) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun submit() {
        val s = _uiState.value
        val amount = Money.parse(s.amountText)
        if (amount == null || amount <= 0L) {
            _uiState.update { it.copy(message = "请输入有效金额") }
            return
        }
        if (s.counterparty.isBlank()) {
            _uiState.update { it.copy(message = "请填写对方姓名") }
            return
        }
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            val result = add(
                Debt(
                    bookId = bookId,
                    direction = DebtDirection.fromCode(s.direction),
                    counterparty = s.counterparty.trim(),
                    totalFen = amount,
                    dueDate = s.dueDate.trim(),
                    note = s.note.trim()
                )
            )
            _uiState.update {
                it.copy(
                    message = if (result is Result.Success) "已登记，收支账单已生成" else
                        (result as? Result.Error)?.error?.userMessage ?: "保存失败",
                    counterparty = "", amountText = "", dueDate = "", note = ""
                )
            }
        }
    }

    fun repay(debt: Debt) {
        val amount = Money.parse(_uiState.value.repayAmounts[debt.debtId].orEmpty())
        if (amount == null || amount <= 0L) {
            _uiState.update { it.copy(message = "请输入还款金额") }
            return
        }
        viewModelScope.launch {
            val r = repay(debt.debtId, amount)
            _uiState.update {
                it.copy(
                    message = if (r is Result.Success) "已登记还款" else
                        (r as? Result.Error)?.error?.userMessage ?: "还款失败",
                    repayAmounts = it.repayAmounts - debt.debtId
                )
            }
        }
    }

    fun delete(id: Long) = viewModelScope.launch {
        val r = delete(id)
        _uiState.update { it.copy(message = if (r.isSuccess) "已删除债务记录" else "删除失败") }
    }
}

@Composable
fun DebtsScreen(vm: DebtsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppTopBar("债务 / 借贷", subtitle = "借入借出，每笔还款都记清楚") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(DebtDirection.BORROW, DebtDirection.LEND).forEach { d ->
                                FilterChipCompat(
                                    selected = state.direction == d.code,
                                    label = d.label,
                                    onClick = { vm.setDirection(d.code) }
                                )
                            }
                        }
                        CuteTextField(value = state.counterparty, onValueChange = vm::setCounterparty,
                            label = "对方姓名", placeholder = "例如：张三", modifier = Modifier.fillMaxWidth())
                        CuteTextField(value = state.amountText, onValueChange = vm::setAmount,
                            label = "金额", placeholder = "例如 1000.00",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            modifier = Modifier.fillMaxWidth())
                        CuteTextField(value = state.dueDate, onValueChange = vm::setDueDate,
                            label = "约定日期（可选）", placeholder = "例如 2026-12-31", modifier = Modifier.fillMaxWidth())
                        CuteTextField(value = state.note, onValueChange = vm::setNote,
                            label = "备注（可选）", placeholder = "借款事由", modifier = Modifier.fillMaxWidth())
                        CutePrimaryButton(onClick = vm::submit, modifier = Modifier.fillMaxWidth()) { Text("登记债务") }
                        state.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("失败")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (state.debts.isEmpty()) {
                item { EmptyState("还没有债务记录") }
            } else {
                items(state.debts) { debt ->
                    SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${debt.direction.label} · ${debt.counterparty}", fontWeight = FontWeight.SemiBold)
                                    if (debt.dueDate.isNotBlank()) {
                                        Text("约定 ${debt.dueDate}", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(Money.format(debt.remainingFen), fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            LinearProgressIndicator(
                                progress = { debt.settledPercent / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp)
                            )
                            Text("已还 ${debt.settledPercent}% · ${debt.status.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!debt.finished) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    CuteTextField(
                                        value = state.repayAmounts[debt.debtId].orEmpty(),
                                        onValueChange = { vm.setRepayAmount(debt.debtId, it) },
                                        label = if (debt.direction == DebtDirection.BORROW) "本次还款" else "本次收回",
                                        placeholder = "金额",
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { vm.repay(debt) }) {
                                        Text(if (debt.direction == DebtDirection.BORROW) "还款" else "收款")
                                    }
                                }
                            }
                            TextButton(onClick = { vm.delete(debt.debtId) }) { Text("删除记录") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipCompat(selected: Boolean, label: String, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) })
}
