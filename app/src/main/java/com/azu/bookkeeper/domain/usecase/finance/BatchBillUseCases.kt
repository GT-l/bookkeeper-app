package com.azu.bookkeeper.domain.usecase.finance

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/**
 * 批量添加账单：多笔共用同一时间基准（逐笔间隔 1 秒避免去重键冲突），
 * 每笔都走 AddBillUseCase 的校验与事务入口，一笔失败不影响其它笔。
 */
class BatchAddBillsUseCase @Inject constructor(
    private val repository: BillRepository
) {
    suspend operator fun invoke(bills: List<Bill>): Result<Int> {
        if (bills.isEmpty()) {
            return Result.Error(AppError.Validation(code = "E_BATCH_001", userMessage = "没有可保存的账单"))
        }
        val base = DateTimeUtils.nowMillis()
        var success = 0
        val failures = mutableListOf<String>()
        bills.forEachIndexed { index, raw ->
            val bill = raw.copy(
                billTime = raw.billTime.takeIf { it > 0L } ?: (base - index * 1000L),
                createAt = base,
                updateAt = base
            )
            if (bill.billTime <= 0L) {
                failures.add("第${index + 1}笔时间无效")
                return@forEachIndexed
            }
            if (bill.amountFen <= 0L || bill.bookId <= 0L) {
                failures.add("第${index + 1}笔金额或账本无效")
                return@forEachIndexed
            }
            val result = repository.addWithBalanceTx(bill)
            if (result.isSuccess) success++ else failures.add("第${index + 1}笔：${result.getOrNull()?.let { "" } ?: "保存失败"}")
        }
        return if (success == 0 && failures.isNotEmpty()) {
            Result.Error(AppError.Validation(code = "E_BATCH_002", userMessage = failures.first()))
        } else {
            Result.Success(success)
        }
    }
}

/**
 * 批量修改：只改「分类 / 账户 / 标签 / 备注」等元数据。
 * 逐笔调用 updateWithBalanceTx——账户变化时由仓储在同一事务里做余额反向差，保证不串账。
 */
class BatchEditBillsUseCase @Inject constructor(
    private val repository: BillRepository
) {
    suspend operator fun invoke(
        billIds: List<Long>,
        categoryId: Long? = null,
        accountId: Long? = null,
        tagIds: List<Long>? = null,
        remark: String? = null
    ): Result<Int> {
        if (billIds.isEmpty()) {
            return Result.Error(AppError.Validation(code = "E_BATCH_003", userMessage = "请先选择账单"))
        }
        var success = 0
        billIds.forEach { id ->
            val bill = repository.getBill(id).getOrNull() ?: return@forEach
            val updated = bill.copy(
                categoryId = categoryId ?: bill.categoryId,
                accountFrom = accountId ?: bill.accountFrom,
                tagIds = tagIds ?: bill.tagIds,
                remark = remark ?: bill.remark,
                updateAt = DateTimeUtils.nowMillis()
            )
            if (repository.updateWithBalanceTx(updated).isSuccess) success++
        }
        return if (success == 0) {
            Result.Error(AppError.Database(code = "E_BATCH_004", userMessage = "批量修改失败，请重试"))
        } else Result.Success(success)
    }
}

/** 批量删除：逐笔走删除事务（含余额回滚）。 */
class BatchDeleteBillsUseCase @Inject constructor(
    private val repository: BillRepository
) {
    suspend operator fun invoke(billIds: List<Long>): Result<Int> {
        if (billIds.isEmpty()) {
            return Result.Error(AppError.Validation(code = "E_BATCH_005", userMessage = "请先选择账单"))
        }
        var success = 0
        billIds.forEach { id ->
            if (repository.deleteWithBalanceTx(id).isSuccess) success++
        }
        return if (success == 0) {
            Result.Error(AppError.Database(code = "E_BATCH_006", userMessage = "批量删除失败，请重试"))
        } else Result.Success(success)
    }
}
