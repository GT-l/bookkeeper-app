package com.azu.bookkeeper.ui.bill

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.automation.verify.VerificationController
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.map
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.model.CycleType
import com.azu.bookkeeper.domain.model.QuickCaptureRequest
import com.azu.bookkeeper.domain.model.QuickCaptureResult
import com.azu.bookkeeper.domain.model.ResolvedBillInput
import com.azu.bookkeeper.domain.model.Tag
import com.azu.bookkeeper.domain.model.VerifyStatus
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.repository.TagRepository
import com.azu.bookkeeper.domain.usecase.bill.AddBillUseCase
import com.azu.bookkeeper.domain.usecase.bill.UpdateBillUseCase
import com.azu.bookkeeper.domain.usecase.quick.QuickCaptureUseCase
import com.azu.bookkeeper.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 记账表单状态；quickPrefill 由路由 JSON 传入并在 init 中回填。 */
@HiltViewModel
class BillEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val billRepository: BillRepository,
    private val addBillUseCase: AddBillUseCase,
    private val updateBillUseCase: UpdateBillUseCase,
    private val quickCaptureUseCase: QuickCaptureUseCase,
    private val verificationController: VerificationController,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val tagRepository: TagRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    data class UiState(
        val billId: Long = 0L,
        val bookId: Long = 0L,
        val books: List<Book> = emptyList(),
        val billType: BillType = BillType.EXPENSE,
        val amountText: String = "",
        val categoryId: Long = 0L,
        val categories: List<Category> = emptyList(),
        val accountFrom: Long = 0L,
        val accountTo: Long = 0L,
        val accounts: List<Account> = emptyList(),
        val billTime: Long = 0L,
        val remark: String = "",
        val tagIds: List<Long> = emptyList(),
        val tags: List<Tag> = emptyList(),
        val imagePaths: List<String> = emptyList(),
        val cycleType: CycleType = CycleType.NONE,
        val source: BillSource = BillSource.MANUAL,
        val fromCaptureEventId: Long? = null,
        val error: String? = null,
        val isSaving: Boolean = false,
        /** 来源事件已终态：UI 提示「该笔已补记」后关闭表单，绝不重复写库。 */
        val duplicateHandled: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState(billTime = DateTimeUtils.nowMillis()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val billId: Long = savedStateHandle.get<Long>(Screen.ARG_BILL_ID) ?: 0L
    private val prefill = Screen.parsePrefill(savedStateHandle.get<String>(Screen.ARG_QUICK_PREFILL))

    init {
        viewModelScope.launch {
            // 幂等短路（AC-16）：同一 fromCaptureEventId 重复点击不建单。
            if (prefill != null && quickCaptureUseCase.isAlreadyHandled(prefill)) {
                _uiState.update {
                    it.copy(
                        duplicateHandled = true,
                        fromCaptureEventId = prefill.fromCaptureEventId
                    )
                }
                return@launch
            }

            val books = bookRepository.observeAll().first()
            _uiState.update { it.copy(books = books) }
            val existing = if (billId > 0L) billRepository.getBill(billId).getOrNull() else null
            val baseBookId = existing?.bookId
                ?: prefill?.bookId
                ?: settings.defaultBookId.first()
                    .takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId
                ?: books.firstOrNull()?.bookId
                ?: 0L
            val accounts = accountRepository.observeAll(baseBookId).first()
            val allTags = tagRepository.observeByBook(baseBookId).first()
            val kind = existing?.billType?.toCategoryKind() ?: CategoryKind.EXPENSE
            val categories = categoryRepository.observeByBook(baseBookId, kind).first()

            _uiState.update { state ->
                val prefilledAmount = prefill?.amountFen?.takeIf { it > 0L }?.let {
                    Money.format(it, withSymbol = false)
                }
                state.copy(
                    billId = existing?.billId ?: 0L,
                    bookId = baseBookId,
                    accounts = accounts,
                    tags = allTags,
                    categories = categories,
                    billType = existing?.billType ?: BillType.EXPENSE,
                    amountText = existing?.amountFen?.let { Money.format(it, withSymbol = false) }
                        ?: prefilledAmount.orEmpty(),
                    categoryId = existing?.categoryId ?: prefill?.categoryId ?: 0L,
                    accountFrom = existing?.accountFrom ?: prefill?.accountId
                        ?: accounts.firstOrNull()?.accountId ?: 0L,
                    accountTo = existing?.accountTo ?: 0L,
                    billTime = existing?.billTime ?: prefill?.billTime ?: DateTimeUtils.nowMillis(),
                    remark = existing?.remark ?: prefill?.merchant.orEmpty(),
                    tagIds = existing?.tagIds ?: emptyList(),
                    imagePaths = existing?.imagePaths ?: emptyList(),
                    cycleType = existing?.cycleType ?: CycleType.NONE,
                    source = existing?.source ?: prefill?.source ?: BillSource.MANUAL,
                    fromCaptureEventId = prefill?.fromCaptureEventId
                )
            }
        }
    }

    fun setBillType(type: BillType) {
        _uiState.update { it.copy(billType = type, categoryId = 0L) }
        viewModelScope.launch {
            val kind = type.toCategoryKind()
            val categories = categoryRepository.observeByBook(_uiState.value.bookId, kind).first()
            _uiState.update { it.copy(categories = categories) }
        }
    }

    fun setAmount(text: String) = _uiState.update { it.copy(amountText = text) }

    fun setBook(bookId: Long) {
        viewModelScope.launch {
            val accounts = accountRepository.observeAll(bookId).first()
            val categories = categoryRepository.observeByBook(bookId, _uiState.value.billType.toCategoryKind()).first()
            val tags = tagRepository.observeByBook(bookId).first()
            _uiState.update {
                it.copy(
                    bookId = bookId,
                    accounts = accounts,
                    categories = categories,
                    tags = tags,
                    accountFrom = accounts.firstOrNull()?.accountId ?: 0L,
                    categoryId = 0L
                )
            }
        }
    }

    fun setCategory(id: Long) = _uiState.update { it.copy(categoryId = id) }

    fun setAccountFrom(id: Long) = _uiState.update { it.copy(accountFrom = id) }

    fun setAccountTo(id: Long) = _uiState.update { it.copy(accountTo = id) }

    fun setBillTime(time: Long) = _uiState.update { it.copy(billTime = time) }

    fun setRemark(text: String) = _uiState.update { it.copy(remark = text) }

    fun toggleTag(tagId: Long) = _uiState.update { state ->
        val next = if (tagId in state.tagIds) state.tagIds - tagId else state.tagIds + tagId
        state.copy(tagIds = next.take(com.azu.bookkeeper.core.Constants.MAX_TAGS_PER_BILL))
    }

    fun addImage(path: String) = _uiState.update { state ->
        state.copy(
            imagePaths = (state.imagePaths + path)
                .distinct()
                .take(com.azu.bookkeeper.core.Constants.MAX_IMAGES_PER_BILL)
        )
    }

    fun removeImage(path: String) = _uiState.update { it.copy(imagePaths = it.imagePaths - path) }

    fun setCycle(type: CycleType) = _uiState.update { it.copy(cycleType = type) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /**
     * 离开表单（取消 / 系统返回 / 保存后退出）：解除「补记中」的提醒抑制。
     * 本方法绝不写库——取消零写入由这里与 UI 的 popBackStack 共同保证。
     */
    fun onCancel() {
        prefill?.fromCaptureEventId?.let { verificationController.cancelQuickCapture(it) }
    }

    /**
     * 保存：手动新增/编辑保持既有路径；所有快捷预填（含 douyin fallback）走
     * [QuickCaptureUseCase]，其内部显式以 USER_CORRECTED 终态调用单一写事务。
     * 取消由 UI 直接 popBackStack，本方法不参与，因此不产生任何写库。
     */
    fun save(onSaved: (Long) -> Unit) {
        val state = _uiState.value
        if (state.isSaving || state.duplicateHandled) return
        val amountFen = Money.parse(state.amountText.replace(",", ""))
        val bill = Bill(
            billId = state.billId,
            bookId = state.bookId,
            billType = state.billType,
            amountFen = amountFen ?: 0L,
            categoryId = state.categoryId,
            tagIds = state.tagIds,
            accountFrom = state.accountFrom,
            accountTo = state.accountTo,
            billTime = state.billTime,
            remark = state.remark,
            imagePaths = state.imagePaths,
            cycleType = state.cycleType,
            source = state.source
        )
        addBillUseCase.validate(bill)?.let { error ->
            _uiState.update { it.copy(error = error.userMessage) }
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val isQuick = state.billId <= 0L && prefill != null &&
                (state.source == BillSource.QUICK_MANUAL || state.fromCaptureEventId != null)
            val result = if (state.billId > 0L) {
                updateBillUseCase(bill).map { state.billId }
            } else if (isQuick) {
                saveQuick(bill, state)
            } else {
                addBillUseCase(bill)
            }
            when (result) {
                is Result.Success -> {
                    _uiState.update { it.copy(isSaving = false) }
                    onSaved(result.data)
                }
                is Result.Error -> _uiState.update {
                    it.copy(isSaving = false, error = result.error.userMessage)
                }
                Result.Loading -> Unit
            }
        }
    }

    /** 表单最终值 → 统一契约 → QuickCaptureUseCase；结果映射回既有 Result 类型。 */
    private suspend fun saveQuick(bill: Bill, state: UiState): Result<Long> {
        val request = prefill?.toRequest()?.copy(
            source = bill.source,
            amountFen = bill.amountFen,
            merchant = bill.remark,
            billTime = bill.billTime,
            bookId = bill.bookId,
            accountId = bill.accountFrom,
            categoryId = bill.categoryId.takeIf { it > 0L },
            fromCaptureEventId = state.fromCaptureEventId
        ) ?: QuickCaptureRequest(entry = com.azu.bookkeeper.domain.model.QuickEntry.DOUYIN_FALLBACK)

        val resolved = ResolvedBillInput(
            bookId = bill.bookId,
            billType = bill.billType,
            amountFen = bill.amountFen,
            categoryId = bill.categoryId,
            accountFrom = bill.accountFrom,
            accountTo = bill.accountTo,
            billTime = bill.billTime,
            merchant = bill.remark,
            tagIds = bill.tagIds,
            imagePaths = bill.imagePaths,
            cycleType = bill.cycleType,
            verifyStatus = if (state.fromCaptureEventId != null) {
                VerifyStatus.USER_CORRECTED
            } else {
                VerifyStatus.USER_CONFIRMED
            }
        )
        return when (val outcome = quickCaptureUseCase(request, resolved)) {
            is QuickCaptureResult.Saved -> Result.Success(outcome.billId)
            is QuickCaptureResult.Rejected -> Result.Error(outcome.error)
            QuickCaptureResult.Cancelled -> Result.Error(
                com.azu.bookkeeper.core.AppError.Validation(
                    code = "E_QUICK_001",
                    userMessage = "已取消，未保存账单"
                )
            )
        }
    }
}

/** 账单类型 → 分类树种类（转账不需要分类）。 */
internal fun BillType.toCategoryKind(): CategoryKind = when (this) {
    BillType.INCOME -> CategoryKind.INCOME
    else -> CategoryKind.EXPENSE
}
