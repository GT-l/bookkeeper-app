package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 月度预算：month 形如 202611；categoryLimitsJson 为 {categoryId: 上限分}。 */
@Serializable
@Entity(
    tableName = "budgets",
    indices = [Index(value = ["book_id", "month"], name = "index_budgets_book_month", unique = true)]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "budget_id")
    val budgetId: Long = 0L,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    val month: Int,
    @ColumnInfo(name = "total_limit_fen")
    val totalLimitFen: Long = 0L,
    @ColumnInfo(name = "category_limits_json")
    val categoryLimitsJson: String = "{}"
)
