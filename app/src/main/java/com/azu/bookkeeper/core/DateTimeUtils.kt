package com.azu.bookkeeper.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 时间工具：存储统一 Long 毫秒（UTC 语义），展示按本地时区（架构 §9.2）。 */
object DateTimeUtils {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
        "yyyy.MM.dd",
        "yyyy年M月d日",
        "yyyy年M月d日 HH:mm",
        "yyyy年M月d日 HH:mm:ss",
        "yyyyMMdd",
        "M月d日",
        "MM/dd",
        "M/d"
    )

    fun nowMillis(): Long = System.currentTimeMillis()

    /** 去重键中的秒级时间（架构 §9.2）。 */
    fun dedupSecond(timeMillis: Long): Long = timeMillis / 1000L

    /** 秒向下取整后的毫秒值，方便与 DAO 的 bill_time 列直接比较。 */
    fun truncateToSecond(timeMillis: Long): Long = dedupSecond(timeMillis) * 1000L

    fun format(timeMillis: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
        DateTimeFormatter.ofPattern(pattern, Locale.CHINA)
            .withZone(zone)
            .format(Instant.ofEpochMilli(timeMillis))

    fun parse(text: String, pattern: String): Long? {
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.CHINA)
        val raw = text.trim()
        return try {
            LocalDateTime.parse(raw, formatter).atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // 导入文件常出现纯日期（yyyy-MM-dd / yyyy年M月d日），按当天 00:00 解析
            try {
                LocalDate.parse(raw, formatter).atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** 多格式容错解析（导入场景），缺省年份按今年补齐。 */
    fun parseFlexible(text: String): Long? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        for (pattern in patterns) {
            val parsed = if (pattern == "M月d日" || pattern == "MM/dd" || pattern == "M/d") {
                parse("$raw ${LocalDate.now(zone).year}", "$pattern yyyy")
            } else {
                parse(raw, pattern)
            }
            if (parsed != null) return parsed
        }
        return null
    }

    fun startOfDay(timeMillis: Long): Long = Instant.ofEpochMilli(timeMillis)
        .atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(timeMillis: Long): Long = Instant.ofEpochMilli(timeMillis)
        .atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L

    fun startOfMonth(timeMillis: Long): Long = Instant.ofEpochMilli(timeMillis)
        .atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfMonth(timeMillis: Long): Long = Instant.ofEpochMilli(timeMillis)
        .atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone)
        .toInstant().toEpochMilli() - 1L

    /** weekStart: 1=周一, 7=周日 */
    fun startOfWeek(timeMillis: Long, weekStart: Int = 1): Long {
        val date = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        val diff = (date.dayOfWeek.value - weekStart + 7) % 7
        return date.minusDays(diff.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun monthKey(timeMillis: Long): Int {
        val date = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        return date.year * 100 + date.monthValue
    }

    fun isSameDay(a: Long, b: Long): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /** 账单列表日期头：今天 / 昨天 / yyyy-MM-dd。 */
    fun formatDateHeader(timeMillis: Long): String {
        val now = nowMillis()
        return when {
            isSameDay(timeMillis, now) -> "今天"
            isSameDay(timeMillis, now - 86_400_000L) -> "昨天"
            else -> format(timeMillis, "yyyy-MM-dd")
        }
    }

    fun plusMonths(timeMillis: Long, months: Long): Long = Instant.ofEpochMilli(timeMillis)
        .atZone(zone).plusMonths(months).toInstant().toEpochMilli()

    fun daysBetween(startMillis: Long, endMillis: Long): Long =
        ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
        )

    fun toIso(timeMillis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeMillis))

    fun fromIso(iso: String): Long? = try {
        Instant.from(DateTimeFormatter.ISO_INSTANT.parse(iso)).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: DateTimeException) {
        null
    }

    fun localDateTime(timeMillis: Long): LocalDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone).toLocalDateTime()
}
