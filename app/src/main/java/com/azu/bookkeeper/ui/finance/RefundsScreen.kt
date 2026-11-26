package com.azu.bookkeeper.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.data.local.db.dao.RefundDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.Refund
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.finance.AddRefundUseCase
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
class RefundsViewModel @Inject constructor(
    private val refundDao: RefundDao,
    private val billRepository: BillRepository,
    private val addRefund: AddRefundUseCase,
    private val settings: SettingsRepository
) : ViewModel() {
    data class UiState(
        val refunds: List<Refund> = emptyList(),
        val sourceBills: List<Bill> = emptyList(),
        val selectedBillId: Long = 0L,
        val amountText: String = "",
        val reason: String = "",
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settings
            val bookId = settings.defaultBookId.first()
            if (bookId > 0L) {
                refundDao.observeByBook(bookId).collect { list ->
                    _uiState.update { it.copy(refunds = list.map { e -> e.toDomain() }) }
                }
            }
        }
        viewModelScope.launch {
            val settings = settings
            val bookId = settings.defaultBookId.first()
            if (bookId > 0L) {
                billRepository.observeBills(bookId).collect { list ->
                    _uiState.update { state ->
                        state.copy(sourceBills = list.filter { it.amountFen > 0L && !it.isAdjustment })
                    }
                }
            }
        }
    }

    fun selectBill(id: Long) = _uiState.update { it.copy(selectedBillId = id, amountText = "", message = null) }
    fun setAmount(text: String) = _uiState.update { it.copy(amountText = text) }
    fun setReason(text: String) = _uiState.update { it.copy(reason = text) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun submit() {
        val state = _uiState.value
        val amount = Money.parse(state.amountText)
        if (state.selectedBillId <= 0L) {
            _uiState.update { it.copy(message = "请选择原账单") }
            return
        }
        if (amount == null || amount <= 0L) {
            _uiState.update { it.copy(message = "请输入有效退款金额") }
            return
        }
        viewModelScope.launch {
            val settings = settings
            val bookId = settings.defaultBookId.first()
            val result = addRefund(
                Refund(
                    bookId = bookId,
                    sourceBillId = state.selectedBillId,
                    amountFen = amount,
                    refundTime = DateTimeUtils.nowMillis(),
                    reason = state.reason
                )
            )
            _uiState.update {
                it.copy(
                    message = if (result is Result.Success) "退款已登记，收入账单已生成" else
                        (result as? Result.Error)?.error?.userMessage ?: "退款失败",
                    amountText = "", reason = ""
                )
            }
        }
    }
}

@Composable
fun RefundsScreen(vm: RefundsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showForm by remember { mutableStateOf(false) }

    Scaffold(topBar = { AppTopBar("退款管理", subtitle = "登记退款，自动记回一笔收入") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("原账单", style = MaterialTheme.typography.labelMedium)
                        state.sourceBills.takeLast(5).reversed().forEach { bill ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${DateTimeUtils.format(bill.billTime, "MM-dd HH:mm")}  ${bill.remark.ifBlank { "无备注" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (state.selectedBillId == bill.billId) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                } else {
                                    androidx.compose.material3.TextButton(onClick = { vm.selectBill(bill.billId) }) {
                                        Text("选择")
                                    }
                                }
                            }
                        }
                        if (state.sourceBills.isEmpty()) {
                            Text("还没有可退款的账单", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CuteTextField(
                            value = state.amountText,
                            onValueChange = vm::setAmount,
                            label = "退款金额",
                            placeholder = "例如 19.90",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            modifier = Modifier.fillMaxWidth()
                        )
                        CuteTextField(
                            value = state.reason,
                            onValueChange = vm::setReason,
                            label = "退款原因（可选）",
                            placeholder = "例如：商品缺货",
                            modifier = Modifier.fillMaxWidth()
                        )
                        CutePrimaryButton(onClick = { vm.submit(); showForm = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("确认退款")
                        }
                        state.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("成功") || it.contains("已登记"))
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (state.refunds.isEmpty()) {
                item { EmptyState("还没有退款记录") }
            } else {
                items(state.refunds) { refund ->
                    SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(DateTimeUtils.format(refund.refundTime), style = MaterialTheme.typography.bodyMedium)
                                Text(refund.reason.ifBlank { "退款" }, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("-${Money.format(refund.amountFen)}", fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
