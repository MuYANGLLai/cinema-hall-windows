package com.cinemahall.core.library.sync

import com.cinemahall.core.library.MovieCategory
import com.cinemahall.core.library.MovieEntry
import com.cinemahall.core.library.MovieLibrarySnapshot
import com.cinemahall.core.library.MovieProgress
import com.cinemahall.core.library.MovieProgressBook
import com.cinemahall.core.library.MovieProgressKey
import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并内核（1b）。全部是纯函数，所以每条判据都能逐字钉住，不用起服务端。
 *
 * 这一层的三条硬规则来自服务端实现（`server/src/library.mjs`），必须**逐字对齐**：
 *  1. LWW 的比较是 `existing.updated_at_ms >= updatedAt ⇒ skip`（**平局保留既有行**）；
 *  2. 影片墓碑要级联进度墓碑（服务端在同一个事务里做）；
 *  3. 孤儿分类由客户端兜底（P9：服务端只存不改）。
 */
class LibrarySyncMergeTest {

    private fun signature(fileName: String = "movie.mkv") = MediaSignature(
        fileName = fileName,
        sizeBytes = 4L * 1024 * 1024,
        blocks = listOf("aa", "bb", "cc"),
    )

    private fun entry(
        fileName: String = "movie.mkv",
        categoryId: String = "cat-1",
        id: String = "local-$fileName",
        title: String? = null,
        finished: Boolean? = null,
        addedAtMs: Long = 10L,
    ) = MovieEntry(
        id = id,
        categoryId = categoryId,
        uri = "content://demo/$fileName",
        signature = signature(fileName),
        titleOverride = title,
        addedAtMs = addedAtMs,
        finishedOverride = finished,
    )

    private fun category(id: String = "cat-1", name: String = "纪录片") =
        MovieCategory(id = id, name = name, createdAtMs = 5L)

    private fun snapshot(
        categories: List<MovieCategory> = listOf(category()),
        movies: List<MovieEntry> = listOf(entry()),
    ) = MovieLibrarySnapshot(categories = categories, movies = movies)

    /** 一条「服务端已经有了」的基线，内容与 [entry] 一致 —— 用来构造「没有变化」的场景。 */
    private fun baselineOf(
        movie: MovieEntry,
        category: MovieCategory = category(),
        updatedAtMs: Long = 100L,
    ) = SyncState(
        categories = mapOf(
            category.id to BaselineCategory(
                name = category.name,
                kind = category.kind,
                createdAtMs = category.createdAtMs,
                updatedAtMs = updatedAtMs,
            ),
        ),
        movies = mapOf(
            movie.syncKeyOrDerived to BaselineMovie(
                displayName = movie.displayName,
                categoryId = movie.categoryId,
                titleOverride = movie.titleOverride,
                finishedOverride = movie.finishedOverride,
                addedAtMs = movie.addedAtMs,
                updatedAtMs = updatedAtMs,
            ),
        ),
    )

    // ── 上行 ──────────────────────────────────────────────────────────────────

    @Test
    fun `首次同步把本地分类与影片一起推上去`() {
        val movie = entry()
        val plan = LibrarySyncMerge.planPush(
            state = SyncState(),
            snapshot = snapshot(movies = listOf(movie)),
            progress = MovieProgressBook(),
            nowMs = 1_000L,
        )

        assertEquals(1, plan.batch.categories.size)
        assertEquals("cat-1", plan.batch.categories[0].id)
        assertEquals(1, plan.batch.movies.size)
        assertEquals(movie.syncKeyOrDerived, plan.batch.movies[0].sigKey)
        assertEquals("movie.mkv", plan.batch.movies[0].displayName)
        assertEquals(1_000L, plan.batch.movies[0].updatedAtMs)
        // 推上去就记进基线，下一轮不会再推一遍
        assertEquals(1, plan.next.movies.size)
        assertEquals(1, plan.next.categories.size)
    }

    @Test
    fun `与基线一致时一个字节都不推`() {
        val movie = entry()
        val plan = LibrarySyncMerge.planPush(
            state = baselineOf(movie),
            snapshot = snapshot(movies = listOf(movie)),
            progress = MovieProgressBook(),
            nowMs = 2_000L,
        )

        assertTrue(plan.batch.isEmpty)
    }

    @Test
    fun `本地删掉的影片补成墓碑行并清出基线`() {
        val movie = entry()
        val plan = LibrarySyncMerge.planPush(
            state = baselineOf(movie),
            snapshot = snapshot(movies = emptyList()), // 用户删了
            progress = MovieProgressBook(),
            nowMs = 3_000L,
        )

        assertEquals(1, plan.batch.movies.size)
        val tombstone = plan.batch.movies[0]
        assertEquals(movie.syncKeyOrDerived, tombstone.sigKey)
        assertEquals(3_000L, tombstone.deletedAtMs)
        // 墓碑也要带显示名：另一台设备可能从没收到过这条的正常版本
        assertEquals("movie.mkv", tombstone.displayName)
        assertTrue("推完之后基线里不该再留着它", plan.next.movies.isEmpty())
    }

    @Test
    fun `改名只推那一条且带上新显示名`() {
        val before = entry()
        val after = before.copy(titleOverride = "我起的名字")
        val plan = LibrarySyncMerge.planPush(
            state = baselineOf(before),
            snapshot = snapshot(movies = listOf(after)),
            progress = MovieProgressBook(),
            nowMs = 4_000L,
        )

        assertEquals(1, plan.batch.movies.size)
        assertEquals("我起的名字", plan.batch.movies[0].titleOverride)
        assertEquals("我起的名字", plan.batch.movies[0].displayName)
        assertTrue("分类没变就不该跟着推", plan.batch.categories.isEmpty())
    }

    @Test
    fun `进度用播放那一刻的时间戳而不是同步时刻`() {
        val key = MovieProgressKey.of(signature())
        val plan = LibrarySyncMerge.planPush(
            state = SyncState(),
            snapshot = snapshot(movies = emptyList()),
            progress = MovieProgressBook(
                byKey = mapOf(key to MovieProgress(positionMs = 60_000L, durationMs = 600_000L, updatedAtMs = 777L)),
            ),
            nowMs = 9_999L,
        )

        assertEquals(1, plan.batch.progress.size)
        assertEquals(
            "LWW 该由「用户真正看到这里」的时刻决定，而不是「谁先联网」",
            777L,
            plan.batch.progress[0].updatedAtMs,
        )
    }

    @Test
    fun `批内同键去重保留时间戳最大的那条`() {
        val batch = ChangesBatch(
            movies = listOf(
                MovieChange("k1", "旧名", updatedAtMs = 100L, addedAtMs = 1L),
                MovieChange("k1", "新名", updatedAtMs = 300L, addedAtMs = 1L),
                MovieChange("k2", "另一部", updatedAtMs = 200L, addedAtMs = 2L),
            ),
        )

        val deduped = LibrarySyncMerge.dedupe(batch)

        assertEquals(2, deduped.movies.size)
        assertEquals("新名", deduped.movies.first { it.sigKey == "k1" }.displayName)
    }

    // ── 下行：LWW ─────────────────────────────────────────────────────────────

    @Test
    fun `远端更新的影片会落成本地条目并标成 remoteOnly`() {
        val page = RemotePage(
            cursor = 12L,
            movies = listOf(
                RemoteMovie(
                    sigKey = "a".repeat(32),
                    categoryId = "cat-1",
                    displayName = "另一台设备上的片.mkv",
                    addedAtMs = 50L,
                    updatedAtMs = 200L,
                ),
            ),
        )

        val plan = LibrarySyncMerge.planApply(SyncState(), page)
        val merged = plan.libraryTransform!!(snapshot(movies = emptyList()))

        assertEquals(1, merged.movies.size)
        val added = merged.movies[0]
        assertTrue("远端条目必须标出来：本机没有这个文件", added.remoteOnly)
        assertEquals("a".repeat(32), added.syncKeyOrDerived)
        assertEquals("另一台设备上的片.mkv", added.displayName)
        assertEquals(12L, plan.next.cursor)
    }

    @Test
    fun `平局时保留既有行（与服务端的大于等于判据一致）`() {
        val key = "b".repeat(32)
        val state = SyncState(
            movies = mapOf(key to BaselineMovie(displayName = "本地名", updatedAtMs = 500L)),
        )
        val page = RemotePage(
            movies = listOf(RemoteMovie(sigKey = key, displayName = "服务端名", updatedAtMs = 500L)),
        )

        val plan = LibrarySyncMerge.planApply(state, page)

        assertNull("平局不该触发任何本地改动", plan.libraryTransform)
        assertEquals(0, plan.applied)
    }

    @Test
    fun `远端更旧的行被丢掉`() {
        val key = "c".repeat(32)
        val state = SyncState(
            movies = mapOf(key to BaselineMovie(displayName = "新", updatedAtMs = 900L)),
        )
        val page = RemotePage(
            movies = listOf(RemoteMovie(sigKey = key, displayName = "旧", updatedAtMs = 100L)),
        )

        val plan = LibrarySyncMerge.planApply(state, page)

        assertEquals(0, plan.applied)
        assertNull(plan.libraryTransform)
    }

    @Test
    fun `影片墓碑删掉本地条目并把进度一起级联掉`() {
        val movie = entry()
        val key = movie.syncKeyOrDerived
        val state = baselineOf(movie).copy(
            progress = mapOf(key to BaselineProgress(60_000L, 600_000L, 100L)),
        )
        val book = MovieProgressBook(
            byKey = mapOf(key to MovieProgress(60_000L, 600_000L, 100L)),
        )
        val page = RemotePage(
            movies = listOf(
                RemoteMovie(
                    sigKey = key,
                    displayName = "movie.mkv",
                    addedAtMs = 10L,
                    updatedAtMs = 300L,
                    deletedAtMs = 300L,
                ),
            ),
        )

        val plan = LibrarySyncMerge.planApply(state, page)
        val mergedLibrary = plan.libraryTransform!!(snapshot(movies = listOf(movie)))
        val mergedProgress = plan.progressTransform!!(book)

        assertTrue("影片被删了，本地条目也要走", mergedLibrary.movies.isEmpty())
        assertTrue(
            "服务端在同一事务里级联了进度墓碑，客户端不复刻就会留下孤儿进度",
            mergedProgress.byKey.isEmpty(),
        )
        assertTrue(plan.next.movies.isEmpty())
        assertTrue(plan.next.progress.isEmpty())
    }

    @Test
    fun `指向已删分类的影片归入未分类`() {
        val movie = entry(categoryId = "cat-gone")
        val page = RemotePage(
            movies = listOf(
                RemoteMovie(
                    sigKey = movie.syncKeyOrDerived,
                    categoryId = "cat-gone",
                    displayName = "movie.mkv",
                    addedAtMs = 10L,
                    updatedAtMs = 400L,
                ),
            ),
        )

        val plan = LibrarySyncMerge.planApply(baselineOf(movie), page)
        val merged = plan.libraryTransform!!(snapshot(categories = emptyList(), movies = listOf(movie)))

        assertEquals("", merged.movies[0].categoryId)
    }

    @Test
    fun `分类比影片晚到时会重新接回去`() {
        val movie = entry(categoryId = "cat-late")
        val key = movie.syncKeyOrDerived
        // 第一次：影片先到，分类还没有 ⇒ 落到未分类
        val firstPage = RemotePage(
            movies = listOf(
                RemoteMovie(
                    sigKey = key,
                    categoryId = "cat-late",
                    displayName = "movie.mkv",
                    addedAtMs = 10L,
                    updatedAtMs = 200L,
                ),
            ),
        )
        val firstPlan = LibrarySyncMerge.planApply(
            baselineOf(movie, category = category(id = "cat-late")),
            firstPage,
        )
        val afterFirst = firstPlan.libraryTransform!!(snapshot(categories = emptyList(), movies = listOf(movie)))
        assertEquals("", afterFirst.movies[0].categoryId)

        // 第二次：分类到了（它的 rev 更小但下一页才取到），重连逻辑把影片挂回去
        val secondPlan = LibrarySyncMerge.planApply(
            firstPlan.next,
            RemotePage(
                cursor = 220L,
                categories = listOf(
                    RemoteCategory(id = "cat-late", name = "后来建的", createdAtMs = 20L, updatedAtMs = 210L),
                ),
            ),
        )
        val afterSecond = secondPlan.libraryTransform!!(afterFirst)

        assertEquals("cat-late", afterSecond.movies[0].categoryId)
        assertEquals("后来建的", afterSecond.categories[0].name)
    }

    @Test
    fun `远端条目只覆盖上云字段，不动本机的 uri 与摘要`() {
        val movie = entry(title = "本机旧名")
        val page = RemotePage(
            movies = listOf(
                RemoteMovie(
                    sigKey = movie.syncKeyOrDerived,
                    categoryId = "cat-1",
                    displayName = "服务端新名",
                    titleOverride = "服务端新名",
                    finishedOverride = true,
                    addedAtMs = 10L,
                    updatedAtMs = 500L,
                ),
            ),
        )

        val plan = LibrarySyncMerge.planApply(baselineOf(movie), page)
        val merged = plan.libraryTransform!!(snapshot(movies = listOf(movie)))
        val result = merged.movies[0]

        assertEquals("服务端新名", result.titleOverride)
        assertEquals(true, result.finishedOverride)
        assertEquals("本机仍是本机那条（有 uri、有摘要）", "content://demo/movie.mkv", result.uri)
        assertEquals(movie.signature, result.signature)
        assertFalse("本机有的条目不该被标成 remoteOnly", result.remoteOnly)
    }

    @Test
    fun `进度行到达后按 sigKey 落进进度簿`() {
        val key = "d".repeat(32)
        val plan = LibrarySyncMerge.planApply(
            SyncState(),
            RemotePage(
                cursor = 30L,
                progress = listOf(
                    RemoteProgress(sigKey = key, positionMs = 90_000L, durationMs = 600_000L, updatedAtMs = 40L),
                ),
            ),
        )

        val merged = plan.progressTransform!!(MovieProgressBook())
        assertEquals(90_000L, merged.byKey[key]?.positionMs)
        assertEquals(40L, merged.byKey[key]?.updatedAtMs)
        assertNull("只有进度变化时不该碰库那份 JSON", plan.libraryTransform)
    }

    @Test
    fun `游标只前进不后退`() {
        val plan = LibrarySyncMerge.planApply(
            SyncState(cursor = 500L),
            RemotePage(cursor = 120L),
        )

        assertEquals(500L, plan.next.cursor)
    }

    @Test
    fun `空页不改动任何东西且游标保持`() {
        val plan = LibrarySyncMerge.planApply(SyncState(cursor = 42L), RemotePage(cursor = 42L))

        assertNull(plan.libraryTransform)
        assertNull(plan.progressTransform)
        assertEquals(0, plan.applied)
        assertEquals(42L, plan.next.cursor)
        assertNotNull(plan.next)
    }
}
