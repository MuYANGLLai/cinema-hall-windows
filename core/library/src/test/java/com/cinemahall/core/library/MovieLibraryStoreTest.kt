package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MovieLibraryStore` 的真逻辑（§16.3 / §16.5 / §16.6 / §16.12）。
 *
 * 全部用例都塞 [FakeStorage]：`SharedPreferences` 在 JVM 上不存在，而这个类里要测的
 * 恰恰是「库怎么变」—— 增删改、失效三态、进度覆盖、坏数据留原文。同一份 [FakeStorage]
 * 喂给两个 store 实例，就是在验证「重启后还在」。
 */
class MovieLibraryStoreTest {

    /**
     * 内存版落盘。[write] 的 `null` 语义与生产实现一致（删键），
     * 因为「坏数据要清掉原键」这条就靠它。
     */
    private class FakeStorage : LibraryStorage {

        val values = mutableMapOf<String, String>()

        /**
         * 落盘次数。用来断言「一条都没改时不该白写一次」（见批量删除那条用例）——
         * 只比对盘上内容证明不了这件事：库没变时写进去的内容与原来**一模一样**。
         */
        var writes = 0

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String?) {
            writes++
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun signature(
        fileName: String = "movie.mkv",
        sizeBytes: Long = 4L * 1024 * 1024,
        blocks: List<String> = listOf("aa", "bb", "cc"),
    ): MediaSignature = MediaSignature(
        fileName = fileName,
        sizeBytes = sizeBytes,
        blocks = blocks,
    )

    /** 建好一个分类、加进一条影片的 store，返回 store 与影片 id。 */
    private fun storeWithMovie(
        fileName: String = "movie.mkv",
        uri: String = "content://demo/1",
        sizeBytes: Long = 4L * 1024 * 1024,
    ): Pair<MovieLibraryStore, String> {
        val store = MovieLibraryStore(FakeStorage())
        val categoryId = store.createCategory("纪录片", nowMs = 1L)!!
        val movieId = store.addMovie(categoryId, uri, signature(fileName, sizeBytes), nowMs = 2L)!!
        return store to movieId
    }

    // ── 分类 ────────────────────────────────────────────────────────────────────

    @Test
    fun `建分类后出现在快照里且返回新 id`() {
        val store = MovieLibraryStore(FakeStorage())

        val id = store.createCategory("纪录片", nowMs = 1_000L)

        assertNotNull(id)
        val category = store.snapshot.value.category(id!!)
        assertEquals("纪录片", category?.name)
        assertEquals(1_000L, category?.createdAtMs)
        assertEquals(1, store.snapshot.value.categories.size)
    }

    @Test
    fun `空名建分类返回 null 且不新增`() {
        val store = MovieLibraryStore(FakeStorage())
        store.createCategory("纪录片", nowMs = 1_000L)

        assertNull(store.createCategory("", nowMs = 2_000L))
        assertNull(store.createCategory("   ", nowMs = 2_000L))

        assertEquals(1, store.snapshot.value.categories.size)
    }

    @Test
    fun `重名分类返回 null 且不新增`() {
        val store = MovieLibraryStore(FakeStorage())
        store.createCategory("纪录片", nowMs = 1_000L)

        assertNull(store.createCategory("纪录片", nowMs = 2_000L))
        // trim 之后相同也算重名：这两个名字在界面上长得一模一样
        assertNull(store.createCategory("  纪录片  ", nowMs = 2_000L))

        assertEquals(1, store.snapshot.value.categories.size)
        assertEquals("纪录片", store.snapshot.value.categories.single().name)
    }

    @Test
    fun `分类改名只改名字不动 id`() {
        val store = MovieLibraryStore(FakeStorage())
        val id = store.createCategory("纪录片", nowMs = 1_000L)!!

        assertTrue(store.renameCategory(id, "  纪实  "))

        val category = store.snapshot.value.categories.single()
        assertEquals(id, category.id)
        assertEquals("纪实", category.name)
        assertEquals(1_000L, category.createdAtMs)

        // 空名与不存在的 id 都不改任何东西
        assertFalse(store.renameCategory(id, "   "))
        assertFalse(store.renameCategory("no-such-category", "纪实"))
        assertEquals("纪实", store.snapshot.value.categories.single().name)
    }

    @Test
    fun `删分类连带删掉影片但进度记录仍在`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val categoryId = store.createCategory("纪录片", nowMs = 1L)!!
        store.addMovie(categoryId, "content://demo/1", signature(), nowMs = 2L)
        store.rememberProgress(signature(), 120_000L, 600_000L, nowMs = 5_000L)

        store.deleteCategory(categoryId)

        assertEquals(0, store.snapshot.value.categories.size)
        assertEquals(0, store.snapshot.value.movies.size)

        // 进度不随分类与条目删除而删（§16.6）：重新添加同一个文件要能接上原来的进度
        val reopened = MovieLibraryStore(storage)
        assertNotNull(reopened.progressOf(signature()))

        val newCategoryId = reopened.createCategory("电影", nowMs = 6L)!!
        reopened.addMovie(newCategoryId, "content://demo/1", signature(), nowMs = 7L)
        assertEquals(120_000L, reopened.progressOf(signature())?.positionMs)
    }

    // ── 影片标记 ────────────────────────────────────────────────────────────────

    @Test
    fun `同一分类内同一个 uri 不能重复添加`() {
        val store = MovieLibraryStore(FakeStorage())
        val first = store.createCategory("纪录片", nowMs = 1L)!!
        val second = store.createCategory("电影", nowMs = 2L)!!

        assertNotNull(store.addMovie(first, "content://demo/1", signature(), nowMs = 3L))

        // 同分类同 uri：拒绝，且条数不变
        assertNull(store.addMovie(first, "content://demo/1", signature(), nowMs = 4L))
        assertEquals(1, store.snapshot.value.movies.size)

        // 不同分类允许同一个文件（同一部片可以同时属于两个分类）
        assertNotNull(store.addMovie(second, "content://demo/1", signature(), nowMs = 5L))
        assertEquals(2, store.snapshot.value.movies.size)
    }

    @Test
    fun `分类不存在时添加影片返回 null`() {
        val store = MovieLibraryStore(FakeStorage())

        assertNull(store.addMovie("no-such-category", "content://demo/1", signature()))

        assertEquals(0, store.snapshot.value.movies.size)
    }

    @Test
    fun `分类内影片按加入时间升序返回`() {
        val store = MovieLibraryStore(FakeStorage())
        val categoryId = store.createCategory("纪录片", nowMs = 1L)!!
        store.addMovie(categoryId, "content://demo/1", signature("late.mkv"), nowMs = 3_000L)
        store.addMovie(categoryId, "content://demo/2", signature("early.mkv"), nowMs = 1_000L)
        store.addMovie(categoryId, "content://demo/3", signature("middle.mkv"), nowMs = 2_000L)

        assertEquals(
            listOf("early.mkv", "middle.mkv", "late.mkv"),
            store.snapshot.value.moviesIn(categoryId).map { it.displayName },
        )

        // 别的分类不混进来
        val otherCategoryId = store.createCategory("电影", nowMs = 2L)!!
        store.addMovie(otherCategoryId, "content://demo/4", signature("other.mkv"), nowMs = 0L)

        assertEquals(3, store.snapshot.value.moviesIn(categoryId).size)
        assertEquals(
            listOf("other.mkv"),
            store.snapshot.value.moviesIn(otherCategoryId).map { it.displayName },
        )
    }

    @Test
    fun `重命名只改显示名不碰摘要里的真实文件名`() {
        val (store, id) = storeWithMovie(fileName = "原始片名.mkv")

        assertTrue(store.renameMovie(id, "我的片名"))

        val movie = store.snapshot.value.movies.single()
        assertEquals("我的片名", movie.displayName)
        // §16.2 的机械守卫：显示名绝不能渗进 signature.fileName
        assertEquals("原始片名.mkv", movie.signature.fileName)
    }

    @Test
    fun `重命名传 null 或空白回到按文件名显示`() {
        val (store, id) = storeWithMovie(fileName = "原始片名.mkv")

        listOf<String?>(null, "", "   ").forEach { blank ->
            store.renameMovie(id, "我的片名")
            assertEquals("我的片名", store.snapshot.value.movies.single().titleOverride)

            assertTrue(store.renameMovie(id, blank))

            val movie = store.snapshot.value.movies.single()
            assertNull("title=$blank 时应当回到 null", movie.titleOverride)
            assertEquals("原始片名.mkv", movie.displayName)
        }
    }

    @Test
    fun `重命名不存在的条目返回 false`() {
        val (store, _) = storeWithMovie()

        assertFalse(store.renameMovie("no-such-movie", "我的片名"))
    }

    @Test
    fun `删掉影片后条目消失`() {
        val (store, id) = storeWithMovie()
        store.rememberProgress(signature(), 1_000L, 2_000L, nowMs = 3L)

        store.removeMovie(id)

        assertEquals(0, store.snapshot.value.movies.size)
        // 进度仍在（§16.6）
        assertNotNull(store.progressOf(signature()))
    }

    // ── 批量删除（2026-10-14 影片管理页的多选）────────────────────────────────────

    @Test
    fun `批量删除一次删掉选中的那几条并返回条数`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val categoryId = store.createCategory("纪录片", nowMs = 1L)!!
        val first = store.addMovie(categoryId, "content://demo/1", signature("a.mkv"), nowMs = 2L)!!
        val keep = store.addMovie(categoryId, "content://demo/2", signature("b.mkv"), nowMs = 3L)!!
        val last = store.addMovie(categoryId, "content://demo/3", signature("c.mkv"), nowMs = 4L)!!
        store.rememberProgress(signature("a.mkv"), 1_000L, 2_000L, nowMs = 5L)

        assertEquals(2, store.removeMovies(listOf(first, last)))

        assertEquals(listOf(keep), store.snapshot.value.movies.map { it.id })
        // 进度不随条目删除而删（§16.6）
        assertNotNull(store.progressOf(signature("a.mkv")))

        // 落盘的是删完之后的库：同一个 storage 上的新实例看到的必须与现在一致
        assertEquals(store.snapshot.value, MovieLibraryStore(storage).snapshot.value)
    }

    @Test
    fun `批量删除可以跨分类删且不动分类本身`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val first = store.createCategory("纪录片", nowMs = 1L)!!
        val second = store.createCategory("电影", nowMs = 2L)!!
        val inFirst = store.addMovie(first, "content://demo/1", signature("a.mkv"), nowMs = 3L)!!
        val inSecond = store.addMovie(second, "content://demo/2", signature("b.mkv"), nowMs = 4L)!!

        assertEquals(2, store.removeMovies(setOf(inFirst, inSecond)))

        assertEquals(0, store.snapshot.value.movies.size)
        // 删的是标记，不是分类
        assertEquals(2, store.snapshot.value.categories.size)
    }

    @Test
    fun `批量删除里不存在的 id 静默忽略`() {
        val (store, id) = storeWithMovie()

        assertEquals(0, store.removeMovies(listOf("no-such-movie")))
        assertEquals(1, store.snapshot.value.movies.size)

        // 混着来：存在的那些照删，不存在的那些不影响返回的条数
        assertEquals(1, store.removeMovies(listOf(id, "no-such-movie")))
        assertEquals(0, store.snapshot.value.movies.size)
    }

    @Test
    fun `批量删除一条都没删掉时不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val categoryId = store.createCategory("纪录片", nowMs = 1L)!!
        store.addMovie(categoryId, "content://demo/1", signature(), nowMs = 2L)
        val writesBefore = storage.writes

        assertEquals(0, store.removeMovies(emptyList()))
        assertEquals(0, store.removeMovies(listOf("no-such-movie")))

        // 白写一次盘 = 换一次 StateFlow = 所有订阅者重组一遍，而库里一个字都没变
        assertEquals(writesBefore, storage.writes)
        assertEquals(1, store.snapshot.value.movies.size)
    }

    // ── 失效三态（§16.5）────────────────────────────────────────────────────────

    @Test
    fun `探测到 Present 时清掉 missing 并记下探测时刻`() {
        val (store, id) = storeWithMovie()
        // 先标成「打不开」，再探测一次应当清掉（能打开了，上次那条结论就过期了）
        store.applyProbe(id, MovieProbe.Unreachable("云盘暂时连不上"), nowMs = 1_000L)

        val removed = store.applyProbe(
            id,
            MovieProbe.Present(fileName = "movie.mkv", sizeBytes = 1024L),
            nowMs = 2_000L,
        )

        assertFalse(removed)
        val movie = store.snapshot.value.movies.single()
        assertFalse(movie.missing)
        assertEquals(2_000L, movie.lastCheckedAtMs)
    }

    @Test
    fun `探测到 Missing 时删掉条目并返回 true`() {
        val (store, id) = storeWithMovie()

        val removed = store.applyProbe(id, MovieProbe.Missing, nowMs = 3_000L)

        assertTrue(removed)
        assertEquals(0, store.snapshot.value.movies.size)
    }

    @Test
    fun `探测到 Unreachable 时保留条目并标 missing`() {
        val (store, id) = storeWithMovie()

        val removed = store.applyProbe(
            id,
            MovieProbe.Unreachable("授权已被回收，重新添加这个文件即可恢复"),
            nowMs = 3_000L,
        )

        assertFalse(removed)
        val movie = store.snapshot.value.movies.single()
        assertTrue(movie.missing)
        assertEquals(3_000L, movie.lastCheckedAtMs)
    }

    @Test
    fun `探测不存在的条目返回 false 且不改动任何东西`() {
        val (store, _) = storeWithMovie()
        val before = store.snapshot.value

        assertFalse(store.applyProbe("no-such-movie", MovieProbe.Missing, nowMs = 3_000L))

        assertEquals(before, store.snapshot.value)
    }

    @Test
    fun `重新登记摘要会清掉 missing 并刷新探测时刻`() {
        val (store, id) = storeWithMovie(fileName = "movie.mkv", sizeBytes = 1024L)
        store.applyProbe(id, MovieProbe.Unreachable("云盘暂时连不上"), nowMs = 3_000L)

        val changed = signature("movie.mkv", sizeBytes = 2_048L)
        assertTrue(store.refreshSignature(id, changed, nowMs = 4_000L))

        val movie = store.snapshot.value.movies.single()
        assertEquals(changed, movie.signature)
        assertFalse(movie.missing)
        assertEquals(4_000L, movie.lastCheckedAtMs)

        assertFalse(store.refreshSignature("no-such-movie", changed, nowMs = 5_000L))
    }

    @Test
    fun `手动清理只删掉标了 missing 的条目`() {
        val store = MovieLibraryStore(FakeStorage())
        val categoryId = store.createCategory("纪录片", nowMs = 1L)!!
        val present = store.addMovie(categoryId, "content://demo/1", signature("a.mkv"), nowMs = 2L)!!
        val unreachableA = store.addMovie(categoryId, "content://demo/2", signature("b.mkv"), nowMs = 3L)!!
        val unreachableB = store.addMovie(categoryId, "content://demo/3", signature("c.mkv"), nowMs = 4L)!!
        store.applyProbe(unreachableA, MovieProbe.Unreachable("云盘暂时连不上"), nowMs = 5L)
        store.applyProbe(unreachableB, MovieProbe.Unreachable("云盘暂时连不上"), nowMs = 5L)

        assertEquals(2, store.missing().size)
        assertEquals(2, store.removeMissing())

        assertEquals(present, store.snapshot.value.movies.single().id)
        // 再点一次是 0，不是负数、也不是把剩下的那条也删了
        assertEquals(0, store.removeMissing())
        assertEquals(1, store.snapshot.value.movies.size)
    }

    // ── 落盘往返与坏数据（§16.4）────────────────────────────────────────────────

    @Test
    fun `落盘往返后第二个 store 读到同样的库与进度`() {
        val storage = FakeStorage()
        val first = MovieLibraryStore(storage)
        val categoryId = first.createCategory("纪录片", nowMs = 1_000L)!!
        val movieId = first.addMovie(categoryId, "content://demo/1", signature(), nowMs = 2_000L)!!
        first.renameMovie(movieId, "我的片名")
        first.rememberProgress(signature(), 120_000L, 600_000L, nowMs = 3_000L)

        // 同一个 FakeStorage 上的新实例 = 重启后的那次构造
        val second = MovieLibraryStore(storage)

        assertEquals(first.snapshot.value, second.snapshot.value)
        assertEquals(first.progress.value, second.progress.value)
        assertEquals("我的片名", second.snapshot.value.movies.single().displayName)
        assertEquals(120_000L, second.progressOf(signature())?.positionMs)
    }

    @Test
    fun `库文件解不开时留原文并清掉原键`() {
        val storage = FakeStorage()
        storage.write(LibraryScope.libraryKey(LibraryScope.LOCAL), "{")

        val store = MovieLibraryStore(storage)

        // 本次以空库继续
        assertEquals(MovieLibrarySnapshot(), store.snapshot.value)
        // 但不许静默丢弃：原文另存一份，原键被清掉（下次写入就是干净的）
        assertEquals("{", storage.values[LibraryScope.libraryCorruptKey(LibraryScope.LOCAL)])
        assertNull(storage.read(LibraryScope.libraryKey(LibraryScope.LOCAL)))
    }

    @Test
    fun `进度文件解不开时同样留原文并清掉原键`() {
        val storage = FakeStorage()
        storage.write(LibraryScope.progressKey(LibraryScope.LOCAL), "{")

        val store = MovieLibraryStore(storage)

        assertEquals(MovieProgressBook(), store.progress.value)
        assertEquals("{", storage.values[LibraryScope.progressCorruptKey(LibraryScope.LOCAL)])
        assertNull(storage.read(LibraryScope.progressKey(LibraryScope.LOCAL)))
    }

    @Test
    fun `第一次打开时两种数据都没有也就不会写 corrupt 键`() {
        val storage = FakeStorage()

        val store = MovieLibraryStore(storage)

        assertEquals(MovieLibrarySnapshot(), store.snapshot.value)
        assertEquals(MovieProgressBook(), store.progress.value)
        // 「没有数据」不是坏数据：不该留下任何键（包括 corrupt 那两个）
        assertTrue("实得 ${storage.values}", storage.values.isEmpty())
    }

    // ── 进度 ────────────────────────────────────────────────────────────────────

    @Test
    fun `记进度后读得到且同一部片再记一次是覆盖`() {
        val store = MovieLibraryStore(FakeStorage())

        store.rememberProgress(signature(), 10_000L, 600_000L, nowMs = 1_000L)

        assertEquals(10_000L, store.progressOf(signature())?.positionMs)
        assertEquals(1_000L, store.progressOf(signature())?.updatedAtMs)

        store.rememberProgress(signature(), 20_000L, 600_000L, nowMs = 16_000L)

        // 覆盖而不是追加：一部片看两小时不该在盘上攒出几百条
        val progress = store.progressOf(signature())!!
        assertEquals(1, store.progress.value.byKey.size)
        assertEquals(20_000L, progress.positionMs)
        assertEquals(16_000L, progress.updatedAtMs)

        // 没记过的影片返回 null，界面据此显示「没看过」
        assertNull(store.progressOf(signature("other.mkv")))
    }

    @Test
    fun `负数位置与时长落库后被夹到 0`() {
        val store = MovieLibraryStore(FakeStorage())

        store.rememberProgress(signature(), -5_000L, -600_000L, nowMs = 1_000L)

        val progress = store.progressOf(signature())!!
        assertEquals(0L, progress.positionMs)
        assertEquals(0L, progress.durationMs)
    }

    @Test
    fun `比例在 0 到 1 之间且分母为零时是 0`() {
        val store = MovieLibraryStore(FakeStorage())

        store.rememberProgress(signature("a.mkv"), 300_000L, 600_000L, nowMs = 1_000L)
        assertEquals(0.5f, store.progressOf(signature("a.mkv"))!!.fraction, 0.0001f)

        // 位置超过时长（关键帧与时间基的误差）：夹到 1，而不是画出超过 100% 的进度
        store.rememberProgress(signature("b.mkv"), 700_000L, 600_000L, nowMs = 1_000L)
        assertEquals(1f, store.progressOf(signature("b.mkv"))!!.fraction, 0.0001f)

        // 时长未知：不能拿 0 当分母（NaN 会让进度条画不出来）
        store.rememberProgress(signature("c.mkv"), 10_000L, 0L, nowMs = 1_000L)
        assertEquals(0f, store.progressOf(signature("c.mkv"))!!.fraction, 0.0001f)
    }

    // ── 扫描目的地（§16.15）──────────────────────────────────────────────────────

    @Test
    fun `两个扫描分类都缺时一次建好且只落一次盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val before = storage.writes

        val ids = store.ensureScanCategories(nowMs = 1_000L)

        assertEquals(1, storage.writes - before)
        assertEquals(2, store.snapshot.value.categories.size)
        assertEquals(MovieCategoryKind.MOVIE, store.snapshot.value.category(ids.movieId)?.kind)
        assertEquals(MovieCategoryKind.SERIES, store.snapshot.value.category(ids.seriesId)?.kind)
        assertEquals(MovieCategoryKind.MOVIE_NAME, store.snapshot.value.category(ids.movieId)?.name)
        assertEquals(MovieCategoryKind.SERIES_NAME, store.snapshot.value.category(ids.seriesId)?.name)
    }

    @Test
    fun `扫描分类已存在时复用且不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val first = store.ensureScanCategories(nowMs = 1_000L)
        val before = storage.writes

        val again = store.ensureScanCategories(nowMs = 2_000L)

        assertEquals(first, again)
        assertEquals(before, storage.writes)
        assertEquals(2, store.snapshot.value.categories.size)
    }

    @Test
    fun `用户改了名也照样认得出来`() {
        // `kind` 才是身份，名字不是。按名字找的话，用户把「电影」改名成「影片」之后
        // 下一次扫描会再建一个「电影」—— 界面上两个分类，其中一个是他刚改过名的。
        val store = MovieLibraryStore(FakeStorage())
        val ids = store.ensureScanCategories(nowMs = 1_000L)
        store.renameCategory(ids.movieId, "影片")

        val again = store.ensureScanCategories(nowMs = 2_000L)

        assertEquals(ids.movieId, again.movieId)
        assertEquals(2, store.snapshot.value.categories.size)
        assertEquals("影片", store.snapshot.value.category(ids.movieId)?.name)
    }

    @Test
    fun `同名的手建分类被收编而不是再建一个`() {
        val store = MovieLibraryStore(FakeStorage())
        val manual = store.createCategory(MovieCategoryKind.MOVIE_NAME, nowMs = 1_000L)!!

        val ids = store.ensureScanCategories(nowMs = 2_000L)

        assertEquals(manual, ids.movieId)
        assertEquals(2, store.snapshot.value.categories.size)
        assertEquals(MovieCategoryKind.MOVIE, store.snapshot.value.category(manual)?.kind)
    }

    // ── 批量加入（§16.15）───────────────────────────────────────────────────────

    @Test
    fun `批量加入返回条数且只落一次盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val categoryId = store.createCategory("电影", nowMs = 1L)!!
        val before = storage.writes

        val added = store.addMovies(
            categoryId = categoryId,
            movies = listOf(
                NewMovie("content://media/1", signature("a.mkv")),
                NewMovie("content://media/2", signature("b.mkv")),
                NewMovie("content://media/3", signature("c.mkv")),
            ),
            nowMs = 2L,
        )

        assertEquals(3, added)
        assertEquals(1, storage.writes - before)
        assertEquals(3, store.snapshot.value.moviesIn(categoryId).size)
    }

    @Test
    fun `批量加入分类不存在时返回 0 且不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val before = storage.writes

        val added = store.addMovies(
            categoryId = "不存在的分类",
            movies = listOf(NewMovie("content://media/1", signature("a.mkv"))),
            nowMs = 2L,
        )

        assertEquals(0, added)
        assertEquals(before, storage.writes)
    }

    @Test
    fun `批量加入时批内同 uri 只留一条`() {
        // 同一部片在 SD 卡与内置存储各一份时 uri 不同，但同一个 uri 在一批里出现两次
        // 只能是调用方的重复输入 —— 加进去之后界面会是两个一模一样的格子。
        val store = MovieLibraryStore(FakeStorage())
        val categoryId = store.createCategory("电影", nowMs = 1L)!!

        val added = store.addMovies(
            categoryId = categoryId,
            movies = listOf(
                NewMovie("content://media/1", signature("a.mkv")),
                NewMovie("content://media/1", signature("a.mkv")),
            ),
            nowMs = 2L,
        )

        assertEquals(1, added)
        assertEquals(1, store.snapshot.value.moviesIn(categoryId).size)
    }

    @Test
    fun `批量加入全被去重时不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val categoryId = store.createCategory("电影", nowMs = 1L)!!
        store.addMovie(categoryId, "content://media/1", signature("a.mkv"), nowMs = 2L)
        val before = storage.writes

        val added = store.addMovies(
            categoryId = categoryId,
            movies = listOf(NewMovie("content://media/1", signature("a.mkv"))),
            nowMs = 3L,
        )

        assertEquals(0, added)
        assertEquals(before, storage.writes)
    }

    // ── 批量移动（§16.16）───────────────────────────────────────────────────────

    /** 两个分类、各一条影片。 */
    private fun storeWithTwoCategories(): Triple<MovieLibraryStore, String, String> {
        val store = MovieLibraryStore(FakeStorage())
        val from = store.createCategory("纪录片", nowMs = 1L)!!
        val to = store.createCategory("电影", nowMs = 2L)!!
        return Triple(store, from, to)
    }

    @Test
    fun `批量移动改分类并返回真的移动的条数`() {
        val (store, from, to) = storeWithTwoCategories()
        val first = store.addMovie(from, "content://media/1", signature("a.mkv"), nowMs = 3L)!!
        val second = store.addMovie(from, "content://media/2", signature("b.mkv"), nowMs = 4L)!!

        val moved = store.moveMovies(listOf(first, second), to)

        assertEquals(2, moved)
        assertEquals(0, store.snapshot.value.moviesIn(from).size)
        assertEquals(2, store.snapshot.value.moviesIn(to).size)
    }

    @Test
    fun `批量移动目标分类不存在时返回 0 且不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val from = store.createCategory("纪录片", nowMs = 1L)!!
        val id = store.addMovie(from, "content://media/1", signature("a.mkv"), nowMs = 2L)!!
        val before = storage.writes

        val moved = store.moveMovies(listOf(id), "不存在的分类")

        assertEquals(0, moved)
        assertEquals(before, storage.writes)
        assertEquals(1, store.snapshot.value.moviesIn(from).size)
    }

    @Test
    fun `批量移动时目标已有同一个 uri 则合并源条目`() {
        // `addMovie` 的规则是「同一分类内同一个文件只留一条」（按 uri 认），
        // 移动必须服从它 —— 否则会造出一条 `addMovie` 自己都不允许的状态。
        val (store, from, to) = storeWithTwoCategories()
        store.addMovie(from, "content://media/1", signature("a.mkv"), nowMs = 3L)
        val duplicate = store.addMovie(to, "content://media/1", signature("a.mkv"), nowMs = 4L)!!

        val moved = store.moveMovies(store.snapshot.value.moviesIn(from).map { it.id }, to)

        assertEquals(0, moved)
        assertEquals(0, store.snapshot.value.moviesIn(from).size)
        assertEquals(1, store.snapshot.value.moviesIn(to).size)
        assertEquals(duplicate, store.snapshot.value.moviesIn(to).single().id)
    }

    @Test
    fun `同一部片换了 URI 不会被合并`() {
        // 判据是 **uri**（与 `addMovie` 同口径），不是摘要：扫描来的 MediaStore URI 与
        // 手动加的 SAF URI 不同，两条在库里是**合法的两条**（§16.3 允许跨分类重复）。
        // 若这里按摘要合并，用户会看到「移了 2 部、只多出 1 部」，而少掉的那条是删了。
        val (store, from, to) = storeWithTwoCategories()
        val source = store.addMovie(from, "content://media/1", signature("a.mkv"), nowMs = 3L)!!
        val existing = store.addMovie(to, "content://other/1", signature("a.mkv"), nowMs = 4L)!!

        val moved = store.moveMovies(listOf(source), to)

        assertEquals(1, moved)
        assertEquals(setOf(source, existing), store.snapshot.value.moviesIn(to).map { it.id }.toSet())
    }

    @Test
    fun `批量移动到原地不算移动且不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val category = store.createCategory("纪录片", nowMs = 1L)!!
        val id = store.addMovie(category, "content://media/1", signature("a.mkv"), nowMs = 2L)!!
        val before = storage.writes

        val moved = store.moveMovies(listOf(id), category)

        assertEquals(0, moved)
        assertEquals(before, storage.writes)
        assertEquals(1, store.snapshot.value.moviesIn(category).size)
    }

    // ── 已看完的手动覆盖（§16.17）───────────────────────────────────────────────

    @Test
    fun `已看完三态可往返且同值不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        val category = store.createCategory("纪录片", nowMs = 1L)!!
        val id = store.addMovie(category, "content://media/1", signature("a.mkv"), nowMs = 2L)!!
        fun override() = store.snapshot.value.movies.single { it.id == id }.finishedOverride

        assertEquals(null, override())

        store.setFinished(id, finished = false)
        assertEquals(false, override())

        val before = storage.writes
        store.setFinished(id, finished = false)
        assertEquals(before, storage.writes)

        store.setFinished(id, finished = null)
        assertEquals(null, override())

        store.setFinished(id, finished = true)
        assertEquals(true, override())
    }

    @Test
    fun `设置已看完时 id 不存在返回 false`() {
        val store = MovieLibraryStore(FakeStorage())

        assertFalse(store.setFinished("不存在", finished = false))
    }
}
