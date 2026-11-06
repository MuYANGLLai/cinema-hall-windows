package com.cinemahall.core.rtc

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 链路状态机。粗粒度、电平型（`StateFlow`），供 UI 直接绑。 */
sealed interface PeerState {

    /** 还没开始，或已被 [PeerLink.close] 收掉。 */
    data object Idle : PeerState

    /** 已建 `PeerConnection`，正在交换 SDP / ICE。 */
    data object Negotiating : PeerState

    /** DataChannel 已 OPEN —— 这才算真正「通了」。 */
    data object Connected : PeerState

    /** 真的失败了。注意**走中继不算失败**（§3.7.3）。 */
    data class Failed(val reason: String) : PeerState

    data object Closed : PeerState
}

/**
 * 原始的 libwebrtc 状态字串，专供诊断页（§9.3）。
 *
 * 为什么是 `String` 而不是 `org.webrtc` 的枚举：本接口的公开签名里不允许出现 SDK 类型
 * （§4.2 接缝 1 的同一条原则，`core:rtc` 对 WebRTC 的依赖因此可以是 `implementation`）。
 */
data class PeerDiagnostics(
    val iceConnectionState: String = UNKNOWN,
    val peerConnectionState: String = UNKNOWN,
    val signalingState: String = UNKNOWN,
    val iceGatheringState: String = UNKNOWN,
    val dataChannelState: String = UNKNOWN,
    /**
     * 本地持有的 TURN 临时凭证还要多久过期（秒），负数表示**已经过期**。
     * §9.3 明确要求这一项：它是判断「重连失败是否因凭证过期」的唯一手段（§3.7.7、R-16）。
     */
    val turnCredentialExpiresInSeconds: Long? = null,
    /**
     * 被 libwebrtc 拒掉的远端候选数。
     *
     * 会需要这个数字，是因为 `addIceCandidate` 失败时**只返回 false、不抛异常、不打日志**；
     * 不自己计数就什么都看不到，而症状只是「连不上」。>0 就说明候选有丢失，
     * 要么是排队逻辑没覆盖到，要么是候选本身非法。
     */
    val rejectedIceCandidates: Int = 0,
    /** 最后一条 ICE 候选错误（STUN/TURN 不可达等）。§3.7.6 判断链路走向的一手证据。 */
    val lastIceError: String? = null,
) {
    companion object {
        const val UNKNOWN = "-"
    }
}

/**
 * `core:rtc` 把本端 SDP / ICE 送出去的出口。实现放在 `app`（接 [com.cinemahall.core.network.Signaler]）。
 *
 * 为什么不让 `core:rtc` 直接依赖 `core:network`：§4.1 画的是 `rtc ──> model`。
 * 而且这么一来，`core:rtc` 完全不需要认识 WebSocket / 信封，链路的单测也不必拖上信令栈。
 *
 * 四个方法都是「尽力而为、立即返回」，**不抛异常**：信令此刻可能正断着，
 * 那是 `Signaler` 自己的重连去处理的事，不是 RTC 层的问题。
 */
interface RtcSignalSink {
    fun sendOffer(sdp: String)
    fun sendAnswer(sdp: String)
    fun sendIce(candidate: String?, sdpMid: String?, sdpMLineIndex: Int?)

    /**
     * 请对端（offer 方）重新发起一轮协商。
     *
     * [reason] **是行为判据，不只是日志**（2026-10-14 起，§5.2）：`ice`（**缺键时即此值**，
     * 兼容旧包）= 重启 ICE；`audio` = 只要一轮**普通 re-offer** —— 关麦要把音频 m 行的
     * 发送方向停掉，而那件事不能顺带重启 ICE（重启会让 DataChannel 掉一下，播放同步锚点
     * 被清）。常量在 `RtcSignal` 的伴生对象里，别在这里写字面量。
     */
    fun requestRenegotiation(reason: String?)
}

/**
 * 一条与对端的 WebRTC 链路（阶段一 1.4）。
 *
 * 生命周期：`start` → 交换 SDP/ICE → DataChannel OPEN → `close`。
 * 重连（信令断开再恢复）由调用方决定是复用还是重建整条链路 —— 见 `app` 的 `PeerCoordinator`。
 *
 * 实现必须在**主线程**创建，且所有改动方法内部会切到主线程执行（libwebrtc 要求
 * 创建 `PeerConnectionFactory` 的线程带 `Looper`），因此调用方**不必**自己保证线程，
 * 但**必须保证调用顺序** —— 方法内部是排队执行的，不会打乱。
 *
 * ── 1.7 起它还是一条**语音链路** ─────────────────────────────────────────────
 * 音频的能力面在 [CallAudio]（`callAudio` / `setCallEnabled` / `setCallVolume`），
 * 没有写在这里：那是玩法唯一需要的三个成员，而玩法不该看见 `send` / `onSignal` /
 * `close`。接口继承而不是复制，是为了让「音频状态」只有一份实现、一份真相。
 */
interface PeerLink : CallAudio {

    val state: StateFlow<PeerState>

    /** 最新一次统计快照。取不到时为 null，UI 该显示「—」而不是 0。 */
    val stats: StateFlow<LinkStats?>

    val diagnostics: StateFlow<PeerDiagnostics>

    /**
     * DataChannel 收到的文本消息。
     *
     * 阶段一 1.4 只做 echo；**1.5 起**这里流过的就是 `EnvelopeCodec` 编出来的信封 JSON。
     * 解码与按前缀分发放在 `app` 的 `PeerCoordinator`（它同时认识 `core:protocol`
     * 与本接口），**不是**放在玩法里：「这条消息属于哪个域」是底座才知道的事。
     * `sys.*` / `rtc.*` 只走 WebSocket 信令，出现在 DataChannel 上属于对端有问题，
     * 应当由底座拦掉并计数（§4.2）。
     *
     * ⚠️ 与信令的 `rtcSignals` 一样的 `SharedFlow` 语义：**订阅必须早于 [start]**。
     */
    val messages: SharedFlow<String>

    /** 当前对端。未建链时为 null。 */
    val remote: StateFlow<UserId?>

    /**
     * 本端是不是 offer 方（`null` = 还没有链路）。
     *
     * 只由 [start] 按 userId 字典序算出。**这是协商角色的唯一判据** —— 需要它的地方
     * 必须读这里，不能再自己算一遍：角色判定一旦有第二份实现，两端就可能都以为自己
     * 该发 offer（glare），而且不会有任何报错。
     *
     * 外面为什么需要知道：offer 只可能由 offer 方发出，所以「链路不对了该怎么救」
     * 在两端本来就不是同一个动作（见 `app` 的 `PeerCoordinator.restart`）。
     */
    val isOfferer: StateFlow<Boolean?>

    /**
     * 开始（或整体重启）与 [remoteUserId] 的链路。
     *
     * **谁是 offer 方由本方法内部按 userId 字典序决定**（小者为 offer），不靠外部约定：
     * 两端各算一次，结果必然一致，于是不可能出现 glare（双方同时发 offer）。
     * 选「字典序」而不是「房主发」是因为房主自己也可能后进/后重连，而字典序与角色无关。
     */
    fun start(
        myUserId: UserId,
        remoteUserId: UserId,
        config: RtcConfig,
        sink: RtcSignalSink,
    )

    /** 喂入对端来的 RTC 信令。来自非当前对端的信号会被丢弃。 */
    fun onSignal(signal: RtcSignal)

    /**
     * 更新中继凭证。
     *
     * ⚠️ **会显式触发 `setConfiguration` + `restartIce()`**：libwebrtc 不会自己重取
     * `iceServers`，只下发新凭证而不重启 ICE 等于什么都没做（§3.7.7）。
     */
    fun setIceServers(servers: List<IceServer>)

    /** 切换传输策略（§9.3 的「强制 relay-only」调试开关）。同样会重启 ICE。 */
    fun setTransportPolicy(policy: TransportPolicy)

    /** 显式重启 ICE。offer 方会随即发新 offer；非 offer 方改为请求对端重协商。 */
    fun restartIce()

    /** 发一条文本。DataChannel 未 OPEN 时返回 false（不排队、不假装成功）。 */
    fun send(text: String): Boolean

    /** 立刻刷一次统计，不等周期。 */
    fun refreshStats()

    fun close()
}
