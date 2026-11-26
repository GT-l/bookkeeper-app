package com.azu.bookkeeper.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
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
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.data.local.db.dao.ReimbursementDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.domain.model.ReimburseStatus
import com.azu.bookkeeper.domain.model.Reimbursement
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.finance.AddReimbursementUseCase
import com.azu.bookkeeper.domain.usecase.finance.ReceiveReimbursementUseCase
import com.azu.bookkeeper.domain.usecase.finance.UpdateReimbursementUseCase
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
class ReimbursementsViewModel @Inject constructor(
    private val dao: ReimbursementDao,
    private val settings: SettingsRepository,
    private val add: AddReimbursementUseCase,
    private val receive: ReceiveReimbursementUseCase,
    private val update: UpdateReimbursementUseCase
) : ViewModel() {
    data class UiState(
        val items: List<Reimbursement> = emptyList(),
        val title: String = "",
        val amountText: String = "",
        val filter: Int = -1,
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            if (bookId > 0L) {
                dao.observeByBook(bookId).collect { list ->
                    _uiState.update { it.copy(items = list.map { e -> e.toDomain() }) }
                }
            }
        }
    }

    fun setTitle(v: String) = _uiState.update { it.copy(title = v) }
    fun setAmount(v: String) = _uiState.update { it.copy(amountText = v) }
    fun setFilter(v: Int) = _uiState.update { it.copy(filter = v) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun submit() {
        val s = _uiState.value
        val amount = Money.parse(s.amountText)
        if (amount == null || amount <= 0L) {
            _uiState.update { it.copy(message = "请输入有效金额") }
            return
        }
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            val result = add(Reimbursement(bookId = bookId, title = s.title, amountFen = amount))
            _uiState.update {
                it.copy(
                    message = if (result is Result.Success) "已加入报销清单" else
                        (result as? Result.Error)?.error?.userMessage ?: "保存失败",
                    title = "", amountText = ""
                )
            }
        }
    }

    fun markSubmitted(id: Long) = viewModelScope.launch {
        val r = update.setStatus(id, ReimburseStatus.SUBMITTED)
        _uiState.update { it.copy(message = if (r.isSuccess) "已标记为待到账" else "操作失败") }
    }

    fun markReceived(id: Long) = viewModelScope.launch {
        val r = receive(id, 0L)
        _uiState.update {
            it.copy(message = if (r.isSuccess) "报销已到账，收入账单已生成" else
                (r as? Result.Error)?.error?.userMessage ?: "操作失败")
        }
    }

    fun reject(id: Long) = viewModelScope.launch {
        val r = update.setStatus(id, ReimburseStatus.REJECTED)
        _uiState.update { it.copy(message = if (r.isSuccess) "已标记为驳回" else "操作失败") }
    }

    fun delete(id: Long) = viewModelScope.launch {
        val r = update.delete(id)
        _uiState.update { it.copy(message = if (r.isSuccess) "已删除报销单" else "删除失败") }
    }
}

@Composable
fun ReimbursementsScreen(vm: ReimbursementsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val shown = remember(state.items, state.filter) {
        if (state.filter < 0) state.items else state.items.filter { it.status.code == state.filter }
    }

    Scaffold(topBar = { AppTopBar("报销管理", subtitle = "垫付的钱，记得收回来") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CuteTextField(value = state.title, onValueChange = vm::setTitle,
                            label = "报销事由", placeholder = "例如：出差高铁票", modifier = Modifier.fillMaxWidth())
                        CuteTextField(value = state.amountText, onValueChange = vm::setAmount,
                            label = "报销金额", placeholder = "例如 553.00",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            modifier = Modifier.fillMaxWidth())
                        CutePrimaryButton(onClick = vm::submit, modifier = Modifier.fillMaxWidth()) { Text("添加报销单") }
                        state.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("失败")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-1 to "全部", 0 to "待提交", 1 to "待到账", 2 to "已到账", 3 to "已驳回").forEach { (code, label) ->
                        FilterChip(selected = state.filter == code, onClick = { vm.setFilter(code) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }

            if (shown.isEmpty()) {
                item { EmptyState("这个筛选下还没有报销单") }
            } else {
                items(shown) { item ->
                    SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, fontWeight = FontWeight.SemiBold)
                                    Text("${item.status.label} · ${DateTimeUtils.format(item.updateAt, "MM-dd")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(Money.format(item.amountFen), fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (item.status == ReimburseStatus.PENDING) {
                                    TextButton(onClick = { vm.markSubmitted(item.reimbursementId) }) { Text("已提交") }
                                }
                                if (item.status == ReimburseStatus.PENDING || item.status == ReimburseStatus.SUBMITTED) {
                                    TextButton(onClick = { vm.markReceived(item.reimbursementId) }) { Text("已到账") }
                                    TextButton(onClick = { vm.reject(item.reimbursementId) }) { Text("驳回") }
                                }
                                if (item.status == ReimburseStatus.REJECTED) {
                                    CuteOutlinedButton(onClick = { vm.delete(item.reimbursementId) }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
