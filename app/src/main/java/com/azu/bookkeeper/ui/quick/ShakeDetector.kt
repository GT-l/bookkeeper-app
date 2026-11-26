package com.azu.bookkeeper.ui.quick

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 摇一摇识别器。
 *
 * 市面上的摇一摇（微信「摇一摇」、支付宝手势、各记账 App 的摇一摇记一笔）都不是
 * 「合加速度超过某个值就触发」这么简单——那样走路、放下手机、单方向推一下都会误触，
 * 于是出现「摇一次记了三笔」。成熟做法是下面四条一起用：
 *
 * 1. **去重力**：用低通滤波估出重力分量，只看线性加速度。手机静止时合加速度恒为 9.8，
 *    不做去重力的话阈值只能往 20+ 抬，手感又变差。
 * 2. **双向确认**：一次真正的摇动必然是「往一个方向甩 → 立刻往反方向甩」。
 *    只出现单方向峰值（放下手机、走路颠一下）不算，这一步能砍掉绝大多数误触。
 * 3. **多帧确认**：同一方向的超阈值样本至少 2 个，过滤单点噪声。
 * 4. **冷却**：命中后 [_cooldownMs] 内不再上报；调用方还会再加一把「正在处理」的锁，
 *    保证一次摇动只走一条链路。
 *
 * 另外只在前台服务里注册，且每次采样都检查屏幕是否点亮（熄屏 = 装兜里，不响应）。
 */
class ShakeDetector(
    private var threshold: Float,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val onShake: () -> Unit
) : SensorEventListener {

    /** 低通滤波的重力分量（m/s²）。 */
    private val gravity = FloatArray(3)
    private var gravityReady = false

    /** 上一次超阈值时的主导方向：+1 / -1；0 表示还没有基准。 */
    private var peakSign = 0

    /** 当前这段连续超阈值采样里的样本数。 */
    private var overCount = 0

    private var lastShakeAt = 0L

    /** 灵敏度变化时热更新阈值，不需要重注册传感器。 */
    fun updateThreshold(value: Float) {
        threshold = value
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!gravityReady) {
            gravity[0] = x
            gravity[1] = y
            gravity[2] = z
            gravityReady = true
            return
        }
        // 低通滤波：alpha 越大越「迟钝」，0.8 对应约 0.2 秒的重力跟随
        gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * x
        gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * y
        gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * z

        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val linear = sqrt(lx * lx + ly * ly + lz * lz)

        if (linear < threshold) {
            overCount = 0
            return
        }
        overCount++
        if (overCount < MIN_SAMPLES) return

        // 主导方向：横向/纵向里位移更大的那一轴，取符号
        val sign = if (abs(lx) >= abs(lz)) {
            if (lx > 0f) 1 else -1
        } else {
            if (lz > 0f) 1 else -1
        }
        if (peakSign == 0) {
            peakSign = sign
            return
        }
        if (sign == peakSign) return // 还在同一次甩动里，等反向

        // 出现反向峰值 → 一次完整的来回摇动
        peakSign = sign
        overCount = 0
        val now = System.currentTimeMillis()
        if (now - lastShakeAt < cooldownMs) return
        lastShakeAt = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ALPHA = 0.8f

        /** 同一方向至少 2 个超阈值样本，滤掉单点噪声。 */
        const val MIN_SAMPLES = 2

        /** 命中后的冷却：2.2 秒内再摇也不再上报。 */
        const val COOLDOWN_MS = 2_200L
    }
}
