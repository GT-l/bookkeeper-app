package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.repository.BillRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 观察账本账单列表（按时间倒序，UI 只读 Flow）。 */
class ObserveBillsUseCase @Inject constructor(
    private val repository: BillRepository
) {

    operator fun invoke(bookId: Long): Flow<List<Bill>> = repository.observeBills(bookId)
}
