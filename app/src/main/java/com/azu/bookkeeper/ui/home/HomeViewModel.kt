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
import com.azu.bookkeeper.domain.usecase.bill.DeleteBillUseCase
import com.azu.bookkeeper.domain.usecase.bill.InstantBillCreator
import com.azu.bookkeeper.domain.usecase.bill.ObserveBillsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 首页状态：日期分组 + 当日收支合计 + 分页可见条数。 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val observeBills: ObserveBillsUseCase,
    private val bookRepository: BookRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val instantBillCreator: InstantBillCreator,
    private val deleteBillUseCase: DeleteBillUseCase
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
        /**
         * 分组结果（v1.3.9 性能修复）。
         *
         * 旧实现是 `get()` 派生属性：**每次访问**都会 `take + groupBy + 两次 filter + sumOf`，
         * 而首页一次重组会访问 2~3 次（标题计数、概览卡、列表），账单几百条时每次切页
         * 都要白白分配几十个临时 List —— 这是「上下滑动不流畅」的主要来源。
         *
         * 现在改为构造时算一次（单次遍历、无中间集合）。`copy()` 会走主构造器，
         * 因此 bills / visibleCount 变化时仍会即时重算，语义不变。
         */
        val dayGroups: List<DayGroup> = buildDayGroups(bills, visibleCount)

        /** 当前可见范围内的收支合计，同样只算一次。 */
        val incomeFen: Long = dayGroups.sumOf { it.incomeFen }
        val expenseFen: Long = dayGroups.sumOf { it.expenseFen }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 当前账本的数据订阅任务；切换账本时取消重建，避免跨账本串流。 */
    private var dataJob: Job? = null

    init {
        viewModelScope.launch {
            bookRepository.observeAll()
                .catch { e ->
                    // 库损坏等异常不允许沿协程炸掉进程：置空并由 CrashGuard 在下次启动自愈
                    Timber.w(e, "observe books failed")
                    emit(emptyList())
                }
                .collect { books ->
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
                            accountRepository.observeAll(resolved)
                                .catch { e ->
                                    Timber.w(e, "observe accounts failed")
                                    emit(emptyList())
                                }
                                .collect { accounts ->
                                    _uiState.update { it.copy(accounts = accounts) }
                                }
                        }
                        launch {
                            observeBills(resolved)
                                .catch { e ->
                                    Timber.w(e, "observe bills failed")
                                    emit(emptyList())
                                }
                                .collect { bills ->
                                    // 展示层过滤 0 值账单（一键建单的 0 分占位账单、余额校准残留等）：
                                    // 只影响列表呈现，不删库——数据由详情页 onLeave / 用户删除负责清理。
                                    _uiState.update {
                                        it.copy(
                                            bills = bills.filter { bill -> bill.amountFen > 0L },
                                            message = null
                                        )
                                    }
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

    /**
     * 首页滑动删除（契约方法，首页列表 swipe-to-dismiss 调用）。
     *
     * 走 DeleteBillUseCase 的带余额回滚事务（deleteWithBalanceTx），
     * 余额回滚与账单删除在同一事务内完成；失败经 UiState.message 提示，成功由 Flow 自动刷新列表。
     *
     * @param billId 待删除账单 ID；<=0 时直接忽略。
     */
    fun deleteBill(billId: Long) {
        if (billId <= 0L) return
        viewModelScope.launch {
            when (val result = deleteBillUseCase(billId)) {
                is Result.Success -> _uiState.update { it.copy(message = null) }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
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

/**
 * 按天分组（v1.3.9）：单次遍历完成分组与小计。
 *
 * 依赖 DAO 已按 billTime 倒序返回；同一笔账单只会进一个组。
 * 刻意不用 `groupBy { }` + 两次 `filter` + `sumOf`：那会在每次访问时产生
 * 1 个 Map + N 个 List + 2N 个过滤 List，滚动时反复分配正是卡顿来源。
 */
private fun buildDayGroups(
    bills: List<Bill>,
    visibleCount: Int
): List<HomeViewModel.DayGroup> {
    if (bills.isEmpty()) return emptyList()
    val end = visibleCount.coerceIn(0, bills.size)
    if (end == 0) return emptyList()
    val groups = ArrayList<HomeViewModel.DayGroup>()
    var currentDay = Long.MIN_VALUE
    var income = 0L
    var expense = 0L
    val items = ArrayList<Bill>()
    fun flush() {
        if (items.isNotEmpty()) {
            groups.add(
                HomeViewModel.DayGroup(
                    dayStart = currentDay,
                    incomeFen = income,
                    expenseFen = expense,
                    bills = ArrayList(items)
                )
            )
            items.clear()
        }
    }
    for (index in 0 until end) {
        val bill = bills[index]
        val day = com.azu.bookkeeper.core.DateTimeUtils.startOfDay(bill.billTime)
        if (day != currentDay) {
            flush()
            currentDay = day
            income = 0L
            expense = 0L
        }
        when (bill.billType) {
            com.azu.bookkeeper.domain.model.BillType.INCOME -> income += bill.amountFen
            com.azu.bookkeeper.domain.model.BillType.EXPENSE -> expense += bill.amountFen
            else -> Unit
        }
        items.add(bill)
    }
    flush()
    return groups
}
