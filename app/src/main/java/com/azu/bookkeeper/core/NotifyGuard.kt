package com.azu.bookkeeper.core

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * 统一的通知投递守卫。
 *
 * 为什么必须先查权限再 notify（而不是只 try/catch）：
 * Android 13+ 未授予 POST_NOTIFICATIONS 时，`NotificationManagerCompat.notify()`
 * 会抛 SecurityException；更糟的是部分 OEM ROM（ColorOS / OrangeOS / HyperOS 等）
 * 不抛异常而是阻塞主线程等待 system_server 响应，冷启动阶段容易直接 ANR。
 * 所以规则是：所有非前台服务的 notify 一律先 `areNotificationsEnabled()` 再投递，
 * 投递本身再套 runCatching 兜底，保证通知层故障绝不拖垮主流程。
 */
object NotifyGuard {

    /** 通知是否可投递（Android 13+ 等价于 POST_NOTIFICATIONS 是否授予）。 */
    fun canPost(context: Context): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

    /**
     * 安全投递：权限未授予直接跳过（返回 false），投递异常吞掉记日志由调用方决定提示。
     * 永不向调用方抛异常。
     */
    fun safeNotify(context: Context, id: Int, notification: Notification): Boolean {
        val app = context.applicationContext
        if (!canPost(app)) return false
        return runCatching {
            NotificationManagerCompat.from(app).notify(id, notification)
            true
        }.getOrDefault(false)
    }
}
