package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

/** 账单 ↔ 标签 多对多关联表（账单最多 10 个标签）。 */
@Serializable
@Entity(
    tableName = "bill_tag_cross_ref",
    primaryKeys = ["bill_id", "tag_id"]
)
data class BillTagCrossRef(
    @ColumnInfo(name = "bill_id")
    val billId: Long,
    @ColumnInfo(name = "tag_id")
    val tagId: Long
)
