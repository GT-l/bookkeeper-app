package com.azu.bookkeeper.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** String → UTF-8 字节的 MD5（去重键、缓存文件名使用，不用于密码存储）。 */
fun String.md5(): String {
    val digest = MessageDigest.getInstance("MD5").digest(toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** String → UTF-8 字节的 SHA-256。 */
fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** 备注归一化：去首尾空白、折叠连续空白，作为去重键的一部分。 */
fun String.normalizeRemark(): String = trim().replace(Regex("\\s+"), " ")

fun Long?.orZero(): Long = this ?: 0L

fun Int?.orZero(): Int = this ?: 0

/** 限制 Long 在合法金额范围（非负、不超过 1 万亿元的分值）。 */
fun Long.coerceAmountFen(): Long = coerceIn(0L, 100_000_000_000_000L)

/** 把集合按最大长度分块，用于导入分批（最后一块可能更小）。 */
fun <T> List<T>.chunkedBySize(size: Int): List<List<T>> {
    require(size > 0) { "chunk size must be positive" }
    if (isEmpty()) return emptyList()
    return chunked(size)
}

/** 可空字符串安全截断（预览行展示用）。 */
fun String?.ellipsize(maxLength: Int): String {
    val source = this.orEmpty()
    return if (source.length <= maxLength) source else source.take(maxLength - 1) + "…"
}
