package com.azu.bookkeeper.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移（SQLCipher 下同样通过 SupportSQLiteDatabase 执行）。
 *
 * v1 → v2（少花点儿 1.2.0）：
 * - accounts 增加账单日 / 还款日 / 提醒开关三列（信用卡账单日提醒）；
 * - 新建 6 张业务表：refunds（退款）、reimbursements（报销）、
 *   debts + debt_repayments（债务借贷）、installments + installment_payments（分期）、
 *   annual_budgets（年度预算）。
 *
 * 安全性：全部用 IF NOT EXISTS / 先查 PRAGMA 再 ALTER，保证重复执行不报错；
 * 只加表加列，不改既有列，老用户的账单与余额完全不受影响。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------- accounts 加列（SQLite 不支持 IF NOT EXISTS，先查列） ----------
        if (!db.hasColumn("accounts", "bill_day")) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN bill_day INTEGER NOT NULL DEFAULT 0")
        }
        if (!db.hasColumn("accounts", "due_day")) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN due_day INTEGER NOT NULL DEFAULT 0")
        }
        if (!db.hasColumn("accounts", "reminder_enabled")) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN reminder_enabled INTEGER NOT NULL DEFAULT 0")
        }

        // ---------- 退款 ----------
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS refunds (
                refund_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                book_id INTEGER NOT NULL,
                source_bill_id INTEGER NOT NULL,
                amount_fen INTEGER NOT NULL,
                refund_time INTEGER NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                create_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refunds_book_time ON refunds (book_id, refund_time)")

        // ---------- 报销 ----------
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS reimbursements (
                reimbursement_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                book_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                amount_fen INTEGER NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                source_bill_id INTEGER NOT NULL DEFAULT 0,
                income_bill_id INTEGER NOT NULL DEFAULT 0,
                account_id INTEGER NOT NULL DEFAULT 0,
                create_at INTEGER NOT NULL,
                update_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_reimbursements_book_status ON reimbursements (book_id, status)"
        )

        // ---------- 债务 ----------
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS debts (
                debt_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                book_id INTEGER NOT NULL,
                direction INTEGER NOT NULL,
                counterparty TEXT NOT NULL,
                total_fen INTEGER NOT NULL,
                settled_fen INTEGER NOT NULL DEFAULT 0,
                account_id INTEGER NOT NULL DEFAULT 0,
                due_date TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                status INTEGER NOT NULL DEFAULT 0,
                create_at INTEGER NOT NULL,
                update_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_book_status ON debts (book_id, status)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS debt_repayments (
                repayment_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                debt_id INTEGER NOT NULL,
                amount_fen INTEGER NOT NULL,
                repay_time INTEGER NOT NULL,
                bill_id INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_debt_repayments_debt ON debt_repayments (debt_id)")

        // ---------- 分期 ----------
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS installments (
                installment_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                book_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                total_fen INTEGER NOT NULL,
                periods INTEGER NOT NULL,
                paid_periods INTEGER NOT NULL DEFAULT 0,
                per_period_fen INTEGER NOT NULL,
                account_id INTEGER NOT NULL DEFAULT 0,
                category_id INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                create_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_installments_book ON installments (book_id)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS installment_payments (
                payment_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                installment_id INTEGER NOT NULL,
                period_index INTEGER NOT NULL,
                amount_fen INTEGER NOT NULL,
                pay_time INTEGER NOT NULL,
                bill_id INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_installment_payments_plan ON installment_payments (installment_id)"
        )

        // ---------- 年度预算 ----------
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS annual_budgets (
                budget_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                book_id INTEGER NOT NULL,
                year INTEGER NOT NULL,
                total_limit_fen INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_annual_budgets_book_year ON annual_budgets (book_id, year)"
        )
    }

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
        val cursor = query("PRAGMA table_info(`$table`)")
        return cursor.use { c ->
            val idx = c.getColumnIndex("name")
            if (idx < 0) false else {
                var found = false
                while (c.moveToNext()) {
                    if (c.getString(idx) == column) {
                        found = true
                        break
                    }
                }
                found
            }
        }
    }
}
