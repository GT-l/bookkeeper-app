package com.azu.bookkeeper.data.local.file

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 加密文件存储（架构 N6 / §9.5）：
 * AES256-GCM + Android Keystore 托管密钥；捕获截图与备份包禁止明文落盘。
 */
@Singleton
class EncryptedFileDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun dirOf(subDir: String): File =
        File(context.filesDir, subDir).apply { if (!exists()) mkdirs() }

    private fun encryptedFile(file: File): EncryptedFile = EncryptedFile.Builder(
        context,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
    ).build()

    private fun writeBytes(subDir: String, fileName: String, bytes: ByteArray): String =
        with(dirOf(subDir)) {
            val target = File(this, fileName)
            encryptedFile(target).openFileOutput().use { it.write(bytes) }
            target.absolutePath
        }

    private fun readBytes(absolutePath: String): ByteArray? {
        val file = File(absolutePath)
        if (!file.exists()) return null
        return encryptedFile(file).openFileInput().use { it.readBytes() }
    }

    /** Bitmap → 加密 JPEG，返回绝对路径。 */
    suspend fun saveCapture(bitmap: Bitmap, quality: Int = 85): String = withContext(ioDispatcher) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)
        val name = "cap_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg"
        writeBytes(Constants.DIR_CAPTURES, name, stream.toByteArray())
    }

    /** 读取加密截图；文件不存在或解密失败返回 null。 */
    suspend fun readCapture(absolutePath: String): Bitmap? = withContext(ioDispatcher) {
        val bytes = readBytes(absolutePath) ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** 保存任意文本（备份 JSON / 导入报告），加密落盘。 */
    suspend fun saveText(subDir: String, fileName: String, text: String): String =
        withContext(ioDispatcher) {
            writeBytes(subDir, fileName, text.toByteArray(Charsets.UTF_8))
        }

    suspend fun readText(absolutePath: String): String? = withContext(ioDispatcher) {
        readBytes(absolutePath)?.toString(Charsets.UTF_8)
    }

    suspend fun saveBytes(subDir: String, fileName: String, bytes: ByteArray): String =
        withContext(ioDispatcher) { writeBytes(subDir, fileName, bytes) }

    suspend fun deleteFile(absolutePath: String): Boolean = withContext(ioDispatcher) {
        runCatching { File(absolutePath).delete() }.getOrDefault(false)
    }

    /** 设置页「一键清除捕获图像」（PRD §22.9）。 */
    suspend fun clearAllCaptures(): Int = withContext(ioDispatcher) {
        val dir = dirOf(Constants.DIR_CAPTURES)
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) deleted++
        }
        deleted
    }

    fun capturesDirectory(): File = dirOf(Constants.DIR_CAPTURES)

    fun backupsDirectory(): File = dirOf(Constants.DIR_BACKUPS)
}
