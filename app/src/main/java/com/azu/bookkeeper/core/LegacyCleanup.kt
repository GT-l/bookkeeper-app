package com.azu.bookkeeper.core

import android.content.Context
import com.azu.bookkeeper.data.local.file.EncryptedFileDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 版本升级后的一次性遗留清理。
 *
 * 只清理「可再生的临时文件」：识屏中间截图、崩溃日志、过期崩溃备份、缓存、过期导入文件。
 * 绝不碰数据库、账本数据、用户主动备份的包；任何一步失败都单独吞掉，绝不影响启动速度。
 * 用 SharedPreferences 记录版本号，同一版本只跑一次。
 */
@Singleton
class LegacyCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedFiles: EncryptedFileDataSource
) {
    companion object {
        private const val PREFS = "legacy_cleanup"
        private const val KEY_SCHEMA = "schema_version"
        private const val CURRENT_SCHEMA = 1
        private const val WEEK_MS = 7L * 24L * 60L * 60L * 1000L
    }

    suspend fun runIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA, 0) >= CURRENT_SCHEMA) return@withContext
        val now = System.currentTimeMillis()

        // 1) 识屏中间截图：终态本就会删，升级时把历史残留一次清干净
        runCatching { encryptedFiles.clearAllCaptures() }
            .onFailure { Timber.w(it, "legacy cleanup: captures failed") }

        // 2) 崩溃日志：CrashGuard 启动时已读完，留着只会占空间
        listOf("crash_last.txt", "crash_overlay.txt").forEach { name ->
            runCatching { File(context.filesDir, name).takeIf { it.exists() }?.delete() }
        }

        // 3) 一周前的崩溃备份（保留最近的，便于反馈问题时取现场）
        runCatching {
            File(context.filesDir, "crash_backup").listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > WEEK_MS) file.delete()
            }
        }

        // 4) 应用缓存目录整体重建
        runCatching {
            context.cacheDir.deleteRecursively()
            context.cacheDir.mkdirs()
        }.onFailure { Timber.w(it, "legacy cleanup: cache failed") }

        // 5) 过期导入临时文件
        runCatching {
            File(context.filesDir, Constants.DIR_IMPORTS).listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > WEEK_MS) file.delete()
            }
        }

        prefs.edit().putInt(KEY_SCHEMA, CURRENT_SCHEMA).apply()
        Timber.i("legacy cleanup done")
    }
}
