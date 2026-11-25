package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.CategoryKind
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 极简记账：一键创建一条「默认支出」账单。
 *
 * 行为（PRD 用户原话："默认创建一条消费记录的所有东西都创建好，用户长按跳转到对应位置重命名"）：
 * 1. 解析默认账本：settings.defaultBookId → bookRepository.findDefault() → 第一个账本；
 * 2. 取该账本第一个支出分类（优先名字含「其他」的，无分类时先补默认分类）；
 * 3. 取该账本第一个账户；
 * 4. 创建账单：EXPENSE / 金额 0 分 / 备注空 / source=MANUAL / 时间为当前时刻；
 * 5. 直接走 BillRepository.addWithBalanceTx 写库（金额为 0，余额差为 0，不破坏架构 N4 事务单一写入口）。
 *
 * 刻意绕过 AddBillUseCase 的「金额必须大于 0」校验：占位账单合法地以 0 分存在，
 * 用户随后在详情页长按各字段就地补全。
 */
@Singleton
class InstantBillCreator @Inject constructor(
    private val settings: SettingsRepository,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val billRepository: BillRepository
) {

    suspend operator fun invoke(): Result<Long> {
        // 1. 默认账本 fallback 链（与 HomeViewModel / BillEditViewModel init 保持同一语义）
        val preferredBookId = settings.defaultBookId.first()
        val bookId = if (preferredBookId > 0L) {
            preferredBookId
        } else {
            bookRepository.findDefault().getOrNull()?.bookId
                ?: bookRepository.observeAll().first().firstOrNull()?.bookId
                ?: 0L
        }
        if (bookId <= 0L) {
            return Result.Error(
                AppError.Validation(code = "E_VALID_002", userMessage = "请先创建一个账本")
            )
        }

        // 2. 第一个支出分类（优先「其他」）；账本还没有分类时先补默认分类再取
        var categories = categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
        if (categories.isEmpty()) {
            categoryRepository.ensureDefaults(bookId)
            categories = categoryRepository.observeByBook(bookId, CategoryKind.EXPENSE).first()
        }
        val categoryId = (categories.firstOrNull { it.name.contains("其他") }
            ?: categories.firstOrNull())?.categoryId ?: 0L

        // 3. 第一个账户
        val accountId = accountRepository.observeAll(bookId).first().firstOrNull()?.accountId ?: 0L

        // 4. 占位账单：金额 0、备注空、时间为当前时刻，其余字段用模型默认值
        val bill = Bill(
            bookId = bookId,
            billType = BillType.EXPENSE,
            amountFen = 0L,
            categoryId = categoryId,
            accountFrom = accountId,
            billTime = DateTimeUtils.nowMillis(),
            remark = "",
            source = BillSource.MANUAL
        )

        // 5. 事务单一写入口新增（addWithBalanceTx 不做金额校验，0 分占位合法）
        return billRepository.addWithBalanceTx(bill)
    }
}
