package com.cinemahall.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * [AccountApi] 的 HTTP 实现（契约 §6.4）。
 *
 * ── 为什么用同步 `execute()` 包 `withContext(IO)` 而不是 OkHttp 的 `enqueue` ────
 * 接口是 `suspend` 的；同步调用 + `withContext` 得到的取消语义最直白：
 * 协程被取消 → 线程中断 → OkHttp 抛 `InterruptedIOException`。用 `enqueue` 还要
 * 额外把回调桥成 `suspendCancellableCoroutine` 并处理「回调晚到」。
 *
 * ── 3 秒只给 `me` ────────────────────────────────────────────────────────────
 * 启动流程卡在「校验 token」上，用户看到的是启动白屏；3 秒足够判定「服务器不可达」，
 * 而激活/恢复是用户主动按了按钮的动作，等 15 秒不会让人以为应用挂了。
 * 这两个值都写成常量，测试用同一个来源，不会出现「测试里是 1 秒、线上是 3 秒」。
 */
class HttpAccountApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** 启动校验 token 的超时。做成参数是为了单测不必真等 3 秒。 */
    private val meTimeoutMs: Long = ME_TIMEOUT_MS,
    /** 其余接口的超时。 */
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : AccountApi {

    private val base = baseUrl.trimEnd('/')

    override suspend fun activate(
        code: String,
        deviceName: String,
        platform: String,
        appVersion: String,
        nickname: String?,
    ): AccountSession {
        val body = ActivateBody(
            code = code,
            deviceName = deviceName,
            platform = platform,
            appVersion = appVersion,
            nickname = nickname,
        )
        return postJson("/api/activate", json.encodeToString(body), defaultTimeoutMs, token = null)
    }

    override suspend fun recover(
        code: String,
        deviceName: String,
        platform: String,
        appVersion: String,
    ): AccountSession {
        // 刻意不带 nickname：契约 v1.2 的 recover 忽略它，带上只会让人以为它能改名。
        val body = RecoverBody(
            code = code,
            deviceName = deviceName,
            platform = platform,
            appVersion = appVersion,
        )
        return postJson("/api/recover", json.encodeToString(body), defaultTimeoutMs, token = null)
    }

    override suspend fun me(token: String): AccountProfile =
        getJson("/api/me", meTimeoutMs, token)

    override suspend fun updateDisplayName(token: String, displayName: String): AccountProfile =
        sendJson(
            path = "/api/me",
            method = "PATCH",
            body = json.encodeToString(DisplayNameBody(displayName)),
            timeoutMs = defaultTimeoutMs,
            token = token,
        )

    override suspend fun revokeDevice(token: String, deviceId: String) {
        // 服务端回 204（无 body），所以这里不复用会反序列化的两个入口。
        execute(
            request = authorized(path = "/api/devices/$deviceId", token = token)
                .delete()
                .build(),
            timeoutMs = defaultTimeoutMs,
        )
    }

    override suspend fun createRecoveryCode(token: String): String {
        val body = execute(
            request = authorized(path = "/api/codes/recovery", token = token)
                .post(EMPTY_JSON.toRequestBody(JSON_MEDIA))
                .build(),
            timeoutMs = defaultTimeoutMs,
        )
        val root = json.parseToJsonElement(body).jsonObject
        return root["code"]?.jsonPrimitive?.content
            ?: throw AccountApiException("BAD_RESPONSE", "服务端没有返回恢复码", 200)
    }

    override suspend fun logout(token: String) {
        execute(
            request = authorized(path = "/api/logout", token = token)
                .post(EMPTY_JSON.toRequestBody(JSON_MEDIA))
                .build(),
            timeoutMs = defaultTimeoutMs,
        )
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(
        path: String,
        timeoutMs: Long,
        token: String?,
    ): T = json.decodeFromString(
        execute(
            request = authorized(path, token).get().build(),
            timeoutMs = timeoutMs,
        ),
    )

    private suspend inline fun <reified T> postJson(
        path: String,
        body: String,
        timeoutMs: Long,
        token: String?,
    ): T = json.decodeFromString(
        execute(
            request = authorized(path, token)
                .post(body.toRequestBody(JSON_MEDIA))
                .build(),
            timeoutMs = timeoutMs,
        ),
    )

    private suspend inline fun <reified T> sendJson(
        path: String,
        method: String,
        body: String,
        timeoutMs: Long,
        token: String?,
    ): T = json.decodeFromString(
        execute(
            request = authorized(path, token)
                .method(method, body.toRequestBody(JSON_MEDIA))
                .build(),
            timeoutMs = timeoutMs,
        ),
    )

    private fun authorized(path: String, token: String?): Request.Builder {
        val builder = Request.Builder()
            .url(AccountEndpoint.url(base, path))
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    /**
     * 唯一发请求的地方。**所有**失败都在这里变成 [AccountApiException] —— 上层只认一种异常。
     */
    private suspend fun execute(request: Request, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            val callClient = if (timeoutMs > 0) {
                client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            } else {
                client
            }
            try {
                callClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // body 一定要在 use 里读完：callTimeout 覆盖整次调用（含读 body），
                        // 把 string() 留在外面会让超时异常绕过下面这层映射。
                        response.body?.string().orEmpty()
                    } else {
                        val (code, message) = parseError(response)
                        throw AccountApiException(code, message, response.code)
                    }
                }
            } catch (e: AccountApiException) {
                throw e
            } catch (e: InterruptedIOException) {
                // 包含 SocketTimeoutException。取消协程也走这里 —— 两者对上层都是「没结果」。
                throw AccountApiException(
                    AccountApiException.TIMEOUT,
                    "服务器响应超时",
                    0,
                    e,
                )
            } catch (e: IOException) {
                throw AccountApiException(
                    AccountApiException.NETWORK,
                    "无法连接服务器",
                    0,
                    e,
                )
            }
        }

    /**
     * 服务端错误体：`{"error":{"code","message"}}`（契约 §6.4）。
     *
     * 解析不出来时**不要编一个错误码**：保留 HTTP 状态并给一句人话，
     * 界面至少能显示「服务器错误 500」。`code` 用 `HTTP_<status>` 也让日志可查。
     */
    private fun parseError(response: Response): Pair<String, String> {
        val fallbackCode = "HTTP_${response.code}"
        val fallbackMessage = "服务器错误（${response.code}）"
        val raw = try {
            response.body?.string().orEmpty()
        } catch (_: IOException) {
            return fallbackCode to fallbackMessage
        }
        if (raw.isBlank()) return fallbackCode to fallbackMessage
        return try {
            val error = json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.content ?: fallbackCode
            val message = error?.get("message")?.jsonPrimitive?.content ?: fallbackMessage
            code to message
        } catch (_: Exception) {
            fallbackCode to fallbackMessage
        }
    }

    @Serializable
    private data class ActivateBody(
        val code: String,
        val deviceName: String,
        val platform: String,
        val appVersion: String,
        val nickname: String? = null,
    )

    @Serializable
    private data class RecoverBody(
        val code: String,
        val deviceName: String,
        val platform: String,
        val appVersion: String,
    )

    @Serializable
    private data class DisplayNameBody(val displayName: String)

    companion object {
        /** 启动校验 token 的超时（用户口径：3 秒）。 */
        const val ME_TIMEOUT_MS = 3_000L

        /** 其余接口（激活 / 恢复 / 改名 / 设备 / 恢复码 / 登出）的超时。 */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val EMPTY_JSON = "{}"

        /** 连接超时比 callTimeout 短一档即可；读超时交给 callTimeout 统一管。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
