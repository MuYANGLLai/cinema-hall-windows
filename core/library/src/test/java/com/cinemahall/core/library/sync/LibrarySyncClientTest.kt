package com.cinemahall.core.library.sync

import com.cinemahall.core.library.LibraryScope
import com.cinemahall.core.library.LibraryStorage
import com.cinemahall.core.library.MovieLibraryStore
import com.cinemahall.core.media.MediaSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 编排层（1b）：推→拉的顺序、分批、游标纪律、错误传播、进度节流。
 *
 * 这里**不 mock OkHttp**，而是实现一份 `LibrarySyncApi` 假实现 —— 要验的是「什么时候
 * 发什么请求」，而不是「HTTP 怎么拼」。请求形状由 `HttpLibrarySyncApiTest` 单独钉。
 */
class LibrarySyncClientTest {

    private class FakeStorage : LibraryStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeApi : LibrarySyncApi {

        val pulls = mutableListOf<Pair<Long, Int>>()
        val pushes = mutableListOf<ChangesBatch>()
        var pages: List<RemotePage> = emptyList()

        /** 上报回执里那个**账号级**游标。故意给一个大值，用来验证「不拿它当读游标」。 */
        var pushCursor: Long = 0L

        var failure: LibrarySyncException? = null

        override suspend fun pull(token: String, since: Long, limit: Int): RemotePage {
            failure?.let { throw it }
            pulls += since to limit
            return pages.getOrElse(pulls.size - 1) { RemotePage(cursor = since) }
        }

        override suspend fun push(token: String, batch: ChangesBatch): ChangesResult {
            failure?.let { throw it }
            pushes += batch
            return ChangesResult(applied = batch.size, cursor = pushCursor)
        }
    }

    private fun signature(fileName: String) = MediaSignature(
        fileName = fileName,
        sizeBytes = 1024L,
        blocks = listOf("aa"),
    )

    private class Fixture(
        val storage: FakeStorage = FakeStorage(),
        val api: FakeApi = FakeApi(),
        val config: SyncConfig = SyncConfig(),
    ) {
        var clock: Long = 0L
        val store = MovieLibraryStore(storage)
        val states = SyncStateStore(storage, LibraryScope.LOCAL)
        val client = LibrarySyncClient(
            api = api,
            store = store,
            states = states,
            token = { "tok-abc" },
            nowMs = { clock },
            config = config,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test
    fun `一轮同步先推后拉并停在最后一页`() = runBlocking {
        val f = Fixture()
        val categoryId = f.store.createCategory("纪录片", nowMs = 1L)!!
        f.store.addMovie(categoryId, "content://demo/a.mkv", signature("a.mkv"), nowMs = 2L)
        f.api.pages = listOf(
            RemotePage(cursor = 5L, hasMore = true),
            RemotePage(cursor = 9L, hasMore = false),
        )

        val report = f.client.sync()

        assertEquals("两页都拉完", 2, f.api.pulls.size)
        assertEquals("第一次从 0 开始", 0L, f.api.pulls[0].first)
        assertEquals("第二次接着上一个游标", 5L, f.api.pulls[1].first)
        assertEquals(9L, report.cursor)
        assertEquals(0, report.pending)
        assertEquals(1, f.api.pushes.size)
        assertEquals(1, f.api.pushes[0].categories.size)
        assertEquals(1, f.api.pushes[0].movies.size)
    }

    @Test
    fun `上报回执里的账号级游标绝不当读游标`() = runBlocking {
        val f = Fixture()
        f.store.createCategory("纪录片", nowMs = 1L)
        f.api.pushCursor = 999L
        f.api.pages = listOf(RemotePage(cursor = 7L))

        f.client.sync()

        assertEquals(
            "拿 pushCursor 当游标会跳过别的设备刚写入、本地还没读到的行",
            7L,
            f.states.load().cursor,
        )
        assertEquals("拉取仍然从真实的读游标开始", 0L, f.api.pulls[0].first)
    }

    @Test
    fun `超过单批上限时自动分批`() = runBlocking {
        val f = Fixture(config = SyncConfig(maxBatch = 2))
        val categoryId = f.store.createCategory("纪录片", nowMs = 1L)!!
        repeat(3) { index ->
            f.store.addMovie(categoryId, "content://demo/$index.mkv", signature("$index.mkv"), nowMs = 2L)
        }

        val report = f.client.sync()

        // 1 个分类 + 3 部影片 = 4 条，每批最多 2 条 ⇒ 2 批
        assertEquals(2, f.api.pushes.size)
        assertEquals(2, f.api.pushes[0].size)
        assertEquals(2, f.api.pushes[1].size)
        assertEquals(4, report.pushed)
    }

    @Test
    fun `没有 token 时不发请求`() = runBlocking {
        val f = Fixture()
        val client = LibrarySyncClient(
            api = f.api,
            store = f.store,
            states = f.states,
            token = { null },
        )

        try {
            client.sync()
            fail("没激活就该立刻失败")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncErrors.NOT_CONNECTED, e.code)
            assertTrue("这不是「后台可重试」的失败，是还没登录", !e.retryable)
        }
        assertTrue(f.api.pulls.isEmpty())
        assertTrue(f.api.pushes.isEmpty())
    }

    @Test
    fun `推送失败时账本保持原样以便下一轮重推`() = runBlocking {
        val f = Fixture()
        f.store.createCategory("纪录片", nowMs = 1L)
        f.api.failure = LibrarySyncException(LibrarySyncErrors.NETWORK, "断网")

        try {
            f.client.sync()
            fail("应该把异常交给调用方")
        } catch (e: LibrarySyncException) {
            assertTrue("网络错误留给调用方决定何时重试", e.retryable)
        }

        val state = f.states.load()
        assertTrue("基线没写进去，下一轮还会重推", state.categories.isEmpty())
        assertEquals(0L, state.cursor)
    }

    @Test
    fun `鉴权失败要能被上层识别出来`() = runBlocking {
        val f = Fixture()
        f.api.failure = LibrarySyncException(LibrarySyncErrors.DEVICE_REVOKED, "这台设备已被移除")

        try {
            f.client.sync()
            fail("应该抛出去")
        } catch (e: LibrarySyncException) {
            assertTrue(e.isAuthFailure)
            assertTrue(
                "鉴权失败不能当网络抖动一直重试 —— 用户要去重新激活",
                !e.retryable,
            )
        }
    }

    @Test
    fun `待推送计数在同步后归零`() = runBlocking {
        val f = Fixture()
        f.store.createCategory("纪录片", nowMs = 1L)
        assertEquals(1, f.client.pendingCount())

        f.client.sync()

        assertEquals(0, f.client.pendingCount())
        assertEquals("基线里已经有那个分类", 1, f.states.load().categories.size)
    }

    @Test
    fun `播放中两次心跳只推一次`() = runBlocking {
        val f = Fixture()
        f.clock = ProgressThrottle.PLAYING_INTERVAL_MS
        val signature = signature("a.mkv")

        f.client.reportProgress(signature, 6_000L, 600_000L, ProgressTrigger.PLAYING)
        f.clock += 1_000L
        f.client.reportProgress(signature, 7_000L, 600_000L, ProgressTrigger.PLAYING)

        assertEquals("第二次没到 5 分钟，不该再发", 1, f.api.pushes.size)
        assertEquals(7_000L, f.store.progressOf(signature)?.positionMs)
    }

    @Test
    fun `暂停时立刻推一次`() = runBlocking {
        val f = Fixture()
        f.clock = ProgressThrottle.PLAYING_INTERVAL_MS
        val signature = signature("a.mkv")
        f.client.reportProgress(signature, 6_000L, 600_000L, ProgressTrigger.PLAYING)
        assertEquals(1, f.api.pushes.size)

        f.clock += 1_000L
        f.client.reportProgress(signature, 42_000L, 600_000L, ProgressTrigger.PAUSED)

        assertEquals("暂停不受 5 分钟限制", 2, f.api.pushes.size)
        assertEquals(42_000L, f.store.progressOf(signature)?.positionMs)
    }

    @Test
    fun `进度上报失败不打扰播放`() = runBlocking {
        val f = Fixture()
        f.clock = ProgressThrottle.PLAYING_INTERVAL_MS
        f.api.failure = LibrarySyncException(LibrarySyncErrors.NETWORK, "断网")
        val signature = signature("a.mkv")

        // 不抛异常就是这条用例的判据：进度上报是后台动作，播放不该因此中断
        f.client.reportProgress(signature, 6_000L, 600_000L, ProgressTrigger.PLAYING)

        assertEquals("本地照样记下了", 6_000L, f.store.progressOf(signature)?.positionMs)
    }

    @Test
    fun `分批按总量切且不丢条目`() {
        val batch = ChangesBatch(
            categories = listOf(CategoryChange("c1", "一", createdAtMs = 1L, updatedAtMs = 1L)),
            movies = listOf(
                MovieChange("m1", "甲", addedAtMs = 1L, updatedAtMs = 1L),
                MovieChange("m2", "乙", addedAtMs = 1L, updatedAtMs = 1L),
            ),
            progress = listOf(ProgressChange("m1", 1L, 2L, updatedAtMs = 1L)),
        )

        val chunks = batch.chunked(2)

        assertEquals(2, chunks.size)
        assertEquals(4, chunks.sumOf { it.size })
        assertEquals(listOf("c1"), chunks[0].categories.map { it.id })
        assertEquals(listOf("m1"), chunks[0].movies.map { it.sigKey })
        assertEquals(listOf("m2"), chunks[1].movies.map { it.sigKey })
        assertEquals(1, chunks[1].progress.size)
    }

    @Test
    fun `没有待推送时 sync 不该发任何 push`() = runBlocking {
        val f = Fixture()
        f.client.sync() // 第一次：空库、空账本 → 什么都不推
        val before = f.api.pushes.size

        f.client.sync()

        assertEquals(before, f.api.pushes.size)
        assertNull(f.states.load().movies["nothing"])
    }
}
