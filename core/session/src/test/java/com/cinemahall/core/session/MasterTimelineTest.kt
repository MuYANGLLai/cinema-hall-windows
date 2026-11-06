package com.cinemahall.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.2 的虚拟主时间轴：锚点 + 外推，以及那句必须做的 `+ flightMs` 飞行时间补偿。
 */
class MasterTimelineTest {

    @Test
    fun 未锚定时目标位置为零且标记为未锚定() {
        val timeline = MasterTimeline()

        assertFalse(timeline.isAnchored)
        assertEquals(0L, timeline.targetPositionMs(1_000))
    }

    @Test
    fun 播放状态下目标位置按本地时钟外推() {
        val timeline = MasterTimeline()
        timeline.anchor(nowClockMs = 10_000, positionMs = 5_000, flightMs = 50, isPlaying = true)

        assertEquals(5_050L, timeline.anchoredMediaMs)
        // 锚点那一刻就是锚点位置本身
        assertEquals(5_050L, timeline.targetPositionMs(10_000))
        // 过了 1 秒就推进 1 秒
        assertEquals(6_050L, timeline.targetPositionMs(11_000))
        assertEquals(5_550L, timeline.targetPositionMs(10_500))
        // 过了 30 秒仍然只是外推，不需要房主再说一句话
        assertEquals(35_050L, timeline.targetPositionMs(40_000))
        assertTrue(timeline.isRunning)
    }

    @Test
    fun 暂停状态下目标位置冻结在锚点() {
        val timeline = MasterTimeline()
        timeline.anchor(nowClockMs = 10_000, positionMs = 5_000, flightMs = 50, isPlaying = false)

        assertEquals(5_050L, timeline.targetPositionMs(10_000))
        assertEquals(5_050L, timeline.targetPositionMs(10_500))
        assertEquals(5_050L, timeline.targetPositionMs(99_999))
        assertFalse(timeline.isRunning)
    }

    @Test
    fun 单向延迟补偿让锚点比房主报告的位置更靠前() {
        val timeline = MasterTimeline()

        timeline.anchor(nowClockMs = 0, positionMs = 1_000, flightMs = 0, isPlaying = true)
        assertEquals("不补偿时锚点就是房主报告的位置", 1_000L, timeline.anchoredMediaMs)

        timeline.anchor(nowClockMs = 0, positionMs = 1_000, flightMs = 150, isPlaying = true)
        assertEquals("补偿后应加上这条消息的飞行时间", 1_150L, timeline.anchoredMediaMs)
    }

    @Test
    fun 影片开头附近的负值被夹到零() {
        val timeline = MasterTimeline()
        // 房主刚起播、位置为 0，本机时钟还比锚点早一点点
        timeline.anchor(nowClockMs = 1_000, positionMs = 0, flightMs = 0, isPlaying = true)

        assertEquals(0L, timeline.targetPositionMs(1_000))
        assertEquals("不能返回负数 —— 那是 seekTo 的非法输入", 0L, timeline.targetPositionMs(400))
    }

    @Test
    fun 重新锚定会整体替换曲线而不是叠加() {
        val timeline = MasterTimeline()
        timeline.anchor(nowClockMs = 10_000, positionMs = 5_000, flightMs = 0, isPlaying = true)
        assertEquals(7_000L, timeline.targetPositionMs(12_000))

        // 房主拖到了 60 秒处
        timeline.anchor(nowClockMs = 12_000, positionMs = 60_000, flightMs = 100, isPlaying = true)
        assertEquals(60_100L, timeline.anchoredMediaMs)
        assertEquals(60_100L, timeline.targetPositionMs(12_000))
        assertEquals(60_600L, timeline.targetPositionMs(12_500))
    }

    @Test
    fun 重置后回到未锚定() {
        val timeline = MasterTimeline()
        timeline.anchor(nowClockMs = 1_000, positionMs = 1_000, flightMs = 0, isPlaying = true)

        timeline.reset()

        assertFalse(timeline.isAnchored)
        assertFalse(timeline.isRunning)
        assertEquals(0L, timeline.targetPositionMs(5_000))
    }
}
