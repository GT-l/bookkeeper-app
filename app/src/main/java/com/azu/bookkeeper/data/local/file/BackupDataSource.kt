package com.azu.bookkeeper.data.local.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.di.IoDispatcher
import com.azu.bookkeeper.core.safeResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份包导入桥接：SAF 选中的 .bak 先复制到 App 私有 backups 目录，
 * 再交给 BackupUseCase / RestoreUseCase。
 */
@Singleton
class BackupDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedFiles: EncryptedFileDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /** 把外部选中的备份文件复制进私有目录，返回可用于恢复的绝对路径。 */
    suspend fun copyIncomingBackup(uriString: String): Result<String> = safeResult {
        withContext(ioDispatcher) {
            val uri = Uri.parse(uriString)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open backup: $uriString")
            val rawName = queryName(uri) ?: "restored.bak"
            val safeName = rawName.substringAfterLast('/').replace(Regex("[^\\w.\\-]"), "_")
            val name = if (safeName.endsWith(".bak")) safeName else "$safeName.bak"
            val dir = encryptedFiles.backupsDirectory()
            val target = File(dir, "restored_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}_$name")
            input.use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            target.absolutePath
        }
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    } catch (_: SecurityException) {
        null
    }
}
