package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已看完」的判定（§16.17）。三态 + 一条阈值，全部纯哈希计算的输入，没有任何 IO。
 */
class MovieWatchStatusTest {

    private fun entry(
        id: String = "e1",
        fileName: String = "movie.mkv",
        finishedOverride: Boolean? = null,
    ): MovieEntry = MovieEntry(
        id = id,
        categoryId = "c1",
        uri = "content://demo/$id",
        signature = MediaSignature(fileName = fileName, sizeBytes = 100L, blocks = listOf("aa")),
        finishedOverride = finishedOverride,
    )

    /** 给某一条造一份进度。[fraction] 由位置 / 时长算出来（不直接塞比例）。 */
    private fun progressFor(
        entry: MovieEntry,
        positionMs: Long,
        durationMs: Long = 1_000_000L,
    ): MovieProgressBook = MovieProgressBook(
        byKey = mapOf(
            MovieProgressKey.of(entry.signature) to MovieProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAtMs = 1L,
            ),
        ),
    )

    @Test
    fun `没有进度就没看完`() {
        assertFalse(MovieWatchStatus.isFinished(entry(), MovieProgressBook()))
    }

    @Test
    fun `84_9 percent 还没看完`() {
        val movie = entry()
        val book = progressFor(movie, positionMs = 849_000L)

        assertFalse(MovieWatchStatus.isFinished(movie, book))
    }

    @Test
    fun `85 percent 整算看完`() {
        // 用户口径是「大于等于 85%」，所以 85% 这一下就该归入。
        val movie = entry()
        val book = progressFor(movie, positionMs = 850_000L)

        assertTrue(MovieWatchStatus.isFinished(movie, book))
    }

    @Test
    fun `时长未知不会被当成看完`() {
        // durationMs = 0 ⇒ fraction = 0f（不能拿 0 当分母），所以它落在「没看完」。
        val movie = entry()
        val book = progressFor(movie, positionMs = 500_000L, durationMs = 0L)

        assertFalse(MovieWatchStatus.isFinished(movie, book))
    }

    @Test
    fun `手动挪出的条目进度再高也不算看完`() {
        // 这就是「粘住」：不粘的话用户点完「移出已看完」，下一帧它又跳回去了。
        val movie = entry(finishedOverride = false)
        val book = progressFor(movie, positionMs = 990_000L)

        assertFalse(MovieWatchStatus.isFinished(movie, book))
    }

    @Test
    fun `手动标为看完的条目没有进度也算看完`() {
        val movie = entry(finishedOverride = true)

        assertTrue(MovieWatchStatus.isFinished(movie, MovieProgressBook()))
    }

    @Test
    fun `分组保持入参顺序`() {
        val first = entry(id = "e1", fileName = "a.mkv")
        val second = entry(id = "e2", fileName = "b.mkv")
        val third = entry(id = "e3", fileName = "c.mkv")

        val book = MovieProgressBook(
            byKey = mapOf(
                MovieProgressKey.of(second.signature) to MovieProgress(
                    positionMs = 900_000L,
                    durationMs = 1_000_000L,
                    updatedAtMs = 1L,
                ),
            ),
        )

        val (unfinished, finished) = MovieWatchStatus.partition(
            movies = listOf(first, second, third),
            progressBook = book,
        )

        // 分组不改变组内相对次序：调用点先过 `MovieOrdering.arrange`，两段各自沿用那套排序。
        assertEquals(listOf("e1", "e3"), unfinished.map { it.id })
        assertEquals(listOf("e2"), finished.map { it.id })
    }
}
