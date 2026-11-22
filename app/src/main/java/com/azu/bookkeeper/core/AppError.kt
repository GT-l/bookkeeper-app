package com.azu.bookkeeper.core

import android.database.SQLException
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * 统一错误模型（架构文档 §9.4）。
 * 每个错误族自带默认错误码与用户可读文案，调用方可覆盖 code/message。
 */
sealed class AppError(
    open val code: String,
    open val userMessage: String,
    open val cause: Throwable? = null
) {

    data class Database(
        override val code: String = "E_DB_001",
        override val userMessage: String = "数据保存失败，请稍后重试",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class Balance(
        override val code: String = "E_DB_002",
        override val userMessage: String = "账户余额异常，请在资产页校准",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    /** 架构文档未列出的输入校验错误族，用于 UseCase 参数校验（不违反既有码段）。 */
    data class Validation(
        override val code: String = "E_VALID_001",
        override val userMessage: String = "填写内容不完整或格式不正确",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class Import(
        override val code: String = "E_IMPORT_001",
        override val userMessage: String = "账单文件解析失败，请检查格式",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class Ocr(
        override val code: String = "E_OCR_001",
        override val userMessage: String = "文字识别失败，请手动录入",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class Permission(
        override val code: String = "E_PERM_001",
        override val userMessage: String = "缺少必要权限，请在设置中开启",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class Automation(
        override val code: String = "E_AUTO_001",
        override val userMessage: String = "自动记账服务未运行，请重新开启",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class File(
        override val code: String = "E_FILE_001",
        override val userMessage: String = "文件读写失败，请检查存储空间",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class Backup(
        override val code: String = "E_BACKUP_001",
        override val userMessage: String = "备份或恢复失败，请重试",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    /** T6：短信权限被拒（用户未授权 READ_SMS 或组件被禁用）。 */
    data class SmsPermission(
        override val code: String = "E_SMS_001",
        override val userMessage: String = "未授予短信读取权限，请在设置中开启",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    /** T6：短信解析失败（非白名单 / 非扣款类 / 字段缺失）。 */
    data class SmsParse(
        override val code: String = "E_SMS_002",
        override val userMessage: String = "这条短信未识别为银行扣款，已跳过",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    /** T7：快捷补记金额非法。 */
    data class BillAmount(
        override val code: String = "E_BILL_001",
        override val userMessage: String = "金额必须大于 0",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    data class Unknown(
        override val code: String = "E_UNKNOWN",
        override val userMessage: String = "发生未知错误，请稍后重试",
        override val cause: Throwable? = null
    ) : AppError(code, userMessage, cause)

    companion object {

        /** 把底层异常归类到对应错误族，供 safeResult 使用。 */
        fun from(throwable: Throwable): AppError = when (throwable) {
            is SQLException -> Database(cause = throwable)
            is IOException -> File(cause = throwable)
            is SecurityException -> Permission(cause = throwable)
            is GeneralSecurityException -> File(
                code = "E_FILE_003",
                userMessage = "解密失败，文件可能已损坏",
                cause = throwable
            )
            is IllegalArgumentException -> Validation(cause = throwable)
            else -> Unknown(cause = throwable)
        }
    }
}
