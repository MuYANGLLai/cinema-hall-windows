package com.cinemahall.core.account

import java.net.URI

/**
 * 从信令地址推出账号 HTTP 的 base URL。
 *
 * ── 为什么需要它，而不是再配一个「API 地址」──────────────────────────────────
 * 信令与账号接口在**同一台服务器、同一个端口**（Caddy 把 `/ws` 与 `/api` 前缀一起反代，
 * 8443 上终止 TLS）。多一个配置项就多一处能让用户填错、多一处要跟服务器同步漂移的地方。
 * 用户只需要知道「服务器在哪」，协议前缀按信令地址推导。
 *
 * 推导规则（与 `SignalPreferences.DEFAULT_ENDPOINT` 的两种写法互为倒数）：
 * ```
 * wss://101.133.234.226:8443/ws  →  https://101.133.234.226:8443
 * ws://192.168.1.7:8080/ws       →  http://192.168.1.7:8080
 * ```
 * **不保留 path**：信令的 `/ws` 是 WebSocket 的升级路径，而 HTTP 接口固定在 `/api`
 * 前缀下（契约 §6.4）。留着 `/ws` 拼出来就是 `/ws/api/activate`，服务端只会回 404。
 *
 * 端口只在显式写了的时候保留 —— `URI.port` 对缺省端口返回 -1，此时不能补一个 80/443：
 * 用户的服务器可能就是 Caddy 默认的 443/80，但补上去改变了原本的语义（尤其反代后面
 * 还挂着别的端口时）。让 URL 自己按 scheme 取默认端口。
 */
object AccountEndpoint {

    /**
     * @throws IllegalArgumentException 地址为空、scheme 不是 ws/wss/http/https、或缺 host。
     */
    fun httpBase(signalEndpoint: String): String {
        val raw = signalEndpoint.trim()
        require(raw.isNotEmpty()) { "信令地址为空" }

        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("信令地址无法解析：$raw", e)
        }

        val scheme = when (uri.scheme?.lowercase()) {
            "wss", "https" -> "https"
            "ws", "http" -> "http"
            else -> throw IllegalArgumentException("信令地址的协议不受支持：${uri.scheme}")
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("信令地址缺少主机名：$raw")

        // IPv6 字面量在 URI.host 里带方括号（`[::1]`），直接拼回 URL 是正确的。
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    /**
     * 拼出某个接口的完整 URL。`path` 传 `/api/activate` 这种**以斜杠开头**的路径。
     *
     * 单独抽出来是为了让「base 末尾有没有斜杠」这件事只在一个地方处理 ——
     * 两侧都手拼字符串的话，`https://h:8443` + `/api/me` 与 `https://h:8443/` + `api/me`
     * 只要有一处漏判就会拼出双斜杠或缺斜杠，而症状是 404（看起来像路径写错）。
     */
    fun url(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')
}
