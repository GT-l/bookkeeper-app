package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 关键词 → 分类 规则；match 按 priority DESC 取首个命中。 */
@Serializable
@Entity(
    tableName = "category_rules",
    indices = [Index(value = ["keyword"], name = "index_rules_keyword")]
)
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rule_id")
    val ruleId: Long = 0L,
    val keyword: String,
    @ColumnInfo(name = "category_id")
    val categoryId: Long,
    val priority: Int = 0,
    val enabled: Boolean = true
)
