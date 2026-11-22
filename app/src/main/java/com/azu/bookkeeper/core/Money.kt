package com.azu.bookkeeper.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * 金额工具：系统内部一律以「分」为单位的 Long 表示，杜绝浮点误差（架构 §9.2）。
 * 仅在边界（文件导入导出）使用 BigDecimal 中转。
 */
object Money {

    private val groupFormatter: NumberFormat = NumberFormat.getIntegerInstance(Locale.CHINA)

    /** 元 → 分，四舍五入到分。 */
    fun toFen(yuan: BigDecimal): Long =
        yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** 字符串金额 → 分，支持 ¥/￥、千分位、空白；非法输入返回 null。 */
    fun parse(raw: String): Long? {
        val cleaned = raw.trim()
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace("，", "")
            .replace("元", "")
            .replace(" ", "")
        if (cleaned.isEmpty()) return null
        return try {
            toFen(BigDecimal(cleaned))
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** 分 → 元（BigDecimal），用于文件导出等边界。 */
    fun toYuan(fen: Long): BigDecimal = BigDecimal(fen).movePointLeft(2).setScale(2)

    /**
     * 分 → 展示文本。内部全程整数运算，不经 Double。
     * 例：format(123456, withSymbol = true) == "¥1,234.56"
     */
    fun format(fen: Long, withSymbol: Boolean = true, symbol: String = "¥"): String {
        val negative = fen < 0
        val magnitude = abs(fen)
        val integerPart = synchronized(groupFormatter) { groupFormatter.format(magnitude / 100L) }
        val fractionPart = (magnitude % 100L).toString().padStart(2, '0')
        val prefix = if (negative) "-" else ""
        val currency = if (withSymbol) symbol else ""
        return "$prefix$currency$integerPart.$fractionPart"
    }

    /** 不带千分位的展示文本，适合窄卡片。 */
    fun formatPlain(fen: Long, withSymbol: Boolean = true): String =
        format(fen, withSymbol = withSymbol).replace(",", "")

    fun absFen(fen: Long): Long = abs(fen)

    /** 按比例分摊总额，保证各分项之和恒等于 total（最后一次取余数）。 */
    fun distribute(totalFen: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        val sum = weights.fold(0L) { acc, w -> acc + maxOf(0L, w) }
        if (sum == 0L) return List(weights.size) { 0L }
        val result = MutableList(weights.size) { 0L }
        var allocated = 0L
        for (i in weights.indices) {
            if (i == weights.lastIndex) {
                result[i] = totalFen - allocated
            } else {
                val part = totalFen * maxOf(0L, weights[i]) / sum
                result[i] = part
                allocated += part
            }
        }
        return result
    }
}
