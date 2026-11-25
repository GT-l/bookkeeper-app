package com.azu.bookkeeper.domain.model

/**
 * 月度预算（month 形如 202611）；categoryLimits 按分类 ID 存上限（分）。
 *
 * 总预算与分类预算的关系（全 App 唯一判定点，禁止在 UI / Worker 里各写一遍）：
 * 用户显式填了总预算（> 0）就用它；没填（0 或空记录）但有分类预算时，
 * 总预算 = 各分类预算之和。
 */
data class Budget(
    val budgetId: Long = 0L,
    val bookId: Long,
    val month: Int,
    val totalLimitFen: Long = 0L,
    val categoryLimits: Map<Long, Long> = emptyMap()
) {

    /** 各分类预算之和（分）；仅累计 > 0 的项，避免脏数据拉低总额。 */
    val categoryLimitSumFen: Long
        get() = categoryLimits.values.filter { it > 0L }.sum()

    /** 用户是否显式设置了总预算。 */
    val hasExplicitTotalLimit: Boolean
        get() = totalLimitFen > 0L

    /**
     * 生效总预算（分）：显式总预算优先，未设置时按分类预算之和兜底。
     *
     * 预算页进度条 / 剩余额度、超支提醒 Worker、桌面组件、统计概览都读这个字段，
     * 保证“没设总预算”与“设了总预算”在展示与告警上行为一致。
     */
    val effectiveTotalLimitFen: Long
        get() = if (hasExplicitTotalLimit) totalLimitFen else categoryLimitSumFen

    /** 生效总预算是否为「分类预算之和」兜底而来；UI 用它决定是否展示兜底说明。 */
    val isTotalLimitDerived: Boolean
        get() = !hasExplicitTotalLimit && categoryLimitSumFen > 0L
}
