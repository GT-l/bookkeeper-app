package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 账单表。字段与架构文档类图一一对应。
 * billTime 在写入前统一按秒取整，保证与自动化 / 导入共用同一去重键。
 */
@Serializable
@Entity(
    tableName = "bills",
    indices = [
        Index(value = ["book_id", "bill_time"], name = "index_bills_book_time"),
        Index(
            value = ["book_id", "bill_time", "amount_fen", "account_from", "remark"],
            name = "index_bills_dedup"
        ),
        Index(value = ["category_id"], name = "index_bills_category"),
        Index(value = ["account_from"], name = "index_bills_account")
    ]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "bill_id")
    val billId: Long = 0L,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    @ColumnInfo(name = "bill_type")
    val billType: Int,
    @ColumnInfo(name = "amount_fen")
    val amountFen: Long,
    @ColumnInfo(name = "category_id")
    val categoryId: Long = 0L,
    @ColumnInfo(name = "tag_ids_json")
    val tagIdsJson: String = "[]",
    @ColumnInfo(name = "account_from")
    val accountFrom: Long = 0L,
    @ColumnInfo(name = "account_to")
    val accountTo: Long = 0L,
    @ColumnInfo(name = "bill_time")
    val billTime: Long,
    val remark: String = "",
    @ColumnInfo(name = "image_paths_json")
    val imagePathsJson: String = "[]",
    @ColumnInfo(name = "cycle_type")
    val cycleType: Int = 0,
    @ColumnInfo(name = "cycle_end_time")
    val cycleEndTime: Long = 0L,
    val source: Int = 0,
    @ColumnInfo(name = "capture_type")
    val captureType: Int = -1,
    @ColumnInfo(name = "capture_image_path")
    val captureImagePath: String = "",
    @ColumnInfo(name = "ocr_confidence")
    val ocrConfidence: Float = 0f,
    @ColumnInfo(name = "ocr_raw_text")
    val ocrRawText: String = "",
    @ColumnInfo(name = "verify_status")
    val verifyStatus: Int = 0,
    @ColumnInfo(name = "create_at")
    val createAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "update_at")
    val updateAt: Long = System.currentTimeMillis()
)
