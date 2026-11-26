package com.azu.bookkeeper.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.BudgetRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 「我的」页数据：不只看当月，而是把「你记了多久、一共记了多少、多少是自动记的」摊开。
 *
 * 取数原则：
 * - 账单/账户/预算都走既有仓储的 Flow，数据库一变（记一笔、删一笔）页面立刻跟着变；
 * - 分类名只在账单变化时补查一次，不做轮询；
 * - 任何一路 Flow 抛异常都只置空这一块，不让整页崩掉。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val bookRepository: BookRepository,
    private val billRepository: BillRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository
) : ViewModel() {

    /** 本月支出构成的一行（分类名 + 金额 + 占比）。 */
    data class CategorySlice(
        val name: String,
        val amountFen: Long,
        val ratio: Float
    )

    data class UiState(
        val bookId: Long = 0L,
        val bookName: String = "",
        val bookCount: Int = 0,
        val accountCount: Int = 0,
        val netAssetFen: Long = 0L,
        val monthIncomeFen: Long = 0L,
        val monthExpenseFen: Long = 0L,
        val totalBills: Int = 0,
        val autoBills: Int = 0,
        val recordDays: Int = 0,
        val firstBillTime: Long = 0L,
        val budgetLimitFen: Long = 0L,
        val topCategories: List<CategorySlice> = emptyList(),
        val message: String? = null
    ) {
        /** 本月结余（收入 - 支出）。 */
        val monthBalanceFen: Long get() = monthIncomeFen - monthExpenseFen

        /** 自动记账占比 0~100，用于文案「其中 N% 是自动记的」。 */
        val autoPercent: Int
            get() = if (totalBills <= 0) 0 else (autoBills * 100 / totalBills)

        /** 预算已用百分比（0~100+，超支可大于 100）。 */
        val budgetUsedPercent: Int
            get() = if (budgetLimitFen <= 0L) 0 else ((monthExpenseFen * 100) / budgetLimitFen).toInt()

        val hasBudget: Boolean get() = budgetLimitFen > 0L
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var dataJob: Job? = null

    init {
        viewModelScope.launch {
            bookRepository.observeAll()
                .catch { e ->
                    Timber.w(e, "profile: observe books failed")
                    emit(emptyList<Book>())
                }
                .collect { books ->
                    _uiState.update { it.copy(bookCount = books.size) }
                }
        }
        viewModelScope.launch {
            settings.defaultBookId.collect { preferred ->
                val resolved = if (preferred > 0L) {
                    preferred
                } else {
                    bookRepository.findDefault().getOrNull()?.bookId
                        ?: bookRepository.observeAll().first().firstOrNull()?.bookId
                        ?: 0L
                }
                val name = bookRepository.observeAll().first()
                    .firstOrNull { it.bookId == resolved }?.name.orEmpty()
                _uiState.update {
                    it.copy(
                        bookId = resolved,
                        bookName = name,
                        // 换账本先清空旧账本的汇总，避免旧数字一闪而过
                        monthIncomeFen = 0L,
                        monthExpenseFen = 0L,
                        totalBills = 0,
                        autoBills = 0,
                        recordDays = 0,
                        firstBillTime = 0L,
                        topCategories = emptyList()
                    )
                }
                dataJob?.cancel()
                if (resolved > 0L) dataJob = launch { observeAll(resolved) }
            }
        }
    }

    /** 三路 Flow 并行收集：任一路异常只影响自己那一块，不牵连整页。 */
    private suspend fun observeAll(bookId: Long) = coroutineScope {
        launch {
            accountRepository.observeAll(bookId)
                .catch { e ->
                    Timber.w(e, "profile: observe accounts failed")
                    emit(emptyList())
                }
                .collect { accounts ->
                    _uiState.update {
                        it.copy(
                            accountCount = accounts.count { account -> account.enabled },
                            netAssetFen = accounts.filter { account -> account.enabled }
                                .sumOf { account -> account.netBalanceFen }
                        )
                    }
                }
        }
        launch {
            budgetRepository.observeByBookAndMonth(bookId, DateTimeUtils.monthKey(DateTimeUtils.nowMillis()))
                .catch { e ->
                    Timber.w(e, "profile: observe budget failed")
                    emit(null)
                }
                .collect { budget ->
                    _uiState.update { it.copy(budgetLimitFen = budget?.effectiveTotalLimitFen ?: 0L) }
                }
        }
        launch {
            billRepository.observeBills(bookId)
                .catch { e ->
                    Timber.w(e, "profile: observe bills failed")
                    emit(emptyList())
                }
                .collect { bills -> applyBills(bookId, bills) }
        }
    }

    /** 把整本账单折成「累计 / 本月 / 天数 / Top 分类」四块，只遍历一次。 */
    private suspend fun applyBills(bookId: Long, allBills: List<Bill>) {
        val bills = allBills.filter { it.amountFen > 0L }
        val monthStart = DateTimeUtils.startOfMonth(DateTimeUtils.nowMillis())
        val monthEnd = DateTimeUtils.endOfMonth(DateTimeUtils.nowMillis())
        val monthBills = bills.filter { it.billTime in monthStart..monthEnd }

        val monthExpense = monthBills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountFen }
        val monthIncome = monthBills.filter { it.billType == BillType.INCOME }.sumOf { it.amountFen }
        val days = bills.map { DateTimeUtils.startOfDay(it.billTime) }.distinct().size

        val top = monthBills
            .filter { it.billType == BillType.EXPENSE && it.categoryId > 0L }
            .groupBy { it.categoryId }
            .mapValues { (_, items) -> items.sumOf { it.amountFen } }
            .entries
            .sortedByDescending { it.value }
            .take(3)

        val names = runCatching {
            categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
                .associate { it.categoryId to it.name }
        }.getOrDefault(emptyMap())

        _uiState.update {
            it.copy(
                monthExpenseFen = monthExpense,
                monthIncomeFen = monthIncome,
                totalBills = bills.size,
                autoBills = bills.count { bill -> bill.source == BillSource.AUTO_CAPTURE },
                recordDays = days,
                firstBillTime = bills.minOfOrNull { bill -> bill.billTime } ?: 0L,
                topCategories = top.map { entry ->
                    CategorySlice(
                        name = names[entry.key] ?: "未分类",
                        amountFen = entry.value,
                        ratio = if (monthExpense <= 0L) 0f else entry.value.toFloat() / monthExpense
                    )
                }
            )
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
