package com.cinemahall.desktop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W1 的「起播前设置不生效」补偿逻辑。
 *
 * 这些用例守的是**行为**而不是实现细节：值是留在待办还是已经清掉，直接决定
 * 「从 0 开始播 / 倍速没生效」这两个症状还会不会出现。
 */
class PendingPlaybackTest {

    @Test
    fun `未就绪时设置会留在待办`() {
        val pending = PendingPlayback()
        pending.requestSeek(90_000)
        pending.requestRate(1.5f)

        val cleared = pending.apply(
            applyRate = { false },
            applySeek = { false },
        )

        assertEquals(0, cleared)
        assertEquals(90_000L, pending.pendingSeekMs)
        assertEquals(1.5f, pending.pendingRate)
    }

    @Test
    fun `就绪后施加成功的项会被清掉`() {
        val pending = PendingPlayback()
        pending.requestSeek(12_345)
        pending.requestRate(1.25f)

        val cleared = pending.apply(applyRate = { true }, applySeek = { true })

        assertEquals(2, cleared)
        assertNull(pending.pendingSeekMs)
        assertNull(pending.pendingRate)
    }

    @Test
    fun `后一次请求覆盖前一次`() {
        val pending = PendingPlayback()
        pending.requestSeek(1_000)
        pending.requestSeek(50_000)

        assertEquals(50_000L, pending.pendingSeekMs)
    }

    @Test
    fun `施加顺序是速率先于位置`() {
        val pending = PendingPlayback()
        pending.requestSeek(5_000)
        pending.requestRate(2f)
        val order = mutableListOf<String>()

        pending.apply(
            applyRate = { order += "rate"; true },
            applySeek = { order += "seek"; true },
        )

        assertEquals(listOf("rate", "seek"), order)
    }

    @Test
    fun `位置不允许是负数`() {
        val pending = PendingPlayback()
        pending.requestSeek(-10)

        assertEquals(0L, pending.pendingSeekMs)
    }

    @Test
    fun `只有失败的那一项会留下`() {
        val pending = PendingPlayback()
        pending.requestRate(1.5f)
        pending.requestSeek(9_000)

        val cleared = pending.apply(applyRate = { true }, applySeek = { false })

        assertEquals(1, cleared)
        assertNull(pending.pendingRate)
        assertEquals(9_000L, pending.pendingSeekMs)
    }
}
