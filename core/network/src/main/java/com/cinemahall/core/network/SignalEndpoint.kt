package com.cinemahall.core.network

import java.net.URI

/**
 * 把用户输入的服务器地址规范成可以交给 WebSocket 客户端的形式。
 *
 * 为什么要有这一步：输入框里人只会敲 `signal.example.com`，而这个项目**日常链路
 * 是 TURN 中继 + 公网**（Q10），没有 TLS 的裸 `ws://` 在生产上会被服务端
 * `verifyClient` 直接 403。所以默认按 `https://` 那一侧的 `wss://` 处理，
 * 想用裸 `ws://` 必须显式写出来。
 *
 * @return 规范化后的地址；输入为空或不合法时返回 null（由调用方决定怎么提示）。
 */
fun normalizeSignalEndpoint(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = when {
        trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
        trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
        // https → wss、http → ws：有人会顺手把浏览器地址栏里的东西贴进来
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
        // 没有 scheme 也没有 "//" → 当成主机名，默认走 wss
        else -> "wss://$trimmed"
    }

    // 用 java.net.URI 而不是 OkHttp 的类型：本函数要能在**纯 JVM 单测**里跑，
    // 不该把 OkHttp 的类型扯进公开签名。
    val uri = try {
        URI(withScheme)
    } catch (_: Exception) {
        return null
    }

    if (uri.host.isNullOrBlank()) return null
    // 端口越界、含空白之类都由 URI 的解析结果兜住
    if (uri.port != -1 && uri.port !in 1..65535) return null

    return withScheme
}
