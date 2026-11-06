package com.cinemahall.core.media

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 把 PGS display set 按组合对象拆分 —— **纯字节操作，可在 JVM 上逐字节测**。
 *
 * Phase 1 从 `PgsMultiObjectSubtitleParser`（Media3 实现）抽到 `core:media`：
 * 真正容易写错的是「重写组合段 + 挑出对应位图段」这一步，而最终的 `Cue` 要用
 * `android.graphics.Bitmap`（JVM 单测里造不出来）。所以把可测的那部分放这里，
 * Android 的解析器只做编排。
 */
object PgsDisplaySet {

    const val SECTION_PALETTE = 0x14
    const val SECTION_OBJECT = 0x15
    const val SECTION_PRESENTATION = 0x16
    const val SECTION_WINDOW = 0x17
    const val SECTION_END = 0x80

    /** 组合段里「头」的长度：画面宽高(4) + 帧率(1) + 组合号(2) + 状态(1) + 调色板(2) + 对象数(1)。 */
    private const val PRESENTATION_HEADER = 11

    /** 每个组合对象的长度：objectId(2) + windowId(1) + flags(1) + x(2) + y(2)。 */
    private const val COMPOSITION_OBJECT = 8

    data class Section(val type: Int, val body: ByteArray)

    data class CompositionObject(val objectId: Int, val x: Int, val y: Int)

    /**
     * 把 zlib 压缩的样本体解开；不是 zlib（首字节不是 0x78）或解不开时返回 null。
     *
     * Media3 自己也是这么判的（`Util.maybeInflate`），所以这里用同一个判据就不会
     * 把未压缩的 display set 误当成压缩流。
     */
    fun inflateIfNeeded(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length < 2) return null
        if ((data[offset].toInt() and 0xFF) != 0x78) return null
        return try {
            val inflater = Inflater()
            inflater.setInput(data, offset, length)
            val out = ByteArrayOutputStream(length * 4)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                out.write(buffer, 0, read)
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** 按 `[type(1)][size(2)][body(size)]` 切成段；长度对不上时停在最后一个完整段。 */
    fun split(data: ByteArray): List<Section> {
        val out = ArrayList<Section>()
        var pos = 0
        while (pos + 3 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            val size = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
            if (pos + 3 + size > data.size) break
            out += Section(type, data.copyOfRange(pos + 3, pos + 3 + size))
            pos += 3 + size
        }
        return out
    }

    /**
     * 这个样本里有几个 display set（按组合段 0x16 的个数算）。
     *
     * MKV 里实测是「一块一个」（1172 个 cluster / 5330 个字幕块，每块段序都是
     * `0x16 0x17 0x14 0x15 … 0x80`），但**格式本身不承诺**。多于一个时按对象拆分
     * 会把第二个 display set 的段拼进第一个，所以调用方要退回原实现。
     */
    fun displaySetCount(sections: List<Section>): Int =
        sections.count { it.type == SECTION_PRESENTATION }

    /** 组合段里的对象表。`body` 不够长时返回已读到的那些（宁少不炸）。 */
    fun compositionObjects(body: ByteArray): List<CompositionObject> {
        if (body.size < PRESENTATION_HEADER) return emptyList()
        val count = body[10].toInt() and 0xFF
        val out = ArrayList<CompositionObject>(count)
        var off = PRESENTATION_HEADER
        repeat(count) {
            if (off + COMPOSITION_OBJECT > body.size) return out
            out += CompositionObject(
                objectId = u16(body, off),
                x = u16(body, off + 4),
                y = u16(body, off + 6),
            )
            off += COMPOSITION_OBJECT
        }
        return out
    }

    /**
     * 造一个「只含 [target] 这一层」的 display set。
     *
     * 返回 null 的两种情形都必须跳过而不是硬造：找不到组合段、以及**这一层没有位图段**
     * （例如只做调色板更新的那类 display set）。硬造出来的空 display set 会被 Media3
     * 判成 null cue，等于白跑一遍。
     */
    fun buildSingleObjectSet(sections: List<Section>, target: CompositionObject): ByteArray? {
        val presentation = sections.firstOrNull { it.type == SECTION_PRESENTATION } ?: return null
        val index = indexOfObject(presentation.body, target.objectId) ?: return null
        if (presentation.body.size < PRESENTATION_HEADER + (index + 1) * COMPOSITION_OBJECT) return null

        val bitmaps = sections.filter {
            it.type == SECTION_OBJECT && objectIdOf(it.body) == target.objectId
        }
        if (bitmaps.isEmpty()) return null

        val out = ByteArrayOutputStream(presentation.body.size + 1024)
        val single = ByteArray(PRESENTATION_HEADER + COMPOSITION_OBJECT)
        System.arraycopy(presentation.body, 0, single, 0, PRESENTATION_HEADER)
        single[PRESENTATION_HEADER - 1] = 1 // 对象数 = 1
        System.arraycopy(
            presentation.body,
            PRESENTATION_HEADER + index * COMPOSITION_OBJECT,
            single,
            PRESENTATION_HEADER,
            COMPOSITION_OBJECT,
        )
        writeSection(out, SECTION_PRESENTATION, single)

        // 调色板与窗口定义是同一个 display set 里共享的，原样带上。
        sections.forEach { section ->
            if (section.type == SECTION_PALETTE || section.type == SECTION_WINDOW) {
                writeSection(out, section.type, section.body)
            }
        }
        bitmaps.forEach { writeSection(out, SECTION_OBJECT, it.body) }
        writeSection(out, SECTION_END, ByteArray(0))
        return out.toByteArray()
    }

    private fun indexOfObject(body: ByteArray, objectId: Int): Int? {
        if (body.size < PRESENTATION_HEADER) return null
        val count = body[10].toInt() and 0xFF
        for (i in 0 until count) {
            val at = PRESENTATION_HEADER + i * COMPOSITION_OBJECT
            if (at + COMPOSITION_OBJECT > body.size) return null
            if (u16(body, at) == objectId) return i
        }
        return null
    }

    private fun objectIdOf(body: ByteArray): Int = if (body.size < 2) -1 else u16(body, 0)

    private fun u16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun writeSection(out: ByteArrayOutputStream, type: Int, body: ByteArray) {
        out.write(type and 0xFF)
        out.write((body.size ushr 8) and 0xFF)
        out.write(body.size and 0xFF)
        out.write(body)
    }
}
