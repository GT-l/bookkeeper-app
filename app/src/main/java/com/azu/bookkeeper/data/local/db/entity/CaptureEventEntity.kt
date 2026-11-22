package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 自动化捕获事件表。
 * status 复用 Int 列，语义见 CaptureEventStatus：
 * 0=待处理 1=低置信度待校对 2=自动入账完成 3=用户改写完成 4=已丢弃；
 * eventId 由捕获层生成，保证幂等。
 */
@Serializable
@Entity(
    tableName = "capture_events",
    indices = [
        Index(value = ["bill_time"], name = "index_capture_events_time"),
        Index(value = ["status"], name = "index_capture_events_status")
    ]
)
data class CaptureEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "event_id")
    val eventId: Long = 0L,
    @ColumnInfo(name = "capture_type")
    val captureType: Int = -1,
    @ColumnInfo(name = "raw_text")
    val rawText: String = "",
    @ColumnInfo(name = "image_path")
    val imagePath: String = "",
    @ColumnInfo(name = "ocr_confidence")
    val ocrConfidence: Float = 0f,
    @ColumnInfo(name = "bill_time")
    val billTime: Long = 0L,
    val status: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
