package com.azu.bookkeeper.domain.model

/** 分类树种类：支出 / 收入两套独立树。 */
enum class CategoryKind(val code: Int) {
    EXPENSE(0),
    INCOME(1);

    companion object {
        fun fromCode(code: Int): CategoryKind = entries.firstOrNull { it.code == code } ?: EXPENSE
    }
}

/** 分类领域模型；parentId == 0L 表示一级分类（最多二级）。 */
data class Category(
    val categoryId: Long = 0L,
    val bookId: Long,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val name: String,
    val parentId: Long = 0L,
    val icon: String = "",
    val sortOrder: Int = 0
) {
    val isRoot: Boolean get() = parentId == 0L
}
