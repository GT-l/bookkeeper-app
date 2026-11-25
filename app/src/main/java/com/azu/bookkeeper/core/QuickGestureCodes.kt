package com.azu.bookkeeper.core

/**
 * 「系统原生手势 → 记一笔」的手势编号。
 *
 * 为什么不再用三指上滑悬浮条：底部悬浮条会吞掉其他应用的触摸，与系统返回手势冲突，
 * 真机上三指事件也常被系统优先消费。这里改成**系统自己识别**的手势：
 * - 指纹传感器四向滑动（FingerprintGestureController，需硬件支持）；
 * - 摇一摇（加速度传感器，几乎所有机型都有）；
 * - 无障碍按钮（系统导航栏提供，部分 ROM 才有）。
 *
 * 不启用触摸探索模式（那会把单击变双击、滑动变双指滑动，严重干扰正常操作）。
 */
object QuickGestureCodes {
    const val OFF = 0
    const val FINGER_UP = 1
    const val FINGER_DOWN = 2
    const val FINGER_LEFT = 3
    const val FINGER_RIGHT = 4
    const val SHAKE = 5
    const val ACCESSIBILITY_BUTTON = 6

    val ALL = intArrayOf(
        OFF, FINGER_UP, FINGER_DOWN, FINGER_LEFT, FINGER_RIGHT, SHAKE, ACCESSIBILITY_BUTTON
    )

    fun label(code: Int): String = when (code) {
        FINGER_UP -> "指纹上滑"
        FINGER_DOWN -> "指纹下滑"
        FINGER_LEFT -> "指纹左滑"
        FINGER_RIGHT -> "指纹右滑"
        SHAKE -> "摇一摇"
        ACCESSIBILITY_BUTTON -> "无障碍按钮"
        else -> "不启用"
    }

    fun isFingerprint(code: Int): Boolean =
        code in FINGER_UP..FINGER_RIGHT
}

/**
 * 手势选择的进程内缓存：无障碍服务与设置页都在主进程，写设置后立刻可见，
 * 避免手势回调里做阻塞式 DataStore 读取。服务连接时会用持久化值重新初始化。
 */
object QuickGestureCache {
    @Volatile
    var code: Int = QuickGestureCodes.OFF
}
