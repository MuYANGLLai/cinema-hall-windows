package com.cinemahall.core.network

/**
 * 信令层错误码。
 *
 * `CLIENT_*` 是本地产生的；其余**逐字对应**服务端 `ERROR_CODES`
 * （`server/src/signaling.mjs`）。别在这里自造服务端码 —— 对不上就会在
 * 「服务端说有错、客户端说不知道」时白耗半天。服务端那边加码时，
 * 未知码会被客户端如实透传，不会因为这里没列就丢掉。
 */
object SignalErrors {

    // ── 本地 ──────────────────────────────────────────────────────────────
    /** 未连接 / 连接未就绪时调用了需要连接的方法。 */
    const val CLIENT_NOT_CONNECTED = "CLIENT_NOT_CONNECTED"

    /** 等回复超时（默认 10s）。 */
    const val CLIENT_TIMEOUT = "CLIENT_TIMEOUT"

    /** 收到的东西不符合信封定义。 */
    const val CLIENT_MALFORMED = "CLIENT_MALFORMED"

    /** 地址不合法，连都没连。 */
    const val CLIENT_BAD_ENDPOINT = "CLIENT_BAD_ENDPOINT"

    /**
     * 指定了房号建房，但回执的房号**不是**请求值。
     *
     * 这是「新客户端 → 旧服务端」的**防静默降级**信号：旧 `handleCreateRoom` 只读
     * `nickname`/`password`，多余的 `payload.roomId` 会被忽略并回一个随机号。协议没有
     * 版本号，所以这条校验是新旧共存期唯一的保护 —— 不校验的话用户会以为自己拿到了
     * 想要的号，实际进了另一个房间（《后端改动说明-自定义房间号》§9.2）。
     */
    const val CLIENT_ROOM_ID_MISMATCH = "CLIENT_ROOM_ID_MISMATCH"

    /** 本地逻辑不应到达的分支。 */
    const val CLIENT_INTERNAL = "CLIENT_INTERNAL"

    // ── 服务端（server/src/signaling.mjs 的 ERROR_CODES）───────────────────
    const val BAD_MESSAGE = "BAD_MESSAGE"
    const val HTTPS_REQUIRED = "HTTPS_REQUIRED"
    const val IDENTITY_CHANGED = "IDENTITY_CHANGED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val CREATE_RATE_LIMITED = "CREATE_RATE_LIMITED"
    const val JOIN_BACKOFF = "JOIN_BACKOFF"
    const val ALREADY_IN_ROOM = "ALREADY_IN_ROOM"
    const val NOT_IN_ROOM = "NOT_IN_ROOM"
    /**
     * 指定房号建房时该号已被占用。服务端**不重试、不换号** —— 调用方要提示用户换一个，
     * 而不是把失败当成「网络不好」重试（重试只会一直撞同一个号）。
     */
    const val ROOM_CODE_TAKEN = "ROOM_CODE_TAKEN"
    const val ROOM_NOT_FOUND = "ROOM_NOT_FOUND"
    const val ROOM_FULL = "ROOM_FULL"
    const val BAD_PASSWORD = "BAD_PASSWORD"
    const val RESUME_FAILED = "RESUME_FAILED"
    const val PEER_OFFLINE = "PEER_OFFLINE"
    const val UNKNOWN_TYPE = "UNKNOWN_TYPE"
    const val BAD_PAYLOAD = "BAD_PAYLOAD"
    const val INTERNAL = "INTERNAL"
}

/**
 * 信令层的可预期失败。
 *
 * 用它而不是 `IllegalStateException`：调用方需要按 [code] 分流（例如
 * `ROOM_NOT_FOUND` 提示「房间号不对」、`CLIENT_NOT_CONNECTED` 提示「正在重连」），
 * 而不是把一段栈丢给用户看。
 */
class SignalException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
