package com.azu.bookkeeper.data.repository

import androidx.room.withTransaction
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import com.azu.bookkeeper.data.local.db.BookkeeperDatabase
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
import com.azu.bookkeeper.data.local.db.entity.BookEntity
import com.azu.bookkeeper.data.local.db.entity.BudgetEntity
import com.azu.bookkeeper.data.local.db.entity.CaptureEventEntity
import com.azu.bookkeeper.data.local.db.entity.CategoryEntity
import com.azu.bookkeeper.data.local.db.entity.CategoryRuleEntity
import com.azu.bookkeeper.data.local.db.entity.ImportTaskEntity
import com.azu.bookkeeper.data.local.db.entity.ImportTemplateEntity
import com.azu.bookkeeper.data.local.db.entity.TagEntity
import com.azu.bookkeeper.data.local.file.BackupDataSource
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import com.azu.bookkeeper.domain.repository.BackupFileInfo
import com.azu.bookkeeper.domain.repository.BackupRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 备份包快照：全部实体以 JSON 序列化后交由 EncryptedFileDataSource 加密落盘。 */
@Serializable
internal data class BackupPayload(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val books: List<BookEntity> = emptyList(),
    val bills: List<BillEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val templates: List<ImportTemplateEntity> = emptyList(),
    val tasks: List<ImportTaskEntity> = emptyList(),
    val rules: List<CategoryRuleEntity> = emptyList(),
    val captureEvents: List<CaptureEventEntity> = emptyList(),
    val billImages: List<BillImageEntity> = emptyList()
)

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val database: BookkeeperDatabase,
    private val billDao: BillDao,
    private val bookDao: BookDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val accountDao: AccountDao,
    private val budgetDao: BudgetDao,
    private val importDao: ImportDao,
    private val ruleDao: RuleDao,
    private val captureDao: CaptureDao,
    private val encryptedFiles: EncryptedFileDataSource,
    private val backupDataSource: BackupDataSource,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BackupRepository {

    override suspend fun createBackup(): Result<String> = safeResult {
        withContext(ioDispatcher) {
            val payload = BackupPayload(
                exportedAt = DateTimeUtils.nowMillis(),
                books = bookDao.getAll(),
                bills = billDao.getAll(),
                categories = categoryDao.getAll(),
                tags = tagDao.getAll(),
                accounts = accountDao.getAll(),
                budgets = budgetDao.getAll(),
                templates = importDao.getAllTemplates(),
                tasks = importDao.getAllTasks(),
                rules = ruleDao.getAll(),
                captureEvents = captureDao.getAll(),
                billImages = billDao.getAllImages()
            )
            val fileName = "bk_${DateTimeUtils.format(DateTimeUtils.nowMillis(), "yyyyMMdd_HHmmss")}.bak"
            encryptedFiles.saveText(Constants.DIR_BACKUPS, fileName, json.encodeToString(payload))
        }
    }

    override suspend fun restoreBackup(path: String): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            val text = encryptedFiles.readText(path)
                ?: throw IllegalArgumentException("backup file missing or undecryptable: $path")
            val payload = json.decodeFromString<BackupPayload>(text)
            require(payload.version == 1) { "unsupported backup version: ${payload.version}" }
            database.withTransaction {
                billDao.deleteAllImages()
                billDao.deleteAll()
                accountDao.deleteAll()
                categoryDao.deleteAll()
                tagDao.deleteAll()
                budgetDao.deleteAll()
                importDao.deleteAllTasks()
                importDao.deleteAllTemplates()
                ruleDao.deleteAll()
                captureDao.deleteAll()
                bookDao.deleteAll()

                bookDao.insertAll(payload.books)
                billDao.insertAll(payload.bills)
                categoryDao.insertAll(payload.categories)
                tagDao.insertAll(payload.tags)
                accountDao.insertAll(payload.accounts)
                budgetDao.insertAll(payload.budgets)
                importDao.insertTemplates(payload.templates)
                importDao.insertTasks(payload.tasks)
                ruleDao.insertAll(payload.rules)
                captureDao.insertAll(payload.captureEvents)
                billDao.insertImages(payload.billImages)
            }
        }
    }

    override suspend fun listBackups(): Result<List<BackupFileInfo>> = safeResult {
        withContext(ioDispatcher) {
            encryptedFiles.backupsDirectory().listFiles()
                ?.filter { it.isFile && it.name.endsWith(".bak") }
                ?.sortedByDescending { it.lastModified() }
                ?.map {
                    BackupFileInfo(
                        path = it.absolutePath,
                        fileName = it.name,
                        sizeBytes = it.length(),
                        createdAt = it.lastModified()
                    )
                }
                ?: emptyList()
        }
    }

    override suspend fun deleteBackup(path: String): Result<Unit> = safeResult {
        withContext(ioDispatcher) {
            if (!encryptedFiles.deleteFile(path)) {
                throw IllegalArgumentException("cannot delete backup: $path")
            }
        }
    }

    override suspend fun importBackupFile(uriString: String): Result<String> =
        backupDataSource.copyIncomingBackup(uriString)
}
