package com.azu.bookkeeper.core

/**
 * 「系统原生手势 → 记一笔」的手势编号。
 *
 * 为什么不再用三指上滑悬浮条：底部悬浮条会吞掉其他应用的触摸，与系统返回手势冲突，
 * 真机上三指事件也常被系统优先消费。这里改成**系统自己识别**的手势：
 * - 指纹传感器四向滑动（FingerprintGestureController，需硬件支持）；
 * - 摇一摇（加速度传感器，独立前台服务，不依赖无障碍）。
 *
 * 曾尝试「无障碍按钮」flagRequestAccessibilityButton，但 ColorOS 会把它渲染成
 * 一个系统级悬浮球（不是本 App 的悬浮球），已在配置里移除。
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
    /** 旧版本"无障碍按钮"选项，已废弃；读到时按关闭处理。 */
    const val DEPRECATED_ACCESSIBILITY_BUTTON = 6

    val ALL = intArrayOf(OFF, FINGER_UP, FINGER_DOWN, FINGER_LEFT, FINGER_RIGHT, SHAKE)

    fun label(code: Int): String = when (code) {
        FINGER_UP -> "指纹上滑"
        FINGER_DOWN -> "指纹下滑"
        FINGER_LEFT -> "指纹左滑"
        FINGER_RIGHT -> "指纹右滑"
        SHAKE -> "摇一摇"
        else -> "不启用"
    }

    fun isFingerprint(code: Int): Boolean =
        code in FINGER_UP..FINGER_RIGHT
}

/** 摇一摇命中后要做什么。 */
object ShakeActions {
    const val OPEN_FORM = 0
    const val SCREEN_CAPTURE = 1

    fun label(action: Int): String = if (action == SCREEN_CAPTURE) "直接识屏" else "打开记一笔"
}

/**
 * 摇一摇灵敏度：阈值即「去重力后的线性加速度」门槛（m/s²）。
 *
 * 为什么要有这一项：同一套阈值在不同人手上体感差别很大——手重的人觉得一碰就触发，
 * 手轻的人摇半天没反应。市面上的摇一摇（微信/支付宝）都给「灵敏度」，
 * 这里同样暴露三档，默认「标准」。
 */
object ShakeSensitivity {
    /** 稳：需要明显用力摇。 */
    const val STABLE = 0

    /** 标准（默认）。 */
    const val STANDARD = 1

    /** 灵敏：轻轻晃就触发，适合单手轻摇的用户。 */
    const val SENSITIVE = 2

    val ALL = intArrayOf(STABLE, STANDARD, SENSITIVE)

    fun label(value: Int): String = when (value) {
        STABLE -> "稳"
        SENSITIVE -> "灵敏"
        else -> "标准"
    }

    fun description(value: Int): String = when (value) {
        STABLE -> "需要明显摇动，最不容易误触"
        SENSITIVE -> "轻轻晃一下就触发"
        else -> "默认，日常使用推荐"
    }

    /** 线性加速度阈值（m/s²）。数值越大越难触发。 */
    fun threshold(value: Int): Float = when (value) {
        STABLE -> 17f
        SENSITIVE -> 8.5f
        else -> 12f
    }
}

/**
 * 手势选择的进程内缓存：无障碍服务与设置页都在主进程，写设置后立刻可见，
 * 避免手势回调里做阻塞式 DataStore 读取。服务连接时会用持久化值重新初始化。
 */
object QuickGestureCache {
    @Volatile
    var code: Int = QuickGestureCodes.OFF

    @Volatile
    var shakeAction: Int = ShakeActions.OPEN_FORM

    @Volatile
    var shakeSensitivity: Int = ShakeSensitivity.STANDARD
}
