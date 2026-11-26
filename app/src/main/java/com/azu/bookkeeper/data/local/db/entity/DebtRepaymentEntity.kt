package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debt_repayments",
    indices = [Index(value = ["debt_id"], name = "index_debt_repayments_debt")]
)
data class DebtRepaymentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "repayment_id") val repaymentId: Long = 0L,
    @ColumnInfo(name = "debt_id") val debtId: Long,
    @ColumnInfo(name = "amount_fen") val amountFen: Long,
    @ColumnInfo(name = "repay_time") val repayTime: Long,
    @ColumnInfo(name = "bill_id") val billId: Long = 0L,
    val note: String = ""
)
