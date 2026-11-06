package com.cinemahall.desktop.rtc

import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.rtc.AudioRtpCounters
import com.cinemahall.core.rtc.LinkStats
import com.cinemahall.core.rtc.PeerDiagnostics
import com.cinemahall.core.rtc.RtcConfig

/**
 * 协商动作的两种「本地描述」。
 *
 * 刻意不复用 SDK 里的 `SessionDescription.Type`：本接口是 [DesktopPeerLink] 的状态机与
 * native 之间的缝，一旦这里出现 SDK 类型，C1 的纯 JVM 单测就再也跑不动了。
 */
enum class DescriptionKind { OFFER, ANSWER }

/**
 * native 侧回调。**实现方可以在任意线程上调用**（libwebrtc 的观察者就在自己的线程上），
 * 收口到单线程是 [DesktopPeerLink] 的责任 —— 与 Android 那边把所有回调 `post` 到主线程
 * 是同一条理由：出站信令（offer / answer / candidate）必须只从一个线程按顺序发出。
 */
interface RtcBackendCallbacks {

    /** [kind] 的本地描述**已经 setLocalDescription 成功**，可以发给对端了。 */
    fun onLocalDescription(kind: DescriptionKind, sdp: String)

    /** 本端候选。[candidate] 非空（结束标记不走这里，见 [onIceGatheringComplete]）。 */
    fun onLocalIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int)

    /** 候选收集结束（end-of-candidates）。对端收到它不影响语义，可以不转发。 */
    fun onIceGatheringComplete()

    /** 远端描述落地成功。answer 方在这里才第一次拥有音频 m 行（C3 起才有意义）。 */
    fun onRemoteDescriptionSet(kind: DescriptionKind)

    /** 信令状态回到 `STABLE`（挂起的下一轮 offer 靠它补发）。 */
    fun onSignalingStateStable()

    fun onDataChannelOpen()

    fun onDataChannelClosed()

    /** DataChannel 上收到的文本（信封 JSON，由上层解码）。 */
    fun onMessage(text: String)

    /** 链路真的失败（不是「走中继」）。[reason] 会原样进 [com.cinemahall.core.rtc.PeerState.Failed]。 */
    fun onFailure(reason: String)

    /** 最新诊断快照。`rejectedIceCandidates` 由状态机自己数，这里给的值会被覆盖。 */
    fun onDiagnostics(snapshot: PeerDiagnostics)
}

/**
 * native 操作面：`DesktopPeerLink` 需要的全部副作用都在这里。
 *
 * ── 为什么要有这层缝 ────────────────────────────────────────────────────────
 * C1 的验收是「状态机在纯 JVM 单测里跑通」。若状态机直接 `new PeerConnection`，
 * 单测就得拖上 7.9 MB 原生库与一套音频设备。缝定好之后：
 *  - C1：测试用 `FakeBackend` 驱动，验证 offerer 判定、ICE 排队、挂起补发、代次丢弃；
 *  - C2：`WebRtcBackend` 用 webrtc-java 实现同一组方法，状态机一行不改。
 *
 * ── 约定 ────────────────────────────────────────────────────────────────────
 * 1. 所有方法**不得抛异常**（失败一律走 [RtcBackendCallbacks.onFailure]）；
 * 2. 回调**可以**在任意线程上触发；
 * 3. 同一个 `start` 到 `close` 之间，每个操作至少回调一次（[createOffer] →
 *    `onLocalDescription(OFFER)`），否则状态机里的「一轮在飞」永远不清。
 */
interface RtcBackend {

    /** 建 factory / PeerConnection / DataChannel（offerer 才建通道）。 */
    fun start(config: RtcConfig, offerer: Boolean, callbacks: RtcBackendCallbacks)

    fun createOffer(iceRestart: Boolean)

    fun createAnswer()

    /**
     * 信令状态是不是 `STABLE`。
     *
     * 状态机靠它决定「现在能不能发 offer」：`have-local-offer` / `have-remote-offer` 下
     * `setLocalDescription(offer)` 必然失败，而本模块把 SDP 失败一律判成链路失败 ——
     * 不挡住的话，两次几乎同时的操作就能把一条好链路打死。
     */
    fun isStable(): Boolean

    fun setRemoteDescription(kind: DescriptionKind, sdp: String)

    /**
     * @return false 表示被 libwebrtc 拒掉（**它本身不抛异常、不打日志**）。
     * 状态机会把它计进 [PeerDiagnostics.rejectedIceCandidates]。
     */
    fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int): Boolean

    /**
     * 换 ICE 配置 / 传输策略（`setConfiguration`）。
     *
     * ⚠️ 这里**只**应用配置，不负责触发新一轮协商：`setConfiguration` 本身不会让 libwebrtc
     * 重取 `iceServers`，必须再有一轮 offer + `restartIce` 才生效。而「谁能发 offer」
     * 是状态机的知识（只有 offer 方能发，非 offer 方只能请求对端），所以那一步由
     * [DesktopPeerLink] 在调用本方法之后自己做。
     */
    fun applyConfiguration(config: RtcConfig)

    /** 显式重启 ICE（只有 offer 方有效）。 */
    fun restartIce()

    /** DataChannel 未 OPEN 时必须返回 false（不排队、不假装成功）。 */
    fun sendText(text: String): Boolean

    fun isChannelOpen(): Boolean

    fun snapshotStats(): LinkStats?

    /**
     * 推一帧采集数据进 RTP 发送端（48k / 16-bit / 单声道 / 960 字节 = 10 ms）。
     *
     * ⚠️ 必须在**单线程**上按帧序调用。返回 false = 这条链路没有上行音频源（还没建/已拆）。
     */
    fun pushCapturePcm(pcm: ByteArray): Boolean

    /** 音频 RTP 包计数（没有音频条目时返回 null，不编一个 0 出来）。 */
    fun audioRtp(): AudioRtpCounters?

    /**
     * 下行播放的即时读数。
     *
     * 存在的理由只有一条：把「一个包都没收到」与「收到了但放不出声」分开。这两种成因的
     * 修法完全不同（前者查协商/网络，后者查扬声器与输出设备），而 2026-11-06 真机上
     * 界面只有一句「还没收到对端语音」，两种都被它盖住了。
     */
    fun downlinkReadout(): DownlinkReadout

    /**
     * 挂上 / 摘下本机音轨（`replaceTrack`）。
     *
     * 为什么要摘而不是「推静音」：音轨挂着但没人 push PCM 时，`CustomAudioSource`
     * 会继续产出静音帧，对端看到的是「一直在收包」。摘掉之后这一路就没有音频了 ——
     * 这是进程内唯一能立刻见效的一步（m 行保持不变，不需要重新协商）。
     *
     * @return false = 还没有音频收发端（链路没建好）
     */
    fun setAudioSending(enabled: Boolean): Boolean

    /**
     * 远端语音的 AEC 参考（C4）：每出一帧就回调一次，**在播放线程上**。
     *
     * 为什么不在「收到」时就喂：`SourceDataLine` 有 100 ms 设备缓冲，收到到出声之间
     * 隔着这段时间。参考流提前喂，AEC3 会把延迟算错、回声消除反而更差。
     *
     * @param sink 传 null 摘掉。
     */
    fun setRenderSink(sink: ((ByteArray) -> Unit)?)

    /**
     * 远端语音的播放音量（0..1）。
     *
     * @return false = 本机没有可用的放音设备（界面据此说「放不出远端语音」）。
     */
    fun setRemoteVolume(volume: Float): Boolean

    fun snapshotDiagnostics(): PeerDiagnostics

    /**
     * 门禁/排障专用：协商链路上最近一步的结局（`remote:OFFER:ok` / `answer:ok` / `answer:sent` / `…失败`）。
     *
     * 读数不收进 [PeerDiagnostics]：那是 core 里 Android 与桌面共用的契约，加字段等于改协议。
     * 只在这一层加一个只读口，桌面自检打印它。默认 `-`，测试替身不必实现。
     */
    val negotiationNote: String get() = "-"

    /** 门禁/排障专用：answer 方把本地音频收发端挂上对端 offer 的结局。默认 `-`。 */
    val audioAttachNote: String get() = "-"

    fun close()
}

/**
 * 下行（对端语音）播放的读数，[RtcBackend.downlinkReadout] 的返回值。
 *
 * [playedFrames] = 真正写进设备的帧数（10 ms 一帧）；[writeFails] = 写设备失败的次数。
 * 两者都为 0 且已经收到包 ⇒ 「收得到、放不出来」，问题在本机输出设备，不在链路。
 */
data class DownlinkReadout(
    val playedFrames: Long = 0,
    val writeFails: Long = 0,
)

/**
 * `DesktopPeerLink` 的执行器：**单线程 + FIFO**。
 *
 * 这条不是风格问题：`PeerCoordinator` 依赖「offer 先于随后的候选」发出，而两者来自
 * 不同来源（本端 createOffer 的回调 vs native 的 ICE 回调）。只要它们不在这一个线程上
 * 排队，顺序就没有保证，对端会因为 `remoteDescription == null` 把候选静默丢掉 ——
 * 症状是「有时连不上、有时绕远路走中继」，且两边都不报错。
 */
interface LinkDispatcher {
    fun execute(block: () -> Unit)
}

/** 直接在当前线程执行。只用于单测与自检。 */
object InlineDispatcher : LinkDispatcher {
    override fun execute(block: () -> Unit) = block()
}
