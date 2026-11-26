package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "installment_payments",
    indices = [Index(value = ["installment_id"], name = "index_installment_payments_plan")]
)
data class InstallmentPaymentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "payment_id") val paymentId: Long = 0L,
    @ColumnInfo(name = "installment_id") val installmentId: Long,
    @ColumnInfo(name = "period_index") val periodIndex: Int,
    @ColumnInfo(name = "amount_fen") val amountFen: Long,
    @ColumnInfo(name = "pay_time") val payTime: Long,
    @ColumnInfo(name = "bill_id") val billId: Long = 0L
)
