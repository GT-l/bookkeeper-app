package com.azu.bookkeeper.automation.capture

import java.util.concurrent.ConcurrentHashMap

/** 收银台阶段读到的「即将支付」上下文。 */
data class PendingPayment(
    val packageName: String,
    val amountFen: Long,
    val merchant: String,
    val at: Long
)

/**
 * 「支付前缓存 → 支付后补齐」（v1.3.6）。
 *
 * 为什么要它：用户在收银台（ENTRY）时，金额/商户往往写得最清楚；
 * 到了支付成功页，微信/支付宝常把金额放在动画节点里，无障碍读到的文本反而残缺，
 * 于是出现「明明付了，却抽不到金额 → 识别失败」。
 *
 * 做法：进入付款界面时把读到的金额/商户暂存 5 分钟；成功页提交时若字段缺失就用它补齐。
 * 只存内存、按包名隔离、过期即弃，不落库、不上传。
 */
object PendingPaymentCache {

    private const val TTL_MS = 5L * 60L * 1000L

    private val store = ConcurrentHashMap<String, PendingPayment>()

    fun remember(packageName: String, amountFen: Long, merchant: String) {
        if (packageName.isBlank()) return
        if (amountFen <= 0L && merchant.isBlank()) return
        store[packageName] = PendingPayment(
            packageName = packageName,
            amountFen = amountFen,
            merchant = merchant.trim(),
            at = System.currentTimeMillis()
        )
    }

    /** 取最近一次待支付上下文；过期或包名不符返回 null。 */
    fun take(packageName: String): PendingPayment? {
        val item = store[packageName] ?: return null
        if (System.currentTimeMillis() - item.at > TTL_MS) {
            store.remove(packageName)
            return null
        }
        return item
    }

    /** 成功消费后清掉，避免下一笔被上一笔的金额污染。 */
    fun clear(packageName: String) {
        store.remove(packageName)
    }
}
