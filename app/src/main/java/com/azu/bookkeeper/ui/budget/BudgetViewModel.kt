package com.azu.bookkeeper.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Budget
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.BudgetRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val billRepository: BillRepository,
    private val categoryRepository: CategoryRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    data class UiState(
        val bookId: Long = 0L,
        val month: Int = 0,
        val budget: Budget? = null,
        val categories: List<Category> = emptyList(),
        val spentFen: Long = 0L,
        val totalLimitText: String = "",
        val categoryLimitTexts: Map<Long, String> = emptyMap(),
        val message: String? = null
    ) {
        val remainingFen: Long get() = (budget?.totalLimitFen ?: 0L) - spentFen
        val isOverBudget: Boolean get() = budget != null && budget.totalLimitFen > 0L && spentFen > budget.totalLimitFen
        val progress: Float
            get() {
                val limit = budget?.totalLimitFen ?: 0L
                return if (limit > 0L) (spentFen.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId ?: 0L
            val month = DateTimeUtils.monthKey(DateTimeUtils.nowMillis())
            _uiState.update { it.copy(bookId = bookId, month = month) }
            if (bookId > 0L) load(bookId, month)
        }
    }

    private suspend fun load(bookId: Long, month: Int) {
        val budget = budgetRepository.findByBookAndMonth(bookId, month).getOrNull()
        val categories = categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
        val start = DateTimeUtils.startOfMonth(DateTimeUtils.nowMillis())
        val end = DateTimeUtils.endOfMonth(DateTimeUtils.nowMillis())
        val spent = billRepository.sumByType(bookId, start, end)
            .getOrDefault(emptyMap())[BillType.EXPENSE] ?: 0L
        _uiState.update { state ->
            state.copy(
                budget = budget,
                categories = categories,
                spentFen = spent,
                totalLimitText = budget?.totalLimitFen?.let { Money.format(it, withSymbol = false) } ?: "",
                categoryLimitTexts = budget?.categoryLimits?.mapValues { (_, fen) ->
                    Money.format(fen, withSymbol = false)
                } ?: emptyMap()
            )
        }
    }

    fun setTotalLimit(text: String) = _uiState.update { it.copy(totalLimitText = text) }

    fun setCategoryLimit(categoryId: Long, text: String) = _uiState.update { state ->
        state.copy(categoryLimitTexts = state.categoryLimitTexts + (categoryId to text))
    }

    fun save() {
        val state = _uiState.value
        val total = Money.parse(state.totalLimitText) ?: 0L
        val limits = state.categories.associate { category ->
            category.categoryId to (Money.parse(state.categoryLimitTexts[category.categoryId].orEmpty()) ?: 0L)
        }.filterValues { it > 0L }
        viewModelScope.launch {
            val existing = state.budget
            val budget = Budget(
                budgetId = existing?.budgetId ?: 0L,
                bookId = state.bookId,
                month = state.month,
                totalLimitFen = total,
                categoryLimits = limits
            )
            when (val result = budgetRepository.save(budget)) {
                is Result.Success -> {
                    _uiState.update { it.copy(message = "预算已保存") }
                    load(state.bookId, state.month)
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun resetMonth() {
        val state = _uiState.value
        viewModelScope.launch {
            when (val result = budgetRepository.resetMonth(state.bookId, state.month)) {
                is Result.Success -> {
                    _uiState.update { it.copy(message = "本月预算已重置（沿用上月上限）") }
                    load(state.bookId, state.month)
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }
}
