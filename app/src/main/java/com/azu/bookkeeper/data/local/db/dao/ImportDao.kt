package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.azu.bookkeeper.data.local.db.entity.ImportTaskEntity
import com.azu.bookkeeper.data.local.db.entity.ImportTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportDao {

    // ---------- 模板 ----------
    @Query("SELECT * FROM import_templates ORDER BY template_id ASC")
    fun observeTemplates(): Flow<List<ImportTemplateEntity>>

    @Query("SELECT * FROM import_templates WHERE template_id = :templateId")
    suspend fun findTemplateById(templateId: Long): ImportTemplateEntity?

    @Query("SELECT * FROM import_templates WHERE source_key = :sourceKey LIMIT 1")
    suspend fun findTemplateBySourceKey(sourceKey: String): ImportTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: ImportTemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<ImportTemplateEntity>): List<Long>

    @Query("DELETE FROM import_templates WHERE template_id = :templateId")
    suspend fun deleteTemplateById(templateId: Long)

    @Query("DELETE FROM import_templates")
    suspend fun deleteAllTemplates()

    // ---------- 任务 ----------
    @Query("SELECT * FROM import_templates")
    suspend fun getAllTemplates(): List<ImportTemplateEntity>

    @Query("SELECT * FROM import_tasks ORDER BY task_id DESC")
    fun observeTasks(): Flow<List<ImportTaskEntity>>

    @Query("SELECT * FROM import_tasks")
    suspend fun getAllTasks(): List<ImportTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<ImportTaskEntity>): List<Long>

    @Query("SELECT * FROM import_tasks WHERE task_id = :taskId")
    suspend fun findTaskById(taskId: Long): ImportTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ImportTaskEntity): Long

    @Update
    suspend fun updateTask(task: ImportTaskEntity)

    @Query("DELETE FROM import_tasks WHERE task_id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Query("DELETE FROM import_tasks")
    suspend fun deleteAllTasks()
}
