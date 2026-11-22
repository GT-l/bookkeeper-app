package com.azu.bookkeeper.domain.model

/** 导入模板位标记。 */
object ImportFlags {
    const val HAS_HEADER = 1
    const val SIGN_RULE = 2
    const val AUTO_DEDUP = 4
    const val TRANSFER_SUPPORT = 8

    fun has(flags: Int, flag: Int): Boolean = flags and flag != 0
}

/** 导入映射模板：mapping 为标准字段 → 源文件列名。 */
data class ImportTemplate(
    val templateId: Long = 0L,
    val name: String,
    val sourceKey: String,
    val mapping: Map<String, String> = emptyMap(),
    val flags: Int = ImportFlags.HAS_HEADER or ImportFlags.AUTO_DEDUP
)
