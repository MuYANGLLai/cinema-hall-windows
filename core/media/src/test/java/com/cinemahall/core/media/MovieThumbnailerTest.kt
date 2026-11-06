package com.cinemahall.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MovieThumbnailer] 里那两个纯函数（抽稀倍数、取帧目标框）。
 *
 * ── 为什么只能测这两个 ──────────────────────────────────────────────────────
 * `cover(...)` 要一个真的 `MediaMetadataRetriever`（要设备与真片源），本模块的单测全是
 * 纯 JVM。真机那一半由《验收操作手册》§7.27 覆盖；这里锁住的是**算错了不会报错、
 * 只会让封面变糊或变黑**的那两步 —— 它们恰恰是没有任何异常可看的。
 *
 * 防的是两件事：
 *  1. `inSampleSize` 多缩一档（图小于目标尺寸，Compose 再放大回去）；
 *  2. 旋转过的片子拿到横竖相反的目标框（短边只得到目标的 1/3 像素）。
 */
class MovieThumbnailerTest {

    // ── sampleSize ─────────────────────────────────────────────────────────

    @Test
    fun `海报远大于目标时按 2 的幂抽稀到刚好不小于目标`() {
        // 4000×3000 的海报、目标 192：除以 16 之后是 250×187 —— 高已经小于 192，
        // 所以只能停在 8（500×375）。再多一档就会缩到目标尺寸以下。
        assertEquals(8, sampleSize(width = 4000, height = 3000, target = 192))
    }

    @Test
    fun `短边不小于目标时抽稀倍数是 1`() {
        assertEquals(1, sampleSize(width = 200, height = 300, target = 192))
    }

    @Test
    fun `恰好等于目标时不抽稀`() {
        assertEquals(1, sampleSize(width = 192, height = 192, target = 192))
    }

    @Test
    fun `尺寸未知时不抽稀`() {
        // 宁可多占一点内存，也不要因为一次元数据缺失把图缩成 1×1。
        assertEquals(1, sampleSize(width = 0, height = 0, target = 192))
        assertEquals(1, sampleSize(width = -1, height = 300, target = 192))
    }

    @Test
    fun `抽稀倍数永远是 2 的幂`() {
        // BitmapFactory 对非 2 的幂只有「向下取到 2 的幂」这一条保证，别依赖它。
        var size = 10000
        while (size >= 1) {
            val sample = sampleSize(width = size, height = size, target = 128)
            assertEquals("size=$size 时抽稀倍数不是 2 的幂", 0, sample and (sample - 1))
            size /= 3
        }
    }

    // ── framePositionUs ────────────────────────────────────────────────────

    @Test
    fun `取帧位置是片长的十分之一`() {
        // 100 分钟的片子 → 第 10 分钟。
        assertEquals(600_000_000L, framePositionUs(durationMs = 6_000_000))
    }

    @Test
    fun `时长未知时取第一帧`() {
        assertEquals(0L, framePositionUs(durationMs = 0))
        assertEquals(0L, framePositionUs(durationMs = -1))
    }

    // ── frameBox ───────────────────────────────────────────────────────────

    @Test
    fun `宽片定高按比例放宽`() {
        // 16:9、短边 192 → 341×192（341.33 向下取整，宁可少一个像素也不放大）。
        assertEquals(341 to 192, frameBox(videoWidth = 1920, videoHeight = 1080, rotationDegrees = 0, sizePx = 192))
    }

    @Test
    fun `竖片定宽按比例放高`() {
        assertEquals(192 to 341, frameBox(videoWidth = 1080, videoHeight = 1920, rotationDegrees = 0, sizePx = 192))
    }

    @Test
    fun `旋转 90 度时横竖换过来`() {
        // 竖着拍的片子：元数据报 1920×1080，实际画面是 1080×1920。
        assertEquals(192 to 341, frameBox(videoWidth = 1920, videoHeight = 1080, rotationDegrees = 90, sizePx = 192))
        assertEquals(192 to 341, frameBox(videoWidth = 1920, videoHeight = 1080, rotationDegrees = 270, sizePx = 192))
    }

    @Test
    fun `旋转 180 度不换横竖`() {
        assertEquals(341 to 192, frameBox(videoWidth = 1920, videoHeight = 1080, rotationDegrees = 180, sizePx = 192))
    }

    @Test
    fun `宽高缺失时退回正方形框`() {
        assertEquals(192 to 192, frameBox(videoWidth = 0, videoHeight = 0, rotationDegrees = 0, sizePx = 192))
        assertEquals(192 to 192, frameBox(videoWidth = 1920, videoHeight = 0, rotationDegrees = 0, sizePx = 192))
    }

    @Test
    fun `方片就是目标尺寸`() {
        assertEquals(192 to 192, frameBox(videoWidth = 1080, videoHeight = 1080, rotationDegrees = 0, sizePx = 192))
    }

    @Test
    fun `短边永远不小于目标`() {
        // 这一条是「裁切之后不必放大」的全部依据：目标框的短边一旦小于 sizePx，
        // 方卡裁出来的那块就要被 Compose 放大，封面当场变糊。
        val cases = listOf(
            1920 to 1080, 1080 to 1920, 3840 to 2160, 720 to 576, 1000 to 1001, 4 to 3,
        )
        for ((width, height) in cases) {
            val (boxWidth, boxHeight) = frameBox(width, height, rotationDegrees = 0, sizePx = 96)
            val shortSide = minOf(boxWidth, boxHeight)
            assertEquals("$width×$height 的短边小于目标", true, shortSide >= 96)
        }
    }
}
