package com.azu.bookkeeper.domain.model

/** 标签领域模型（扁平，归属账本；删标签不删账单）。 */
data class Tag(
    val tagId: Long = 0L,
    val bookId: Long,
    val name: String
)
