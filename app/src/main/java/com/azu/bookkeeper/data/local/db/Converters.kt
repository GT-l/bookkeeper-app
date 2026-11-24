package com.azu.bookkeeper.data.local.db

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room 复杂类型转换器（JSON ↔ 集合 / Map）；必须是可实例化的类，不能用 object。 */
class Converters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromLongList(value: List<Long>): String = json.encodeToString(value)

    @TypeConverter
    fun toLongList(value: String): List<Long> = runCatching {
        json.decodeFromString<List<Long>>(value)
    }.getOrDefault(emptyList())

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = runCatching {
        json.decodeFromString<List<String>>(value)
    }.getOrDefault(emptyList())

    @TypeConverter
    fun fromLongMap(value: Map<Long, Long>): String = json.encodeToString(value)

    @TypeConverter
    fun toLongMap(value: String): Map<Long, Long> = runCatching {
        json.decodeFromString<Map<Long, Long>>(value)
    }.getOrDefault(emptyMap())

    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringMap(value: String): Map<String, String> = runCatching {
        json.decodeFromString<Map<String, String>>(value)
    }.getOrDefault(emptyMap())
}
