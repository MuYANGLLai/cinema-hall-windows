package com.cinemahall.core.library.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * HTTP 那一层：请求形状（路径、查询串、鉴权头、请求体）与**错误码映射**。
 *
 * 用真的 `MockWebServer` 而不是 mock OkHttp：要验的正是「发出去的字节长什么样」，
 * 而契约里服务端对 `since` / `limit` / `Authorization` 的判据都在字节上。
 */
class HttpLibrarySyncApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpLibrarySyncApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpLibrarySyncApi(
            baseUrl = server.url("/").toString(),
            timeoutMs = 5_000,
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `拉取带 since 与 limit 和 Bearer 头`() = runBlocking {
        server.enqueue(
            json(
                """
                {"cursor":42,"hasMore":true,
                 "categories":[{"id":"c1","name":"纪录片","kind":null,"createdAtMs":1,"updatedAtMs":2,"deletedAtMs":null,"rev":7}],
                 "movies":[],"progress":[]}
                """.trimIndent(),
            ),
        )

        val page = api.pull(token = "tok-abc", since = 12L, limit = 200)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/library?since=12&limit=200", request.path)
        assertEquals("Bearer tok-abc", request.getHeader("Authorization"))
        assertEquals(42L, page.cursor)
        assertTrue(page.hasMore)
        assertEquals("纪录片", page.categories[0].name)
        assertEquals(7L, page.categories[0].rev)
    }

    @Test
    fun `上报把三张表原样放进请求体且省略 null 字段`() = runBlocking {
        server.enqueue(json("""{"applied":2,"skipped":0,"tombstoned":0,"cursor":88}"""))

        val result = api.push(
            token = "tok-abc",
            batch = ChangesBatch(
                movies = listOf(
                    MovieChange(
                        sigKey = "a".repeat(32),
                        displayName = "片子.mkv",
                        categoryId = null,
                        titleOverride = null,
                        finishedOverride = null,
                        addedAtMs = 10L,
                        updatedAtMs = 20L,
                        deletedAtMs = null,
                    ),
                ),
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/library/changes", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"sigKey\":\"${"a".repeat(32)}\""))
        assertTrue(body, body.contains("\"displayName\":\"片子.mkv\""))
        assertTrue(
            "deletedAtMs 为 null 时不该出现在请求体里（服务端把「缺失」与 null 当同一件事）",
            !body.contains("deletedAtMs"),
        )
        assertEquals(88L, result.cursor)
        assertEquals(2, result.applied)
    }

    @Test
    fun `设备被移除按鉴权失败上报`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"DEVICE_REVOKED","message":"这台设备已被移除"}}""", code = 401))

        try {
            api.pull("tok-abc", 0L, 200)
            fail("401 必须抛异常")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncErrors.DEVICE_REVOKED, e.code)
            assertEquals("这台设备已被移除", e.message)
            assertTrue(e.isAuthFailure)
            assertFalse("鉴权失败不该被后台无限重试", e.retryable)
        }
    }

    @Test
    fun `限流是可重试的`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"TOO_MANY_REQUESTS","message":"同步请求过于频繁"}}""", code = 429))

        try {
            api.push("tok-abc", ChangesBatch(movies = listOf(MovieChange("b".repeat(32), "x", addedAtMs = 1L, updatedAtMs = 1L))))
            fail("429 必须抛异常")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncErrors.TOO_MANY_REQUESTS, e.code)
            assertTrue(e.retryable)
            assertFalse(e.isAuthFailure)
        }
    }

    @Test
    fun `配额超限要把服务端的理由原样带出来`() = runBlocking {
        server.enqueue(
            json(
                """{"error":{"code":"LIBRARY_FULL","message":"影片数超过上限（5000），本批已整体回滚"}}""",
                code = 400,
            ),
        )

        try {
            api.push("tok-abc", ChangesBatch(progress = listOf(ProgressChange("c".repeat(32), 1L, 2L, 1L))))
            fail("LIBRARY_FULL 必须抛异常")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncErrors.LIBRARY_FULL, e.code)
            assertTrue("要用户去清库，不是重试能解决的", !e.retryable)
            assertTrue(e.message!!.contains("5000"))
        }
    }

    @Test
    fun `反代返回的 HTML 错误页也要收敛成可重试的服务端错误`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))

        try {
            api.pull("tok-abc", 0L, 200)
            fail("502 必须抛异常")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncErrors.SERVER, e.code)
            assertTrue(e.retryable)
            assertFalse("别把 HTML 怼给用户", e.message!!.contains("<html>"))
        }
    }

    @Test
    fun `没有状态码字段的错误体会按状态码兜底`() = runBlocking {
        server.enqueue(json("""{"message":"怪东西"}""", code = 401))

        try {
            api.pull("tok-abc", 0L, 200)
            fail("401 必须抛异常")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncErrors.AUTH_INVALID, e.code)
            assertTrue(e.isAuthFailure)
        }
    }
}
