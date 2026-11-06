package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描判据（§16.15）。**全是纯函数**，所以四条边界能逐毫秒钉住 ——
 * 而它们骗过真机的方式是「归错分类但不报任何错」。
 */
class MovieScanRulesTest {

    private fun scanned(
        fileName: String = "movie.mkv",
        sizeBytes: Long = 4L * 1024 * 1024,
        durationMs: Long = 90L * 60 * 1000,
        uri: String = "content://media/external/video/media/1",
    ): ScannedMovie = ScannedMovie(
        uri = uri,
        fileName = fileName,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
    )

    private fun entry(
        fileName: String = "movie.mkv",
        sizeBytes: Long = 4L * 1024 * 1024,
        categoryId: String = "c1",
    ): MovieEntry = MovieEntry(
        id = "e-$fileName-$sizeBytes",
        categoryId = categoryId,
        uri = "content://demo/$fileName",
        signature = MediaSignature(fileName = fileName, sizeBytes = sizeBytes, blocks = listOf("aa")),
    )

    // ── 时长阈值：四条边界 ──────────────────────────────────────────────────────

    @Test
    fun `80 分钟整是电影`() {
        assertEquals(ScanBucket.MOVIE, MovieScanRules.classify(4_800_000L))
    }

    @Test
    fun `差 1 毫秒不到 80 分钟是电视剧`() {
        assertEquals(ScanBucket.SERIES, MovieScanRules.classify(4_799_999L))
    }

    @Test
    fun `刚过 30 分钟是电视剧`() {
        assertEquals(ScanBucket.SERIES, MovieScanRules.classify(1_800_001L))
    }

    @Test
    fun `30 分钟整不收录`() {
        assertEquals(ScanBucket.SKIP, MovieScanRules.classify(1_800_000L))
    }

    @Test
    fun `时长未知与负数都不收录`() {
        // MediaStore 里元数据没解出来的片子报 0；0 不是「很短」，是「不知道」。
        assertEquals(ScanBucket.SKIP, MovieScanRules.classify(0L))
        assertEquals(ScanBucket.SKIP, MovieScanRules.classify(-1L))
    }

    @Test
    fun `归档映射到两个分类 kind`() {
        assertEquals(MovieCategoryKind.MOVIE, MovieScanRules.kindOf(ScanBucket.MOVIE))
        assertEquals(MovieCategoryKind.SERIES, MovieScanRules.kindOf(ScanBucket.SERIES))
        assertEquals(null, MovieScanRules.kindOf(ScanBucket.SKIP))
    }

    // ── 判重：文件名 + 大小，全库范围 ────────────────────────────────────────────

    @Test
    fun `库里已有的片不再收录`() {
        val kept = MovieScanRules.selectNew(
            scanned = listOf(scanned()),
            existing = listOf(entry()),
        )

        assertTrue(kept.isEmpty())
    }

    @Test
    fun `已存在的条目在别的分类里也算重复`() {
        // 扫描是「补全」，不是「往某个分类里塞」：一部片已经在「纪录片」里了，
        // 不该再被抄一份进「电影」（§16.15 裁决④）。
        val kept = MovieScanRules.selectNew(
            scanned = listOf(scanned()),
            existing = listOf(entry(categoryId = "纪录片")),
        )

        assertTrue(kept.isEmpty())
    }

    @Test
    fun `同名但大小不同算两部不同的片`() {
        val kept = MovieScanRules.selectNew(
            scanned = listOf(scanned(sizeBytes = 5L * 1024 * 1024)),
            existing = listOf(entry(sizeBytes = 4L * 1024 * 1024)),
        )

        assertEquals(1, kept.size)
    }

    @Test
    fun `同一批里的同名同大小只留一条`() {
        // 同一部片在 SD 卡与内置存储各一份：两条 uri 不同，`addMovie` 的
        // 「同一分类内同一个 uri 只留一条」拦不住，必须在扫描这一层去掉。
        val kept = MovieScanRules.selectNew(
            scanned = listOf(
                scanned(uri = "content://media/external/video/media/1"),
                scanned(uri = "content://media/external/video/media/2"),
            ),
            existing = emptyList(),
        )

        assertEquals(1, kept.size)
    }

    @Test
    fun `文件名按 NFC 归一化后判重`() {
        // 「é」可以是一颗预组合字符，也可以是 e + 组合重音。它们是同一个文件名，
        // 与 §16.6 的进度键同一个口径（都走 `normalizeFileName`）。
        val kept = MovieScanRules.selectNew(
            scanned = listOf(scanned(fileName = "cafe\u0301.mkv")),
            existing = listOf(entry(fileName = "caf\u00e9.mkv")),
        )

        assertTrue(kept.isEmpty())
    }

    @Test
    fun `文件名大小写不同就是两部片`() {
        // 与 §7 / §16.6 同一条：大小写**敏感**。这里若折叠了，扫描会把两个真实文件
        // 当成一个，第二个永远进不了库。
        val kept = MovieScanRules.selectNew(
            scanned = listOf(scanned(fileName = "Movie.mkv")),
            existing = listOf(entry(fileName = "movie.mkv")),
        )

        assertEquals(1, kept.size)
    }

    @Test
    fun `空候选返回空`() {
        assertTrue(MovieScanRules.selectNew(emptyList(), listOf(entry())).isEmpty())
    }
}
