package com.azu.bookkeeper.automation

import com.azu.bookkeeper.core.CaptureChannel
import com.azu.bookkeeper.core.CaptureGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截屏通道准入逻辑单测。
 *
 * 覆盖「熔断」这一层的判定：通道可用但熔断打开时必须被拒绝，
 * 避免上次崩溃过的通道被再次使用（用户会看到反复闪退）。
 */
class CaptureGateTest {

    @Test
    fun `可用且未熔断才允许尝试`() {
        assertTrue(CaptureGate.shouldAttempt(available = true, breakerOpen = false))
    }

    @Test
    fun `熔断打开时即使可用也必须拒绝`() {
        assertFalse(CaptureGate.shouldAttempt(available = true, breakerOpen = true))
    }

    @Test
    fun `不可用时无论熔断状态都拒绝`() {
        assertFalse(CaptureGate.shouldAttempt(available = false, breakerOpen = false))
        assertFalse(CaptureGate.shouldAttempt(available = false, breakerOpen = true))
    }

    @Test
    fun `每条通道都有非空熔断说明`() {
        CaptureChannel.entries.forEach { channel ->
            assertTrue(CaptureGate.breakerReason(channel).isNotBlank())
        }
    }
}
