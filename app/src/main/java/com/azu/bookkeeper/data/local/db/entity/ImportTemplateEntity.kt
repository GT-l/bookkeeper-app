package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 导入映射模板（预设 + 用户自定义）。 */
@Serializable
@Entity(tableName = "import_templates")
data class ImportTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "template_id")
    val templateId: Long = 0L,
    val name: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "mapping_json")
    val mappingJson: String = "{}",
    val flags: Int = 0
)
