package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 标签表（扁平，账本隔离）。 */
@Serializable
@Entity(
    tableName = "tags",
    indices = [Index(value = ["book_id", "name"], name = "index_tags_book_name", unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "tag_id")
    val tagId: Long = 0L,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    val name: String
)
