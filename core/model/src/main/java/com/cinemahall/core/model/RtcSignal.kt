package com.cinemahall.core.model

/**
 * 对端经信令服务转发的 RTC 信令（《技术方案评审》§5.2「客户端 → 服务端」表里的 `rtc.*`）。
 *
 * 服务端对 `rtc.*` 是**原样转发**（`server/src/signaling.mjs` 的 `forwardToSeat`
 * 直接 `JSON.stringify(env)` 转发整只信封，不解析 SDP/candidate），所以下面这些
 * payload 字段是**两端客户端自己约定**的，改它们不需要动服务端。这一点很容易
 * 误以为要改服务端，特意记在这里。
 *
 * [from] 取自信封的 `from` 字段 —— 服务端在连接首次收到信封时把 `userId` 绑定到该连接
 * （`signaling.mjs` 的 `IDENTITY_CHANGED` 分支），所以这个字段不可伪造。
 *
 * 住在 `core:model` 的原因见 [IceServer] 的 KDoc：`core:network` 生产它、
 * `core:rtc` 消费它，两者都只依赖 `core:model`，不需要 `rtc ──> network` 这条新边。
 */
sealed interface RtcSignal {

    /** 发信对端。 */
    val from: UserId

    /** `rtc.offer`，payload `{"sdp": "..."}`。 */
    data class Offer(override val from: UserId, val sdp: String) : RtcSignal

    /** `rtc.answer`，payload `{"sdp": "..."}`。 */
    data class Answer(override val from: UserId, val sdp: String) : RtcSignal

    /**
     * `rtc.ice`，payload `{"candidate": "...", "sdpMid": "...", "sdpMLineIndex": 0}`。
     *
     * [candidate] 为 null 表示**候选收集结束**（end-of-candidates）。这不是错误，
     * 也**绝不能丢**：libwebrtc 在 UDPMux / 单候选场景下会先给出真实候选再给出结束标记，
     * 而 `addIceCandidate` 收到结束标记后才会把远端候选集合标记为完整。
     */
    data class Ice(
        override val from: UserId,
        val candidate: String?,
        val sdpMid: String?,
        val sdpMLineIndex: Int?,
    ) : RtcSignal

    /**
     * `rtc.renegotiate`，payload `{"reason": "…"}`。语义是「我这边判断需要一轮新的协商，
     * 请你（offer 方）重新发起」。
     *
     * 为什么要有它：`restartIce()` 只有 **offer 方**发新 offer 才生效（§3.7.7 同源结论）。
     * 若失败是由非 offer 方先发现的，它无法自己发起 —— 只能请求对端。
     *
     * [reason] **是行为判据，不只是日志**（2026-10-14 起，§5.2）：
     *  - [RENEGOTIATE_REASON_ICE]（**缺键时即此值**，兼容旧包）：重启 ICE —— 换凭证、
     *    网络变化那两条路要的就是它；
     *  - [RENEGOTIATE_REASON_AUDIO]：只要一轮**普通 re-offer**。关麦要把音频 m 行的
     *    发送方向停掉（`recvonly`），那件事**不能**顺带重启 ICE —— 重启 ICE 会让
     *    DataChannel 掉一下，`PeerCoordinator` 会据此清掉播放同步锚点。
     */
    data class Renegotiate(override val from: UserId, val reason: String?) : RtcSignal

    /**
     * `rtc.reset`，payload `{"reason": "…"}`（阶段二 2.4，2026-10-14）。语义是
     * 「**我这半边已经拆了**」—— 整条链路对称重建，不是「请你重建」。
     *
     * 两条硬约定（《技术方案评审》§5.2 有完整说明）：
     *  ① 收到它的一方**绝不回发**同一条消息，否则两端互相对拆、形成风暴；
     *  ② 请求方必须**先发它、再拆再建** —— 新 offer 于是必然排在它之后到达，
     *     落在对端的新连接上。反过来（先建后发）会留下一条没人回答的 offer，
     *     症状与「一直停在协商中」同形。
     *
     * [reason] 只进日志（`manual` = 本机按了按钮，`peer` = 应 / 对端的请求），不参与判据。
     */
    data class Reset(override val from: UserId, val reason: String?) : RtcSignal

    companion object {
        /**
         * `rtc.renegotiate` 的 `reason`：重启 ICE。
         *
         * **缺键时按它处理** —— 旧包（0.1.12 及以前）发这条消息时没有 payload，
         * 那时它只有「重启 ICE」这一种含义。
         */
        const val RENEGOTIATE_REASON_ICE = "ice"

        /**
         * `rtc.renegotiate` 的 `reason`：只要一轮**普通 re-offer**（不重启 ICE）。
         *
         * 唯一用途：关麦时把音频 m 行的发送方向停掉（`recvonly`）—— 见 §5.2 那条说明。
         */
        const val RENEGOTIATE_REASON_AUDIO = "audio"
    }
}
