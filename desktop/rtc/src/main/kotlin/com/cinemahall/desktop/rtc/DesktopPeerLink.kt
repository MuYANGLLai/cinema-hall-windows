package com.cinemahall.desktop.rtc

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.rtc.CallAudio
import com.cinemahall.core.rtc.CallAudioState
import com.cinemahall.core.rtc.LinkStats
import com.cinemahall.core.rtc.PeerDiagnostics
import com.cinemahall.core.rtc.PeerLink
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.rtc.RtcConfig
import com.cinemahall.core.rtc.RtcSignalSink
import com.cinemahall.core.rtc.TransportPolicy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 麦克风增益上限（2026-11-12 用户口径「麦克风输入可以调节到 200%」）。
 *
 * 100% = 原样推给发送端；200% = 每个样点 ×2。超过 100% 时**会削顶**（16-bit 满刻度
 * 就截断），这是增益本身的物理限制 —— 所以界面只在用户明确往上拖时才给到 2.0，
 * 默认仍是 1.0。只有桌面端放宽：Android 侧的同名端口仍是 0..1。
 */
const val MAX_MIC_GAIN: Float = com.cinemahall.core.rtc.CallAudioState.MAX_MIC_GAIN

/**
 * [PeerLink] 的 Windows 实现 —— **W7 C1：状态机骨架**。
 *
 * ── 这一版做了什么、没做什么 ────────────────────────────────────────────────
 * 做：从 Android 的 `WebRtcPeerLink` 逐条搬过来的**协商状态机**，一个字节的 native 都不碰。
 *  - 谁是 offer 方：`myUserId.value < remoteUserId.value`，两端各算一次必然一致（无 glare）；
 *  - 第二轮 offer 在「首轮还在飞」或「信令未 STABLE」时**挂起**，回到 `STABLE` 再补；
 *  - 远端候选先排队，`setRemoteDescription` 落地后才补投（否则被 libwebrtc 静默丢掉）；
 *  - `rejectedIceCandidates` 自己数（`addIceCandidate` 失败不抛异常、不打日志）；
 *  - 代次（generation）：旧连接迟到的回调一律丢弃，避免过期的 FAILED 打死新链路；
 *  - `rtc.renegotiate` 的 `reason` 是**行为判据**：`audio` 只做普通 re-offer，其余重启 ICE。
 *
 * 没做（C3/C4 才做）：音频。现在 [callAudio] 只如实表达「用户意图」，
 * `sending` / `receiving` **恒为 false** —— 界面会显示「已开启，等音频通道建立」，
 * 那是真的：这条链路上确实还没有任何音频。
 *
 * ── 线程 ────────────────────────────────────────────────────────────────────
 * 所有会改状态的动作都 `post` 到 [dispatcher]（单线程 FIFO）。native 回调可以来自任意线程，
 * 也统一收到这里 —— 出站信令（offer / answer / candidate）必须只从一个线程按序发出，
 * 否则「候选抢在 offer 前面」会让对端静默丢候选，症状是「有时连不上、有时绕远路走中继」。
 */
class DesktopPeerLink(
    private val backend: RtcBackend,
    private val dispatcher: LinkDispatcher = SingleThreadDispatcher(),
    /**
     * 上行采集（C3）。`null` = 这台机器上没装配音频（C1/C2 的状态机测试就是这种情况）——
     * 那时 [callAudio] 的 `sending` 永远是 false，界面该显示「没有音频设备」。
     */
    private val uplink: AudioUplink? = null,
    /**
     * AEC / NS / AGC 链（C3）。`CustomAudioSource` 不经过 WebRTC 内部 APM，
     * 所以回声消除必须自己跑，见 [AecChain] 的注释。
     */
    private val aec: AecChain? = null,
) : PeerLink {

    private val _state = MutableStateFlow<PeerState>(PeerState.Idle)
    override val state: StateFlow<PeerState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<LinkStats?>(null)
    override val stats: StateFlow<LinkStats?> = _stats.asStateFlow()

    private val _diagnostics = MutableStateFlow(PeerDiagnostics())
    override val diagnostics: StateFlow<PeerDiagnostics> = _diagnostics.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _remote = MutableStateFlow<UserId?>(null)
    override val remote: StateFlow<UserId?> = _remote.asStateFlow()

    private val _isOfferer = MutableStateFlow<Boolean?>(null)
    override val isOfferer: StateFlow<Boolean?> = _isOfferer.asStateFlow()

    private val _callAudio = MutableStateFlow(CallAudioState())
    override val callAudio: StateFlow<CallAudioState> = _callAudio.asStateFlow()

    /** 内部简写：读的就是 [isOfferer]，不存在第二份真相（角色判定的重复实现会导致 glare）。 */
    private val offerer: Boolean get() = _isOfferer.value == true

    // ── 会话状态（只在 dispatcher 线程上改）──────────────────────────────────
    private var peerUserId: UserId? = null
    private var sink: RtcSignalSink? = null
    private var config: RtcConfig = RtcConfig()

    /** `start` 之后、`close` 之前为 true。所有「要不要往后端发东西」的判断都看它。 */
    private var started = false

    /** 门禁/诊断：`onSignal` 收了几条。0 = 信号流根本没到这一层。 */
    @Volatile
    var receivedSignals: Int = 0
        private set

    /** 门禁/诊断：最后收到的一条信号类型（`-` = 一条都没有）。 */
    @Volatile
    var lastSignalKind: String = "-"
        private set

    /** 门禁/诊断：协商最近一步的结局（见 [RtcBackend.negotiationNote]）。 */
    val negotiationNote: String get() = backend.negotiationNote

    /** 门禁/诊断：answer 方挂音频收发端的结局（见 [RtcBackend.audioAttachNote]）。 */
    val audioAttachNote: String get() = backend.audioAttachNote

    /**
     * 门禁/诊断：这条链路到底有没有接上上行采集。
     *
     * `false` = 点开麦克风只会报「本机没有接入音频采集」—— 那是**装配缺失**，不是设备坏。
     * 它是 `0.1.2` 那条真机缺陷的直接读数（界面先造出来的空壳链路丢掉了 `uplink`），
     * 所以自检行里会打印它，别只信「链路已连通」。
     */
    val hasAudioUplink: Boolean get() = uplink != null

    /**
     * 门禁/诊断：通过入口条件、真进了状态机的信号条数。
     *
     * 与 [receivedSignals] 的差就是「到了这一层但被前置条件丢掉」的那几条 ——
     * 这两个数一样时，卡点一定在后端/原生侧，不在分发。
     */
    @Volatile
    var acceptedSignals: Int = 0
        private set

    /** 门禁/诊断：最近一条被入口条件丢掉的原因（`-` = 没丢过）。 */
    @Volatile
    var lastDrop: String = "-"
        private set

    /**
     * 本端已经发起一轮 offer、但还没拿到本地描述。
     *
     * 补的是 `signalingState()` 追不上的那个窗口：`createOffer` 发出到 native 回调之间，
     * 状态读出来仍是 `STABLE`。不记这一笔，一次几乎同时的操作就会叠出第二条 offer，
     * 而 `setLocalDescription(offer)` 在 `have-local-offer` 下会失败 —— 本类把 SDP 失败
     * 一律判成链路失败，于是「点两下」能打死一条好链路。
     */
    private var offerInFlight = false

    /** 挂起的那一轮要不要带 ICE 重启；`null` = 没有挂起的 offer。 */
    private var deferredOfferIceRestart: Boolean? = null

    /**
     * 远端候选暂存区。
     *
     * 候选与 offer 走两条不同的路径（候选来自 native 的 ICE 线程），谁先到不确定。
     * 早到的候选在 `remoteDescription == null` 时 `addIceCandidate` 会**返回 false 且不报错**，
     * 候选就这么没了。所以先排队，等远端描述落地再补投。
     */
    private val pendingCandidates = ArrayDeque<PendingCandidate>()

    /** 远端描述是否已落地（决定候选是「补投」还是「排队」）。 */
    private var remoteDescriptionSet = false

    private var rejectedIceCandidates = 0

    /**
     * 连接代次。**只在 [teardown] 里自增。**
     *
     * `start()` 是「先拆旧的、再建新的」，而两条连接的回调都是异步的：不认代次的话，
     * 旧连接一条迟到的 `FAILED`/`CLOSED` 会把刚建好的链路直接标死。
     */
    private var generation = 0L

    // ── 通话意图（资源在 C3/C4 才有）──────────────────────────────────────────
    private var callRequested = false
    private var callVolume = CallAudioState.DEFAULT_VOLUME
    /** 麦克风发送音量：桌面端采集是自有 PCM，这是**真增益**（见 [pushWithGain]）。 */
    private var micVolume = 1f
    // 兜底开关默认关（2026-11-06 用户口径；见 `CallAudioState.limitMovieVolume`）。
    private var movieVolumeLimited = false

    // ── 对外 API ───────────────────────────────────────────────────────────

    override fun start(
        myUserId: UserId,
        remoteUserId: UserId,
        config: RtcConfig,
        sink: RtcSignalSink,
    ) = post {
        teardown()

        this.peerUserId = remoteUserId
        this.sink = sink
        this.config = config
        // 两端各算一次，结果必然一致 ⇒ 不可能出现双方同时发 offer（glare）。
        _isOfferer.value = myUserId.value < remoteUserId.value

        _remote.value = remoteUserId
        _stats.value = null
        _diagnostics.value = PeerDiagnostics()
        rejectedIceCandidates = 0
        _state.value = PeerState.Negotiating
        started = true

        val gen = generation
        // 下行播放的 AEC 参考：`WebRtcBackend` 在**播放线程**上逐帧回调，
        // 用「本机实际放出来的声音」喂 AEC3 的参考流（见 AecChain 的注释）。
        val chain = aec
        backend.setRenderSink(if (chain == null) null else { pcm -> chain.feedRender(pcm) })
        backend.setRemoteVolume(callVolume)
        backend.start(config, offerer, callbacksFor(gen))

        // 只由 offer 方发起首轮（answer 方自己新增不了 m 行，只能等对端 offer）。
        if (offerer) requestOffer(iceRestart = false)
    }

    override fun onSignal(signal: RtcSignal) = post {
        // 门禁/诊断：这一格能区分「信号根本没到这一层」与「到了但在前置条件上被丢掉」。
        receivedSignals++
        lastSignalKind = signal.javaClass.simpleName
        // 迟到的旧对端消息（换了房间、换了人）一律丢。
        if (signal.from != peerUserId) {
            lastDrop = "${signal.javaClass.simpleName}:发起人不符 from=${signal.from.value} peer=${peerUserId?.value ?: "null"}"
            return@post
        }
        if (!started) {
            lastDrop = "${signal.javaClass.simpleName}:链路未启动"
            return@post
        }
        acceptedSignals++

        when (signal) {
            is RtcSignal.Offer -> handleRemoteOffer(signal.sdp)
            is RtcSignal.Answer -> handleRemoteAnswer(signal.sdp)
            is RtcSignal.Ice -> handleRemoteIce(signal)
            // 只有 offer 方能发新 offer；对端发这条 = 请本端重新发起。
            // 非 offer 方收到说明对端判错了自己的角色 —— 忽略，不要跟着乱发 offer。
            is RtcSignal.Renegotiate -> if (offerer) {
                requestOffer(iceRestart = signal.reason != RtcSignal.RENEGOTIATE_REASON_AUDIO)
            }

            // `rtc.reset` 是链路生命周期的事（拆了重建），PeerCoordinator 会在把信令
            // 转进来之前截走它。真收到只可能是装配写错：什么都不做，而不是自己拆自己。
            is RtcSignal.Reset -> Unit
        }
    }

    override fun setIceServers(servers: List<IceServer>) = post {
        config = config.copy(iceServers = servers)
        if (!started) return@post
        backend.applyConfiguration(config)
        // libwebrtc 不会因为 iceServers 变了就自己重取：只下发凭证而不重启 ICE 等于没做。
        restartIceInternal()
    }

    override fun setTransportPolicy(policy: TransportPolicy) = post {
        config = config.copy(transportPolicy = policy)
        if (!started) return@post
        backend.applyConfiguration(config)
        restartIceInternal()
    }

    override fun restartIce() = post { restartIceInternal() }

    override fun send(text: String): Boolean = if (!started) false else backend.sendText(text)

    override fun refreshStats() = post {
        if (!started) return@post
        _stats.value = backend.snapshotStats()
        // 音量的「真值」在统计里：包计数为 0 时界面才能说「一个包都还没到」。
        backend.audioRtp()?.let { counters ->
            val readout = runCatching { backend.downlinkReadout() }.getOrNull()
            _callAudio.update { current ->
                val received = (counters.packetsReceived ?: 0) > 0
                // 「收得到、放不出来」必须与「一个包都没收到」分成两句话：前者查扬声器 /
                // 输出设备，后者查协商与对端。2026-11-06 真机上这两件事被同一句盖住了。
                val nextError = when {
                    received && readout != null && readout.playedFrames == 0L ->
                        if (readout.writeFails > 0) {
                            "能收到对端语音，但写不进扬声器（${readout.writeFails} 次失败，查输出设备）"
                        } else {
                            "能收到对端语音，正在等播放设备"
                        }
                    // 只清我们自己写的那一句；采集/设备的报错不能在这一层被抹掉。
                    current.error?.startsWith("能收到对端语音") == true -> null
                    else -> current.error
                }
                current.copy(
                    audioPacketsSent = counters.packetsSent,
                    audioPacketsReceived = counters.packetsReceived,
                    // 「在收」的判据是**对端真的在发音频包**，不是本机放出了声。
                    // 这两件事要分得开：放不出声是扬声器的问题，界面该说的是另一句。
                    receiving = received,
                    error = nextError,
                )
            }
        }
        val diagnostics = backend.snapshotDiagnostics()
        _diagnostics.value = diagnostics.copy(rejectedIceCandidates = rejectedIceCandidates)
    }

    override fun close() = post { closeNow() }

    /**
     * 就地收尾：**必须由 dispatcher 线程调用**（[close] 就是这么进来的）。
     *
     * 与 [close] 分开是因为收尾路径不能只往队列里丢一条 `close`：执行器随后就
     * `shutdownNow()`，排队任务会被丢掉，PC 与 native 工厂都不会释放，进程退出时
     * 崩在 native 侧（0xC0000005）。[com.cinemahall.desktop.app.net.DesktopModule]
     * 用 `runBlocking` 把它拉到 dispatcher 线程上同步跑完。
     */
    fun closeNow() {
        // 先杀 helper，再拆 PC：反过来的话父进程先退出会让采集进程短暂变孤儿
        // （虽然 stdin EOF 看门狗也会兜住，但不要依赖它）。
        runCatching { uplink?.stop() }
        runCatching { aec?.close() }
        teardown()
        // 链路拆掉 = 那一场通话结束：`requested` 是用户意图，但它属于这一条链路。
        // 留着会让重新进房时「通话自己复活」，用户没按过任何开关（Android 侧踩过）。
        callRequested = false
        // 采集已经在上面的 `uplink.stop()` 里真的停了；这里把读数同步成事实。
        _callAudio.update {
            it.copy(
                sending = false,
                receiving = false,
                audioPacketsSent = null,
                audioPacketsReceived = null,
                error = null,
            )
        }
        peerUserId = null
        sink = null
        _isOfferer.value = null
        _remote.value = null
        _stats.value = null
        _diagnostics.value = PeerDiagnostics()
        rejectedIceCandidates = 0
        _state.value = PeerState.Closed
        refreshCallAudio()
    }

    // ── CallAudio（C1：只有意图，没有资源）────────────────────────────────────

    override fun setCallEnabled(enabled: Boolean) = post {
        if (callRequested == enabled) return@post
        callRequested = enabled
        // `requested` 是**意图**，必须在任何早退分支之前写下去 —— 否则界面会出现
        // 「点开了但开关说自己没开」这种自相矛盾的读数（C3 首次跑就撞到了）。
        _callAudio.update { it.copy(requested = enabled) }
        // 这是一次新尝试：把上一次的失败原因清掉，否则界面会一直挂着一条已经不成立的红字。
        _callAudio.update { it.copy(error = null) }

        if (!enabled) {
            // 关麦 = **杀进程** + **摘音轨**。
            // 前者是 Windows 的硬约束（Phase 0 §6：注册表只看进程，kill 之后 6–139 ms 才更新）；
            // 后者是这个进程内能让 RTP 真正停住的那一步 —— 光停采集、音轨还挂着的话，
            // `CustomAudioSource` 会继续产出静音帧，对端看到的是「一直在收包但没声音」。
            uplink?.stop()
            backend.setAudioSending(false)
            _callAudio.update { it.copy(sending = false) }
            return@post
        }

        startCapture()
    }

    /**
     * 换麦克风输入源（2026-11-12 用户口径「点『麦克风』下拉选择输入源」）。
     *
     * ── 为什么换设备要停一次采集 ────────────────────────────────────────────────
     * 采集跑在 `--audio-helper` **子进程**里（设计文档 §3 的硬约束），设备是它启动时
     * `getMixer()` 定的。改父进程里的一个字段不会让子进程换线，只能 kill 再起 ——
     * 这与「关麦 = 杀进程」是同一条机制，顺带也是注册表口径正确的做法。
     *
     * 没在通话时只记下来：下次 [setCallEnabled] 起采集就用新设备。
     */
    fun setAudioInput(name: String?) = post {
        val device = uplink ?: return@post
        device.setPreferredInput(name)
        if (!callRequested) return@post
        device.stop()
        _callAudio.update { it.copy(sending = false) }
        startCapture()
    }

    /** 起采集并挂音轨（[setCallEnabled] 与 [setAudioInput] 共用这一份）。 */
    private fun startCapture() {
        val device = uplink
        if (device == null) {
            // 没有装配音频管线：如实说「没有」，不假装在采集。
            _callAudio.update { it.copy(sending = false, error = "本机没有接入音频采集") }
            return
        }

        val started = device.start(
            onBlock = { block ->
                // ⚠️ 这里在 **helper 的读取线程**上：单线程按帧序推，正是 pushAudio 的要求。
                // 先过 AEC（用本机正在放出来的声音作参考），再按「麦克风输入音量」缩放，
                // 最后推进发送端。
                val processed = aec?.capture(block) ?: block
                backend.pushCapturePcm(pushWithGain(processed))
            },
            onState = { state, note ->
                // 回调来自采集线程：状态改动一律回到 dispatcher 线程。
                post {
                    when (state) {
                        AudioUplinkState.ON -> {
                            // 采集真的起来了才挂音轨：先挂后采会让对端先收到一段静音。
                            backend.setAudioSending(true)
                            _callAudio.update { it.copy(sending = true, error = null) }
                        }
                        AudioUplinkState.STARTING -> Unit
                        AudioUplinkState.OFF -> _callAudio.update {
                            it.copy(sending = false)
                        }
                        AudioUplinkState.FAILED -> {
                            // 崩溃也要摘音轨：否则对端会一直收「静音包」，误以为麦克风还开着。
                            backend.setAudioSending(false)
                            // 链路不降级：PC / DataChannel / 播放同步都留着，只是没声音了。
                            _callAudio.update {
                                it.copy(sending = false, error = note ?: "麦克风已断开，可重试")
                            }
                        }
                    }
                }
            },
        )
        _callAudio.update {
            it.copy(sending = started, error = if (started) null else "麦克风不可用（见日志）")
        }
    }

    override fun setCallVolume(volume: Float) = post {
        callVolume = volume.coerceIn(0f, 1f)
        // 音量在播放线程上逐帧生效，改这里不需要重开设备、也不会打断正在说的话。
        backend.setRemoteVolume(callVolume)
        refreshCallAudio()
    }

    /**
     * 麦克风**发送**音量。桌面端的采集是我们自己的 PCM 块（见 [AudioUplink]），
     * 所以这里是**真增益**：在推给发送端之前把样点按系数缩放。
     */
    override fun setMicVolume(volume: Float) = post {
        micVolume = volume.coerceIn(0f, MAX_MIC_GAIN)
        refreshCallAudio()
    }

    override fun setMovieVolumeLimit(enabled: Boolean) = post {
        movieVolumeLimited = enabled
        refreshCallAudio()
    }

    // ── 协商 ────────────────────────────────────────────────────────────────

    private fun requestOffer(iceRestart: Boolean) {
        if (!started) return
        // 不 `STABLE` 就先挂着 —— 连发两条 offer 会让 answer 落在错误状态。
        if (offerInFlight || !backend.isStable()) {
            deferredOfferIceRestart = iceRestart
            return
        }
        deferredOfferIceRestart = null
        offerInFlight = true
        backend.createOffer(iceRestart)
    }

    /** 回到 `STABLE` 时把挂起的那一轮补上。 */
    private fun flushDeferredOffer() {
        val iceRestart = deferredOfferIceRestart ?: return
        if (offerInFlight || !backend.isStable()) return
        deferredOfferIceRestart = null
        offerInFlight = true
        backend.createOffer(iceRestart)
    }

    private fun handleRemoteOffer(sdp: String) {
        if (offerer) {
            // 双方算出的 offer 方必然一致；走到这里说明对端判错了角色。
            // 如实记下来而不是硬接 —— 硬接会让两端都以为自己该发 offer。
            fail("收到 offer，但按 userId 字典序本端才是 offer 方（协商角色冲突）")
            return
        }
        remoteDescriptionSet = false
        backend.setRemoteDescription(DescriptionKind.OFFER, sdp)
    }

    private fun handleRemoteAnswer(sdp: String) {
        if (!offerer) {
            fail("收到 answer，但本端不是 offer 方（协商角色冲突）")
            return
        }
        remoteDescriptionSet = false
        backend.setRemoteDescription(DescriptionKind.ANSWER, sdp)
    }

    private fun handleRemoteIce(signal: RtcSignal.Ice) {
        val candidate = signal.candidate
        // end-of-candidates。Java API 没有「标记远端候选收完」这个动作，正确做法是**什么都不做**,
        // 但要认得出来，别当坏消息报错。
        if (candidate.isNullOrEmpty()) return

        val pending = PendingCandidate(candidate, signal.sdpMid, signal.sdpMLineIndex ?: 0)
        if (!remoteDescriptionSet) {
            pendingCandidates.addLast(pending)
            return
        }
        addCandidate(pending)
    }

    private fun flushPendingCandidates() {
        while (pendingCandidates.isNotEmpty()) addCandidate(pendingCandidates.removeFirst())
    }

    private fun addCandidate(candidate: PendingCandidate) {
        val accepted = backend.addIceCandidate(
            candidate.candidate,
            candidate.sdpMid,
            candidate.sdpMLineIndex,
        )
        // 失败是**静默**的（只返回 false）：不自己计数，诊断页什么都看不到，症状只是「连不上」。
        if (!accepted) {
            rejectedIceCandidates += 1
            _diagnostics.update { it.copy(rejectedIceCandidates = rejectedIceCandidates) }
        }
    }

    private fun restartIceInternal() {
        if (!started) return
        if (offerer) {
            requestOffer(iceRestart = true)
        } else {
            // 非 offer 方自己发起不了：`restartIce()` 只有 offer 方发新 offer 才生效。
            sink?.requestRenegotiation(RtcSignal.RENEGOTIATE_REASON_ICE)
        }
    }

    private fun fail(reason: String) {
        // 失败之后不再相信「有一轮在飞」：不清掉的话，后续补救动作会被它一直挡住。
        offerInFlight = false
        _state.value = PeerState.Failed(reason)
    }

    private fun teardown() {
        // 先换代次：此后旧连接/旧通道的回调全部被丢弃。
        generation += 1
        pendingCandidates.clear()
        offerInFlight = false
        deferredOfferIceRestart = null
        remoteDescriptionSet = false
        started = false
        runCatching { backend.close() }
    }

    // ── 后端回调（任意线程 → dispatcher）────────────────────────────────────

    private fun callbacksFor(gen: Long): RtcBackendCallbacks = object : RtcBackendCallbacks {

        override fun onLocalDescription(kind: DescriptionKind, sdp: String) = onGen(gen) {
            when (kind) {
                // 本地描述落地 = 这一轮不再「在飞」；随后才发，保证「offer 先于候选」。
                DescriptionKind.OFFER -> {
                    offerInFlight = false
                    sink?.sendOffer(sdp)
                }
                DescriptionKind.ANSWER -> sink?.sendAnswer(sdp)
            }
        }

        override fun onLocalIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int) = onGen(gen) {
            sink?.sendIce(candidate, sdpMid, sdpMLineIndex)
        }

        override fun onIceGatheringComplete() = onGen(gen) {
            // 转发结束标记（candidate = null）。对端不认识也不会出错，认得出来更好。
            sink?.sendIce(null, null, null)
        }

        override fun onRemoteDescriptionSet(kind: DescriptionKind) = onGen(gen) {
            remoteDescriptionSet = true
            // 先补齐排队的候选，再回 answer —— 顺序反了会让候选等到下一轮才有机会。
            flushPendingCandidates()
            if (kind == DescriptionKind.OFFER) backend.createAnswer()
        }

        override fun onSignalingStateStable() = onGen(gen) {
            offerInFlight = false
            // 协商落定之后 `RTCRtpEncodingParameters` 才真的存在 —— 建链那一刻调
            // `setAudioSending` 拿不到 encodings（见 WebRtcBackend 的注释）。
            // 这里按用户意图再对齐一次：没开麦时把这一路编码置为不活动，否则
            // `CustomAudioSource` 会一直产静音包，对端看到的是「一直在收包但没声音」。
            backend.setAudioSending(callRequested)
            flushDeferredOffer()
        }

        override fun onDataChannelOpen() = onGen(gen) {
            _state.value = PeerState.Connected
        }

        override fun onDataChannelClosed() = onGen(gen) {
            // 通道关了但连接还活着（例如对端重启协商）：退回协商中而不是判失败 ——
            // 一次 restartIce 就能救回来。
            if (_state.value == PeerState.Connected) _state.value = PeerState.Negotiating
        }

        override fun onMessage(text: String) = onGen(gen) {
            _messages.tryEmit(text)
        }

        override fun onFailure(reason: String) = onGen(gen) { fail(reason) }

        override fun onDiagnostics(snapshot: PeerDiagnostics) = onGen(gen) {
            // `rejectedIceCandidates` 由本类自己数（后端拿不到这些被拒的候选），不许被覆盖。
            _diagnostics.value = snapshot.copy(rejectedIceCandidates = rejectedIceCandidates)
        }
    }

    /** 回调收口：先换代次过滤，再进 dispatcher。 */
    private fun onGen(gen: Long, block: () -> Unit) = post { if (gen == generation) block() }

    private fun post(block: () -> Unit) = dispatcher.execute(block)

    private fun refreshCallAudio() {
        // 用 copy 而不是新建：`sending` / `error` / 包计数由采集路径与统计路径各自维护，
        // 整只替换会把它们抹回默认值（表现为「改个音量，通话状态就变回未开启」）。
        _callAudio.update {
            it.copy(
                requested = callRequested,
                volume = callVolume,
                micVolume = micVolume,
                limitMovieVolume = movieVolumeLimited,
            )
        }
    }

    /**
     * 按 [micVolume] 缩放一块 16-bit 小端单声道 PCM。
     *
     * 1.0 时原样返回（不复制）：这是唯一的高频路径（每 10 ms 一次），常态下不该多一次分配。
     */
    private fun pushWithGain(block: ByteArray): ByteArray {
        val gain = micVolume
        if (gain >= 0.999f) return block
        val out = ByteArray(block.size)
        var index = 0
        while (index + 1 < block.size) {
            val lo = block[index].toInt() and 0xFF
            val hi = block[index + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            out[index] = (scaled and 0xFF).toByte()
            out[index + 1] = ((scaled shr 8) and 0xFF).toByte()
            index += 2
        }
        return out
    }

    private data class PendingCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
    )

    private companion object {
        /** 信封 JSON 不大，64 足够吸收一次突发（溢出时 `tryEmit` 返回 false，不阻塞 native 线程）。 */
        const val MESSAGE_BUFFER = 64
    }
}
