package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 收支分类树（最多二级）；kind 0=支出 1=收入。 */
@Serializable
@Entity(
    tableName = "categories",
    indices = [Index(value = ["book_id", "kind"], name = "index_categories_book_kind")]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "category_id")
    val categoryId: Long = 0L,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    val kind: Int = 0,
    val name: String,
    @ColumnInfo(name = "parent_id")
    val parentId: Long = 0L,
    val icon: String = "",
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0
)
