package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "annual_budgets",
    indices = [Index(value = ["book_id", "year"], name = "index_annual_budgets_book_year", unique = true)]
)
data class AnnualBudgetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "budget_id") val budgetId: Long = 0L,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val year: Int,
    @ColumnInfo(name = "total_limit_fen") val totalLimitFen: Long
)
