package com.azu.bookkeeper.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.finance.BatchAddBillsUseCase
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.ui.common.CutePrimaryButton
import com.azu.bookkeeper.ui.common.CuteTextField
import com.azu.bookkeeper.ui.common.SoftCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class BatchAddViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val billRepository: BillRepository,
    private val batchAdd: BatchAddBillsUseCase
) : ViewModel() {
    data class Draft(val amount: String = "", val remark: String = "")
    data class UiState(
        val bookId: Long = 0L,
        val drafts: List<Draft> = listOf(Draft()),
        val categories: List<Category> = emptyList(),
        val accounts: List<Account> = emptyList(),
        val selectedCategory: Long = 0L,
        val selectedAccount: Long = 0L,
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first()
            val accounts = accountRepository.observeAll(bookId).first()
            val categories = categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
            _uiState.update {
                it.copy(
                    bookId = bookId,
                    accounts = accounts,
                    categories = categories,
                    selectedAccount = it.selectedAccount.takeIf { id -> id > 0L }
                        ?: accounts.firstOrNull()?.accountId ?: 0L,
                    selectedCategory = it.selectedCategory.takeIf { id -> id > 0L }
                        ?: categories.firstOrNull()?.categoryId ?: 0L
                )
            }
        }
    }

    fun setAmount(index: Int, value: String) = updateDraft(index) { it.copy(amount = value) }
    fun setRemark(index: Int, value: String) = updateDraft(index) { it.copy(remark = value) }
    private fun updateDraft(index: Int, block: (Draft) -> Draft) = _uiState.update { s ->
        s.copy(drafts = s.drafts.mapIndexed { i, d -> if (i == index) block(d) else d })
    }

    fun addRow() = _uiState.update { it.copy(drafts = it.drafts + Draft()) }
    fun removeRow(index: Int) = _uiState.update { s ->
        if (s.drafts.size <= 1) s else s.copy(drafts = s.drafts.filterIndexed { i, _ -> i != index })
    }
    fun setCategory(id: Long) = _uiState.update { it.copy(selectedCategory = id) }
    fun setAccount(id: Long) = _uiState.update { it.copy(selectedAccount = id) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun submit() {
        val s = _uiState.value
        val valid = s.drafts.mapNotNull { d -> Money.parse(d.amount)?.takeIf { it > 0L }?.let { d to it } }
        if (valid.isEmpty()) {
            _uiState.update { it.copy(message = "请至少填写一笔金额") }
            return
        }
        val bills = valid.map { (draft, fen) ->
            Bill(
                bookId = s.bookId,
                billType = BillType.EXPENSE,
                amountFen = fen,
                categoryId = s.selectedCategory,
                accountFrom = s.selectedAccount,
                remark = draft.remark.trim()
            )
        }
        viewModelScope.launch {
            val result = batchAdd(bills)
            // 展示成功笔数：Result.Success 的 data 即成功数
            val count = (result as? Result.Success)?.data ?: 0
            _uiState.update {
                it.copy(
                    message = if (result is Result.Success) "已保存 $count 笔账单" else
                        (result as? Result.Error)?.error?.userMessage ?: "保存失败",
                    drafts = if (result is Result.Success) listOf(Draft()) else it.drafts
                )
            }
        }
    }
}

@Composable
fun BatchAddScreen(vm: BatchAddViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppTopBar("批量添加", subtitle = "一次录入多笔，共用分类和账户") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("统一分类", style = MaterialTheme.typography.labelMedium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.categories.take(6).forEach { c ->
                                FilterChip(
                                    selected = state.selectedCategory == c.categoryId,
                                    onClick = { vm.setCategory(c.categoryId) },
                                    label = { Text(c.name, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        Text("统一账户", style = MaterialTheme.typography.labelMedium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.accounts.take(6).forEach { a ->
                                FilterChip(
                                    selected = state.selectedAccount == a.accountId,
                                    onClick = { vm.setAccount(a.accountId) },
                                    label = { Text(a.name, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(state.drafts) { index, draft ->
                SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("第 ${index + 1} 笔", fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            if (state.drafts.size > 1) {
                                TextButton(onClick = { vm.removeRow(index) }) { Text("删除") }
                            }
                        }
                        CuteTextField(value = draft.amount, onValueChange = { vm.setAmount(index, it) },
                            label = "金额", placeholder = "0.00",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            modifier = Modifier.fillMaxWidth())
                        CuteTextField(value = draft.remark, onValueChange = { vm.setRemark(index, it) },
                            label = "备注（可选）", placeholder = "买了什么",
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CutePrimaryButton(onClick = vm::addRow, modifier = Modifier.fillMaxWidth()) { Text("+ 再加一笔") }
                    CutePrimaryButton(onClick = vm::submit, modifier = Modifier.fillMaxWidth()) {
                        Text("保存全部（${state.drafts.count { Money.parse(it.amount) != null }} 笔）")
                    }
                    androidx.compose.material3.TextButton(onClick = vm::consumeMessage, modifier = Modifier.fillMaxWidth()) {
                        Text(state.message.orEmpty())
                    }
                }
            }
        }
    }
}
