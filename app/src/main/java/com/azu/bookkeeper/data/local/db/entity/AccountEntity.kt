package com.azu.bookkeeper.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 账户表。
 * balanceFen 为当前余额（初始余额 + 历次账单净变动），
 * 只能由 BillRepository 的单一事务写入口经 AccountDao 修改（架构 N4）。
 */
@Serializable
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["book_id"], name = "index_accounts_book")]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "account_id")
    val accountId: Long = 0L,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    val name: String,
    val type: Int = 7,
    @ColumnInfo(name = "initial_balance_fen")
    val initialBalanceFen: Long = 0L,
    @ColumnInfo(name = "balance_fen")
    val balanceFen: Long = initialBalanceFen,
    val icon: String = "",
    val remark: String = "",
    val enabled: Boolean = true
)
