package com.azu.bookkeeper.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.BillEntity
import com.azu.bookkeeper.data.local.db.entity.BillImageEntity
import kotlinx.coroutines.flow.Flow

/** 分类汇总投影（Data 层）。 */
data class CategorySum(
    @ColumnInfo(name = "categoryId") val categoryId: Long,
    @ColumnInfo(name = "totalFen") val totalFen: Long,
    @ColumnInfo(name = "count") val count: Int
)

/** 类型汇总投影（Data 层）。 */
data class TypeSum(
    @ColumnInfo(name = "billType") val billType: Int,
    @ColumnInfo(name = "totalFen") val totalFen: Long
)

@Dao
interface BillDao {

    @Query("SELECT * FROM bills WHERE book_id = :bookId ORDER BY bill_time DESC")
    fun observeByBook(bookId: Long): Flow<List<BillEntity>>

    @Query(
        "SELECT * FROM bills WHERE book_id = :bookId AND bill_time BETWEEN :startMillis AND :endMillis " +
            "ORDER BY bill_time DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun queryPaged(
        bookId: Long,
        startMillis: Long,
        endMillis: Long,
        limit: Int,
        offset: Int
    ): List<BillEntity>

    @Query(
        "SELECT * FROM bills WHERE book_id = :bookId AND bill_time BETWEEN :startMillis AND :endMillis " +
            "ORDER BY bill_time DESC"
    )
    suspend fun findByTimeRange(bookId: Long, startMillis: Long, endMillis: Long): List<BillEntity>

    @Query("SELECT * FROM bills WHERE bill_id = :billId")
    suspend fun findById(billId: Long): BillEntity?

    /**
     * 全局去重键查询（架构 §4 关键契约）：
     * 账本 + 秒级 bill_time + 金额分 + 转出账户 + 归一化备注。
     */
    @Query(
        "SELECT * FROM bills WHERE book_id = :bookId AND bill_time = :billTime " +
            "AND amount_fen = :amountFen AND account_from = :accountFrom AND remark = :remark LIMIT 1"
    )
    suspend fun findDuplicate(
        bookId: Long,
        billTime: Long,
        amountFen: Long,
        accountFrom: Long,
        remark: String
    ): BillEntity?

    /**
     * 短信源 ±180s 容差去重：刻意不含 remark，避免短信文案中的商户简称差异导致漏判。
     * 表名使用本工程实际表名 bills（架构增量稿写作 bill_entity，仅命名差异）。
     */
    @Query(
        "SELECT * FROM bills WHERE book_id = :bookId AND amount_fen = :amountFen " +
            "AND account_from = :accountFrom AND bill_time BETWEEN :start AND :end " +
            "ORDER BY bill_time DESC LIMIT 1"
    )
    suspend fun findDuplicateInTimeRange(
        bookId: Long,
        amountFen: Long,
        accountFrom: Long,
        start: Long,
        end: Long
    ): BillEntity?

    /**
     * 疑似重复提醒：统计同账本 + 同商户（remark，调用方需先归一化）+ 同金额，
     * 且 bill_time 不早于 sinceMillis 的账单条数。
     *
     * 用途：去重命中时不直接丢弃，先看近期已存在几笔——达到 2 笔时本次将是第 3 笔，
     * 视为「疑似重复」转人工校验（用户可能确实连续消费了 3 笔相同金额）。
     */
    @Query(
        "SELECT COUNT(*) FROM bills WHERE book_id = :bookId AND remark = :merchant " +
            "AND amount_fen = :amountFen AND bill_time >= :sinceMillis"
    )
    suspend fun countRecentSameAmountMerchant(
        bookId: Long,
        merchant: String,
        amountFen: Long,
        sinceMillis: Long
    ): Int

    @Query(
        "SELECT category_id AS categoryId, SUM(amount_fen) AS totalFen, COUNT(*) AS count " +
            "FROM bills WHERE book_id = :bookId AND bill_type = :billType AND `source` != :excludedSource " +
            "AND bill_time BETWEEN :startMillis AND :endMillis GROUP BY category_id"
    )
    suspend fun sumByCategory(
        bookId: Long,
        billType: Int,
        startMillis: Long,
        endMillis: Long,
        excludedSource: Int
    ): List<CategorySum>

    @Query(
        "SELECT bill_type AS billType, SUM(amount_fen) AS totalFen FROM bills " +
            "WHERE book_id = :bookId AND `source` != :excludedSource " +
            "AND bill_time BETWEEN :startMillis AND :endMillis GROUP BY bill_type"
    )
    suspend fun sumByType(
        bookId: Long,
        startMillis: Long,
        endMillis: Long,
        excludedSource: Int
    ): List<TypeSum>

    @Query("SELECT * FROM bills WHERE book_id = :bookId AND `source` = :source ORDER BY bill_time DESC")
    suspend fun findBySource(bookId: Long, source: Int): List<BillEntity>

    @Query("SELECT * FROM bills WHERE book_id = :bookId ORDER BY bill_time DESC")
    suspend fun findAllByBook(bookId: Long): List<BillEntity>

    @Query("SELECT * FROM bills")
    suspend fun getAll(): List<BillEntity>

    @Query("SELECT COUNT(*) FROM bills WHERE account_from = :accountId OR account_to = :accountId")
    suspend fun countByAccount(accountId: Long): Int

    @Query("UPDATE bills SET category_id = :newCategoryId WHERE category_id = :oldCategoryId")
    suspend fun migrateCategory(oldCategoryId: Long, newCategoryId: Long)

    @Query("SELECT * FROM bill_images")
    suspend fun getAllImages(): List<BillImageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<BillImageEntity>)

    @Query("DELETE FROM bill_images")
    suspend fun deleteAllImages()

    @Query("SELECT COUNT(*) FROM bills WHERE book_id = :bookId")
    suspend fun countByBook(bookId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bill: BillEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bills: List<BillEntity>): List<Long>

    @Update
    suspend fun update(bill: BillEntity)

    @Query("DELETE FROM bills WHERE bill_id = :billId")
    suspend fun deleteById(billId: Long)

    @Query("DELETE FROM bills WHERE book_id = :bookId")
    suspend fun deleteAllByBook(bookId: Long)

    @Query("DELETE FROM bills")
    suspend fun deleteAll()
}
