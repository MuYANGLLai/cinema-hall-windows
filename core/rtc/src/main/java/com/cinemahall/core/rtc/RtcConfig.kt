package com.cinemahall.core.rtc

import com.cinemahall.core.model.IceServer

/**
 * ICE 传输策略（对应 `RTCConfiguration.iceTransportsType`）。
 *
 * ⚠️ org.webrtc 里这个字段叫 `iceTransportsType` 而不是 `iceTransportPolicy`，
 * 是两个容易写混的名字 —— 用错会把「强制中继」静默写成「默认全部候选」，
 * 于是 1.4 的「强制 relay-only 也能建连」验收就变成了自欺。
 * 映射由 `LibwebrtcMapping.kt` 一处集中，并有单测钉住。
 */
enum class TransportPolicy {

    /** 全部候选（host / srflx / relay）。日常默认。 */
    ALL,

    /**
     * 只允许中继候选。
     *
     * 两个用处（§9.3）：
     *  1. 1.4 验收要求「**强制 relay-only 下也能建连**」（因为 §3.7.3 已把中继定为默认数据面）；
     *  2. 排查「到底走没走 TURN」—— 打开它就只可能成功于中继路径。
     */
    RELAY_ONLY,
}

/**
 * 一条 WebRTC 链路的配置。
 *
 * @param iceServers 服务端下发的 ICE 配置。为空是**合法**的，含义是「服务端说没有中继」
 *   （`turn.mjs` 在没配 `TURN_HOST` 时就是返回 `[]`）—— 此时只剩 STUN，
 *   蜂窝 CGNAT 下大概率连不上，诊断页必须明说，而不是笼统报「连接失败」（§8.4）。
 * @param transportPolicy 见 [TransportPolicy]。
 * @param dataChannelLabel DataChannel 标签。两端必须一致才会被算作同一条。
 */
data class RtcConfig(
    val iceServers: List<IceServer> = emptyList(),
    val transportPolicy: TransportPolicy = TransportPolicy.ALL,
    val dataChannelLabel: String = DEFAULT_LABEL,
) {
    companion object {
        const val DEFAULT_LABEL = "cinema"
    }
}
