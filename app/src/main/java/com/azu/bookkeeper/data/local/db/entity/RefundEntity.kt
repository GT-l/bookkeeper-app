package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "refunds",
    indices = [Index(value = ["book_id", "refund_time"], name = "index_refunds_book_time")]
)
data class RefundEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "refund_id") val refundId: Long = 0L,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "source_bill_id") val sourceBillId: Long,
    @ColumnInfo(name = "amount_fen") val amountFen: Long,
    @ColumnInfo(name = "refund_time") val refundTime: Long,
    val reason: String = "",
    @ColumnInfo(name = "create_at") val createAt: Long = System.currentTimeMillis()
)
