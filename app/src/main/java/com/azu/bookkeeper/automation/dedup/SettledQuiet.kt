package com.azu.bookkeeper.automation.dedup

import com.azu.bookkeeper.automation.capture.PaymentScene
import com.azu.bookkeeper.core.DateTimeUtils

/**
 * v1.3.22 问题三方案 A：支付成功后的静默期判定（纯函数，可单测）。
 *
 * 真机问题：同一笔收到的转账被反复扫描，又记成一笔新收入。
 * 旧逻辑里 `differentBillByTime` 只要「两个时间字符串不相等」就放行静默期——
 * 转账详情页反复扫到不同格式 / 半个时间串时，静默期被轻易绕过，
 * 而页面没有可靠交易时间时 DB 去重键里的 billTime 落成识屏时刻，去重也跟着失效。
 *
 * 修法（不碰冻结的 PaymentScreenRecognizer）：
 * 1. 只有**双方时间串都能解析成真实时间戳、且确实不同**才算「另一笔」；
 *    解析失败的字符串一律视为无证据，继续受静默期约束。
 * 2. 转账收入 / 红包收入场景与抖音订单一致，静默期 12s → 60s（已有先例）。
 *
 * 「宁可漏记不可错记」：宁可多静默几秒，也不能把同一笔收入记两遍。
 */
object SettledQuiet {

    data class Mark(val at: Long, val money: String, val timeKey: String)

    /**
     * 是否确认为另一笔交易，可以解除静默期。
     * 受问题三方案 A 约束：无有效时间证据时不放行。
     */
    fun isDifferentBill(previous: Mark?, currentTimeKey: String): Boolean {
        if (previous == null) return false
        val oldKey = previous.timeKey.trim()
        val newKey = currentTimeKey.trim()
        if (oldKey.isEmpty() || newKey.isEmpty() || oldKey == newKey) return false
        val oldTime = DateTimeUtils.parseFlexible(oldKey) ?: return false
        val newTime = DateTimeUtils.parseFlexible(newKey) ?: return false
        return oldTime != newTime
    }

    /** 受问题三方案 A 约束：转账 / 红包收入与抖音订单同样用 60s 静默期。 */
    fun quietMsFor(scene: PaymentScene?): Long = when (scene) {
        PaymentScene.DOUYIN_ORDER,
        PaymentScene.WECHAT_TRANSFER_IN,
        PaymentScene.WECHAT_RED_PACKET_IN,
        PaymentScene.ALIPAY_TRANSFER_IN,
        PaymentScene.ALIPAY_RED_PACKET_IN -> 60_000L
        else -> 12_000L
    }

    /**
     * 是否应当跳过本次提交。
     * 受问题三方案 A 约束：时间无证据时静默期生效；金额明确不同时仍放行另一笔。
     */
    fun shouldBlock(
        previous: Mark?,
        nowMillis: Long,
        currentMoney: String,
        currentTimeKey: String,
        scene: PaymentScene?
    ): Boolean {
        if (previous == null) return false
        if (isDifferentBill(previous, currentTimeKey)) return false
        if (nowMillis - previous.at >= quietMsFor(scene)) return false
        val oldMoney = previous.money.trim()
        val newMoney = currentMoney.trim()
        // 金额两边都非空且不同 → 是另一笔，放行；任一为空则证据不足，继续静默
        return oldMoney.isEmpty() || newMoney.isEmpty() || oldMoney == newMoney
    }
}
