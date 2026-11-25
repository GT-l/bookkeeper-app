package com.azu.bookkeeper.core

import android.content.Context
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.domain.model.Account
import com.azu.bookkeeper.domain.model.AccountType
import com.azu.bookkeeper.domain.model.Book
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首启种子数据（修复「全新安装后无法创建账户」）。
 *
 * 根因：全新安装时 books 表为空 → settings.defaultBookId=0 →
 * `BookRepository.findDefault()` / `AccountEditViewModel` 解析出 bookId=0 →
 * 新建账户被写进 book_id=0 的孤儿账本 → 所有按账本过滤的列表永远查不到它。
 *
 * 本类在 `Application.onCreate()` 之后于后台协程执行一次 [ensureSeeded]：
 * 1. 账本为空 → 建「我的账本」(isDefault=true) 并把 defaultBookId 指向它；
 * 2. 补齐该账本的默认分类（复用 CategoryRepository.ensureDefaults，内部已幂等）；
 * 3. 插入 3 个默认资产账户（现金 / 支付宝 / 微信钱包），同名账户已存在则跳过。
 *
 * 幂等：账本非空时直接返回 `false`，不做任何写操作，因此重启 / 多进程重复调用安全。
 */
@Singleton
class FirstRunSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * 确保首启种子数据就绪。
     *
     * @return true 表示本次真正执行了播种（全新安装），false 表示账本已存在、直接跳过。
     */
    suspend fun ensureSeeded(): Boolean = withContext(ioDispatcher) {
        // 一次性读取账本列表（Flow 的 first() 即「读一次当前快照」语义）
        val existingBooks = bookRepository.observeAll().first()
        if (existingBooks.isNotEmpty()) {
            Timber.d("first run seed skipped: %d book(s) already present", existingBooks.size)
            return@withContext false
        }

        val defaultBook = Book(name = DEFAULT_BOOK_NAME, isDefault = true)
        val bookId = when (val inserted = bookRepository.insert(defaultBook)) {
            is Result.Success -> inserted.data
            is Result.Error -> {
                // 首启播种失败不阻断启动：用户仍可在账本管理页手动建账本
                Timber.w(
                    inserted.error.cause,
                    "first run seed failed: insert default book %s",
                    inserted.error.code
                )
                return@withContext false
            }
            Result.Loading -> return@withContext false
        }
        if (bookId <= 0L) {
            Timber.w("first run seed failed: default book id is invalid (%d)", bookId)
            return@withContext false
        }

        settings.setDefaultBookId(bookId)
        // 默认分类：ensureDefaults 内部先查后插，重复调用不会产生重复分类
        categoryRepository.ensureDefaults(bookId)
        val accountCount = seedDefaultAccounts(bookId)

        Timber.d(
            "first run seed done pkg=%s bookId=%d accounts=%d",
            context.packageName, bookId, accountCount
        )
        true
    }

    /**
     * 插入 3 个默认账户；同名账户已存在时跳过，避免重复播种产生冗余账户。
     *
     * @return 本次实际插入的账户数量。
     */
    private suspend fun seedDefaultAccounts(bookId: Long): Int {
        var inserted = 0
        DEFAULT_ACCOUNTS.forEach { (name, type) ->
            val exists = accountRepository.findByName(bookId, name).getOrNull() != null
            if (exists) return@forEach
            val account = Account(
                bookId = bookId,
                name = name,
                type = type,
                initialBalanceFen = 0L,
                balanceFen = 0L,
                enabled = true
            )
            when (val result = accountRepository.insert(account)) {
                is Result.Success -> inserted++
                is Result.Error -> Timber.w(
                    result.error.cause,
                    "first run seed: insert account %s failed %s",
                    name,
                    result.error.code
                )
                Result.Loading -> Unit
            }
        }
        return inserted
    }

    companion object {

        /** 默认账本名（PRD 首启体验）。 */
        const val DEFAULT_BOOK_NAME = "我的账本"

        /** 默认账户（名称 → 类型）；账户非必须，但首启即有一组账户体验更完整。 */
        val DEFAULT_ACCOUNTS: List<Pair<String, AccountType>> = listOf(
            "现金" to AccountType.CASH,
            "支付宝" to AccountType.ALIPAY,
            "微信钱包" to AccountType.WECHAT
        )
    }
}
