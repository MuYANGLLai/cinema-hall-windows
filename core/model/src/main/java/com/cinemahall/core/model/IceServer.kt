package com.cinemahall.core.model

import kotlinx.serialization.Serializable

/**
 * 一条 [WebRTC ICE server 配置](https://developer.mozilla.org/docs/Web/API/RTCIceServer)，
 * 对应服务端 `sys.room_created` / `sys.room_joined` / `sys.ice_servers` 里的 `iceServers[]`。
 *
 * 服务端组装规则见 `server/src/turn.mjs`：永远先给一条 STUN；只有在 coturn 可达时
 * 才追加一条带 `username` / `credential` 的 TURN。
 *
 * ⚠️ [credential] 是**临时凭证**，不是长期密钥。但它仍然是敏感值：
 * 只允许出现在内存与诊断页的「剩余有效期」展示里，**绝不写日志、绝不落盘**。
 *
 * ── 为什么它住在 `core:model` 而不是 `core:network`（阶段一 1.4 迁移）──────────
 * 阶段一 1.4 起有两个模块要用它：`core:network`（从服务端收到、下发事件）和
 * `core:rtc`（喂给 `RTCConfiguration.iceServers`）。§4.1 画的依赖图里
 * `rtc ──> model` 而没有 `rtc ──> network`，若把它留在 `core:network`，
 * 就只能被迫加一条 `rtc ──> network` 的边（rtc 为了一个纯数据类去依赖整个
 * 信令模块，连单测都得拖上 WebSocket 栈）。
 *
 * 判定规则：`core:model` 放**跨模块共享的纯值类型**（无 Android API、无 I/O），
 * `core:protocol` 放**线格式的编解码与路由**。本类与 [RtcSignal] 都属于前者，
 * 于是 `network ──> model` 与 `rtc ──> model` 这两条既有边就够用了，**零新增模块依赖**。
 */
@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {

    /** 是否含中继地址（`turn:` / `turns:`）。§8.4「当前无中继」的判定就靠它。 */
    val isRelay: Boolean
        get() = urls.any { it.startsWith("turn:") || it.startsWith("turns:") }

    /**
     * TURN REST 临时凭证的过期时刻（unix 秒），从 username 的第一段解析。
     * 非 TURN 条目或格式不符时返回 null。诊断页用它显示凭证剩余有效期（§3.7.7、R-16）。
     */
    val expiryEpochSeconds: Long?
        get() = username?.substringBefore(':')?.toLongOrNull()

    companion object {
        /** 这组配置里是否至少有一条中继。 */
        fun hasRelay(servers: List<IceServer>): Boolean = servers.any { it.isRelay }

        /**
         * 这组配置里**最早**的凭证过期时刻（unix 秒）；一条都解析不出时返回 null。
         *
         * ── 为什么取最早而不是最新（阶段二 2.1）──────────────────────────────────
         * 中继条目理论上可以有多条（每台 TURN 一份凭证）。判断「该续签了 / 已经过期了」
         * 必须按**最先失效**的那条算：只要有一条过期，下一次对着它重新 allocation
         * 就会失败，而失败是静默的（候选少一条，看起来只是「连得慢」）。
         *
         * ── 为什么是伴生函数而不是 `List<IceServer>` 的扩展 ────────────────────
         * 两条调用路（`core:rtc` 的诊断余期、`app` 的续签判据）分属不同模块，
         * 伴生函数是它们唯一能共享的位置 —— 见 [hasRelay] 同样的理由。
         * 这个判据曾各写一份（`WebRtcPeerLink.turnCredentialExpiresInSeconds`），
         * 两处迟早会分岔，而分岔的症状是「诊断页说还有 5 分钟、续签逻辑却认为已过期」。
         */
        fun earliestExpiryEpochSeconds(servers: List<IceServer>): Long? =
            servers.mapNotNull { it.expiryEpochSeconds }.minOrNull()
    }
}
