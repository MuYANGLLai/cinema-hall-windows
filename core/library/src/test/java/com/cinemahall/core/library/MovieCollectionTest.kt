package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieCollectionTest {

    private fun signature(fileName: String): MediaSignature =
        MediaSignature(fileName = fileName, sizeBytes = 100L, blocks = listOf("aa"))

    private fun entry(
        fileName: String,
        categoryId: String = "剧集",
        id: String = fileName,
        addedAtMs: Long = 0L,
    ): MovieEntry = MovieEntry(
        id = id,
        categoryId = categoryId,
        uri = "content://demo/$id",
        signature = signature(fileName),
        addedAtMs = addedAtMs,
    )

    private fun snapshotOf(vararg entries: MovieEntry): MovieLibrarySnapshot =
        MovieLibrarySnapshot(
            categories = listOf(MovieCategory(id = "剧集", name = "剧集", createdAtMs = 0L)),
            movies = entries.toList(),
        )

    @Test
    fun `同一个分类的影片全部返回`() {
        val snapshot = snapshotOf(
            entry("海贼王 第1集.mkv", addedAtMs = 1),
            entry("海贼王 第2集.mkv", addedAtMs = 2),
            entry("别的片.mkv", categoryId = "电影", addedAtMs = 3),
        )

        val siblings = MovieCollection.siblings(snapshot, signature("海贼王 第1集.mkv"), MovieProgressBook())

        assertEquals(2, siblings.size)
        assertTrue(siblings.none { it.displayName == "别的片.mkv" })
    }

    @Test
    fun `当前影片不在库里时返回空`() {
        val snapshot = snapshotOf(entry("海贼王 第1集.mkv"))

        assertTrue(
            MovieCollection.siblings(snapshot, signature("不在库里的片.mkv"), MovieProgressBook()).isEmpty(),
        )
    }

    @Test
    fun `没有当前影片时返回空`() {
        val snapshot = snapshotOf(entry("海贼王 第1集.mkv"))

        assertTrue(MovieCollection.siblings(snapshot, null, MovieProgressBook()).isEmpty())
        assertNull(MovieCollection.next(snapshot, null, MovieProgressBook()))
    }

    @Test
    fun `下一集按集号加一`() {
        val snapshot = snapshotOf(
            entry("海贼王 第1集.mkv", addedAtMs = 1),
            entry("海贼王 第2集.mkv", addedAtMs = 2),
            entry("海贼王 第3集.mkv", addedAtMs = 3),
        )

        val next = MovieCollection.next(snapshot, signature("海贼王 第2集.mkv"), MovieProgressBook())

        assertEquals("海贼王 第3集.mkv", next?.displayName)
    }

    @Test
    fun `没有下一集时退回列表下一条`() {
        // 排序是 `MovieOrdering.arrange` 给的（名称序："A mkv" 在 "重播.mkv" 之前），
        // 不是加入顺序 —— 所以这里刻意用一个没有集号、名字排在后面的片子。
        val snapshot = snapshotOf(
            entry("A 第1集.mkv", addedAtMs = 1),
            entry("重播.mkv", addedAtMs = 2),
        )

        val next = MovieCollection.next(snapshot, signature("A 第1集.mkv"), MovieProgressBook())

        assertEquals("重播.mkv", next?.displayName)
    }

    @Test
    fun `最后一条没有下一集`() {
        val snapshot = snapshotOf(entry("海贼王 第1集.mkv"))

        assertNull(MovieCollection.next(snapshot, signature("海贼王 第1集.mkv"), MovieProgressBook()))
    }

    @Test
    fun `看完的影片排到末尾`() {
        val snapshot = snapshotOf(
            entry("A.mkv", addedAtMs = 1),
            entry("B.mkv", addedAtMs = 2),
            entry("C.mkv", addedAtMs = 3),
        )
        // B 最近看过（`arrange` 会把它排到最前），看进度 95% ⇒ 只有「已看完排末尾」
        // 这一条能把它移到队尾；`arrange` 单独跑不会给出这个结果。
        val progress = MovieProgressBook(
            byKey = mapOf(
                MovieProgressKey.of(signature("B.mkv")) to MovieProgress(
                    positionMs = 9_500L,
                    durationMs = 10_000L,
                    updatedAtMs = 1L,
                ),
            ),
        )

        val siblings = MovieCollection.siblings(snapshot, signature("A.mkv"), progress)

        assertEquals(listOf("A.mkv", "C.mkv", "B.mkv"), siblings.map { it.displayName })
    }

    @Test
    fun `未看完的影片之间保持原排序`() {
        val snapshot = snapshotOf(
            entry("A.mkv", addedAtMs = 1),
            entry("B.mkv", addedAtMs = 2),
            entry("C.mkv", addedAtMs = 3),
        )

        val siblings = MovieCollection.siblings(snapshot, signature("A.mkv"), MovieProgressBook())

        assertEquals(listOf("A.mkv", "B.mkv", "C.mkv"), siblings.map { it.displayName })
    }
}
