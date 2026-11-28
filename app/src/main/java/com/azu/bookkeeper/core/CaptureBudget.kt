package com.azu.bookkeeper.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * 前台重活的资源闸门（v1.3.15）。
 *
 * 背景：更新包 88MB，下载慢时用户往往会一边下一边继续用微信，此时识屏的「截图 OCR 兜底」
 * 会在后台反复跑（截图 1080×2400 + ML Kit 中文模型推理，单次几百毫秒到数秒）。
 * 它和单线程下载线程抢 CPU / IO，实测会让下载速度明显掉下来，用户看到的是「下载越来越慢」。
 *
 * 这里用引用计数表示「有前台重活正在进行」：下载期间关闸，识屏只走读无障碍节点这条
 * 几乎零成本的路径；截图 OCR 兜底直接跳过（不排队、不补跑，避免闸门一开就雪崩）。
 *
 * 注意进程边界：无障碍服务没有独立 `android:process`，与本对象同进程，所以计数有效。
 * OCR 在 `:ocr` 进程里跑，闸门在派发前判断，不依赖跨进程共享。
 */
object CaptureBudget {

    private val heavyTasks = AtomicInteger(0)

    /** 是否有重活在进行（下载更新等）。 */
    val isBusy: Boolean get() = heavyTasks.get() > 0

    fun beginHeavyTask() {
        heavyTasks.incrementAndGet()
    }

    fun endHeavyTask() {
        // 计数不允许掉到负数：多调一次 end 只当作「已经空闲」，否则闸门会永久关死
        if (heavyTasks.decrementAndGet() < 0) heavyTasks.set(0)
    }

    /** 包一层，异常路径也能保证关闸。 */
    inline fun <T> runHeavy(block: () -> T): T {
        beginHeavyTask()
        return try {
            block()
        } finally {
            endHeavyTask()
        }
    }
}
