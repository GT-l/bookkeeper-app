package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 账单小票图片索引；文件本体由 EncryptedFileDataSource 加密落盘。 */
@Serializable
@Entity(
    tableName = "bill_images",
    indices = [Index(value = ["bill_id"], name = "index_bill_images_bill")]
)
data class BillImageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "image_id")
    val imageId: Long = 0L,
    @ColumnInfo(name = "bill_id")
    val billId: Long,
    @ColumnInfo(name = "encrypted_path")
    val encryptedPath: String
)
