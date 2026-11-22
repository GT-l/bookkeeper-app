package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 导入任务进度记录，用于后台导入与报告。 */
@Serializable
@Entity(tableName = "import_tasks")
data class ImportTaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "task_id")
    val taskId: Long = 0L,
    @ColumnInfo(name = "template_id")
    val templateId: Long = 0L,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    val status: Int = 0,
    @ColumnInfo(name = "total_rows")
    val totalRows: Int = 0,
    @ColumnInfo(name = "success_rows")
    val successRows: Int = 0,
    @ColumnInfo(name = "failed_rows")
    val failedRows: Int = 0,
    @ColumnInfo(name = "report_path")
    val reportPath: String = ""
)
