package com.azu.bookkeeper.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillQuery
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.bill.DeleteBillUseCase
import com.azu.bookkeeper.domain.usecase.bill.SearchBillsUseCase
import com.azu.bookkeeper.domain.usecase.bill.UpdateBillUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchBills: SearchBillsUseCase,
    private val billRepository: BillRepository,
    private val updateBill: UpdateBillUseCase,
    private val deleteBill: DeleteBillUseCase,
    private val settings: SettingsRepository
) : ViewModel() {

    data class UiState(
        val keyword: String = "",
        val typeFilter: BillType? = null,
        val sourceFilter: BillSource? = null,
        val results: List<Bill> = emptyList(),
        val selectedIds: Set<Long> = emptySet(),
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null

    init { search() }

    fun setKeyword(value: String) {
        _uiState.update { it.copy(keyword = value) }
        search()
    }

    fun setTypeFilter(value: BillType?) {
        _uiState.update { it.copy(typeFilter = value) }
        search()
    }

    fun setSourceFilter(value: BillSource?) {
        _uiState.update { it.copy(sourceFilter = value) }
        search()
    }

    fun toggleSelect(billId: Long) = _uiState.update { state ->
        state.copy(
            selectedIds = if (billId in state.selectedIds) state.selectedIds - billId
            else state.selectedIds + billId
        )
    }

    fun selectAll() = _uiState.update { state ->
        state.copy(
            selectedIds = if (state.selectedIds.size == state.results.size) emptySet()
            else state.results.map { it.billId }.toSet()
        )
    }

    private fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250L)
            val state = _uiState.value
            val bookId = settings.defaultBookId.first()
            val query = BillQuery(
                bookId = bookId,
                keyword = state.keyword.trim(),
                billType = state.typeFilter,
                sources = state.sourceFilter?.let { listOf(it) } ?: emptyList(),
                limit = 500
            )
            when (val result = searchBills(query)) {
                is Result.Success -> _uiState.update {
                    it.copy(results = result.data, selectedIds = emptySet())
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun batchDelete() {
        val ids = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            var success = 0
            ids.forEach { id ->
                if (deleteBill(id) is Result.Success) success++
            }
            _uiState.update { it.copy(message = "已删除 $success 条账单") }
            search()
        }
    }

    fun batchSetCategory(categoryId: Long) {
        if (categoryId <= 0L) return
        val ids = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            var success = 0
            ids.forEach { id ->
                val bill = billRepository.getBill(id).getOrNull()
                if (bill != null && updateBill(bill.copy(categoryId = categoryId)) is Result.Success) success++
            }
            _uiState.update { it.copy(message = "已修改 $success 条账单分类") }
            search()
        }
    }

    fun batchSetAccount(accountId: Long) {
        if (accountId <= 0L) return
        val ids = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            var success = 0
            ids.forEach { id ->
                val bill = billRepository.getBill(id).getOrNull()
                if (bill != null && updateBill(bill.copy(accountFrom = accountId)) is Result.Success) success++
            }
            _uiState.update { it.copy(message = "已修改 $success 条账单账户") }
            search()
        }
    }
}
