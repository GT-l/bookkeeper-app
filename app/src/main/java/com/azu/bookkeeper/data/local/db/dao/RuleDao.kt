package com.azu.bookkeeper.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.azu.bookkeeper.data.local.db.entity.CategoryRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Query("SELECT * FROM category_rules WHERE enabled = 1 ORDER BY priority DESC, rule_id ASC")
    fun observeAllRules(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules WHERE enabled = 1 ORDER BY priority DESC, rule_id ASC")
    suspend fun allRules(): List<CategoryRuleEntity>

    /** 关键词命中：文本包含规则关键词，按优先级降序返回供引擎取首个命中。 */
    @Query(
        "SELECT * FROM category_rules WHERE enabled = 1 AND :text LIKE '%' || keyword || '%' " +
            "ORDER BY priority DESC, rule_id ASC"
    )
    suspend fun match(text: String): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules WHERE category_id = :categoryId")
    suspend fun findByCategory(categoryId: Long): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules")
    suspend fun getAll(): List<CategoryRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: CategoryRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<CategoryRuleEntity>): List<Long>

    @Query("UPDATE category_rules SET enabled = :enabled WHERE rule_id = :ruleId")
    suspend fun setEnabled(ruleId: Long, enabled: Boolean)

    @Query("DELETE FROM category_rules WHERE rule_id = :ruleId")
    suspend fun deleteById(ruleId: Long)

    @Query("DELETE FROM category_rules")
    suspend fun deleteAll()
}
