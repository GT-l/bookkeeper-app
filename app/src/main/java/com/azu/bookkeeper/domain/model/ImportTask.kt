package com.azu.bookkeeper.domain.model

enum class ImportTaskStatus(val code: Int) {
    PENDING(0),
    RUNNING(1),
    SUCCESS(2),
    FAILED(3),
    PARTIAL(4);

    companion object {
        fun fromCode(code: Int): ImportTaskStatus = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

/** 导入任务进度与报告。 */
data class ImportTask(
    val taskId: Long = 0L,
    val templateId: Long = 0L,
    val filePath: String,
    val status: ImportTaskStatus = ImportTaskStatus.PENDING,
    val totalRows: Int = 0,
    val successRows: Int = 0,
    val failedRows: Int = 0,
    val reportPath: String = ""
)
