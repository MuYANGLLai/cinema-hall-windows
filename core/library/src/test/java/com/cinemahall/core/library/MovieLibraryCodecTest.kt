package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘 JSON 的往返与容错（§16.4 / §16.12）。
 *
 * 最要紧的一条是「自定义显示名不许渗进 `signature.fileName`」—— §16.2 那条
 * 若被写错，症状是「用户改了显示名 → 开播被文件名不一致拦住」，所以这里不只做往返，
 * 还直接对编码出来的原文断言两个字段各在各自的位置。
 */
class MovieLibraryCodecTest {

    private fun signature(
        fileName: String = "movie.mkv",
        sizeBytes: Long = 4L * 1024 * 1024,
        blocks: List<String> = listOf("aa", "bb", "cc"),
    ): MediaSignature = MediaSignature(
        fileName = fileName,
        sizeBytes = sizeBytes,
        blocks = blocks,
    )

    @Test
    fun `库往返保持相等`() {
        val original = MovieLibrarySnapshot(
            categories = listOf(
                MovieCategory(id = "c1", name = "纪录片", createdAtMs = 1_000L),
                MovieCategory(id = "c2", name = "给客人看", createdAtMs = 2_000L),
            ),
            movies = listOf(
                MovieEntry(
                    id = "m1",
                    categoryId = "c1",
                    uri = "content://com.android.providers.media.documents/document/video%3A42",
                    signature = signature(),
                    titleOverride = "我的片名",
                    addedAtMs = 3_000L,
                    lastCheckedAtMs = 4_000L,
                    missing = true,
                ),
                MovieEntry(
                    id = "m2",
                    categoryId = "c2",
                    uri = "content://demo/2",
                    signature = signature(fileName = "another.mkv", blocks = listOf("cc")),
                ),
            ),
        )

        assertEquals(original, MovieLibraryCodec.decodeLibrary(MovieLibraryCodec.encodeLibrary(original)))
    }

    @Test
    fun `自定义显示名不会渗进摘要里的真实文件名`() {
        val real = "原始片名.mkv"
        val custom = "我的片名"
        val snapshot = MovieLibrarySnapshot(
            categories = listOf(MovieCategory(id = "c1", name = "纪录片", createdAtMs = 1_000L)),
            movies = listOf(
                MovieEntry(
                    id = "m1",
                    categoryId = "c1",
                    uri = "content://demo/1",
                    signature = signature(fileName = real),
                    titleOverride = custom,
                    addedAtMs = 2_000L,
                ),
            ),
        )

        val encoded = MovieLibraryCodec.encodeLibrary(snapshot)

        // §16.2 的机械守卫：两者在落盘的原文里就是两个不同的键
        assertTrue("原文：$encoded", encoded.contains("\"fileName\":\"$real\""))
        assertTrue("原文：$encoded", encoded.contains("\"titleOverride\":\"$custom\""))

        val movie = MovieLibraryCodec.decodeLibrary(encoded)!!.movies.single()
        assertEquals(real, movie.signature.fileName)
        assertEquals(custom, movie.displayName)
    }

    @Test
    fun `进度往返保持相等`() {
        val original = MovieProgressBook(
            byKey = mapOf(
                "0123456789abcdef0123456789abcdef" to MovieProgress(
                    positionMs = 120_000L,
                    durationMs = 600_000L,
                    updatedAtMs = 7_000L,
                ),
                "fedcba9876543210fedcba9876543210" to MovieProgress(
                    positionMs = 0L,
                    durationMs = 0L,
                    updatedAtMs = 8_000L,
                ),
            ),
        )

        assertEquals(original, MovieLibraryCodec.decodeProgress(MovieLibraryCodec.encodeProgress(original)))
    }

    @Test
    fun `解不开的输入一律返回 null`() {
        // 「没有数据」与「有数据但解不开」在调用方那里处置不同（§16.4），
        // 所以这里只保证「解不开 ⇒ null」，不在这里吞成空对象。
        val broken: List<String?> = listOf(null, "", "   ", "\n", "{", "null", "[1,2]", "{\"movies\":}")

        broken.forEach { raw ->
            assertNull("raw=$raw 时库应当是 null", MovieLibraryCodec.decodeLibrary(raw))
            assertNull("raw=$raw 时进度应当是 null", MovieLibraryCodec.decodeProgress(raw))
        }
    }

    @Test
    fun `多出来的未知键被忽略`() {
        // 更高版本的本机多写了字段时，旧版本必须还能读出库来（§16.4）
        val raw = """
            {
              "categories": [],
              "movies": [],
              "watchedTogether": true,
              "schemaVersion": 99
            }
        """.trimIndent()

        assertEquals(MovieLibrarySnapshot(), MovieLibraryCodec.decodeLibrary(raw))
    }

    @Test
    fun `缺少可选字段时取默认值`() {
        // 只有 4 个必需键：titleOverride 缺 ⇒ 按文件名显示，两个时间戳缺 ⇒ 0，missing 缺 ⇒ false
        val raw = """
            {
              "movies": [
                {
                  "id": "m1",
                  "categoryId": "c1",
                  "uri": "content://demo/1",
                  "signature": {"fileName": "a.mkv", "size": 1024, "blocks": ["aa"]}
                }
              ]
            }
        """.trimIndent()

        val decoded = MovieLibraryCodec.decodeLibrary(raw)!!

        assertEquals(emptyList<MovieCategory>(), decoded.categories)
        val movie = decoded.movies.single()
        assertNull(movie.titleOverride)
        assertEquals(0L, movie.addedAtMs)
        assertEquals(0L, movie.lastCheckedAtMs)
        assertFalse(movie.missing)
        assertEquals("a.mkv", movie.displayName)

        // 连两个集合键都没有时，取默认的空集合
        assertEquals(MovieLibrarySnapshot(), MovieLibraryCodec.decodeLibrary("{}"))
    }
}
