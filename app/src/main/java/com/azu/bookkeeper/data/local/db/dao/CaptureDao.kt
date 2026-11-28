package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.azu.bookkeeper.data.local.db.entity.CaptureEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CaptureEventEntity): Long

    @Query("SELECT * FROM capture_events WHERE event_id = :eventId")
    suspend fun findByEventId(eventId: Long): CaptureEventEntity?

    @Query("SELECT * FROM capture_events")
    suspend fun getAll(): List<CaptureEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CaptureEventEntity>): List<Long>

    /**
     * 语义保持「已消费」（行为兼容），但落库值改为 CaptureEventStatus.CONSUMED_AUTO=2；
     * 旧值 1 现在表示低置信度待校对，不可再用于消费态。
     */
    @Query("UPDATE capture_events SET status = 2 WHERE event_id = :eventId")
    suspend fun markConsumed(eventId: Long): Int

    @Query("UPDATE capture_events SET status = :status WHERE event_id = :eventId")
    suspend fun markStatus(eventId: Long, status: Int): Int

    /** 重复提醒扫描只看非终态：status IN (PENDING, LOW_CONFIDENCE)。 */
    @Query("SELECT * FROM capture_events WHERE status IN (0, 1) ORDER BY created_at ASC")
    fun observePending(): Flow<List<CaptureEventEntity>>

    @Query("SELECT * FROM capture_events WHERE status IN (0, 1) ORDER BY created_at ASC LIMIT :limit")
    suspend fun pendingEvents(limit: Int): List<CaptureEventEntity>

    @Query("DELETE FROM capture_events WHERE created_at < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)

    /**
     * v1.3.22 问题五方案 E：只清理**终态**事件（2=自动入账 / 3=用户改写 / 4=已丢弃）。
     * 待处理(0) / 待校对(1) 事件绝不能删——用户还没确认，删了就等于丢账单。
     */
    @Query("DELETE FROM capture_events WHERE created_at < :beforeMillis AND status IN (2, 3, 4)")
    suspend fun deleteTerminalOlderThan(beforeMillis: Long): Int

    @Query("DELETE FROM capture_events")
    suspend fun deleteAll()
}
