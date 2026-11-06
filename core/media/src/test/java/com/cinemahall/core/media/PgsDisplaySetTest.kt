package com.cinemahall.core.media

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PGS 多对象拆分的逐字节判据（2026-10-25「多层字幕只画一层」的修复）。
 *
 * ── 为什么这一层必须单测 ────────────────────────────────────────────────────
 * 真正会被写错的是「重写组合段、只带那一层的位图段」这段纯字节操作；而最终 `Cue`
 * 要用 `android.graphics.Bitmap`（JVM 上造不出来），所以整条解析链在单测里跑不到底。
 * 把可测的那部分抽成 [PgsDisplaySet] 就是为了这个：**它能逐字节验**，剩下的编排只是
 * 循环调用。
 *
 * ── 判据来自真实片源 ────────────────────────────────────────────────────────
 * `怦然心动 1080p 特效中字` 的 PGS 轨里，1354 个 display set 带 **2 个组合对象**，
 * 例如 t=12.888s：对象 0 在 (832,983)、对象 1 在 (729,460)。下面的合成样本照着
 * 这个形状造（1920×1080 画面、两个对象、两段位图），因为二进制片段本身不适合入库。
 */
class PgsDisplaySetTest {

    // ── 造样本 ──────────────────────────────────────────────────────────────

    private fun u16(value: Int): ByteArray =
        byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun section(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(
            type.toByte(),
            ((body.size ushr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        ) + body

    /** 组合段（0x16）：11 字节头 + 每个对象 8 字节。 */
    private fun presentation(
        objects: List<Triple<Int, Int, Int>>,
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u16(videoWidth))
        out.write(u16(videoHeight))
        out.write(23) // frameRate
        out.write(u16(1)) // compositionNumber
        out.write(0x80) // compositionState
        out.write(0) // paletteUpdateFlag
        out.write(0) // paletteId
        out.write(objects.size) // 对象数
        objects.forEach { (id, x, y) ->
            out.write(u16(id))
            out.write(0) // windowId
            out.write(0) // flags
            out.write(u16(x))
            out.write(u16(y))
        }
        return out.toByteArray()
    }

    /** 位图段（0x15）：objectId(2) + version(1) + 序号标志(1) + 长度(3) + 宽高(2+2) + 数据。 */
    private fun objectBitmap(objectId: Int, width: Int, height: Int): ByteArray {
        val data = byteArrayOf(0, 0) // 两个字节的占位 RLE 数据，本层不验解码
        val out = ByteArrayOutputStream()
        out.write(u16(objectId))
        out.write(0) // version
        out.write(0xC0) // last-in-sequence
        out.write((data.size ushr 16) and 0xFF)
        out.write((data.size ushr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(u16(width))
        out.write(u16(height))
        out.write(data)
        return out.toByteArray()
    }

    /** 两个对象、各带一张位图 —— 与真实片源里那 1354 个 display set 同形。 */
    private fun twoObjectDisplaySet(): ByteArray =
        section(
            PgsDisplaySet.SECTION_PRESENTATION,
            presentation(listOf(Triple(0, 832, 983), Triple(1, 729, 460))),
        ) +
            section(PgsDisplaySet.SECTION_WINDOW, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) +
            section(PgsDisplaySet.SECTION_PALETTE, byteArrayOf(0, 0, 0, 0, 0, 0)) +
            section(PgsDisplaySet.SECTION_OBJECT, objectBitmap(0, 10, 4)) +
            section(PgsDisplaySet.SECTION_OBJECT, objectBitmap(1, 8, 3)) +
            section(PgsDisplaySet.SECTION_END, ByteArray(0))

    // ── 切段 ────────────────────────────────────────────────────────────────

    @Test
    fun `切段_按类型与长度读出全部段()`() {
        val sections = PgsDisplaySet.split(twoObjectDisplaySet())
        assertEquals(
            "段的顺序与个数必须原样保留（Media3 是按顺序累积的）",
            listOf(0x16, 0x17, 0x14, 0x15, 0x15, 0x80),
            sections.map { it.type },
        )
    }

    @Test
    fun `切段_遇到不完整的段就停下而不是硬猜()`() {
        val full = section(PgsDisplaySet.SECTION_END, ByteArray(0))
        val truncated = section(PgsDisplaySet.SECTION_OBJECT, ByteArray(20)).copyOf(10)
        val sections = PgsDisplaySet.split(truncated + full)
        assertEquals(
            "样本已经被截断，后面那些字节的边界不再可信 —— 停在这里，不要尝试重同步" +
                "（重同步会把随机字节当成段头，比丢掉一条字幕糟糕得多）",
            0,
            sections.size,
        )
    }

    // ── 组合对象 ────────────────────────────────────────────────────────────

    @Test
    fun `组合段_读出两个对象的编号与坐标()`() {
        val objects = PgsDisplaySet.compositionObjects(presentation(
            listOf(Triple(0, 832, 983), Triple(1, 729, 460)),
        ))
        assertEquals(2, objects.size)
        assertEquals(PgsDisplaySet.CompositionObject(0, 832, 983), objects[0])
        assertEquals(PgsDisplaySet.CompositionObject(1, 729, 460), objects[1])
    }

    @Test
    fun `组合段_只有一个对象时_解析器走原实现()`() {
        val objects = PgsDisplaySet.compositionObjects(presentation(listOf(Triple(0, 100, 200))))
        assertEquals("单对象不走我们的拆分路径", 1, objects.size)
    }

    @Test
    fun `样本里只有一个_display_set_时才允许拆分()`() {
        val single = PgsDisplaySet.split(twoObjectDisplaySet())
        assertEquals(1, PgsDisplaySet.displaySetCount(single))

        val twoInOne = single +
            PgsDisplaySet.Section(
                PgsDisplaySet.SECTION_PRESENTATION,
                presentation(listOf(Triple(2, 10, 20))),
            ) +
            PgsDisplaySet.Section(PgsDisplaySet.SECTION_END, ByteArray(0))
        assertEquals(
            "多 display set 的样本要退回原实现 —— 按对象拼会把第二个的段混进第一个",
            2,
            PgsDisplaySet.displaySetCount(twoInOne),
        )
    }

    // ── 拆成单对象 ──────────────────────────────────────────────────────────

    @Test
    fun `拆分后_组合段只剩一个对象且保留原坐标()`() {
        val sections = PgsDisplaySet.split(twoObjectDisplaySet())
        val objects = PgsDisplaySet.compositionObjects(
            sections.first { it.type == PgsDisplaySet.SECTION_PRESENTATION }.body,
        )

        val single = PgsDisplaySet.buildSingleObjectSet(sections, objects[1])
        assertNotNull("第二层必须能单独拼出来", single)

        val singleSections = PgsDisplaySet.split(single!!)
        val pcs = singleSections.first { it.type == PgsDisplaySet.SECTION_PRESENTATION }.body
        assertEquals("对象数必须改成 1", 1, pcs[10].toInt() and 0xFF)
        assertEquals(
            "对象数的位置在头的最后一个字节，头本身不能变形（Media3 要求 size >= 19）",
            19,
            pcs.size,
        )
        assertEquals(
            "坐标必须原样保留 —— 歌词那一层的 y 与对白不同，丢了就是位置不对",
            listOf(PgsDisplaySet.CompositionObject(1, 729, 460)),
            PgsDisplaySet.compositionObjects(pcs),
        )
    }

    @Test
    fun `拆分后_只带该对象的位图段()`() {
        val sections = PgsDisplaySet.split(twoObjectDisplaySet())
        val objects = PgsDisplaySet.compositionObjects(
            sections.first { it.type == PgsDisplaySet.SECTION_PRESENTATION }.body,
        )

        val single = PgsDisplaySet.split(PgsDisplaySet.buildSingleObjectSet(sections, objects[0])!!)
        val bitmaps = single.filter { it.type == PgsDisplaySet.SECTION_OBJECT }
        assertEquals("一个子集只该有一张位图", 1, bitmaps.size)
        assertEquals(
            "留下的必须是对应那一层（objectId = 0）",
            0,
            ((bitmaps[0].body[0].toInt() and 0xFF) shl 8) or (bitmaps[0].body[1].toInt() and 0xFF),
        )
    }

    @Test
    fun `拆分后_保留调色板与窗口定义与结束段()`() {
        val sections = PgsDisplaySet.split(twoObjectDisplaySet())
        val objects = PgsDisplaySet.compositionObjects(
            sections.first { it.type == PgsDisplaySet.SECTION_PRESENTATION }.body,
        )

        val single = PgsDisplaySet.split(PgsDisplaySet.buildSingleObjectSet(sections, objects[1])!!)
        assertEquals(
            "调色板 / 窗口定义 / 结束段一个都不能丢：调色板丢了画面全黑，结束段丢了 Media3 不会 build",
            listOf(0x16, 0x17, 0x14, 0x15, 0x80),
            single.map { it.type },
        )
    }

    @Test
    fun `该对象没有位图段时_返回空()`() {
        val sections = PgsDisplaySet.split(twoObjectDisplaySet())
        val missing = PgsDisplaySet.CompositionObject(objectId = 2, x = 10, y = 20)
        assertNull("没有位图的层不该硬造一个空 display set", PgsDisplaySet.buildSingleObjectSet(sections, missing))
    }

    // ── zlib ────────────────────────────────────────────────────────────────

    @Test
    fun `不是_zlib_流时不解压()`() {
        assertNull(
            "MKV 里未压缩的 PGS 段首字节不可能是 0x78，不能误判成压缩流",
            PgsDisplaySet.inflateIfNeeded(twoObjectDisplaySet(), 0, 64),
        )
    }

    @Test
    fun `zlib_流能解回原文()`() {
        val raw = "PGS 显示集".toByteArray()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        val compressed = out.toByteArray()
        assertEquals("实测片源的样本体就是这个形状：首字节 0x78", 0x78, compressed[0].toInt() and 0xFF)
        assertArrayEquals(raw, PgsDisplaySet.inflateIfNeeded(compressed, 0, compressed.size))
    }
}
