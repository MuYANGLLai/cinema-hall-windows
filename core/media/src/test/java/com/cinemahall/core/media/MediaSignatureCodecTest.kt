package com.cinemahall.core.media

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `{fileName, size, blocks[]}` 的键名是**跨端约定**（§7 / §5.3），
 * 所以第一条用例直接断言序列化出来的键名，而不是只做一次 round-trip ——
 * round-trip 在「两端都写错成同一个别名」时依然会通过。
 */
class MediaSignatureCodecTest {

    @Test
    fun `编码出来的键名就是 fileName size blocks`() {
        val json = MediaSignatureCodec.encode(
            MediaSignature(
                fileName = "movie.mkv",
                sizeBytes = 4L * 1024 * 1024,
                blocks = listOf("aa", "bb", "cc"),
            ),
        )

        assertEquals(setOf("fileName", "size", "blocks"), json.keys)
        assertEquals("movie.mkv", json.getValue("fileName").jsonPrimitive.content)
        assertEquals("4194304", json.getValue("size").jsonPrimitive.content)
        assertEquals(3, json.getValue("blocks").jsonArray.size)
    }

    @Test
    fun `超出 Int 的范围也能往返`() {
        // 20 GiB 的影片：sizeBytes 若被当成 Int 处理，这里就会溢出成负数。
        val original = MediaSignature(
            fileName = "huge.mkv",
            sizeBytes = 20L * 1024 * 1024 * 1024,
            blocks = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64)),
        )

        assertEquals(original, MediaSignatureCodec.decode(MediaSignatureCodec.encode(original)))
    }

    @Test
    fun `整文件模式一块也能往返`() {
        val original = MediaSignature("clip.mkv", 1024, listOf("d".repeat(64)))

        assertEquals(original, MediaSignatureCodec.decode(MediaSignatureCodec.encode(original)))
    }

    // ── 坏输入不得打断链路（§4.2 的同一条原则）──────────────────────────────

    @Test
    fun `空载荷解成 null 而不是抛异常`() {
        assertNull(MediaSignatureCodec.decode(null))
    }

    @Test
    fun `缺少字段解成 null`() {
        val broken = buildJsonObject {
            put("fileName", "movie.mkv")
            put("size", 1048576)
            // 没有 blocks
        }

        assertNull(MediaSignatureCodec.decode(broken))
    }

    @Test
    fun `字段类型不对解成 null`() {
        val broken = buildJsonObject {
            put("fileName", "movie.mkv")
            put("size", "四兆") // 该是数字
            putJsonArray("blocks") { add(JsonPrimitive("aa")) }
        }

        assertNull(MediaSignatureCodec.decode(broken))
    }

    @Test
    fun `多出来的未知字段被忽略`() {
        // 对端比本机新时会带上新字段。校验的失败原因有且只有 §7 那三种，
        // 不该因为多了一个字段就冒出第四种「解析失败」。
        val extended = buildJsonObject {
            put("fileName", "movie.mkv")
            put("size", 1024)
            putJsonArray("blocks") { add(JsonPrimitive("aa")) }
            put("codecName", "h264") // 未来的字段
        }

        val decoded = MediaSignatureCodec.decode(extended)

        assertEquals("movie.mkv", decoded?.fileName)
        assertEquals(1024L, decoded?.sizeBytes)
    }

    @Test
    fun `blocks 是空数组时能解出但比对会报结构不一致`() {
        val decoded = MediaSignatureCodec.decode(
            buildJsonObject {
                put("fileName", "movie.mkv")
                put("size", 1024)
                putJsonArray("blocks") { }
            },
        )

        assertTrue(decoded != null)
        assertEquals(0, decoded!!.blocks.size)

        val result = MediaSignatureComparator.compare(
            decoded,
            MediaSignature("movie.mkv", 1024, listOf("aa")),
        )
        assertTrue(
            "实得 $result",
            (result as MediaCheck.Mismatch).reason is MismatchReason.BlockCount,
        )
    }
}