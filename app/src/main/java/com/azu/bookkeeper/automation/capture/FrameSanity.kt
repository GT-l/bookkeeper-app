package com.azu.bookkeeper.automation.capture

import android.graphics.Bitmap

/**
 * 截图帧体检（v1.3.16）。
 *
 * 背景（realme RMX3888 / Android 37 真机日志）：
 * 无障碍截屏返回「成功 1264x2780」，交给 OCR 却得到 `识别完成 0 字 conf=0.0 blocks=0`，
 * 最后报 EXTRACT_EMPTY「没找到扣款金额」。也就是说：**截图调用成功，但画面上没有内容**，
 * 只是帧不是纯黑（旧版只判了平均亮度 < 8），所以既没被黑帧检查挡住，也没法解释为什么 OCR 没字。
 *
 * 这里把判定从「是否够黑」升级为「是否整帧单色」：
 * - 采样 32x64 个点，算平均亮度、最暗、最亮、近黑/近白占比；
 * - 亮暗跨度 < [UNIFORM_SPAN] 视为单色帧（全黑、全白、全灰都算），说明系统没给到真实画面；
 * - 判定结果与统计一起写日志，下次用户贴 log 就能直接区分「帧是空的」和「OCR 没识别出来」。
 *
 * 为什么不误杀正常页面：正常支付页有标题、金额大字、按钮，采样点里必然出现亮暗跨度 > 100，
 * 而单色帧的跨度接近 0。
 */
object FrameSanity {

    /** 亮暗跨度小于该值 → 整帧单色。 */
    const val UNIFORM_SPAN = 12

    /** 采样点太少（极小图）不做单色判定，避免误判。 */
    const val MIN_SAMPLES = 64

    data class Stats(
        val width: Int,
        val height: Int,
        val samples: Int,
        val meanLuma: Int,
        val minLuma: Int,
        val maxLuma: Int,
        val darkPercent: Int,
        val brightPercent: Int
    ) {
        /** 整帧单色：系统返回的是空白/黑屏帧，不是真实画面。 */
        val uniform: Boolean get() = isUniform(minLuma, maxLuma, samples)

        fun describe(): String =
            "${width}x${height} 采样${samples} 亮度均值$meanLuma [$minLuma..$maxLuma] " +
                "近黑$darkPercent% 近白$brightPercent%"
    }

    /** 纯函数，便于 JVM 单测（Bitmap 在单测里不可用）。 */
    fun isUniform(minLuma: Int, maxLuma: Int, samples: Int): Boolean =
        samples >= MIN_SAMPLES && (maxLuma - minLuma) < UNIFORM_SPAN

    fun analyze(bitmap: Bitmap): Stats {
        val width = bitmap.width
        val height = bitmap.height
        val stepX = (width / 32).coerceAtLeast(1)
        val stepY = (height / 64).coerceAtLeast(1)
        var samples = 0
        var sum = 0L
        var min = 255
        var max = 0
        var dark = 0
        var bright = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val px = runCatching { bitmap.getPixel(x, y) }.getOrDefault(0)
                val luma = ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
                sum += luma
                if (luma < min) min = luma
                if (luma > max) max = luma
                if (luma <= 10) dark++
                if (luma >= 245) bright++
                samples++
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) {
            return Stats(width, height, 0, 0, 0, 0, 0, 0)
        }
        return Stats(
            width = width,
            height = height,
            samples = samples,
            meanLuma = (sum / samples).toInt(),
            minLuma = min,
            maxLuma = max,
            darkPercent = dark * 100 / samples,
            brightPercent = bright * 100 / samples
        )
    }
}
