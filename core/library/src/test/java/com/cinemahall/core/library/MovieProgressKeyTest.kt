package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度键的口径（§16.6 / §16.12）。
 *
 * 这个文件里的用例守的几乎都是**跨端一致**：键在两台设备上必须算出同一个值，
 * 否则症状就是「同一部片两台各记一份进度、永远对不上」，而用户看不到任何报错。
 */
class MovieProgressKeyTest {

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
    fun `同一份摘要算两次得到同一个键且是 32 位小写十六进制`() {
        val first = MovieProgressKey.of(signature())
        val second = MovieProgressKey.of(signature())

        assertEquals(first, second)
        assertEquals(32, first.length)
        assertTrue("实得 $first", first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `摘要重载与手工传参得到同一个键`() {
        // of(signature) 必须只是把三个字段拆出来，不能自己再拼一遍别的格式
        assertEquals(
            MovieProgressKey.of(signature()),
            MovieProgressKey.of("movie.mkv", 4L * 1024 * 1024, listOf("aa", "bb", "cc")),
        )
    }

    @Test
    fun `文件名的 NFC 与 NFD 写法得到同一个键`() {
        // 同一个 "café.mkv"：一个是预组合的 é（U+00E9，NFC），一个是 e + 组合重音（NFD）。
        // 两者显示完全一样、字节不同 —— 直接拿字节算哈希就会得到两个键。
        val composed = MovieProgressKey.of("caf\u00e9.mkv", 1024L, listOf("aa"))
        val decomposed = MovieProgressKey.of("cafe\u0301.mkv", 1024L, listOf("aa"))

        assertEquals(composed, decomposed)
    }

    @Test
    fun `大小写不同就是两个键`() {
        // 刻意与 §7 的文件名比对同口径（大小写敏感）：若这里小写化，进度键会比开播判据
        // 更宽松，于是出现「进度对得上、开播却被拦」的自相矛盾状态。
        assertNotEquals(
            MovieProgressKey.of("A.mkv", 1024L, listOf("aa")),
            MovieProgressKey.of("a.mkv", 1024L, listOf("aa")),
        )
    }

    @Test
    fun `大小与块哈希与块数任一不同都是不同的键`() {
        val base = signature()

        // 大小不同
        assertNotEquals(
            MovieProgressKey.of(base),
            MovieProgressKey.of(signature(sizeBytes = 5L * 1024 * 1024)),
        )

        // 块哈希不同（内容变了，文件大小可以一样）
        assertNotEquals(
            MovieProgressKey.of(base),
            MovieProgressKey.of(signature(blocks = listOf("ab", "bb", "cc"))),
        )

        // 块数不同（旧版本客户端在小于 3 MiB 时只算一块）
        assertNotEquals(
            MovieProgressKey.of(signature(blocks = listOf("aa"))),
            MovieProgressKey.of(signature(blocks = listOf("aa", "bb"))),
        )
    }

    @Test
    fun `拼接处不会撞成一个键`() {
        // 无分隔符时这几对会拼成同一个串，键也就会相同 —— 那意味着两部不同的片共用一条进度。
        assertNotEquals(
            MovieProgressKey.of("ab", 1L, emptyList()),
            MovieProgressKey.of("a", 1L, emptyList()),
        )

        // "movie1" + 23 与 "movie12" + 3：直接拼都是 "movie123"
        assertNotEquals(
            MovieProgressKey.of("movie1", 23L, emptyList()),
            MovieProgressKey.of("movie12", 3L, emptyList()),
        )

        // "a" + 1 + ["1"] 与 "a1" + 1 + 无块：直接拼都是 "a11"
        assertNotEquals(
            MovieProgressKey.of("a", 1L, listOf("1")),
            MovieProgressKey.of("a1", 1L, emptyList()),
        )
    }
}
