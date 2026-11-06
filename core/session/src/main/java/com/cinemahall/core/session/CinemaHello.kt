package com.cinemahall.core.session

import com.cinemahall.core.media.MediaSignature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `cinema.hello` 的载荷（《技术方案评审》§5.3）：`{mediaSignature, nickname}`。
 *
 * ── 它为什么叫 hello ────────────────────────────────────────────────────────
 * 它是 DataChannel 一打开就交换的第一条玩法消息：一方面带上自己的片源摘要，
 * 让对方立刻能做一致性校验；另一方面它本身就是「通道真的通了」的**端到端证据** ——
 * `PeerState.Connected` 只说明 DataChannel 进入 OPEN，而一条真正被解析出来的
 * `cinema.hello` 才说明两侧的玩法代码都在正常工作。
 */
@Serializable
data class CinemaHello(
    val mediaSignature: MediaSignature,
    val nickname: String,
) {
    companion object {
        /** 剥离前缀后的动作名，[com.cinemahall.core.protocol.PlayModeHandler.onMessage] 收到的形态。 */
        const val ACTION = "hello"

        /** 完整 type。日志、诊断与单测都用它，避免各处手写字符串。 */
        const val TYPE = "cinema.hello"
    }
}

object CinemaHelloCodec {

    /** 与 [com.cinemahall.core.media.MediaSignatureCodec] 同一取向：未知字段忽略，坏消息不抛。 */
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(hello: CinemaHello): JsonObject =
        json.encodeToJsonElement(CinemaHello.serializer(), hello).jsonObject

    /** 解析失败返回 null —— 对端发来一条坏消息不该打断本机的接收链路（§4.2 的同一条原则）。 */
    fun decode(payload: JsonObject?): CinemaHello? {
        if (payload == null) return null
        return try {
            json.decodeFromJsonElement(CinemaHello.serializer(), payload)
        } catch (_: Exception) {
            null
        }
    }
}