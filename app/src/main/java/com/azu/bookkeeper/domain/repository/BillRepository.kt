package com.azu.bookkeeper.domain.repository

import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillQuery
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.BillSource
import com.azu.bookkeeper.domain.model.CaptureEventStatus
import com.azu.bookkeeper.domain.model.CategoryAmount
import com.azu.bookkeeper.domain.model.DedupKey
import kotlinx.coroutines.flow.Flow

/**
 * 账单仓储契约。
 * 所有余额变更必须经本接口的三个 *WithBalanceTx 方法（事务单一写入口，架构 N4）。
 */
interface BillRepository {

    fun observeBills(bookId: Long): Flow<List<Bill>>

    suspend fun getBill(billId: Long): Result<Bill?>

    /**
     * 新增：事务内 INSERT bill + UPDATE account balance。
     * sourceEventId 非空时，在同一事务内把关联捕获事件置 terminalStatus 终态，严禁拆成两步。
     *
     * 终态语义（主理人裁决，覆盖增量文档两处冲突表述）：
     * - CONSUMED_AUTO（默认）：高置信度自动入账，T5 自动路径使用，现有调用零改动；
     * - USER_CORRECTED：用户介入修正后入账，T7 快捷补记 / 低置信度弹窗用户确认显式传入。
     */
    suspend fun addWithBalanceTx(
        bill: Bill,
        sourceEventId: Long? = null,
        terminalStatus: CaptureEventStatus = CaptureEventStatus.CONSUMED_AUTO
    ): Result<Long>

    /** 修改：事务内按「旧账单反向差 + 新账单正向差」调整余额。 */
    suspend fun updateWithBalanceTx(bill: Bill): Result<Unit>

    /** 删除：事务内反向回滚余额。 */
    suspend fun deleteWithBalanceTx(billId: Long): Result<Unit>

    /** 批量导入：每 500 条一个事务，返回成功条数。 */
    suspend fun bulkInsert(bills: List<Bill>): Result<Int>

    suspend fun findDuplicate(key: DedupKey): Result<Bill?>

    /**
     * T7 幂等：来源捕获事件是否已处理（不存在 / 终态均视为已处理）。
     * 供 QuickCaptureUseCase 在进入表单与保存时双重短路，防止重复建单与重复提醒。
     */
    suspend fun isCaptureEventHandled(eventId: Long): Boolean

    /** 短信源 ±N 秒容差去重；键刻意不含 remark（增量架构 §2.4）。 */
    suspend fun findDuplicateInTimeRange(
        bookId: Long,
        amountFen: Long,
        accountFrom: Long,
        startMillis: Long,
        endMillis: Long
    ): Result<Bill?>

    suspend fun search(query: BillQuery): Result<List<Bill>>

    suspend fun findByTimeRange(bookId: Long, startMillis: Long, endMillis: Long): Result<List<Bill>>

    suspend fun sumByCategory(
        bookId: Long,
        type: BillType,
        startMillis: Long,
        endMillis: Long
    ): Result<List<CategoryAmount>>

    suspend fun sumByType(bookId: Long, startMillis: Long, endMillis: Long): Result<Map<BillType, Long>>

    suspend fun findBySource(bookId: Long, source: BillSource): Result<List<Bill>>

    // 架构类图中的语义别名，默认转发到事务方法，便于 UI / 自动化调用。
    suspend fun addBill(bill: Bill): Result<Long> = addWithBalanceTx(bill)

    suspend fun updateBill(bill: Bill): Result<Unit> = updateWithBalanceTx(bill)

    suspend fun deleteBill(billId: Long): Result<Unit> = deleteWithBalanceTx(billId)
}
