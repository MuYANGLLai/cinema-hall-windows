package com.cinemahall.desktop.rtc

import com.cinemahall.core.rtc.AudioRtpCounters
import com.cinemahall.core.rtc.LinkStats
import com.cinemahall.core.rtc.LinkStatsParser
import com.cinemahall.core.rtc.PeerDiagnostics
import com.cinemahall.core.rtc.RtcConfig
import com.cinemahall.core.rtc.TransportPolicy
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCIceTransportPolicy
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.RTCStats
import dev.onvoid.webrtc.RTCStatsCollectorCallback
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * [RtcBackend] 的 webrtc-java 实现（W7 C2）。
 *
 * ── 线程 ────────────────────────────────────────────────────────────────────
 * 所有方法都**必须**在同一个线程上调用（就是 `DesktopPeerLink` 的 dispatcher）。
 * 这条约束来自 libwebrtc 自己：`PeerConnection` 的 Java 包装器会把调用排到内部的
 * signaling 线程上，但 `createOffer`/`setLocalDescription` 与随后的 `send` 之间的
 * 先后关系只有调用方串行才成立。而 native 的回调（observer）在 libwebrtc 自己的线程上，
 * 这里只负责把它们原样交给 [RtcBackendCallbacks] —— 收口到单线程是状态机的事。
 *
 * ── 与 Android 那套的三处实质差异（照搬会错）──────────────────────────────────
 * 1. `addIceCandidate` **没有返回值**（`void`，失败才抛异常）。所以 `rejectedIceCandidates`
 *    在 Windows 上只能靠捕获异常来数 —— 比 Android 弱一档，如实写在诊断字段里。
 * 2. 统计类型是**枚举** `RTCStatsType`，不是字符串。`LinkStatsParser` 吃的是
 *    `local-candidate` / `outbound-rtp` 这种 kebab 形式，所以要转一次（[toFlatEntry]）。
 * 3. `getStats` 是**异步**的：`snapshotStats()` 只能「发起一次查询并返回上次的结果」。
 *    第一次调用必然是 null，这是 API 形状决定的，不是没实现。
 */
class WebRtcBackend : RtcBackend {

    private var pc: RTCPeerConnection? = null
    private var channel: RTCDataChannel? = null
    private var callbacks: RtcBackendCallbacks? = null
    private var config: RtcConfig = RtcConfig()

    // ── 通话音频（C3 上行；C4 下行）──────────────────────────────────────────
    /**
     * 上行音频源：**父进程唯一的声音入口**。
     *
     * 它绕过 ADM（所以父进程全程 headless），帧由 [pushCapturePcm] 从 helper 的
     * 读取线程推进来。一条链路一个 source：`dispose()` 之后不能再 push。
     */
    private var audioSource: CustomAudioSource? = null

    private var audioTrack: AudioTrack? = null

    /**
     * 音频收发端。
     *
     * offer 方在 `start` 里自己建（`addTransceiver`）；answer 方**新增不了 m 行**，
     * 只能在收到 offer 之后拿到对端那条，并在回 answer **之前**设方向、挂音轨 ——
     * 晚一步方向就写进 SDP 了（Android 侧 2026-10-11 的缺陷同源）。
     */
    private var audioTransceiver: RTCRtpTransceiver? = null

    /** 上一次 `getStats` 的扁平条目（音频包计数、ERLE 都从同一份里解析）。 */
    @Volatile
    private var lastEntries: List<Map<String, Any?>> = emptyList()

    /** 本地推进了多少帧（门禁判据：helper 停了这里必须冻结）。 */
    @Volatile
    private var audioPushedFrames = 0

    /** 最近一次 `setAudioSending` 的读数（门禁判据，见 [audioDebug]）。 */
    @Volatile
    private var audioNote = "-"

    override val audioAttachNote: String get() = audioNote

    // ── 下行播放（C4）──────────────────────────────────────────────────────
    /** 远端语音的播放器。跟着链路生命周期走，见 [start] / [close]。 */
    private var downlink: AudioDownlink? = null

    /** 已经挂了 sink 的那条远端音轨。换轨道（链路重建）时要先摘旧的。 */
    private var remoteTrack: AudioTrack? = null

    private var remoteSink: AudioTrackSink? = null

    @Volatile
    private var remoteVolume = 1f

    /** AEC 参考：由 [setRenderSink] 注入，在**播放线程**上被调用。 */
    @Volatile
    private var renderSink: ((ByteArray) -> Unit)? = null

    /** 收到远端帧但写不进放音设备的次数（门禁判据：区分「没收到」和「收到了放不出」）。 */
    @Volatile
    private var downlinkWriteFails = 0L

    /** 远端 PCM 的历史峰值（16-bit 绝对值）。门禁用：0 = 从头到尾都是静音。 */
    @Volatile
    private var renderPeak = 0

    /** 峰值超过 [LOUD_THRESHOLD] 的帧数。门禁用：证明「确实有声音到过这一端」。 */
    @Volatile
    private var renderLoudFrames = 0L

    /** 第一次收到远端帧时的原始参数（门禁用：确认帧长/声道/采样率与本地设备一致）。 */
    @Volatile
    private var sinkFormat = "-"

    /**
     * 门禁用：协商链路上最近一步的结局。
     *
     * 「offer 到了却一直不产生 answer」这种卡点，光看 `signalingState` 分不出
     * 「`setRemoteDescription` 没成功」还是「成功了但 `createAnswer` 没跑」，
     * 所以每一步落地都写一格。[negotiationNote] 只是它的只读出口。
     */
    @Volatile
    private var remoteDescNote = "-"

    override val negotiationNote: String get() = remoteDescNote

    /** 下行 sink 的挂/摘互斥（两个入口在不同线程上）。 */
    private val sinkLock = Any()

    /** 门禁用：addSink 成功了几次。大于 1 = 同一个音轨上挂了多个 sink（会让下行数据翻倍）。 */
    @Volatile
    private var sinkAttachCount = 0

    /**
     * 门禁用：sink 两次回调的平均 / 最小间隔（ms）。
     *
     * 健康值约 10 ms（每帧 480 样本 = 10 ms 音频）。明显小于 10 = playout 节拍没起来，
     * 下游一定会丢帧 —— 这一格就是那个问题的判据。
     */
    @Volatile
    private var sinkGapMinMs = Double.MAX_VALUE

    @Volatile
    private var sinkGapAvgMs = 0.0

    private var sinkGapSumMs = 0.0

    private var sinkGapCount = 0L

    private var lastSinkAt = 0L

    /** 上一次异步 `getStats` 的结果。可能在 native 线程上写、dispatcher 线程上读。 */
    @Volatile
    private var lastStats: LinkStats? = null

    override fun start(config: RtcConfig, offerer: Boolean, callbacks: RtcBackendCallbacks) {
        this.config = config
        this.callbacks = callbacks
        this.lastStats = null

        val factory = WebRtcEngine.acquire()
        val created = runCatching {
            factory.createPeerConnection(config.toWebRtc(), observer())
        }.getOrElse { error ->
            callbacks.onFailure("创建 PeerConnection 失败：${error.message ?: error::class.java.simpleName}")
            return
        }
        if (created == null) {
            callbacks.onFailure("创建 PeerConnection 返回 null（通常是 ICE 配置非法）")
            return
        }
        pc = created

        // 下行播放设备跟着这一条链路走：拆链 = 释放扬声器（Windows 的音频会话按进程算，
        // 但把设备句柄留着只会让「关麦后还响一下」变成常态）。
        downlink = AudioDownlink().also { it.setVolume(remoteVolume) }

        // 音频收发端与 DataChannel 的顺序：先音频，再通道，最后才让状态机发 offer ——
        // 音频 m 行是 offer 的一部分，晚建就只能重新协商（而那会把 DataChannel 打散）。
        createLocalAudio(factory, created, offerer)

        if (offerer) {
            // 由 offer 方建通道：answer 方通过 onDataChannel 拿到**同一条**，
            // 两端各建一条会得到两条互不相干的通道。
            runCatching {
                created.createDataChannel(
                    config.dataChannelLabel,
                    RTCDataChannelInit().apply { ordered = true },
                )
            }.onSuccess { attachChannel(it) }
                .onFailure { callbacks.onFailure("创建 DataChannel 失败：${it.message}") }
        }
        // 非 offer 方在这里什么都不做，等对端 offer。
        pushDiagnostics(callbacks)
    }

    override fun createOffer(iceRestart: Boolean) {
        val pc = pc ?: return
        val sink = callbacks ?: return
        // restartIce() 必须显式调用才会带来新的 ufrag/pwd —— libwebrtc 不会因为
        // iceServers 变了就自己重启。
        if (iceRestart) runCatching { pc.restartIce() }

        val options = RTCOfferOptions().apply { this.iceRestart = iceRestart }
        pc.createOffer(
            options,
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription?) {
                    val value = description
                    if (value == null) {
                        sink.onFailure("createOffer：成功回调里没有 SDP")
                        return
                    }
                    setLocal(pc, value, DescriptionKind.OFFER, sink)
                }

                override fun onFailure(error: String?) {
                    sink.onFailure("createOffer 失败：${error.orEmpty()}")
                }
            },
        )
    }

    override fun createAnswer() {
        val pc = pc
        val sink = callbacks
        if (pc == null || sink == null) {
            remoteDescNote = "answer:no-pc"
            return
        }
        pc.createAnswer(
            RTCAnswerOptions(),
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription?) {
                    val value = description
                    if (value == null) {
                        remoteDescNote = "answer:empty-sdp"
                        sink.onFailure("createAnswer：成功回调里没有 SDP")
                        return
                    }
                    remoteDescNote = "answer:ok"
                    setLocal(pc, value, DescriptionKind.ANSWER, sink)
                }

                override fun onFailure(error: String?) {
                    remoteDescNote = "answer:fail:${error.orEmpty()}"
                    sink.onFailure("createAnswer 失败：${error.orEmpty()}")
                }
            },
        )
    }

    private fun setLocal(
        pc: RTCPeerConnection,
        description: RTCSessionDescription,
        kind: DescriptionKind,
        sink: RtcBackendCallbacks,
    ) {
        pc.setLocalDescription(
            description,
            object : SetSessionDescriptionObserver {
                // 本地描述落地之后才允许发出去：这样「offer 先于候选」是对的，
                // 也让状态机在发之前把「一轮在飞」清掉。
                override fun onSuccess() {
                    if (kind == DescriptionKind.ANSWER) remoteDescNote = "answer:sent"
                    sink.onLocalDescription(kind, description.sdp)
                }

                override fun onFailure(error: String?) {
                    sink.onFailure("setLocalDescription($kind) 失败：${error.orEmpty()}")
                }
            },
        )
    }

    override fun setRemoteDescription(kind: DescriptionKind, sdp: String) {
        val pc = pc
        val sink = callbacks
        // 这两条早退原本是静默的：没有连接/没有回调时，状态机会以为「投递了、在等回执」，
        // 表现就是「offer 收到了，但永远不产生 answer」。所以必须留痕。
        if (pc == null || sink == null) {
            remoteDescNote = "remote:$kind:无连接(pc=${pc != null} cb=${sink != null})"
            return
        }
        remoteDescNote = "remote:$kind:已投递(${sdp.length}B)"
        val type = if (kind == DescriptionKind.OFFER) RTCSdpType.OFFER else RTCSdpType.ANSWER
        pc.setRemoteDescription(
            RTCSessionDescription(type, sdp),
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    // ⚠️ 必须在通知状态机 createAnswer **之前**：answer 里那条音频 m 行的方向
                    // 由收发端此刻的方向决定，晚一步就写进 SDP 了 —— 而 recvonly 的 m 行
                    // 协商不出 SSRC，`replaceTrack` 之后一个字节都发不出去，且每一步都不报错。
                    // 异常只记录、不吞掉整步：远端描述此刻已经落地，answer 那条路还得走完，
                    // 否则表现为「offer 收到了却永远不产生 answer」，而日志里什么都没有。
                    val audioError = runCatching {
                        if (kind == DescriptionKind.OFFER) attachAudioToRemoteOffer()
                        // 远端描述落地 = 这一侧才第一次拥有「远端音轨」这个对象。
                        ensureRemoteAudioSink()
                    }.exceptionOrNull()
                    remoteDescNote = if (audioError == null) {
                        "remote:$kind:ok"
                    } else {
                        "remote:$kind:音频挂载异常:${audioError.javaClass.simpleName}:${audioError.message}"
                    }
                    sink.onRemoteDescriptionSet(kind)
                }

                override fun onFailure(error: String?) {
                    sink.onFailure("setRemoteDescription($kind) 失败：${error.orEmpty()}")
                }
            },
        )
    }

    // ── 音频（C3）────────────────────────────────────────────────────────────

    private fun createLocalAudio(
        factory: PeerConnectionFactory,
        pc: RTCPeerConnection,
        offerer: Boolean,
    ) {
        val source = runCatching { CustomAudioSource() }.getOrNull()
        if (source == null) {
            callbacks?.onFailure("创建 CustomAudioSource 失败")
            return
        }
        val track = runCatching { factory.createAudioTrack(AUDIO_TRACK_ID, source) }.getOrNull()
        if (track == null) {
            runCatching { source.dispose() }
            callbacks?.onFailure("创建 AudioTrack 失败")
            return
        }
        audioSource = source
        audioTrack = track

        if (offerer) {
            audioTransceiver = runCatching {
                pc.addTransceiver(
                    track,
                    RTCRtpTransceiverInit().apply { direction = RTCRtpTransceiverDirection.SEND_RECV },
                )
            }.getOrNull()
            if (audioTransceiver == null) {
                callbacks?.onFailure("创建音频收发端失败")
                return
            }
            // 建完立刻摘掉音轨：m 行与 sendrecv 方向留在 SDP 里，但用户没按「开麦」之前
            // 一个音频帧都不发。`addTransceiver(track)` 只接受带轨道的重载（没有按 kind 建
            // 收发端的重载），所以「先挂后摘」是唯一能同时拿到 m 行和静默的办法。
            setAudioSending(false)
        }
        // answer 方在这里什么都不做：它还没有 m 行，见 attachAudioToRemoteOffer。
    }

    /**
     * answer 方拿到对端 offer 之后的第一件事：把本机音轨挂到那条音频收发端上。
     *
     * 本机方向**必须显式设成 SEND_RECV**：libwebrtc 给 answer 方自动造的收发端默认是
     * `recvonly`，不设的话 answer 里那条 m 行也是 recvonly —— 对端听不到本机，
     * 而两端的面板都会显示「在通话」。
     */
    private fun attachAudioToRemoteOffer() {
        val pc = pc ?: return
        if (audioTrack == null) {
            audioNote = "无本地音轨"
            return
        }
        val transceiver = pc.transceivers.firstOrNull {
            it.receiver?.track?.kind == dev.onvoid.webrtc.media.MediaStreamTrack.AUDIO_TRACK_KIND
        } ?: run {
            // 走到这里说明对端 offer 里那条音频 m 行没匹配上（例如对端没建音频收发端）。
            audioNote = "对端 offer 无音频行"
            return
        }
        runCatching { transceiver.setDirection(RTCRtpTransceiverDirection.SEND_RECV) }
        audioTransceiver = transceiver
        // 这里**不挂音轨**：方向 setrecv 已经写进 answer，之后 `replaceTrack` 就能直接开说。
        // 反过来（一收到 offer 就挂上）等于「进房即开麦」—— 用户没按过任何开关，
        // 对端却已经在收本机的静音包，界面还显示「未开启」。
        audioNote = "attached dir=${runCatching { transceiver.direction }.getOrNull()}"
    }

    /**
     * 推一帧采集数据（960 字节 / 48k / 16-bit / 单声道）。
     *
     * ⚠️ 由 helper 的**读取线程**直接调用：`pushAudio` 要求单线程按帧序推，
     * 而那条线程天然是串行的。不要改成 post 到状态机线程 —— 协商忙的时候会把音频
     * 排到后面，直接表现为断续。
     */
    override fun pushCapturePcm(pcm: ByteArray): Boolean {
        val source = audioSource ?: return false
        return runCatching {
            source.pushAudio(pcm, BITS_PER_SAMPLE, SAMPLE_RATE, CHANNELS, pcm.size / 2)
            audioPushedFrames++
            true
        }.getOrDefault(false)
    }

    override fun audioRtp(): AudioRtpCounters? = LinkStatsParser.parseAudioRtp(lastEntries)

    override fun downlinkReadout(): DownlinkReadout =
        DownlinkReadout(playedFrames = downlink?.playedFrames ?: 0, writeFails = downlinkWriteFails)

    override fun setAudioSending(enabled: Boolean): Boolean {
        val transceiver = audioTransceiver
        if (transceiver == null) {
            audioNote = "no-transceiver"
            return false
        }
        val track = if (enabled) audioTrack else null
        if (enabled && track == null) {
            audioNote = "no-track"
            return false
        }
        // 判据取 `sender.track` 的**读回值**：`replaceTrack` 无返回值，只有读回来才
        // 知道它到底生效没有（这条缝不能靠「调用没抛异常」当成功）。
        val sender = runCatching { transceiver.sender }.getOrNull()
        if (sender == null) {
            audioNote = "no-sender"
            return false
        }
        val before = runCatching { sender.track?.kind }.getOrNull()

        // ⚠️ **只摘音轨不够**。2026-11-05 两进程实测：`replaceTrack(null)` 之后
        // `sender.track` 确实读回 null、本地 push 也停了，但对端的 `packetsReceived`
        // 仍以满速增长（≈96 pps）—— 和 Android 侧 `AudioState::RemoveSendingStream`
        // 按指针 erase、指针对不上就静默漏掉是同一族问题。真正让 RTP 停住的是
        // **把编码置为不活动**：`RTCRtpEncodingParameters.active = false`，
        // 不需要重新协商（这也是 W3C 里「暂停发送」的标准做法）。
        val encodingNote = setEncodingsActive(sender, enabled)

        val attempt = runCatching {
            // `replaceTrack(null)` = 从发送端摘掉音轨。m 行还在，不需要重新协商。
            sender.replaceTrack(track)
        }
        val after = runCatching { sender.track?.kind }.getOrNull()
        audioNote = if (attempt.isSuccess) {
            "ok want=${if (enabled) "AUDIO" else "null"} before=$before after=$after enc=$encodingNote"
        } else {
            val e = attempt.exceptionOrNull()
            "throw ${e?.javaClass?.simpleName}: ${e?.message} enc=$encodingNote"
        }
        return attempt.isSuccess
    }

    /** 把这一路的所有编码置为 `active` / 不活动，返回读数（门禁用）。 */
    private fun setEncodingsActive(sender: dev.onvoid.webrtc.RTCRtpSender, active: Boolean): String {
        val params = runCatching { sender.parameters }.getOrNull() ?: return "no-params"
        val encodings = params.encodings
        if (encodings.isNullOrEmpty()) return "no-encodings"
        encodings.forEach { it.active = active }
        return runCatching {
            sender.setParameters(params)
            "ok(n=${encodings.size})"
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    }

    /** 门禁用：远端 PCM 的历史峰值（16-bit 绝对值）。 */
    val renderedPeak: Int get() = renderPeak

    /** 门禁用：峰值超过阈值的帧数 —— 大于 0 就证明「确实有声音到过这一端」。 */
    val receivedLoudFrames: Long get() = renderLoudFrames

    /** 门禁用：写不进放音设备的次数。 */
    val downlinkFailures: Long get() = downlinkWriteFails

    /** 门禁用：真正写进设备的帧数。 */
    val playedAudioFrames: Long get() = downlink?.playedFrames ?: 0

    override fun setRenderSink(sink: ((ByteArray) -> Unit)?) {
        renderSink = sink
        downlink?.onRender = sink
    }

    override fun setRemoteVolume(volume: Float): Boolean {
        remoteVolume = volume.coerceIn(0f, 1f)
        val device = downlink ?: return false
        device.setVolume(remoteVolume)
        return true
    }

    /**
     * 把下行 sink 挂到远端音轨上。
     *
     * [authoritative] = 这条轨道是 [onTrack] 刚交上来的那个（libwebrtc 只在**第一次**拿到
     * 该收发端的远端轨道时通知一次）。它必须能**顶掉**之前的绑定 —— 两个方向的历史缺陷
     * 都出在这里，症状恰好相反：
     *
     *   ① **2026-11-06「两端都听不到对方」**（本机双端复现）：answer 方在
     *      `setRemoteDescription` 成功时就先绑了一次，那时 `receiver.track` 还是个
     *      **占位壳**；随后 `onTrack` 带着真轨道到达，却被 `remoteSink != null` 早退挡掉
     *      ⇒ 实测「收到 125 个音频包、`sink=[-]`、`play=0`」，一个包都没进设备。
     *   ② 反过来（每次调用都重挂）会让 `onData` 被回调两次、下行数据量翻倍
     *      （实测 170 帧/秒 vs 设备 100 帧/秒，队列丢 41%，听感持续断续）。
     *
     * 所以：非权威入口只在「还没挂过」时绑；权威入口永远先摘后挂。
     */
    private fun bindRemoteAudioSink(candidate: MediaStreamTrack?, authoritative: Boolean) {
        // ⚠️ 必须整段串行：`onTrack` 和 `setRemoteDescription` 的成功回调**在不同线程**上，
        // 两处都会走到这里。不加锁时两边都看到 `remoteSink == null`，各挂一个 sink。
        synchronized(sinkLock) {
            val pc = pc ?: return
            val device = downlink ?: return
            val track = (candidate ?: pickRemoteAudioTrack(pc)) as? AudioTrack ?: return
            if (remoteSink != null) {
                if (!authoritative) return
                // 权威入口：之前那条是占位壳（或被换掉的旧轨道），先摘掉再挂。
                detachRemoteAudioSink()
            }

            // ⚠️ `onData` 在 **libwebrtc 的音频线程**上：这里只入队，绝不碰设备。
            val sink = AudioTrackSink { data, bitsPerSample, sampleRate, channels, numberOfFrames ->
                if (data != null) {
                    if (sinkFormat == "-") {
                        sinkFormat = "bytes=${data.size} bits=$bitsPerSample sr=$sampleRate " +
                            "ch=$channels frames=$numberOfFrames"
                    }
                    val now = System.nanoTime()
                    if (lastSinkAt != 0L) {
                        val gapMs = (now - lastSinkAt) / 1_000_000.0
                        sinkGapSumMs += gapMs
                        sinkGapCount++
                        if (gapMs < sinkGapMinMs) sinkGapMinMs = gapMs
                        sinkGapAvgMs = sinkGapSumMs / sinkGapCount
                    }
                    lastSinkAt = now
                    updateRenderLevel(data)
                    if (!device.write(data, sampleRate, channels, bitsPerSample)) {
                        downlinkWriteFails++
                    }
                }
            }
            runCatching { track.addSink(sink) }
                .onSuccess {
                    remoteTrack = track
                    remoteSink = sink
                    sinkAttachCount++
                    device.onRender = renderSink
                    device.setVolume(remoteVolume)
                    audioNote = if (authoritative) "remote-sink ok(onTrack)" else "remote-sink ok"
                }
                .onFailure { audioNote = "remote-sink 失败：${it.message}" }
        }
    }

    /** 从收发端列表里挑一条远端音频轨（非权威入口用；权威入口直接用 `onTrack` 交上来的那条）。 */
    private fun pickRemoteAudioTrack(pc: RTCPeerConnection): AudioTrack? = runCatching {
        pc.transceivers.orEmpty().asSequence()
            .mapNotNull { it?.receiver?.track }
            .filterIsInstance<AudioTrack>()
            .firstOrNull()
    }.getOrNull()

    /** 兼容既有两个非权威入口（`onTrack` 之外）。 */
    private fun ensureRemoteAudioSink() = bindRemoteAudioSink(candidate = null, authoritative = false)

    private fun detachRemoteAudioSink() {
        synchronized(sinkLock) {
            val track = remoteTrack
            val sink = remoteSink
            remoteTrack = null
            remoteSink = null
            if (track != null && sink != null) runCatching { track.removeSink(sink) }
        }
    }

    /** 远端帧的峰值扫描（480 个样本 / 帧，在音频线程上做，很便宜）。 */
    private fun updateRenderLevel(pcm: ByteArray) {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        if (peak > renderPeak) renderPeak = peak
        if (peak > LOUD_THRESHOLD) renderLoudFrames++
    }

    /** 门禁用：发送端的即时状态。
     *
     * `push` = 本地推进了多少帧（helper 停了这里就该冻结）；
     * `track` = 读回发送端此刻挂着的轨道 —— 这是 `replaceTrack` 有没有生效的唯一证据。
     */
    fun audioDebug(): String {
        val track = runCatching { audioTransceiver?.sender?.track?.kind }.getOrNull()
        val device = downlink
        val gap = if (sinkGapCount == 0L) "-" else "%.1f/%.1f".format(sinkGapAvgMs, sinkGapMinMs)
        return "push=$audioPushedFrames track=${track ?: "null"} " +
            "play=${device?.playedFrames ?: 0} dropped=${device?.droppedFrames ?: 0} " +
            "writeFail=$downlinkWriteFails peak=$renderPeak loud=$renderLoudFrames " +
            "gap=$gap attach=$sinkAttachCount sink=[$sinkFormat] note=[$audioNote]"
    }

    /**
     * 门禁/诊断：把「下行 sink 到底挂在哪个音轨上」摊开。
     *
     * 每个收发端一段 `方向/种类/id 前 6 位/是不是本机那条`。2026-11-06 真机上
     * 「两端都听不到对方」时，靠这两组数分清两种成因：
     *   ① 挂在**本机**音轨上（`LOCAL`）⇒ 自己播自己，对端一帧都收不到；
     *   ② 一条都没挂上（`sinkdbg=[]` / `bound=-`）⇒ 下行一帧都不会回调。
     */
    fun audioSinkDebug(): String {
        val pc = pc ?: return "sinkdbg=no-pc"
        val localId = runCatching { audioTrack?.id }.getOrNull()
        val boundId = runCatching { remoteTrack?.id }.getOrNull()
        val parts = runCatching {
            pc.transceivers.orEmpty().map { transceiver ->
                val track = transceiver?.receiver?.track
                val kind = runCatching { track?.kind }.getOrNull() ?: "-"
                val id = runCatching { track?.id }.getOrNull()
                val dir = runCatching { transceiver?.direction?.name }.getOrNull() ?: "-"
                val mark = when {
                    id == null -> "none"
                    localId != null && id == localId -> "LOCAL"
                    else -> "remote"
                }
                "$dir/$kind/${id?.take(6) ?: "-"}/$mark"
            }
        }.getOrElse { listOf("err=${it.message}") }
        return "sinkdbg=[${parts.joinToString(" ")}] bound=${boundId?.take(6) ?: "-"} " +
            "local=${localId?.take(6) ?: "-"}"
    }

    override fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int): Boolean {
        val pc = pc ?: return false
        // ⚠️ 与 Android 不同：这个方法没有返回值，非法候选只表现为**抛异常**。
        // 所以「被拒候选数」在 Windows 上只能靠这里数，且可能比 Android 少（native 也可能静默吞掉）。
        return runCatching {
            pc.addIceCandidate(RTCIceCandidate(sdpMid.orEmpty(), sdpMLineIndex, candidate))
            true
        }.getOrDefault(false)
    }

    override fun isStable(): Boolean = pc?.signalingState == RTCSignalingState.STABLE

    override fun applyConfiguration(config: RtcConfig) {
        this.config = config
        val pc = pc ?: return
        runCatching { pc.setConfiguration(config.toWebRtc()) }
            .onFailure { callbacks?.onFailure("setConfiguration 被拒：${it.message}") }
        callbacks?.let { pushDiagnostics(it) }
    }

    override fun restartIce() {
        runCatching { pc?.restartIce() }
            .onFailure { callbacks?.onFailure("restartIce 失败：${it.message}") }
    }

    override fun sendText(text: String): Boolean {
        val channel = channel ?: return false
        if (channel.state != RTCDataChannelState.OPEN) return false
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return runCatching {
            // `send` 在 webrtc-java 里声明了 throws Exception —— 通道刚关时就会走到这里。
            channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), false))
            true
        }.getOrDefault(false)
    }

    override fun isChannelOpen(): Boolean = channel?.state == RTCDataChannelState.OPEN

    override fun snapshotStats(): LinkStats? {
        val pc = pc ?: return null
        // 异步：本次只发起查询，返回的是上一次的结果（首次必然为 null）。
        runCatching {
            pc.getStats(object : RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: dev.onvoid.webrtc.RTCStatsReport?) {
                    val entries = report?.stats?.values?.map { it.toFlatEntry() }.orEmpty()
                    // 纯解析，不碰任何状态，所以可以在 native 线程上做完再存快照。
                    lastEntries = entries
                    lastStats = LinkStatsParser.parse(entries)
                }
            })
        }
        return lastStats
    }

    override fun snapshotDiagnostics(): PeerDiagnostics {
        val current = pc
        return PeerDiagnostics(
            iceConnectionState = current?.iceConnectionState?.name ?: PeerDiagnostics.UNKNOWN,
            peerConnectionState = current?.connectionState?.name ?: PeerDiagnostics.UNKNOWN,
            signalingState = current?.signalingState?.name ?: PeerDiagnostics.UNKNOWN,
            iceGatheringState = current?.iceGatheringState?.name ?: PeerDiagnostics.UNKNOWN,
            dataChannelState = channel?.state?.name ?: PeerDiagnostics.UNKNOWN,
        )
    }

    override fun close() {
        val currentChannel = channel
        channel = null
        currentChannel?.let {
            runCatching { it.unregisterObserver() }
            runCatching { it.close() }
            runCatching { it.dispose() }
        }
        // 下行先摘 sink 再关设备：顺序反了会让还在回放的线程往已释放的句柄里写。
        detachRemoteAudioSink()
        downlink?.let { runCatching { it.close() } }
        downlink = null
        // 音频资源是本类自己的：先音轨后音源（反了会让 track 持有已释放的 source）。
        audioTrack?.let { runCatching { it.dispose() } }
        audioTrack = null
        audioSource?.let { runCatching { it.dispose() } }
        audioSource = null
        audioTransceiver = null
        lastEntries = emptyList()
        // 诊断读数按「一条链路一份」算：重连之后不继承上一条链路的数字。
        audioPushedFrames = 0
        renderPeak = 0
        renderLoudFrames = 0
        downlinkWriteFails = 0
        sinkFormat = "-"
        sinkAttachCount = 0
        sinkGapMinMs = Double.MAX_VALUE
        sinkGapAvgMs = 0.0
        sinkGapSumMs = 0.0
        sinkGapCount = 0
        lastSinkAt = 0
        val currentPc = pc
        pc = null
        currentPc?.let {
            // `RTCPeerConnection` 继承的是 `NativeObject`，**没有** `dispose()`；
            // `close()` 就是它的收尾（与 `RTCDataChannel` 那个 `DisposableNativeObject` 不同）。
            runCatching { it.close() }
        }
        callbacks = null
        lastStats = null
    }

    // ── 观察者 ──────────────────────────────────────────────────────────────

    private fun observer(): PeerConnectionObserver = object : PeerConnectionObserver {

        override fun onIceCandidate(candidate: RTCIceCandidate?) {
            val value = candidate ?: return
            callbacks?.onLocalIce(value.sdp, value.sdpMid, value.sdpMLineIndex)
        }

        override fun onIceGatheringChange(state: RTCIceGatheringState?) {
            if (state == RTCIceGatheringState.COMPLETE) callbacks?.onIceGatheringComplete()
            callbacks?.let { pushDiagnostics(it) }
        }

        override fun onSignalingChange(state: RTCSignalingState?) {
            // 回到 STABLE 才允许补发挂起的那一轮 offer（见 DesktopPeerLink.requestOffer）。
            if (state == RTCSignalingState.STABLE) callbacks?.onSignalingStateStable()
            callbacks?.let { pushDiagnostics(it) }
        }

        override fun onConnectionChange(state: RTCPeerConnectionState?) {
            if (state == RTCPeerConnectionState.FAILED) callbacks?.onFailure("连接失败（DTLS/ICE 均未通）")
            callbacks?.let { pushDiagnostics(it) }
        }

        override fun onIceConnectionChange(state: RTCIceConnectionState?) {
            if (state == RTCIceConnectionState.FAILED) callbacks?.onFailure("ICE 连接失败")
            callbacks?.let { pushDiagnostics(it) }
        }

        override fun onDataChannel(channel: RTCDataChannel?) {
            // answer 方在这里拿到 offer 方建的那一条。
            if (channel != null) attachChannel(channel)
        }

        override fun onTrack(transceiver: RTCRtpTransceiver?) {
            // libwebrtc 只在**第一次**拿到该收发端的远端轨道时通知一次。
            // 对端后开麦的情况靠 setRemoteDescription 那条路兜住，两处都调才是幂等完整的。
            //
            // ⚠️ 这里必须是**权威**入口：`transceiver.receiver.track` 是刚刚到达的真轨道，
            // 而更早那次（`setRemoteDescription` 成功时）拿到的往往只是占位壳。2026-11-06
            // 真机「两端都听不到」就是被壳顶住了真轨道（见 [bindRemoteAudioSink]）。
            bindRemoteAudioSink(transceiver?.receiver?.track, authoritative = true)
        }
    }

    private fun attachChannel(channel: RTCDataChannel) {
        this.channel = channel
        runCatching {
            channel.registerObserver(object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    when (channel.state) {
                        RTCDataChannelState.OPEN -> callbacks?.onDataChannelOpen()
                        RTCDataChannelState.CLOSING, RTCDataChannelState.CLOSED -> {
                            callbacks?.onDataChannelClosed()
                        }
                        else -> Unit
                    }
                    callbacks?.let { pushDiagnostics(it) }
                }

                override fun onMessage(buffer: RTCDataChannelBuffer?) {
                    val data = buffer?.data ?: return
                    if (buffer.binary) return
                    val bytes = ByteArray(data.remaining())
                    data.duplicate().get(bytes)
                    callbacks?.onMessage(String(bytes, StandardCharsets.UTF_8))
                }
            })
        }.onFailure { callbacks?.onFailure("注册 DataChannel 观察者失败：${it.message}") }
    }

    private fun pushDiagnostics(sink: RtcBackendCallbacks) {
        sink.onDiagnostics(snapshotDiagnostics())
    }

    private companion object {
        const val AUDIO_TRACK_ID = "cinema-voice"

        /** `pushAudio` 的 `bitsPerSample` 只能是 16（官方文档与 spike 实测一致）。 */
        const val BITS_PER_SAMPLE = 16

        const val SAMPLE_RATE = 48_000

        /** helper 采集是单声道；立体声要在 helper 侧重采样，不在父进程里瞎猜。 */
        const val CHANNELS = 1

        /**
         * 「这一帧有声音」的峰值下限（16-bit 满幅 32767）。
         *
         * 取 ~-40 dBFS：房间底噪和小声说话都能过，纯编码静音帧（峰值 0–个位数）过不去。
         * 只用于诊断计数，不参与任何音频处理。
         */
        const val LOUD_THRESHOLD = 300
    }
}

/**
 * 进程级 `PeerConnectionFactory`（+ headless ADM）。
 *
 * ── 为什么必须单例 ──────────────────────────────────────────────────────────
 * 工厂本身很重（内部起 network / signaling / worker 三组线程），而且**一个进程里
 * 多条 PeerConnection 完全应该共用一个工厂**（C2 的自检就是两个 PC 共用一个）。
 *
 * ── 为什么是 headless ───────────────────────────────────────────────────────
 * 这是整个 W7 的音频方案的地基（见 `docs/Windows-音频独立进程-设计.md` §1.1）：
 * 一个 factory 只能有一种音频来源。父进程要外部推 PCM（`CustomAudioSource`），
 * 就只能全程 headless —— 一旦让 ADM 去开麦克风，`pushAudio` 那条路会
 * `IllegalStateException` + `RACE_CHECKER` abort。
 *
 * ⚠️ 代价（W7 拆分 §4/R1，已获批）：**没有「退回系统麦克风」这个降级档**。
 */
object WebRtcEngine {

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Volatile
    private var adm: HeadlessAudioDeviceModule? = null

    /** 必须在同一条线程上调用（就是 `DesktopPeerLink` 的 dispatcher）。 */
    fun acquire(): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            val createdAdm = HeadlessAudioDeviceModule()
            // ⚠️ **必须显式 initPlayout() + startPlayout()**（顺序不能反）。
            // 官方文档：playout「drives the receive side of every peer connection of this factory」——
            // 远端音频是靠 playout 从渲染管线里拉出来的。不启动它时 sink 仍会收到回调，
            // 但**没有任何节拍约束**：实测回调间隔 5.6 ms 却每次给 10 ms 音频（快 ~1.8 倍），
            // 下行队列因此丢 42%，听感是持续断续。启动之后节拍才由 dummy 音频层按实时驱动。
            val playoutStarted = runCatching {
                createdAdm.initPlayout()
                createdAdm.startPlayout()
            }
            if (playoutStarted.isFailure) {
                runCatching { createdAdm.dispose() }
                throw IllegalStateException(
                    "headless ADM 启动 playout 失败：${playoutStarted.exceptionOrNull()?.message}",
                    playoutStarted.exceptionOrNull(),
                )
            }
            // ⚠️ 不要在这里 `setAudioSource(...)`：实测 headless ADM **从不**调用
            // `AudioSource.onPlaybackData`，而设了之后进程会在 20 秒内访问违规退出（0xC0000005）。
            // 播放走 AudioTrackSink。
            //
            // 也不要传 `AudioProcessing`：实测对渲染节拍没有任何影响，却多出一个会和
            // AecChain 抢配置的实例。
            val created = try {
                PeerConnectionFactory(createdAdm)
            } catch (error: Throwable) {
                runCatching { createdAdm.dispose() }
                throw error
            }
            adm = createdAdm
            factory = created
            return created
        }
    }

    fun shutdown() {
        synchronized(this) {
            factory?.let { runCatching { it.dispose() } }
            factory = null
            // 工厂先拆，再停 playout、最后放 ADM：顺序反了会让 native 侧持有已释放的 ADM。
            adm?.let {
                runCatching { it.stopPlayout() }
                runCatching { it.dispose() }
            }
            adm = null
        }
    }
}

// ── 映射 ────────────────────────────────────────────────────────────────────

private fun RtcConfig.toWebRtc(): RTCConfiguration = RTCConfiguration().apply {
    iceServers = this@toWebRtc.iceServers.map { server ->
        RTCIceServer().apply {
            urls = server.urls
            username = server.username
            // core:model 的 `credential` 对应 webrtc-java 的 `password`。
            password = server.credential
        }
    }
    iceTransportPolicy = when (this@toWebRtc.transportPolicy) {
        TransportPolicy.ALL -> RTCIceTransportPolicy.ALL
        TransportPolicy.RELAY_ONLY -> RTCIceTransportPolicy.RELAY
    }
}

/**
 * 与 Android 侧 `RTCStats.toFlatEntry()` 同构：把 `id` / `type` 补回去。
 *
 * ⚠️ `type` 必须是 **kebab-case**（`local-candidate` / `outbound-rtp` / `media-source`）——
 * `LinkStatsParser` 是按这几个字面量索引的，而 webrtc-java 给的是枚举
 * （`LOCAL_CANDIDATE`）。不转这一下，诊断页会**永远空白而功能一切正常**，
 * 是最难发现的那类错。
 */
private fun RTCStats.toFlatEntry(): Map<String, Any?> {
    val entry = HashMap<String, Any?>(attributes.size + 2)
    entry.putAll(attributes)
    entry["id"] = id
    entry["type"] = type.name.lowercase().replace('_', '-')
    return entry
}
