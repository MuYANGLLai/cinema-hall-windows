package com.cinemahall.core.library.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度节流（任务书 W5–W6：「播放中每 5 分钟且变化 > 30 秒；暂停/退房立即」）。
 *
 * 这一条是**用户可感知**的：发太勤会撞服务端限流（60 次/分钟）并把重要的一条挤掉；
 * 发太懒则另一台设备看到的进度是十几分钟前的。所以两个边界都要钉死：
 * 正好 5 分钟算不算、正好 30 秒算不算。
 */
class ProgressThrottleTest {

    private val minute = 60_000L

    private fun playing(
        positionMs: Long,
        nowMs: Long,
        lastPosition: Long?,
        lastAt: Long,
    ) = ProgressThrottle.shouldSend(
        trigger = ProgressTrigger.PLAYING,
        positionMs = positionMs,
        nowMs = nowMs,
        lastSentPositionMs = lastPosition,
        lastSentAtMs = lastAt,
    )

    @Test
    fun `播放中 四分钟多不发`() {
        assertFalse(playing(positionMs = 5 * minute, nowMs = 4 * minute, lastPosition = 0L, lastAt = 0L))
    }

    @Test
    fun `播放中满五分钟且前进超过 30 秒才发`() {
        assertTrue(
            playing(
                positionMs = 5 * minute + 31_000L,
                nowMs = 5 * minute,
                lastPosition = 5 * minute,
                lastAt = 0L,
            ),
        )
    }

    @Test
    fun `正好 30 秒的变化不发（判据是严格大于）`() {
        assertFalse(
            playing(
                positionMs = 5 * minute + 30_000L,
                nowMs = 5 * minute,
                lastPosition = 5 * minute,
                lastAt = 0L,
            ),
        )
    }

    @Test
    fun `满五分钟但几乎没动也不发`() {
        assertFalse(
            playing(
                positionMs = 5 * minute + 1_000L,
                nowMs = 5 * minute,
                lastPosition = 5 * minute,
                lastAt = 0L,
            ),
        )
    }

    @Test
    fun `从没上报过的片不卡变化量`() {
        assertTrue(
            "第一次上报没有基准可比，卡住只会让首条进度等到下一轮",
            playing(positionMs = 3_000L, nowMs = 5 * minute, lastPosition = null, lastAt = 0L),
        )
    }

    @Test
    fun `暂停立即发`() {
        assertTrue(
            ProgressThrottle.shouldSend(
                trigger = ProgressTrigger.PAUSED,
                positionMs = 61_000L,
                nowMs = 1_000L,
                lastSentPositionMs = 60_000L,
                lastSentAtMs = 0L,
            ),
        )
    }

    @Test
    fun `退房立即发`() {
        assertTrue(
            ProgressThrottle.shouldSend(
                trigger = ProgressTrigger.LEFT,
                positionMs = 61_000L,
                nowMs = 1_000L,
                lastSentPositionMs = 60_000L,
                lastSentAtMs = 0L,
            ),
        )
    }

    @Test
    fun `暂停但位置没变就不重复发同一份值`() {
        assertFalse(
            ProgressThrottle.shouldSend(
                trigger = ProgressTrigger.PAUSED,
                positionMs = 60_000L,
                nowMs = 1_000L,
                lastSentPositionMs = 60_000L,
                lastSentAtMs = 0L,
            ),
        )
    }
}
