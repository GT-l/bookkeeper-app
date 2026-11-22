package com.azu.bookkeeper.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.azu.bookkeeper.data.local.db.dao.AccountDao
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.local.db.dao.BookDao
import com.azu.bookkeeper.data.local.db.dao.BudgetDao
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.data.local.db.dao.CategoryDao
import com.azu.bookkeeper.data.local.db.dao.ImportDao
import com.azu.bookkeeper.data.local.db.dao.RuleDao
import com.azu.bookkeeper.data.local.db.dao.TagDao
import com.azu.bookkeeper.data.local.db.entity.AccountEntity
import com.azu.bookkeeper.data.local.db.entity.BillEntity
import com.azu.bookkeeper.data.local.db.entity.BillImageEntity
import com.azu.bookkeeper.data.local.db.entity.BillTagCrossRef
import com.azu.bookkeeper.data.local.db.entity.BookEntity
import com.azu.bookkeeper.data.local.db.entity.BudgetEntity
import com.azu.bookkeeper.data.local.db.entity.CaptureEventEntity
import com.azu.bookkeeper.data.local.db.entity.CategoryEntity
import com.azu.bookkeeper.data.local.db.entity.CategoryRuleEntity
import com.azu.bookkeeper.data.local.db.entity.ImportTaskEntity
import com.azu.bookkeeper.data.local.db.entity.ImportTemplateEntity
import com.azu.bookkeeper.data.local.db.entity.TagEntity

/** Room 唯一数据库入口；SQLCipher 的 openHelperFactory 由 DatabaseModule 注入。 */
@Database(
    entities = [
        BookEntity::class,
        BillEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        BillTagCrossRef::class,
        AccountEntity::class,
        BudgetEntity::class,
        ImportTemplateEntity::class,
        ImportTaskEntity::class,
        CategoryRuleEntity::class,
        CaptureEventEntity::class,
        BillImageEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BookkeeperDatabase : RoomDatabase() {

    abstract fun billDao(): BillDao

    abstract fun bookDao(): BookDao

    abstract fun categoryDao(): CategoryDao

    abstract fun tagDao(): TagDao

    abstract fun accountDao(): AccountDao

    abstract fun budgetDao(): BudgetDao

    abstract fun importDao(): ImportDao

    abstract fun ruleDao(): RuleDao

    abstract fun captureDao(): CaptureDao
}
