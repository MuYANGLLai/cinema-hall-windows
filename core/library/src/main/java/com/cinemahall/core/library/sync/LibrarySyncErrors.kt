package com.cinemahall.core.library.sync

/**
 * 同步失败的唯一异常类型（与 `AccountApiException` 同形：上层只认一种）。
 *
 * ── 为什么要分 [retryable] ────────────────────────────────────────────────────
 * 同步是**后台自动跑**的，没有人盯着它点重试。所以「这次失败该不该过一会儿再试」
 * 必须由异常自己说清楚，否则调用方会把「账号被停用」也当成网络抖动一路重试，
 * 或者反过来把一次 429 当成永久失败、从此不再同步。
 *
 * 判据只有两类：
 *  - **可重试**：网络、超时、限流、5xx。过一会儿再试大概率就好；
 *  - **不可重试**：鉴权类（要重新激活）、`LIBRARY_FULL`（要用户清库）、
 *    `BAD_PAYLOAD`（客户端自己的 bug，重试一万次也一样）。
 *
 * [code] 用字符串而不是枚举：服务端会加错误码，而客户端旧版本收到一个不认识的码时
 * 应当**照常显示 message**，而不是整个崩在 `when` 上。未知码按 [LibrarySyncErrors.SERVER] 归类。
 */
class LibrarySyncException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val retryable: Boolean get() = LibrarySyncErrors.isRetryable(code)

    /** 鉴权失效：调用方据此把用户带回激活页（与 `AccountApiException.isAuthFailure` 同一口径）。 */
    val isAuthFailure: Boolean get() = LibrarySyncErrors.isAuthFailure(code)

    override fun toString(): String = "LibrarySyncException($code): $message"
}

/**
 * 错误码表。前半段与服务端 `ACCOUNT_ERRORS` 一一对应，后半段是**客户端侧**的码。
 *
 * 客户端侧的码必须自己造（服务端不会发）：`NETWORK` / `TIMEOUT` / `NOT_CONNECTED`。
 * 用同一套常量而不是散落的字符串字面量，是因为这些值会进诊断面版与日志，
 * 拼错一个字母就变成两个「不同」的故障，排查时最费时间。
 */
object LibrarySyncErrors {

    /** 连不上（DNS、拒绝连接、断网）。 */
    const val NETWORK = "NETWORK"

    /** 连上了但没等到结果（含 OkHttp 的 callTimeout）。 */
    const val TIMEOUT = "TIMEOUT"

    /** 本机还没有 token（未激活 / 已退出）。**不是错误**，只是不该发请求。 */
    const val NOT_CONNECTED = "NOT_CONNECTED"

    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val AUTH_INVALID = "AUTH_INVALID"
    const val DEVICE_REVOKED = "DEVICE_REVOKED"
    const val ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED"
    const val LIBRARY_FULL = "LIBRARY_FULL"
    const val TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS"
    const val BAD_PAYLOAD = "BAD_PAYLOAD"

    /** 5xx 或错误体里出现了不认识的码。 */
    const val SERVER = "SERVER"

    private val AUTH_FAILURES = setOf(
        AUTH_REQUIRED,
        AUTH_INVALID,
        DEVICE_REVOKED,
        ACCOUNT_SUSPENDED,
    )

    private val RETRYABLE = setOf(NETWORK, TIMEOUT, TOO_MANY_REQUESTS, SERVER)

    fun isAuthFailure(code: String): Boolean = code in AUTH_FAILURES

    fun isRetryable(code: String): Boolean = code in RETRYABLE

    /**
     * HTTP 状态码 → 客户端码（错误体里没给可用的 `code` 时的兜底）。
     *
     * 401/403 一律算鉴权失败：服务端在这两个状态上只会回鉴权那四个码，
     * 而**「分不清是哪种鉴权失败」时把用户留在原地**比「当成网络抖动无限重试」更糟。
     */
    fun fromStatus(status: Int): String = when (status) {
        401, 403 -> AUTH_INVALID
        413, 422 -> BAD_PAYLOAD
        429 -> TOO_MANY_REQUESTS
        else -> SERVER
    }
}
