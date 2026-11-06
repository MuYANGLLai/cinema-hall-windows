package com.cinemahall.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VlcPlaybackMapping] 的判据（2026-10-26 换内核）。
 *
 * ── 这一层为什么能在 JVM 上测 ────────────────────────────────────────────────
 * 换内核之后最容易写错的不是「调哪个方法」，而是三处标度：音量 `0f..1f` ↔ `0..100`、
 * 宽高比要乘像素宽高比（sar）、字幕语言码要按 **id** 而不是按顺序配对。这三件事一旦
 * 错，屏幕上分别是「音量滑块没用 / 画面比例不对 / 字幕按钮写着别人的语言」—— 都不报错，
 * 只能靠人眼看出来。所以把它们抽成纯函数、在这里逐条钉住。
 */
class VlcPlaybackMappingTest {

    // ── 音量 ────────────────────────────────────────────────────────────────

    @Test
    fun `满音量与静音正好落在两端`() {
        assertEquals(100, VlcPlaybackMapping.volumeToVlc(1f))
        assertEquals(0, VlcPlaybackMapping.volumeToVlc(0f))
        assertEquals(1f, VlcPlaybackMapping.volumeFromVlc(100), 1e-6f)
        assertEquals(0f, VlcPlaybackMapping.volumeFromVlc(0), 1e-6f)
    }

    @Test
    fun `影片音量上限 0点4 换算成 40`() {
        // 界面上的滑块上限是 MOVIE_VOLUME_LIMIT = 0.4f（§1.7 的听感取舍）。它必须
        // 逐字换成 40，否则「滑到顶」与「诊断页写的数字」会对不上。
        assertEquals(40, VlcPlaybackMapping.volumeToVlc(0.4f))
        assertEquals(0.4f, VlcPlaybackMapping.volumeFromVlc(40), 1e-6f)
    }

    @Test
    fun `越界的音量按边界处理而不是静默乱来`() {
        assertEquals(0, VlcPlaybackMapping.volumeToVlc(-1f))
        assertEquals(100, VlcPlaybackMapping.volumeToVlc(2f))
        assertEquals(1f, VlcPlaybackMapping.volumeFromVlc(250), 1e-6f)
    }

    @Test
    fun `音量算不出来时给满音量而不是静音`() {
        // NaN 只能来自上游算错。把它当静音，症状是「突然没声音且查不出原因」；
        // 当满音量则与端口默认值 DEFAULT_VOLUME 一致 —— 后者才是「没有意见」的语义。
        assertEquals(100, VlcPlaybackMapping.volumeToVlc(Float.NaN))
    }

    @Test
    fun `还没建音频输出时 getVolume 返回负一 不能读成静音`() {
        // libVLC 的 getVolume() 在 aout 建起来之前是 -1。读成 0 会让界面显示「音量 0」，
        // 用户以为被自己滑到了底。这里要求退回默认值。
        assertEquals(PlaybackTarget.DEFAULT_VOLUME, VlcPlaybackMapping.volumeFromVlc(-1), 1e-6f)
    }

    // ── 宽高比 ──────────────────────────────────────────────────────────────

    @Test
    fun `方形像素的 1080p 就是 16比9`() {
        val ratio = VlcPlaybackMapping.aspectRatio(1920, 1080, 1, 1)
        assertEquals(16f / 9f, ratio!!, 1e-4f)
    }

    @Test
    fun `非方形像素要乘 sar 否则 720x480 会被算成 3比2`() {
        // 720×480 存 16:9 画面（sar = 32/27）。只算 width/height 会得到 1.5，
        // 全屏时画面会明显变形 —— 这正是这一条要防的。
        val ratio = VlcPlaybackMapping.aspectRatio(720, 480, 32, 27)
        assertEquals(16f / 9f, ratio!!, 1e-4f)
        assertTrue("必须比 3:2 宽", ratio > 1.5f)
    }

    @Test
    fun `sar 未知时退回解码尺寸之比`() {
        val ratio = VlcPlaybackMapping.aspectRatio(1920, 1080, 0, 0)
        assertEquals(16f / 9f, ratio!!, 1e-4f)
        // libVLC 用 0 表示未知；分子有、分母为 0 同样是坏数据，不能拿去做除数。
        val half = VlcPlaybackMapping.aspectRatio(720, 480, 32, 0)
        assertEquals(1.5f, half!!, 1e-4f)
    }

    @Test
    fun `尺寸为 0 时候给 null 让界面用 16比9 兜底`() {
        assertNull(VlcPlaybackMapping.aspectRatio(0, 1080, 1, 1))
        assertNull(VlcPlaybackMapping.aspectRatio(1920, 0, 1, 1))
        assertNull(VlcPlaybackMapping.aspectRatio(-1, -1, 1, 1))
    }

    // ── 字幕轨 ──────────────────────────────────────────────────────────────

    @Test
    fun `语言码按 id 配对 配不上给空串`() {
        val languages = VlcPlaybackMapping.subtitleLanguages(
            spuIds = listOf(0, 1, 2),
            languageById = mapOf(0 to "chi", 1 to null, 2 to " eng "),
        )
        assertEquals(listOf("chi", "", "eng"), languages)
    }

    @Test
    fun `乱序与多余的键都不影响配对结果`() {
        // 两个列表来自 libVLC 两个不同 API，不承诺同序；key 也可能比 spuIds 多
        // （视频/音频轨也在 media 的轨道表里）。
        val languages = VlcPlaybackMapping.subtitleLanguages(
            spuIds = listOf(2, 0),
            languageById = mapOf(0 to "chi", 1 to "jpn", 2 to "eng"),
        )
        assertEquals(listOf("eng", "chi"), languages)
    }

    @Test
    fun `没有字幕轨时是空表 界面据此整行不画`() {
        assertTrue(VlcPlaybackMapping.subtitleLanguages(emptyList(), emptyMap()).isEmpty())
        assertTrue(VlcPlaybackMapping.subtitleLanguages(listOf(3), emptyMap()) == listOf(""))
    }

    @Test
    fun `字幕序号越界时安静忽略`() {
        val ids = listOf(1, 3)
        assertEquals(3, VlcPlaybackMapping.spuIdAt(ids, 1))
        assertNull(VlcPlaybackMapping.spuIdAt(ids, 2))
        assertNull(VlcPlaybackMapping.spuIdAt(ids, -1))
        assertNull(VlcPlaybackMapping.spuIdAt(emptyList(), 0))
    }
}
