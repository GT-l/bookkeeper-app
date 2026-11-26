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

/**
 * 把「商户」与「购买内容」拼成一条备注。
 *
 * 识屏拿到两项时写成「瑞幸咖啡 · 生椰拿铁」，只有一项时就用那一项，
 * 两项互相包含（支付宝常把商品名写成「商户-商品」）时取信息更多的那个，
 * 避免出现「瑞幸咖啡 · 瑞幸咖啡-生椰拿铁」这种重复。
 *
 * 注意：商户始终是备注的**前缀**，疑似重复查询依赖这个约定。
 */
fun composeRemark(merchant: String, goods: String): String {
    val m = merchant.trim()
    val g = goods.trim()
    return when {
        g.isBlank() -> m
        m.isBlank() -> g
        g == m -> m
        g.startsWith(m) || m.startsWith(g) -> if (g.length >= m.length) g else m
        else -> "$m · $g"
    }
}

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
