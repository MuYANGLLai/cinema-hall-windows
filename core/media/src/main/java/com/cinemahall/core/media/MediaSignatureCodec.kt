package com.cinemahall.core.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * [MediaSignature] 与 `{fileName, size, blocks[]}` 这个 JSON 对象之间的编解码（§7）。
 *
 * 为什么它住在 `core:media` 而不是 `core:protocol`：这个对象的形状是**媒体摘要**
 * 的私有约定，属于玩法域载荷。§4.2 的接缝原则是底座不解析玩法载荷 —— 只有外层
 * [com.cinemahall.core.protocol.Envelope] 的编解码才归 `core:protocol`。
 * 放在这里，`cinema.hello` 的发送与接收共用同一个 `MediaSignature.serializer()`，
 * 键名不可能两边写岔。
 */
object MediaSignatureCodec {

    /**
     * `ignoreUnknownKeys`：对端比本机新时，多出来的字段应该被忽略而不是让整条
     * 校验流程失败。校验的失败原因有且只有三种（§7），不该冒出第四种。
     */
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(signature: MediaSignature): JsonObject =
        json.encodeToJsonElement(MediaSignature.serializer(), signature).jsonObject

    /**
     * 解析失败返回 null，**不抛异常**。
     *
     * 这与 §4.2「未知消息：计数 + 丢弃，不抛异常」是同一条原则：对端发来一条
     * 结构坏掉的消息，不应该把本机的接收链路打断。调用方拿到 null 时按
     * 「对端还没给出摘要」处理即可。
     */
    fun decode(payload: JsonObject?): MediaSignature? {
        if (payload == null) return null
        return try {
            json.decodeFromJsonElement(MediaSignature.serializer(), payload)
        } catch (_: Exception) {
            null
        }
    }
}