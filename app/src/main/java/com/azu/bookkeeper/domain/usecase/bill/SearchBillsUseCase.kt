package com.azu.bookkeeper.domain.usecase.bill

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillQuery
import com.azu.bookkeeper.domain.repository.BillRepository
import javax.inject.Inject

/** 搜索 / 高级筛选（PRD §15），UI 可对 keyword 做防抖后调用。 */
class SearchBillsUseCase @Inject constructor(
    private val repository: BillRepository
) {

    suspend operator fun invoke(query: BillQuery): Result<List<Bill>> = repository.search(query)
}
