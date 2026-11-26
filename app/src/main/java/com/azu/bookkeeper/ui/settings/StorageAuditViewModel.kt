package com.azu.bookkeeper.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 存储排查。
 *
 * 起因：App 从 1.0.0 一路迭代，数据库换过加密方式、日志换过文件名、备份换过目录，
 * 旧版本留下的 `.db` / `.db-journal` / 崩溃日志 / 过期导入文件会一直躺在私有目录里，
 * 用户既看不见也删不掉。这一页把「本机到底存了些什么」摊开：
 *
 * - 每一行都给出文件名、大小、最后修改时间、属于哪一类；
 * - **正在使用的文件会被锁住**（当前数据库及其 -wal/-shm/-journal、数据库密钥、设置 DataStore），
 *   勾都勾不上，杜绝「删了密钥导致账本打不开」这类事故；
 * - 其余历史遗留项默认不勾选，用户自己决定保留还是删除，删除前再确认一次并列出文件名。
 *
 * 只扫 App 自己的私有目录，不碰外部存储 / 相册 / 下载目录。
 */
@HiltViewModel
class StorageAuditViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class Kind(val label: String) {
        DATABASE("数据库"),
        BACKUP("备份文件"),
        LOG("日志与诊断"),
        PREF("偏好设置"),
        CACHE("缓存与临时文件"),
        OTHER("其他")
    }

    data class Item(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val kind: Kind,
        val note: String,
        /** 当前正在使用（数据库/密钥/设置）→ 禁止勾选。 */
        val locked: Boolean = false,
        val isDirectory: Boolean = false
    ) {
        val sizeText: String get() = formatStorageSize(sizeBytes)
        val timeText: String get() = formatStorageTime(lastModified)
    }

    data class UiState(
        val loading: Boolean = true,
        val items: List<Item> = emptyList(),
        val selected: Set<String> = emptySet(),
        val message: String? = null,
        val rootPath: String = "",
        val lastScanAt: Long = 0L
    ) {
        val totalBytes: Long get() = items.sumOf { it.sizeBytes }
        val totalText: String get() = formatStorageSize(totalBytes)
        val selectedItems: List<Item> get() = items.filter { it.path in selected }
        val selectedBytes: Long get() = selectedItems.sumOf { it.sizeBytes }
        val selectedText: String get() = formatStorageSize(selectedBytes)
        val lockedCount: Int get() = items.count { it.locked }
        val hasSelection: Boolean get() = selected.isNotEmpty()

        /** 按类别归组，UI 直接照着渲染分组头。 */
        val grouped: List<Pair<Kind, List<Item>>>
            get() = Kind.entries.mapNotNull { kind ->
                val list = items.filter { it.kind == kind }
                if (list.isEmpty()) null else kind to list
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        rescan()
    }

    fun toggle(path: String) = _uiState.update { state ->
        val item = state.items.firstOrNull { it.path == path }
        if (item == null || item.locked) {
            state
        } else {
            val next = if (path in state.selected) state.selected - path else state.selected + path
            state.copy(selected = next)
        }
    }

    fun selectAllRemovable() = _uiState.update { state ->
        state.copy(selected = state.items.filter { !it.locked }.map { it.path }.toSet())
    }

    /** 全部保留 = 清空选择，不删任何东西。 */
    fun keepAll() = _uiState.update { it.copy(selected = emptySet(), message = "已全部保留，没有删除任何文件") }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /**
     * [clearMessage] = false 用于「删除后刷新」：StateFlow 只保留最后一次更新，
     * 如果这里把 message 置空，deleteSelected() 刚写好的结果提示会瞬间消失。
     */
    fun rescan(clearMessage: Boolean = true) {
        _uiState.update { state ->
            state.copy(loading = true, message = if (clearMessage) null else state.message)
        }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { scan() }.getOrElse { e ->
                Timber.w(e, "storage audit scan failed")
                emptyList()
            } }
            _uiState.update { state ->
                state.copy(
                    loading = false,
                    items = items,
                    // 之前选过但这次扫不到的文件（已被删）要从选择里剔除
                    selected = state.selected.intersect(items.map { it.path }.toSet()),
                    rootPath = context.applicationInfo.dataDir
                )
            }
        }
    }

    /** 删除所选。返回实际删除成功的条数，失败逐条忽略并汇总提示。 */
    fun deleteSelected() {
        val targets = _uiState.value.selectedItems.filter { !it.locked }
        if (targets.isEmpty()) {
            _uiState.update { it.copy(message = "没有选中任何可删除的文件") }
            return
        }
        viewModelScope.launch {
            val (ok, failed, freed) = withContext(Dispatchers.IO) {
                var success = 0
                var fail = 0
                var bytes = 0L
                targets.forEach { item ->
                    val file = File(item.path)
                    val size = item.sizeBytes
                    val deleted = runCatching {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }.getOrDefault(false)
                    if (deleted) {
                        success++
                        bytes += size
                    } else {
                        fail++
                        Timber.w("storage audit: delete failed %s", item.path)
                    }
                }
                Triple(success, fail, bytes)
            }
            _uiState.update {
                it.copy(
                    selected = emptySet(),
                    message = buildString {
                        append("已删除 $ok 项，释放 ${formatStorageSize(freed)}")
                        if (failed > 0) append("；$failed 项被系统占用，稍后重试")
                    }
                )
            }
            // 保留删除结果提示，再刷新列表
            rescan(clearMessage = false)
        }
    }

    // ---------------- 扫描 ----------------

    private fun scan(): List<Item> {
        val result = mutableListOf<Item>()
        val now = System.currentTimeMillis()

        // 1) 数据库目录：当前库 + 历史遗留库 + 运行时旁路文件
        val dbDirs = linkedSetOf<File>()
        runCatching { context.getDatabasePath(Constants.DATABASE_NAME).parentFile?.let(dbDirs::add) }
        runCatching { dbDirs.add(File(context.applicationInfo.dataDir, "databases")) }
        val currentDbName = Constants.DATABASE_NAME

        dbDirs.filterNotNull().filter { it.exists() }.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val name = file.name
                val isCurrent = name == currentDbName || name.startsWith("$currentDbName-")
                val kind = if (name.endsWith(".db") || name.contains(".db-") || name.endsWith(".sqlite")) {
                    Kind.DATABASE
                } else {
                    Kind.OTHER
                }
                val note = when {
                    isCurrent -> "当前正在使用，已锁定"
                    name.contains(".db-wal") || name.contains(".db-shm") || name.contains(".db-journal") ->
                        "某个数据库的运行时日志"
                    else -> "疑似旧版本遗留的数据库"
                }
                result += Item(
                    path = file.absolutePath,
                    name = name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    kind = kind,
                    note = note,
                    locked = isCurrent
                )
            }
        }

        // 2) 用户可见的数据目录（files）：备份、日志、导入临时文件、图片
        val filesDir = context.filesDir
        val backupsDir = File(filesDir, Constants.DIR_BACKUPS)
        val importsDir = File(filesDir, Constants.DIR_IMPORTS)
        val capturesDir = File(filesDir, Constants.DIR_CAPTURES)
        val crashBackupDir = File(filesDir, "crash_backup")
        val dataStoreDir = File(filesDir, "datastore")

        listOf(backupsDir, importsDir, capturesDir, crashBackupDir, dataStoreDir)
            .filter { it.exists() }
            .forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        result += Item(
                            path = file.absolutePath,
                            name = "${dir.name}/${file.name}",
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                            kind = when (dir) {
                                backupsDir -> Kind.BACKUP
                                importsDir, capturesDir -> Kind.CACHE
                                crashBackupDir -> Kind.LOG
                                else -> Kind.PREF
                            },
                            note = when (dir) {
                                backupsDir -> "本地备份包，删除后无法用它恢复"
                                importsDir -> "导入时落地的临时文件"
                                capturesDir -> "识屏中间截图，可随时重建"
                                crashBackupDir -> "历史崩溃堆栈备份"
                                else -> "应用偏好设置（删除会重置对应配置）"
                            },
                            // DataStore 文件是当前设置的载体，删了配置就没了 → 锁住
                            locked = dir == dataStoreDir
                        )
                    }
                }
            }

        // 3) files 根目录下的散装日志（崩溃 / 启动探针 / 识屏日志）
        filesDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            val isLog = name.endsWith(".txt") || name.endsWith(".log") ||
                name.endsWith(".db") || name.endsWith(".bak") || name.endsWith(".backup")
            if (!isLog) return@forEach
            val kind = when {
                name.endsWith(".db") || name.endsWith(".bak") || name.endsWith(".backup") -> Kind.DATABASE
                else -> Kind.LOG
            }
            result += Item(
                path = file.absolutePath,
                name = name,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                kind = kind,
                note = if (kind == Kind.DATABASE) "旧版本数据库副本" else "运行时日志，可安全删除"
            )
        }

        // 4) 缓存目录：子目录按整体聚合，避免几百行缩略图缓存刷屏
        context.cacheDir.listFiles()?.forEach { file ->
            val size = if (file.isFile) file.length() else directorySize(file)
            if (size <= 0L) return@forEach
            result += Item(
                path = file.absolutePath,
                name = "cache/${file.name}",
                sizeBytes = size,
                lastModified = file.lastModified(),
                kind = Kind.CACHE,
                note = if (file.isDirectory) "缓存目录（可整体清理）" else "缓存文件",
                isDirectory = file.isDirectory
            )
        }

        // 5) 共享偏好：数据库密钥与当前设置锁住，其余是历史遗留
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".xml")) return@forEach
            val name = file.name
            val locked = name == "${Constants.DATABASE_KEY_PREF}.xml" ||
                name == "${Constants.SETTINGS_DATASTORE}.xml" ||
                name == "${Constants.AUTOMATION_DATASTORE}.xml" ||
                name.startsWith("${Constants.SETTINGS_DATASTORE}.") ||
                name.startsWith("${Constants.AUTOMATION_DATASTORE}.")
            result += Item(
                path = file.absolutePath,
                name = "shared_prefs/$name",
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                kind = Kind.PREF,
                note = when {
                    name == "${Constants.DATABASE_KEY_PREF}.xml" -> "数据库加密密钥，删除会导致账本打不开"
                    locked -> "当前设置，已锁定"
                    else -> "旧版本遗留的偏好文件"
                },
                locked = locked
            )
        }

        return result
            // 空文件（0 字节）也列出来：它同样是历史残留，删掉无损失
            .filter { it.sizeBytes >= 0L && it.lastModified <= now }
            .sortedWith(compareBy<Item>({ it.locked }, { it.kind.ordinal }, { -it.sizeBytes }))
    }

    private fun directorySize(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    companion object {
        /** 给 UI 用的体积格式化（内部转调同文件顶层函数，避免嵌套类作用域问题）。 */
        fun formatSize(bytes: Long): String = formatStorageSize(bytes)

        fun formatTime(millis: Long): String = formatStorageTime(millis)
    }
}

/** 人类可读体积：B / KB / MB / GB，保留一位小数。 */
internal fun formatStorageSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

internal fun formatStorageTime(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
