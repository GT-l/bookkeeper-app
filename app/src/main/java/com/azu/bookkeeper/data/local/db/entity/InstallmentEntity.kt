package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "installments",
    indices = [Index(value = ["book_id"], name = "index_installments_book")]
)
data class InstallmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "installment_id") val installmentId: Long = 0L,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val title: String,
    @ColumnInfo(name = "total_fen") val totalFen: Long,
    val periods: Int,
    @ColumnInfo(name = "paid_periods") val paidPeriods: Int = 0,
    @ColumnInfo(name = "per_period_fen") val perPeriodFen: Long,
    @ColumnInfo(name = "account_id") val accountId: Long = 0L,
    @ColumnInfo(name = "category_id") val categoryId: Long = 0L,
    val note: String = "",
    @ColumnInfo(name = "create_at") val createAt: Long = System.currentTimeMillis()
)
