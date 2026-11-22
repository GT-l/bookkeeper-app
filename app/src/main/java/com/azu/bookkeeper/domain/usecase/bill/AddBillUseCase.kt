package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/** 新增账单：参数校验后走事务单一写入口。 */
class AddBillUseCase @Inject constructor(
    private val repository: BillRepository
) {

    suspend operator fun invoke(bill: Bill): Result<Long> {
        validate(bill)?.let { return Result.Error(it) }
        val now = DateTimeUtils.nowMillis()
        return repository.addWithBalanceTx(bill.copy(createAt = now, updateAt = now))
    }

    internal fun validate(bill: Bill): AppError? = when {
        bill.bookId <= 0L -> AppError.Validation(
            code = "E_VALID_002",
            userMessage = "请先选择账本"
        )
        bill.amountFen <= 0L -> AppError.Validation(
            code = "E_VALID_003",
            userMessage = "金额必须大于 0"
        )
        bill.accountFrom <= 0L -> AppError.Validation(
            code = "E_VALID_004",
            userMessage = "请选择账户"
        )
        bill.billType == BillType.TRANSFER && bill.accountTo <= 0L -> AppError.Validation(
            code = "E_VALID_005",
            userMessage = "请选择转入账户"
        )
        bill.billType == BillType.TRANSFER && bill.accountTo == bill.accountFrom -> AppError.Validation(
            code = "E_VALID_006",
            userMessage = "转出与转入账户不能相同"
        )
        bill.billType != BillType.TRANSFER && bill.categoryId <= 0L && !bill.isAdjustment ->
            AppError.Validation(code = "E_VALID_007", userMessage = "请选择分类")
        else -> null
    }
}
