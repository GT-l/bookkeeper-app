package com.azu.bookkeeper.domain.usecase.finance

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.data.local.db.dao.ReimbursementDao
import com.azu.bookkeeper.data.mapper.toDomain
import com.azu.bookkeeper.data.mapper.toEntity
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.ReimburseStatus
import com.azu.bookkeeper.domain.model.Reimbursement
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/** 新增报销单：可关联一笔已垫付的支出账单。 */
class AddReimbursementUseCase @Inject constructor(
    private val dao: ReimbursementDao
) {
    suspend operator fun invoke(item: Reimbursement): Result<Long> {
        if (item.title.isBlank()) {
            return Result.Error(AppError.Validation(code = "E_REIMB_001", userMessage = "请填写报销事由"))
        }
        if (item.amountFen <= 0L) {
            return Result.Error(AppError.Validation(code = "E_REIMB_002", userMessage = "报销金额必须大于 0"))
        }
        val now = DateTimeUtils.nowMillis()
        return runCatching {
            Result.Success(
                dao.insert(item.copy(createAt = now, updateAt = now).toEntity())
            )
        }.getOrElse { Result.Error(AppError.from(it)) }
    }
}

/**
 * 报销到账：生成一笔收入账单（走事务），并把报销单标记为已到账、回填账单 ID。
 * 若收入账单写入失败，报销单状态保持不变，不会出现「标了已到账但钱没进账」。
 */
class ReceiveReimbursementUseCase @Inject constructor(
    private val dao: ReimbursementDao,
    private val billRepository: BillRepository
) {
    suspend operator fun invoke(id: Long, accountId: Long): Result<Unit> {
        val entity = dao.findById(id)
            ?: return Result.Error(AppError.Validation(code = "E_REIMB_003", userMessage = "报销单不存在"))
        val item = entity.toDomain()
        if (item.status == ReimburseStatus.RECEIVED) {
            return Result.Error(AppError.Validation(code = "E_REIMB_004", userMessage = "该笔已到账"))
        }
        val targetAccount = accountId.takeIf { it > 0L } ?: item.accountId
        val incomeResult = billRepository.addWithBalanceTx(
            Bill(
                bookId = item.bookId,
                billType = BillType.INCOME,
                amountFen = item.amountFen,
                categoryId = 0L,
                accountFrom = targetAccount,
                billTime = DateTimeUtils.nowMillis(),
                remark = "报销到账 · ${item.title}".take(80)
            )
        )
        if (incomeResult is Result.Error) return Result.Error(incomeResult.error)
        val billId = incomeResult.getOrNull() ?: 0L
        return runCatching {
            dao.update(
                item.copy(
                    status = ReimburseStatus.RECEIVED,
                    incomeBillId = billId,
                    accountId = targetAccount,
                    updateAt = DateTimeUtils.nowMillis()
                ).toEntity()
            )
            Result.Success(Unit)
        }.getOrElse { Result.Error(AppError.from(it)) }
    }
}

/** 更新报销状态（提交 / 驳回）与删除报销单。 */
class UpdateReimbursementUseCase @Inject constructor(private val dao: ReimbursementDao) {
    suspend fun setStatus(id: Long, status: ReimburseStatus): Result<Unit> {
        val item = dao.findById(id)?.toDomain()
            ?: return Result.Error(AppError.Validation(code = "E_REIMB_005", userMessage = "报销单不存在"))
        return runCatching {
            dao.update(item.copy(status = status, updateAt = DateTimeUtils.nowMillis()).toEntity())
            Result.Success(Unit)
        }.getOrElse { Result.Error(AppError.from(it)) }
    }

    suspend fun deleteRecord(id: Long): Result<Unit> = runCatching {
        dao.deleteById(id)
        Result.Success(Unit)
    }.getOrElse { Result.Error(AppError.from(it)) }
}
