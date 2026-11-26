package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debts",
    indices = [Index(value = ["book_id", "status"], name = "index_debts_book_status")]
)
data class DebtEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "debt_id") val debtId: Long = 0L,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val direction: Int,
    val counterparty: String,
    @ColumnInfo(name = "total_fen") val totalFen: Long,
    @ColumnInfo(name = "settled_fen") val settledFen: Long = 0L,
    @ColumnInfo(name = "account_id") val accountId: Long = 0L,
    @ColumnInfo(name = "due_date") val dueDate: String = "",
    val note: String = "",
    val status: Int = 0,
    @ColumnInfo(name = "create_at") val createAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "update_at") val updateAt: Long = System.currentTimeMillis()
)
