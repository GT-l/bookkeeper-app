package com.azu.bookkeeper.ui.bill

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.Category
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.usecase.bill.AddBillUseCase
import com.azu.bookkeeper.domain.usecase.bill.DeleteBillUseCase
import com.azu.bookkeeper.domain.usecase.bill.UpdateBillUseCase
import com.azu.bookkeeper.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 详情页支持就地编辑（长按对应行触发）的字段。 */
sealed interface EditField {
    object Amount : EditField
    object Remark : EditField
    object Category : EditField
    object Account : EditField
    object Time : EditField
}

/**
 * 账单详情：查看、就地编辑（长按金额/备注/分类/账户/时间弹单字段编辑器）、
 * 复制、删除（删除会经事务回滚余额）。
 */
@HiltViewModel
class BillDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val billRepository: BillRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val updateBill: UpdateBillUseCase,
    private val deleteBill: DeleteBillUseCase,
    private val addBill: AddBillUseCase,
    @IoDispatcher ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    data class UiState(
        val bill: Bill? = null,
        val categories: List<Category> = emptyList(),
        val accounts: List<Account> = emptyList(),
        val editingField: EditField? = null,
        val message: String? = null,
        val deleted: Boolean = false
    )

    private val billId: Long = savedStateHandle.get<Long>(Screen.ARG_BILL_ID) ?: 0L
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 进入页面时的账单快照：用于判断用户是否真的改动过这笔账单。
     * 一键建单会先落一条 0 分占位账单再跳详情页，用户什么都没改就返回时必须把它删掉。
     */
    private data class BillSnapshot(
        val billId: Long,
        val amountFen: Long,
        val remark: String,
        val categoryId: Long,
        val accountFrom: Long,
        val billTime: Long,
        val tagIds: List<Long>,
        val imagePaths: List<String>
    ) {
        companion object {
            fun from(bill: Bill): BillSnapshot = BillSnapshot(
                billId = bill.billId,
                amountFen = bill.amountFen,
                remark = bill.remark,
                categoryId = bill.categoryId,
                accountFrom = bill.accountFrom,
                billTime = bill.billTime,
                tagIds = bill.tagIds,
                imagePaths = bill.imagePaths
            )
        }
    }

    /** 初始快照；账单还没加载完成时为 null。 */
    private var initialSnapshot: BillSnapshot? = null

    /** 任一 updateXxx 成功落库后置 true：用户确实改过，离开时不得删除。 */
    private var dirty: Boolean = false

    /** onLeave 幂等保护：返回键与顶部返回按钮可能先后触发。 */
    private var leaveHandled: Boolean = false

    /**
     * 离开页面时的静默清理 scope。
     *
     * 不能用 viewModelScope：[onLeave] 之后页面立刻 popBackStack，ViewModel 随即 onCleared，
     * viewModelScope 会被取消，删除事务可能半途中断。这里用与 Service 同款的
     * SupervisorJob + 注入的 IO 调度器（禁止硬编码 Dispatchers，架构 §9.5）。
     */
    private val leaveScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        viewModelScope.launch {
            when (val result = billRepository.getBill(billId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(bill = result.data) }
                    result.data?.let { bill ->
                        initialSnapshot = BillSnapshot.from(bill)
                        loadBookData(bill.bookId)
                    }
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    /** 加载账本下的支出分类与账户，供就地编辑的选择列表使用。 */
    private suspend fun loadBookData(bookId: Long) {
        val categories = categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
        val accounts = accountRepository.observeAll(bookId).first()
        _uiState.update { it.copy(categories = categories, accounts = accounts) }
    }

    // ---------- 就地编辑：打开 / 关闭编辑器 ----------

    fun startEdit(field: EditField) = _uiState.update { it.copy(editingField = field) }

    fun cancelEdit() = _uiState.update { it.copy(editingField = null) }

    // ---------- 就地编辑：各字段保存 ----------

    /** 金额（元文本）→ 分；非法或非正数时提示且不关闭编辑器。 */
    fun updateAmount(rawText: String) {
        val fen = Money.parse(rawText)
        if (fen == null || fen <= 0L) {
            _uiState.update { it.copy(message = "金额必须是大于 0 的数字") }
            return
        }
        persist { it.copy(amountFen = fen) }
    }

    /** 备注（允许为空，空时展示「—」）。 */
    fun updateRemark(text: String) = persist { it.copy(remark = text.trim()) }

    fun updateCategory(categoryId: Long) = persist { it.copy(categoryId = categoryId) }

    fun updateAccount(accountId: Long) = persist { it.copy(accountFrom = accountId) }

    /** 时间：接受解析出的毫秒时间戳；保留原时刻的时分秒由「改为现在」覆盖。 */
    fun updateBillTime(timeMillis: Long) = persist { it.copy(billTime = timeMillis) }

    /** 时间快捷入口：一键把账单时间改为现在。 */
    fun updateBillTimeToNow() = persist { it.copy(billTime = DateTimeUtils.nowMillis()) }

    /**
     * 时间（文本输入）：经 DateTimeUtils.parseFlexible 多格式容错解析；
     * 解析失败时提示且不关闭编辑器。
     */
    fun updateBillTimeText(rawText: String) {
        val parsed = DateTimeUtils.parseFlexible(rawText)
        if (parsed == null) {
            _uiState.update { it.copy(message = "时间格式不对，试试 2026-01-01 12:00") }
            return
        }
        persist { it.copy(billTime = parsed) }
    }

    /**
     * 通用持久化：经 UpdateBillUseCase（事务内反向回滚旧余额再应用新余额），
     * 成功后用新账单刷新状态并关闭编辑器。
     */
    private fun persist(transform: (Bill) -> Bill) {
        val current = _uiState.value.bill ?: return
        val updated = transform(current)
        viewModelScope.launch {
            when (val result = updateBill(updated)) {
                is Result.Success -> {
                    // 用户确实改过这笔账单：打上 dirty 标记，离开时不再当占位账单清理
                    dirty = true
                    _uiState.update {
                        it.copy(bill = updated, editingField = null, message = null)
                    }
                }
                is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
                Result.Loading -> Unit
            }
        }
    }

    // ---------- 离开页面：清理未编辑的 0 值占位账单 ----------

    /**
     * 离开详情页时调用（系统返回键 / 顶部返回按钮）。
     *
     * 一键建单会先插入一条 0 分占位账单并跳转到详情页；若用户什么都没改就返回，
     * 这条占位账单会残留在首页列表里（表现为「莫名多出一笔 0 元账单」）。
     * 因此：账单存在 && 未被改动 && 金额为 0 && 来源为 MANUAL && billId>0 时，
     * 走 DeleteBillUseCase 的带余额回滚事务静默删除（金额为 0，实际余额差为 0）。
     */
    fun onLeave() {
        if (leaveHandled) return
        leaveHandled = true

        val bill = _uiState.value.bill ?: return
        if (dirty) return
        val snapshot = initialSnapshot ?: return
        if (bill.billId <= 0L || bill.billId != snapshot.billId) return
        if (bill.source != BillSource.MANUAL || bill.amountFen != 0L) return

        val placeholderId = bill.billId
        leaveScope.launch {
            when (val result = deleteBill(placeholderId)) {
                is Result.Success -> Timber.d("placeholder bill discarded on leave id=%d", placeholderId)
                is Result.Error -> Timber.w(
                    result.error.cause,
                    "discard placeholder bill failed id=%d code=%s",
                    placeholderId,
                    result.error.code
                )
                Result.Loading -> Unit
            }
        }
    }

    // ---------- 原有功能保持不变 ----------

    fun delete(onDeleted: () -> Unit) {
        // 用户显式删除：标记已处理，避免随后的 onLeave 再触发一次占位账单清理
        leaveHandled = true
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
