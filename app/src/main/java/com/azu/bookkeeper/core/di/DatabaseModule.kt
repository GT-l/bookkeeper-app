package com.azu.bookkeeper.core.di

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.data.local.db.BookkeeperDatabase
import com.azu.bookkeeper.data.local.db.MIGRATION_1_2
import com.azu.bookkeeper.data.local.db.dao.AccountDao
import com.azu.bookkeeper.data.local.db.dao.AnnualBudgetDao
import com.azu.bookkeeper.data.local.db.dao.BillDao
import com.azu.bookkeeper.data.local.db.dao.DebtDao
import com.azu.bookkeeper.data.local.db.dao.InstallmentDao
import com.azu.bookkeeper.data.local.db.dao.BookDao
import com.azu.bookkeeper.data.local.db.dao.BudgetDao
import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import com.azu.bookkeeper.data.local.db.dao.CategoryDao
import com.azu.bookkeeper.data.local.db.dao.ImportDao
import com.azu.bookkeeper.data.local.db.dao.RefundDao
import com.azu.bookkeeper.data.local.db.dao.ReimbursementDao
import com.azu.bookkeeper.data.local.db.dao.RuleDao
import com.azu.bookkeeper.data.local.db.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import javax.inject.Singleton

/** Room + SQLCipher：透明加密，对 DAO 层零侵入。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * 32 字节随机口令由 Android Keystore 保护的 EncryptedSharedPreferences 托管。
     * 首次启动生成，之后复用，避免硬编码密钥。
     */
    @Provides
    @Singleton
    fun provideSupportFactory(@ApplicationContext context: Context): SupportOpenHelperFactory {
        // sqlcipher-android 4.x 已移除 SQLiteDatabase.loadLibs，必须显式加载 native 库
        System.loadLibrary("sqlcipher")
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            Constants.DATABASE_KEY_PREF,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val encoded = prefs.getString(Constants.DATABASE_KEY_ENTRY, null) ?: run {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val value = Base64.encodeToString(bytes, Base64.NO_WRAP)
            prefs.edit().putString(Constants.DATABASE_KEY_ENTRY, value).apply()
            value
        }
        return SupportOpenHelperFactory(Base64.decode(encoded, Base64.NO_WRAP))
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        factory: SupportOpenHelperFactory
    ): BookkeeperDatabase = Room.databaseBuilder(
        context,
        BookkeeperDatabase::class.java,
        Constants.DATABASE_NAME
    ).openHelperFactory(factory)
        .addMigrations(MIGRATION_1_2)
        .build()

    @Provides
    fun provideBillDao(database: BookkeeperDatabase): BillDao = database.billDao()

    @Provides
    fun provideBookDao(database: BookkeeperDatabase): BookDao = database.bookDao()

    @Provides
    fun provideCategoryDao(database: BookkeeperDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun provideTagDao(database: BookkeeperDatabase): TagDao = database.tagDao()

    @Provides
    fun provideAccountDao(database: BookkeeperDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideBudgetDao(database: BookkeeperDatabase): BudgetDao = database.budgetDao()

    @Provides
    fun provideImportDao(database: BookkeeperDatabase): ImportDao = database.importDao()

    @Provides
    fun provideRuleDao(database: BookkeeperDatabase): RuleDao = database.ruleDao()

    @Provides
    fun provideCaptureDao(database: BookkeeperDatabase): CaptureDao = database.captureDao()

    @Provides
    fun provideRefundDao(database: BookkeeperDatabase): RefundDao = database.refundDao()

    @Provides
    fun provideReimbursementDao(database: BookkeeperDatabase): ReimbursementDao = database.reimbursementDao()

    @Provides
    fun provideDebtDao(database: BookkeeperDatabase): DebtDao = database.debtDao()

    @Provides
    fun provideInstallmentDao(database: BookkeeperDatabase): InstallmentDao = database.installmentDao()

    @Provides
    fun provideAnnualBudgetDao(database: BookkeeperDatabase): AnnualBudgetDao = database.annualBudgetDao()
}
