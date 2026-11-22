package com.azu.bookkeeper.domain.model

/** 账本状态：归档后仍可查询导出，不能新增账单。 */
enum class BookStatus(val code: Int) {
    ARCHIVED(0),
    ACTIVE(1);

    companion object {
        fun fromCode(code: Int): BookStatus = entries.firstOrNull { it.code == code } ?: ACTIVE
    }
}

/** 账本领域模型（账本间数据完全隔离）。 */
data class Book(
    val bookId: Long = 0L,
    val name: String,
    val icon: String = "",
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false,
    val status: BookStatus = BookStatus.ACTIVE
) {
    val isActive: Boolean get() = status == BookStatus.ACTIVE
}
