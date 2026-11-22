package com.azu.bookkeeper.domain.model

/**
 * 捕获事件终态枚举（复用 capture_events.status 的 Int 列，不新增列）。
 * 非终态（PENDING / LOW_CONFIDENCE）才纳入重复提醒扫描；
 * CONSUMED_AUTO / USER_CORRECTED / DISCARDED 均为终态，不再提醒。
 */
enum class CaptureEventStatus(val code: Int, val label: String, val isTerminal: Boolean) {
    PENDING(0, "待处理", false),
    LOW_CONFIDENCE(1, "低置信度待校对", false),
    CONSUMED_AUTO(2, "自动入账完成", true),
    USER_CORRECTED(3, "用户改写完成", true),
    DISCARDED(4, "已丢弃", true);

    companion object {
        fun fromCode(code: Int): CaptureEventStatus =
            entries.firstOrNull { it.code == code } ?: PENDING
    }
}
