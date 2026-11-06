package com.cinemahall.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 信封格式错误 —— 对方发来的东西不符合 §5.1 的信封定义。
 *
 * 单独一个异常类型，是为了让调用方能把它与「网络断了」区分开：
 * 前者要计数并继续跑，后者要退避重连。
 */
class MalformedEnvelopeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 信封的线格式编解码（《技术方案评审》§5.1）。
 *
 * 放在 `core:protocol` 而不是 `core:network`：§4.1 把「Envelope 编解码」划给了本模块，
 * 而且 WebRTC 信令（`core:rtc`）之后也要走同一套信封。
 */
object EnvelopeCodec {

    /**
     * 容错取向说明：
     * - `ignoreUnknownKeys = true`：**故意**开着。两端版本不可能永远同步，多出来的字段
     *   应当被忽略而不是让整条连接报错 —— 这与 §4.2「未知消息计数 + 丢弃，不抛异常」
     *   是同一条原则。
     * - `explicitNulls = false`：`roomId = null` 时干脆不写这个键，报文更短；
     *   解码时缺键与 null 一律得到 null。
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(envelope: Envelope): String =
        json.encodeToString(Envelope.serializer(), envelope)

    /**
     * 解码并做**最低限度的结构校验**。
     *
     * 为什么只校验这两项：服务端（`server/src/signaling.mjs`）只认 `type` 与 `from`，
     * 缺任一就回 `BAD_MESSAGE`。客户端做同样的检查，是为了把一个明显非法的报文
     * 在本地挡住并计入「协议错配」，而不是让它进到业务逻辑里再炸。
     */
    fun decode(text: String): Envelope {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (error: SerializationException) {
            throw MalformedEnvelopeException("JSON 不符合信封定义：${error.message}", error)
        }

        if (envelope.type.isBlank()) throw MalformedEnvelopeException("信封缺 type")
        if (envelope.from.isBlank()) throw MalformedEnvelopeException("信封缺 from")

        return envelope
    }
}
