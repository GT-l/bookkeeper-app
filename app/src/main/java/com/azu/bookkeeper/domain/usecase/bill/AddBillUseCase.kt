package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.AppError
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillSource
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
        // 账户改为「非必须」：手动记账 / 快捷补记允许 accountFrom=0（先记金额，账户随后在详情页补选）。
        // 转账与自动捕获（含导入、余额校准）保持原有严格校验，避免记账落到不存在的账户。
        bill.accountFrom <= 0L && mustSelectAccount(bill) -> AppError.Validation(
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

    /**
     * 该账单是否必须携带有效账户。
     *
     * 仅 MANUAL / QUICK_MANUAL 允许缺账户（PRD：账户非必填，用户可在详情页补选）；
     * 转账无论来源都必须有转出账户；其余来源（自动捕获 / 导入 / 余额校准）保持严格口径。
     */
    private fun mustSelectAccount(bill: Bill): Boolean = when (bill.billType) {
        BillType.TRANSFER -> true
        else -> bill.source != BillSource.MANUAL && bill.source != BillSource.QUICK_MANUAL
    }
}
