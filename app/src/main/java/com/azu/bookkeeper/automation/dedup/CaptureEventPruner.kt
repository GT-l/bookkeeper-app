package com.azu.bookkeeper.automation.dedup

import com.azu.bookkeeper.data.local.db.dao.CaptureDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.3.22 问题五方案 A+E：捕获事件表清理策略（纯函数）+ 清理入口。
 *
 * 真机问题：几十条账单的数据库接近 4MB。根因是 `capture_events.raw_text` 全量落库
 * （节点读屏上限 6000 字），而 `CaptureDao.deleteOlderThan()` 全项目没有调用点——
 * 事件表只增不减。账单表的 `ocr_raw_text` 早已截到 2000 字，事件表却没限制。
 *
 * 策略（受问题五方案 E 约束）：
 * - 终态事件（已入账 / 已改写 / 已丢弃）保留 [RETAIN_TERMINAL_DAYS] 天；
 * - 待处理 / 待校对的事件**永不清理**（用户还没确认，删了就丢账单）；
 * - 清理由新捕获事件触发，进程内 [PRUNE_INTERVAL_MS] 节流，避免每次事件都写库。
 */
object CaptureEventPruner {

    const val RETAIN_TERMINAL_DAYS = 7L
    const val PRUNE_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val MAX_EVENT_RAW_TEXT_LENGTH = 2000

    /**
     * 是否到了该清理的时刻。
     * 受问题五方案 E 约束：首次进入（lastAt=0）立即清一次，之后按节流间隔。
     */
    fun shouldPrune(nowMillis: Long, lastPruneAtMillis: Long): Boolean =
        lastPruneAtMillis <= 0L || nowMillis - lastPruneAtMillis >= PRUNE_INTERVAL_MS

    /** 受问题五方案 E 约束：计算终态事件的清理截止时间（早于此刻的才删）。 */
    fun cutoffMillis(nowMillis: Long, retainDays: Long = RETAIN_TERMINAL_DAYS): Long =
        nowMillis - retainDays * 24L * 60 * 60 * 1000

    /** 受问题五方案 A 约束：落库原文统一截断，与账单表口径一致。 */
    fun boundedRawText(rawText: String): String = rawText.take(MAX_EVENT_RAW_TEXT_LENGTH)
}

/** 清理执行器：只在 `CaptureCoordinator.onCapture` 的 IO 协程里调用。 */
@Singleton
class PruneCaptureEventsUseCase @Inject constructor(
    private val captureDao: CaptureDao
) {
    private var lastPruneAt = 0L

    suspend operator fun invoke(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!CaptureEventPruner.shouldPrune(nowMillis, lastPruneAt)) return false
        val cutoff = CaptureEventPruner.cutoffMillis(nowMillis)
        // 只删终态：待处理 / 待校对事件删了就等于丢账单（受问题五方案 E 约束）
        val deleted = runCatching { captureDao.deleteTerminalOlderThan(cutoff) }.getOrElse { 0 }
        // 只有真正执行过才更新时间；DB 异常时下次再试，不吞掉清理机会
        if (deleted >= 0) lastPruneAt = nowMillis
        return deleted > 0
    }
}
