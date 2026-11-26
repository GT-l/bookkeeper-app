package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reimbursements",
    indices = [Index(value = ["book_id", "status"], name = "index_reimbursements_book_status")]
)
data class ReimbursementEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "reimbursement_id") val reimbursementId: Long = 0L,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val title: String,
    @ColumnInfo(name = "amount_fen") val amountFen: Long,
    val status: Int = 0,
    @ColumnInfo(name = "source_bill_id") val sourceBillId: Long = 0L,
    @ColumnInfo(name = "income_bill_id") val incomeBillId: Long = 0L,
    @ColumnInfo(name = "account_id") val accountId: Long = 0L,
    @ColumnInfo(name = "create_at") val createAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "update_at") val updateAt: Long = System.currentTimeMillis()
)
