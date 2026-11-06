package com.cinemahall.core.library.sync

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 片单同步的 HTTP 实现（OkHttp + kotlinx.serialization）。
 *
 * ── 为什么和 `HttpAccountApi` 长得很像却各写一份 ──────────────────────────────
 * 两者都要做同一件事：发请求、把**所有**失败收敛成一个带码的异常。但它们的错误集
 * 不同（片单多一个 `LIBRARY_FULL`），重试语义不同（片单是后台自动跑的，必须区分
 * 「过会儿再试」与「别试了」），而且 `core:library` 不该反向依赖 `core:account` ——
 * 那样片单模块会把账号模块的整个 HTTP 栈一起拖进来，而片单在「未激活」时也要能用。
 *
 * ── 超时不设成无限 ──────────────────────────────────────────────────────────
 * 同步是后台动作，卡住的请求会一直占着那个协程。给 15 秒：一部 5000 条的全量拉取
 * 在局域网里是毫秒级，15 秒已经足够宽；超时后按 `TIMEOUT`（可重试）上报，
 * 下一轮会从同一个游标重来 —— 拉取是幂等的。
 */
class HttpLibrarySyncApi(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val json: Json = defaultJson(),
) : LibrarySyncApi {

    private val base: String = baseUrl.trimEnd('/')

    override suspend fun pull(token: String, since: Long, limit: Int): RemotePage =
        json.decodeFromString(
            RemotePage.serializer(),
            execute(
                request = authorized("/api/library?since=$since&limit=$limit", token).get().build(),
            ),
        )

    override suspend fun push(token: String, batch: ChangesBatch): ChangesResult =
        json.decodeFromString(
            ChangesResult.serializer(),
            execute(
                request = authorized("/api/library/changes", token)
                    .post(
                        json.encodeToString(ChangesBatch.serializer(), batch)
                            .toRequestBody(JSON_MEDIA),
                    )
                    .build(),
            ),
        )

    private fun authorized(path: String, token: String): Request.Builder = Request.Builder()
        .url(base + path)
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $token")

    /** 唯一发请求的地方：**所有**失败都在这里变成 [LibrarySyncException]。 */
    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val callClient = if (timeoutMs > 0) {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        } else {
            client
        }
        try {
            callClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // body 必须在 use 里读完：callTimeout 覆盖整次调用（含读 body）。
                    response.body?.string().orEmpty()
                } else {
                    throw parseError(response.code, response.body?.string().orEmpty())
                }
            }
        } catch (e: LibrarySyncException) {
            throw e
        } catch (e: InterruptedIOException) {
            // 含 SocketTimeoutException；取消协程也走这里，两者对上层都是「没结果」。
            throw LibrarySyncException(LibrarySyncErrors.TIMEOUT, "同步请求超时", e)
        } catch (e: IOException) {
            throw LibrarySyncException(LibrarySyncErrors.NETWORK, "无法连接服务器", e)
        } catch (e: Exception) {
            // 解码失败也算服务端问题：错误体/正常体不符合契约时，重试是有意义的
            // （服务端刚升级、或反代截断了响应）。
            throw LibrarySyncException(LibrarySyncErrors.SERVER, "同步响应无法解析：${e.message}", e)
        }
    }

    /**
     * 错误体是 `{"error":{"code","message"}}`（契约 Q1，与 WS 的 `sys.error` 同形）。
     *
     * 解不出来就按状态码兜底 —— 反代（Caddy）自己返回的 502/504 页面不是 JSON，
     * 而那种页面的 HTML 正文塞进用户可见的错误行里毫无意义。
     */
    private fun parseError(status: Int, body: String): LibrarySyncException {
        val parsed = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val error = root["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.content
            val message = error?.get("message")?.jsonPrimitive?.content
            code to message
        }.getOrNull()

        val code = parsed?.first?.takeIf { it.isNotBlank() } ?: LibrarySyncErrors.fromStatus(status)
        // 台账满了要把理由说全（服务端会给「超过上限（5000），本批已整体回滚」），
        // 其余情况用一句人话，别把状态码直接怼到用户面前。
        val message = parsed?.second?.takeIf { it.isNotBlank() }
            ?: when (code) {
                LibrarySyncErrors.AUTH_INVALID, LibrarySyncErrors.AUTH_REQUIRED -> "登录已失效，请重新激活"
                LibrarySyncErrors.DEVICE_REVOKED -> "这台设备已被移除"
                LibrarySyncErrors.ACCOUNT_SUSPENDED -> "账号已停用"
                LibrarySyncErrors.TOO_MANY_REQUESTS -> "同步太频繁，稍后自动重试"
                else -> "同步失败（HTTP $status）"
            }
        return LibrarySyncException(code, message)
    }

    companion object {
        /** 一次同步请求的超时（毫秒）。可注入，单测里压到很短。 */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 与 `HttpAccountApi` 同一套宽容度：服务端加字段不能让旧客户端解不开。
         * `explicitNulls = false` 让请求体里不出现 `"deletedAtMs": null` 这类噪音 ——
         * 服务端把 null 与「字段缺失」当同一件事。
         */
        internal fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}

/** 判断一个 JSON 对象里有没有 `error`：给单测与诊断用，避免各处重复写强转。 */
internal fun JsonObject.errorCodeOrNull(): String? =
    (this["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content
