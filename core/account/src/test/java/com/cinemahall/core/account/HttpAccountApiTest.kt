package com.cinemahall.core.account

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 真起一个本地 HTTP 服务，把**请求形状**逐条钉住（不 mock OkHttp）。
 *
 * 为什么不用 mock 框架：契约 §6.4 冻结的是「方法 + 路径 + body 字段名」，
 * 这正是 mock 最容易假装通过、真机上却 404/400 的部分。MockWebServer 会连
 * HTTP 方法、路径、Header 一起校验，错一个字就是红。
 */
class HttpAccountApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpAccountApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpAccountApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            meTimeoutMs = 1_000,
            defaultTimeoutMs = 1_000,
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── activate / recover ─────────────────────────────────────────────────

    @Test
    fun `activate 发 POST 到 api activate 且带齐四个字段与昵称`() = runTest {
        server.enqueue(jsonResponse(SESSION_JSON))

        val session = api.activate(
            code = "ABCD2345",
            deviceName = "Xiaomi Pad",
            platform = "android",
            appVersion = "0.1.31",
            nickname = "张三",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/activate", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("ABCD2345", body["code"]?.jsonPrimitive?.content)
        assertEquals("Xiaomi Pad", body["deviceName"]?.jsonPrimitive?.content)
        assertEquals("android", body["platform"]?.jsonPrimitive?.content)
        assertEquals("0.1.31", body["appVersion"]?.jsonPrimitive?.content)
        assertEquals("张三", body["nickname"]?.jsonPrimitive?.content)
        assertEquals("ac_1", session.accountId)
        assertEquals("dv_1", session.deviceId)
    }

    @Test
    fun `activate 不给昵称时请求体里没有 nickname`() = runTest {
        server.enqueue(jsonResponse(SESSION_JSON))

        api.activate("ABCD2345", "Pad", "android", "0.1.31", nickname = null)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse("不给昵称就不该出现这个字段", body.containsKey("nickname"))
    }

    @Test
    fun `recover 发 POST 到 api recover 且不带 nickname`() = runTest {
        server.enqueue(jsonResponse(SESSION_JSON))

        api.recover("ABCDEFGHJKMN", "Pad", "android", "0.1.31")

        val request = server.takeRequest()
        assertEquals("/api/recover", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("ABCDEFGHJKMN", body["code"]?.jsonPrimitive?.content)
        assertFalse("契约 v1.2：recover 刻意不收 nickname", body.containsKey("nickname"))
    }

    // ── me ─────────────────────────────────────────────────────────────────

    @Test
    fun `me 带 Bearer 并解析设备列表`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {"accountId":"ac_1","displayName":"张三","devices":[
                  {"deviceId":"dv_1","name":"Pad","platform":"android","lastSeenAt":123,"current":true},
                  {"deviceId":"dv_2","name":"Phone","platform":"windows","lastSeenAt":null,"current":false}
                ]}
                """.trimIndent(),
            ),
        )

        val profile = api.me("tok-abc")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/me", request.path)
        assertEquals("Bearer tok-abc", request.getHeader("Authorization"))
        assertEquals("张三", profile.displayName)
        assertEquals(2, profile.devices.size)
        assertTrue(profile.devices[0].current)
        assertEquals(null, profile.devices[1].lastSeenAt)
    }

    @Test
    fun `四零一要带出服务端错误码`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"AUTH_INVALID","message":"token 无效"}}"""),
        )

        val error = apiError { api.me("bad") }

        assertEquals(401, error.httpStatus)
        assertEquals(AccountErrorCodes.AUTH_INVALID, error.code)
        assertTrue(error.isAuthFailure)
    }

    @Test
    fun `设备被撤销与账号停用都能单独认出来`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"DEVICE_REVOKED","message":"这台设备已被移除"}}"""),
        )
        val revoked = apiError { api.me("bad") }
        assertEquals(AccountErrorCodes.DEVICE_REVOKED, revoked.code)
        assertTrue(revoked.isAuthFailure)

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"ACCOUNT_SUSPENDED","message":"账号已停用"}}"""),
        )
        val suspended = apiError { api.me("bad") }
        assertEquals(AccountErrorCodes.ACCOUNT_SUSPENDED, suspended.code)
    }

    @Test
    fun `非 JSON 的错误体也能给出可读的码与状态`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))

        val error = apiError { api.me("bad") }

        assertEquals(502, error.httpStatus)
        assertEquals("HTTP_502", error.code)
    }

    @Test
    fun `空错误体不会抛第二次异常`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = apiError { api.me("bad") }

        assertEquals("HTTP_500", error.code)
    }

    // ── 其余接口 ───────────────────────────────────────────────────────────

    @Test
    fun `改名走 PATCH 且 body 只有 displayName`() = runTest {
        server.enqueue(jsonResponse(PROFILE_JSON))

        val profile = api.updateDisplayName("tok", "李四")

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/me", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("displayName"), body.keys)
        assertEquals("李四", body["displayName"]?.jsonPrimitive?.content)
        assertEquals("李四", profile.displayName)
    }

    @Test
    fun `撤销设备走 DELETE 且接受 204 无 body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.revokeDevice("tok", "dv_2")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/devices/dv_2", request.path)
    }

    @Test
    fun `生成恢复码解析出 code`() = runTest {
        server.enqueue(jsonResponse("""{"code":"ABCD-EFGH-JKMN"}"""))

        val code = api.createRecoveryCode("tok")

        assertEquals("POST", server.takeRequest().method)
        assertEquals("ABCD-EFGH-JKMN", code)
    }

    @Test
    fun `服务端没给恢复码时报错而不是返回空串`() = runTest {
        server.enqueue(jsonResponse("{}"))

        val error = apiError { api.createRecoveryCode("tok") }

        assertEquals("BAD_RESPONSE", error.code)
    }

    @Test
    fun `登出走 POST 到 api logout 并带 Bearer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.logout("tok")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/logout", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    // ── 传输层失败 ─────────────────────────────────────────────────────────

    @Test
    fun `连不上时映射成 NETWORK`() = runTest {
        server.shutdown()

        val error = apiError { api.me("tok") }

        assertEquals(AccountApiException.NETWORK, error.code)
        assertEquals(0, error.httpStatus)
        assertTrue(error.isTransportFailure)
        assertFalse(error.isAuthFailure)
    }

    @Test
    fun `超时映射成 TIMEOUT 而不是 NETWORK`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("{}")
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )
        val shortApi = HttpAccountApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            meTimeoutMs = 100,
            defaultTimeoutMs = 100,
        )

        val error = apiError { shortApi.me("tok") }

        assertEquals(AccountApiException.TIMEOUT, error.code)
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /** 断言抛 [AccountApiException] 并把它取出来（`assertThrows` 装不下 suspend 调用）。 */
    private suspend fun apiError(block: suspend () -> Unit): AccountApiException {
        try {
            block()
        } catch (e: AccountApiException) {
            return e
        }
        throw AssertionError("期望 AccountApiException，但没有抛出")
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private companion object {
        const val SESSION_JSON =
            """{"accountId":"ac_1","displayName":"张三","deviceId":"dv_1","token":"tok","expiresAt":1700000000000}"""

        const val PROFILE_JSON =
            """{"accountId":"ac_1","displayName":"李四","devices":[]}"""
    }
}
