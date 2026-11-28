package com.azu.bookkeeper.automation.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.16：截图帧体检的纯函数部分。
 *
 * 背景：真机上无障碍截屏「成功 1264x2780」，但 OCR 0 字 —— 系统给的是空白帧。
 * 旧的黑帧判定只看平均亮度 < 8，全白/全灰帧漏过，空图被丢进 OCR，
 * 最后报成「没找到扣款金额」。这里钉住「整帧单色」的判定边界。
 */
class FrameSanityTest {

    @Test
    fun `亮暗跨度小于阈值判为单色帧`() {
        assertTrue("全黑帧", FrameSanity.isUniform(0, 0, 2048))
        assertTrue("全白帧", FrameSanity.isUniform(255, 260, 2048))
        assertTrue("全灰帧", FrameSanity.isUniform(128, 139, 2048))
        assertFalse("有文字的页面", FrameSanity.isUniform(0, 255, 2048))
        assertFalse("跨度达到阈值就不算单色", FrameSanity.isUniform(20, 33, 2048))
    }

    @Test
    fun `采样点太少不做单色判定`() {
        assertFalse(FrameSanity.isUniform(0, 0, FrameSanity.MIN_SAMPLES - 1))
        assertTrue(FrameSanity.isUniform(0, 0, FrameSanity.MIN_SAMPLES))
    }
}
