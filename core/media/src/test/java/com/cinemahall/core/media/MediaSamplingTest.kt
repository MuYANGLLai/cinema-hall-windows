package com.cinemahall.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7 的抽样规则。
 *
 * 这些常量是**协议的一部分**（两端必须用同一条规则），所以钉住它们的值本身
 * 就有意义：改动 `BLOCK_BYTES` 或偏移公式会让所有已发布的客户端摘要全部对不上。
 */
class MediaSamplingTest {

    private val mib = 1024L * 1024L

    @Test
    fun `分块大小是 1 MiB`() {
        assertEquals(1024 * 1024, MediaSampling.BLOCK_BYTES)
    }

    @Test
    fun `小于 3 MiB 退化为整文件一块`() {
        val plan = MediaSampling.blocks(3 * mib - 1)

        assertEquals(1, plan.size)
        assertEquals(SampledBlock(index = 0, offset = 0L, length = (3 * mib - 1).toInt()), plan[0])
        assertTrue(MediaSampling.isWholeFile(3 * mib - 1))
    }

    @Test
    fun `空文件是合法的整文件一块`() {
        val plan = MediaSampling.blocks(0)

        assertEquals(1, plan.size)
        assertEquals(0L, plan[0].offset)
        assertEquals(0, plan[0].length)
        assertTrue(MediaSampling.isWholeFile(0))
    }

    @Test
    fun `正好 3 MiB 进入三段模式`() {
        // 边界是 `<` 而不是 `<=`：3 MiB 本身已经够三段，不该退化。
        assertFalse(MediaSampling.isWholeFile(3 * mib))

        val plan = MediaSampling.blocks(3 * mib)

        assertEquals(3, plan.size)
        assertEquals(listOf(0L, 3 * mib / 2, 3 * mib - mib), plan.map { it.offset })
        assertEquals(listOf(1024 * 1024, 1024 * 1024, 1024 * 1024), plan.map { it.length })
    }

    @Test
    fun `三段模式的偏移是 0 与 size 的一半与 size 减 1 MiB`() {
        val size = 20L * 1024 * 1024 * 1024 // 20 GiB：真机上完全可能

        val plan = MediaSampling.blocks(size)

        assertEquals(0L, plan[0].offset)
        assertEquals(size / 2, plan[1].offset)
        assertEquals(size - mib, plan[2].offset)
        assertEquals(listOf(0, 1, 2), plan.map { it.index })
    }

    @Test
    fun `几十 GB 的文件不会整数溢出`() {
        val size = 64L * 1024 * 1024 * 1024 // 64 GiB

        val plan = MediaSampling.blocks(size)

        // 若内部用 Int 运算，size 的一半会变成负数，这一条就会失败。
        assertTrue("中段偏移必须为正：${plan[1].offset}", plan[1].offset > 0)
        assertEquals(32L * 1024 * 1024 * 1024, plan[1].offset)
        assertEquals(64L * 1024 * 1024 * 1024 - mib, plan[2].offset)
    }

    @Test
    fun `负数大小被拒绝`() {
        val error = runCatching { MediaSampling.blocks(-1) }.exceptionOrNull()

        assertTrue("应当抛异常，实得 $error", error is IllegalArgumentException)
    }
}