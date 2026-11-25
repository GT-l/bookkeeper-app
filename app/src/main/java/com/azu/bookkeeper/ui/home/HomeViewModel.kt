package com.azu.bookkeeper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.usecase.bill.InstantBillCreator
import com.azu.bookkeeper.domain.usecase.bill.ObserveBillsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 首页状态：日期分组 + 当日收支合计 + 分页可见条数。 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val observeBills: ObserveBillsUseCase,
    private val bookRepository: BookRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val instantBillCreator: InstantBillCreator
) : ViewModel() {

    data class DayGroup(
        val dayStart: Long,
        val incomeFen: Long,
        val expenseFen: Long,
        val bills: List<Bill>
    )

    data class UiState(
        val bookId: Long = 0L,
        val books: List<Book> = emptyList(),
        val accounts: List<Account> = emptyList(),
        val bills: List<Bill> = emptyList(),
        val visibleCount: Int = PAGE_SIZE,
        val message: String? = null
    ) {
        val dayGroups: List<DayGroup>
            get() = bills.take(visibleCount)
                .groupBy { com.azu.bookkeeper.core.DateTimeUtils.startOfDay(it.billTime) }
                .map { (day, items) ->
                    DayGroup(
                        dayStart = day,
                        incomeFen = items.filter { it.billType == com.azu.bookkeeper.domain.model.BillType.INCOME }
                            .sumOf { it.amountFen },
                        expenseFen = items.filter { it.billType == com.azu.bookkeeper.domain.model.BillType.EXPENSE }
                            .sumOf { it.amountFen },
                        bills = items
                    )
                }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 当前账本的数据订阅任务；切换账本时取消重建，避免跨账本串流。 */
    private var dataJob: Job? = null

    init {
        viewModelScope.launch {
            bookRepository.observeAll().collect { books ->
                _uiState.update { it.copy(books = books) }
            }
        }
        viewModelScope.launch {
            settings.defaultBookId.collect { preferred ->
                val resolved = if (preferred > 0L) preferred else {
                    bookRepository.findDefault().getOrNull()?.bookId
                        ?: bookRepository.observeAll().first().firstOrNull()?.bookId
                        ?: 0L
                }
                _uiState.update { it.copy(bookId = resolved, bills = emptyList(), visibleCount = PAGE_SIZE) }
                dataJob?.cancel()
                if (resolved > 0L) {
                    // 修复「添加消费后概览/列表不更新」：账户流与账单流必须各自独立协程收集。
                    // 旧实现把 observeBills().collect 串在 observeAll().collect 之后，
                    // 而 Flow.collect 永不返回，账单流永远不会被订阅，数据库变更推送全部丢失。
                    dataJob = launch {
                        launch {
                            accountRepository.observeAll(resolved).collect { accounts ->
                                _uiState.update { it.copy(accounts = accounts) }
                            }
                        }
                        launch {
                            observeBills(resolved).collect { bills ->
                                _uiState.update { it.copy(bills = bills, message = null) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 极简记账：一键按默认配置创建占位账单，
     * 成功后回调新账单 ID（首页跳转到详情页让用户长按就地补全）。
     */
    fun createInstantBill(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            when (val result = instantBillCreator()) {
                is Result.Success -> onCreated(result.data)
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    fun switchBook(bookId: Long) {
        viewModelScope.launch { settings.setDefaultBookId(bookId) }
    }

    fun loadMore() {
        _uiState.update { it.copy(visibleCount = it.visibleCount + PAGE_SIZE) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
