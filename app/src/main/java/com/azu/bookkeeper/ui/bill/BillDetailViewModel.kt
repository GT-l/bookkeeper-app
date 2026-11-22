package com.azu.bookkeeper.ui.bill

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.usecase.bill.AddBillUseCase
import com.azu.bookkeeper.domain.usecase.bill.DeleteBillUseCase
import com.azu.bookkeeper.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账单详情：展示、复制、删除（删除会经事务回滚余额）。 */
@HiltViewModel
class BillDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val billRepository: BillRepository,
    private val deleteBill: DeleteBillUseCase,
    private val addBill: AddBillUseCase
) : ViewModel() {

    data class UiState(
        val bill: Bill? = null,
        val message: String? = null,
        val deleted: Boolean = false
    )

    private val billId: Long = savedStateHandle.get<Long>(Screen.ARG_BILL_ID) ?: 0L
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = billRepository.getBill(billId)) {
                is Result.Success -> _uiState.update { it.copy(bill = result.data) }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            when (val result = deleteBill(billId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(deleted = true) }
                    onDeleted()
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    /** 复制为新账单：新 ID、新时间戳，余额按新账单再次变更。 */
    fun copy(onCopied: (Long) -> Unit) {
        val source = _uiState.value.bill ?: return
        val duplicate = source.copy(
            billId = 0L,
            remark = source.remark.ifBlank { source.billType.label } + "（副本）",
            createAt = 0L,
            updateAt = 0L
        )
        viewModelScope.launch {
            when (val result = addBill(duplicate)) {
                is Result.Success -> onCopied(result.data)
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}
