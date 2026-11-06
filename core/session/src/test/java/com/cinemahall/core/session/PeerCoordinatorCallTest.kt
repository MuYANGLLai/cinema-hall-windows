package com.cinemahall.core.session

import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.network.SignalEvent
import com.cinemahall.core.network.SignalState
import com.cinemahall.core.network.Signaler
import com.cinemahall.core.protocol.MessageRouter
import com.cinemahall.core.rtc.CallAudioState
import com.cinemahall.core.rtc.LinkStats
import com.cinemahall.core.rtc.PeerDiagnostics
import com.cinemahall.core.rtc.PeerLink
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.rtc.RtcConfig
import com.cinemahall.core.rtc.RtcSignalSink
import com.cinemahall.core.rtc.TransportPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PeerCoordinator` 这一层的两条**结构性**约定（阶段一 1.11 · 2026-10-12 真机缺陷）。
 *
 * ── 用户报的现象 ─────────────────────────────────────────────────────────────
 * 「刚进入房间，未点击开启通话的按钮，但是却开启通话了」——麦克风绿点亮着。
 *
 * ── 为什么缺陷会落在「协调者」这一层 ─────────────────────────────────────────
 * 通话意图（`WebRtcPeerLink.callRequested`）是**进程级、刻意跨链路存活**的字段
 * （§9.4 宽限期要的：断一下网，通话不该静默消失）。代价是**每一条退房来路都必须
 * 有机会把它清零**，而协调者是这些来路的必经之处：
 *
 *  ① `setCallEnabled` 曾按 `rtc.callAudio.value.requested` 短路 —— 那是链路在
 *     自己协程里**异步发布**出来的状态，不是此刻的真值。于是「开」的意图还没落地时
 *     到达的一次「关」会被判成「值没变」丢掉，而那个「开」随后落地 ⇒ 关被开吞掉。
 *  ② `reconcile` 在 `room == null` 那一支曾与「还在房里但没别人」共用同一个
 *     `boundPeer != null` 判据 ⇒「还没绑上对端就先开了通话、随后退房」整个跳过
 *     `rtc.close()`，而它是**唯一**会清零 `callRequested` 的地方。
 *
 * 两条都不是「某条来路漏了」，而是判据本身可以被绕过，所以修法也不是再补一次复位，
 * 而是把判据换掉。本文件钉的就是换过之后的约定：
 *
 *  - [PeerCoordinator.setCallEnabled] 是**纯转发**：短路只允许留在
 *    `WebRtcPeerLink.setCallEnabled`（那边比的是自己的 `callRequested`，同步真值）。
 *  - `room == null` ⇒ **无条件**收链路。
 *
 * ── 为什么要为这两行造一整套假实现 ───────────────────────────────────────────
 * `PeerCoordinator` 的构造要四个接口（`Signaler` / `PeerLink` / `MessageRouter` /
 * `CoroutineScope`）。假实现里那条**刻意不发布**的 `callAudio`（见 [FakePeerLink]）
 * 就是缺陷的复现条件本身 —— 换成真的 `WebRtcPeerLink` 反而没法稳定制造这个窗口。
 */
class PeerCoordinatorCallTest {

    /**
     * 「关通话」不许被**异步已发布状态**短路掉。
     *
     * 复现的就是 KDoc ① 里那个交错：链路此刻的已发布状态是 `requested = false`
     * （用户刚按下的「开」还在链路的队列里没落地），这时到达一次「关」——
     * 旧实现读到 `false == false` 判定「值没变」，**不转发**，于是那次「开」落地后
     * 意图停在 `true` 上，下一次进房建链时麦克风自己接回来。
     *
     * 断言的是「协调者有没有**下达**这次关」，不是链路最后落成了什么 —— 后者是
     * `WebRtcPeerLink` 自己的幂等语义，已有 `CallAudioPlanTest` 一类用例守着。
     */
    @Test
    fun 关通话不许被已发布状态短路() = runBlocking {
        val rtc = FakePeerLink()
        val signaler = FakeSignaler()
        val coordinator = newCoordinator(rtc, signaler)
        bindPeer(coordinator, signaler)

        coordinator.setCallEnabled(false)

        assertEquals(
            "已发布状态落后于真值时，「关」仍必须原样转发下去",
            listOf(false),
            rtc.calls,
        )
    }

    /**
     * 同一方向连着来两次也**每次都转发**：协调者不是真值持有者，无权吞掉任何一次。
     *
     * 这一条钉的是「纯转发」这个约定本身。旧实现下第二次调用会被吞掉
     * （已发布状态仍是 `false`），而真值此时恰好可能是 `true` —— 例如链路刚被
     * 别处的 `close()` 收掉、或「开」还没落地。日志去重改由
     * `PeerCoordinator.lastCallIntent` 承担（它只影响日志，不参与判断）。
     */
    @Test
    fun 通话开关每次都原样转发_方向与次数都不打折() = runBlocking {
        val rtc = FakePeerLink()
        val signaler = FakeSignaler()
        val coordinator = newCoordinator(rtc, signaler)
        bindPeer(coordinator, signaler)

        coordinator.setCallEnabled(true)
        coordinator.setCallEnabled(false)
        coordinator.setCallEnabled(false)

        assertEquals(
            "三次调用必须对应三次转发（顺序也要对）",
            listOf(true, false, false),
            rtc.calls,
        )
    }

    /**
     * `rtc.renegotiate` 的 `reason` 必须**逐字**带出去（2026-10-14「真停采集」）。
     *
     * 它不是日志，是**行为判据**：对端按它决定要不要重启 ICE（`audio` = 只要一轮普通
     * re-offer）。协调者若替对端做主（例如一律填 `ice`、或一律发 `null`），关麦就会顺带
     * 重启 ICE —— DataChannel 掉一下、播放同步锚点被清，而这条链路上**一个字都不会报错**，
     * 只在真机上表现为「按一下麦克风开关，画面重新同步了一次」。
     * 所以这一条钉的是「本层不认识、也不改写它」，`null` 同样原样传（缺键 = `ice`
     * 是**对端**的判据，不是本层的）。
     */
    @Test
    fun 重协商的_reason_原样带出去() = runBlocking {
        val signaler = FakeSignaler()
        val rtc = FakePeerLink()
        newCoordinator(rtc, signaler)

        // 建链的两个前置（与 [PeerCoordinator.reconcile] 的判据一一对应）：
        // ① ICE 配置到齐；② 成员表里出现一个**在线**的对端。
        signaler.pushEvent(SignalEvent.IceServersUpdated(emptyList(), turnAvailable = false))
        signaler.room.value = room(
            "4F7K2Q",
            members = listOf(Member(ME, "我", online = true), Member(PEER, "他", online = true)),
        )

        // 直接走 `RtcSignalSink` 那一面：真链路的 `WebRtcPeerLink` 就是这样调它的。
        val sink = requireNotNull(rtc.sink) { "成员表里有在线对端却没能建链" }
        sink.requestRenegotiation(RtcSignal.RENEGOTIATE_REASON_AUDIO)
        sink.requestRenegotiation(null)

        assertEquals(
            "reason 必须逐字转发（null 也要原样传下去）",
            listOf("audio", null),
            signaler.renegotiationReasons,
        )
        assertEquals(
            "sink 属于哪条链路，出站信令就必须发给谁（契约 §6）",
            listOf(PEER, PEER),
            signaler.renegotiationTargets,
        )
    }

    /**
     * 房间没了 ⇒ **无条件**收链路（KDoc ②）。
     *
     * `rtc.close()` 是唯一会把 `callRequested` 清零的地方，所以它不许挂在
     * 「已经绑上对端」这种**偶然**条件上。这里刻意让协调者**从未绑上过对端**
     * （房间里只有自己）：旧实现下这一次退房整个跳过收链路，通话意图就活到了下一场。
     */
    @Test
    fun 退房必须把房间级的通话意图清零() = runBlocking {
        val rtc = FakePeerLink()
        val signaler = FakeSignaler()
        val coordinator = newCoordinator(rtc, signaler)

        // 进房 + 对端在线 ⇒ 建链；用户在这一刻按了「开麦」。
        signaler.pushEvent(SignalEvent.IceServersUpdated(emptyList(), turnAvailable = false))
        signaler.room.value = room(
            "4F7K2Q",
            members = listOf(Member(ME, "我", online = true), Member(PEER, "他", online = true)),
        )
        coordinator.setCallEnabled(true)

        // 退房：`Signaler.leave()` 把房间视图置空。
        signaler.room.value = null

        // 再进一个房、再有对端：新链路**不许**自己把麦克风接回来。
        //
        // 阶段二之前这一条靠「`rtc.close()` 是唯一会清零 `callRequested` 的地方」实现，
        // 于是「还没绑上对端就退房」那条路整个跳过收链路，意图活到了下一场。
        // 现在意图是**协调者自己的字段**（`callIntent`），`room == null` 那一支无条件清它 ——
        // 与「有没有链路」彻底解耦，判据不再能被绕过。
        rtc.calls.clear()
        signaler.room.value = room(
            "8B2M4T",
            members = listOf(Member(ME, "我", online = true), Member(PEER, "他", online = true)),
        )

        assertEquals(
            "上一场的通话意图不许跟到新的一场来（用户没按过任何开关）",
            emptyList<Boolean>(),
            rtc.calls,
        )
    }

    /**
     * 对照：还在房间里、只是没有别人（对端离开 / 还没进来）时**不**收链路。
     *
     * 这一条是上面那条的边界：`room == null` 与 `room != null 且没别人` 的语义相反
     * —— 后者是「等对端」，用户此刻按「开启通话」是合法动作，链路要留着。
     */
    @Test
    fun 还在房间里只是没别人时不动链路() {
        val rtc = FakePeerLink()
        val signaler = FakeSignaler()
        newCoordinator(rtc, signaler)
        val closedAtStart = rtc.closed

        signaler.room.value = room("4F7K2Q", members = listOf(Member(ME, "我", online = true)))

        assertEquals("对端没来不等于会话结束", closedAtStart, rtc.closed)
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    private fun newCoordinator(
        rtc: FakePeerLink,
        signaler: FakeSignaler = FakeSignaler(),
    ) = PeerCoordinator(
        myUserId = ME,
        signaler = signaler,
        // 阶段二：协调者收的是「造一条链路」的工厂。本文件只关心通话开关与一条链路的
        // 行为，所以所有 peer 都拿到同一个 fake —— 它同时承担「记录器」的角色。
        newLink = { rtc },
        router = MessageRouter(handlers = emptyList()),
        // 单测里没有主线程（`kotlinx-coroutines-android` 的 Main 在 JVM 上不可用），
        // 用 Unconfined：`signaler.room.value = …` 会在**赋值这一行**里同步跑完
        // `reconcile`，断言不必等、也不会 flaky。
        scope = CoroutineScope(Dispatchers.Unconfined),
        // 关掉续签巡检（阶段二 2.1 的定时器）：本文件不测它，留着只会让用例里
        // 多一个后台协程。续签自己那套在 `PeerCoordinatorCredentialTest`。
        credentialCheckIntervalMs = 0,
    )

    /**
     * 让房间里出现一个在线对端 ⇒ 协调者会建出**一条**链路。
     *
     * 阶段二起「通话开关转发给谁」有了前提：没有链路时本层只记意图（并由建链那一刻
     * 补上），所以「转发」这类用例必须先有链路，否则断言的是「零次转发」。
     */
    private suspend fun bindPeer(coordinator: PeerCoordinator, signaler: FakeSignaler) {
        signaler.pushEvent(SignalEvent.IceServersUpdated(emptyList(), turnAvailable = false))
        signaler.room.value = room(
            "4F7K2Q",
            members = listOf(Member(ME, "我", online = true), Member(PEER, "他", online = true)),
        )
    }

    private fun room(roomId: String, members: List<Member>) = Room(
        roomId = roomId,
        hostUserId = ME,
        mode = "cinema",
        members = members,
        modeState = null,
    )

    private companion object {
        val ME = UserId("me")

        /** 房间里的另一个座位。阶段二起「每条链路一个 sink」，所以用例需要一个具体的对端。 */
        val PEER = UserId("user-peer")
    }
}

/**
 * 只记「协调者下达了什么」的假链路。
 *
 * ⚠️ [callAudio] **刻意永远停在 `requested = false`**（不随 [setCallEnabled] 更新）：
 * 真链路的发布是异步的，用例要复现的正是「已发布状态落后于真值」那个窗口。
 * 让它跟着更新反而会把缺陷藏起来 —— 旧实现在那个窗口里短路，而窗口被抹掉了。
 */
private class FakePeerLink : PeerLink {

    private val _state = MutableStateFlow<PeerState>(PeerState.Idle)

    /** 每一次 `setCallEnabled` 的原样记录（**不幂等**，见上面那条说明）。 */
    val calls = mutableListOf<Boolean>()

    /** `close()` 被调了几次。`reconcile` 里那条「房间没了就收链路」靠它取证。 */
    var closed = 0
        private set

    override val state: StateFlow<PeerState> get() = _state

    override val stats: StateFlow<LinkStats?> = MutableStateFlow(null)

    override val diagnostics: StateFlow<PeerDiagnostics> = MutableStateFlow(PeerDiagnostics())

    override val messages: SharedFlow<String> = MutableSharedFlow()

    override val remote: StateFlow<UserId?> = MutableStateFlow(null)

    override val isOfferer: StateFlow<Boolean?> = MutableStateFlow(null)

    override val callAudio: StateFlow<CallAudioState> = MutableStateFlow(CallAudioState())

    override fun start(myUserId: UserId, remoteUserId: UserId, config: RtcConfig, sink: RtcSignalSink) {
        // 阶段二：sink 是**每条链路一个**、构造时就把目标绑死了（契约 §6）。
        // 用例要验「`reason` 原样带出去」，就得拿真正交给链路的那一个。
        this.sink = sink
        this.remoteUserId = remoteUserId
    }

    var sink: RtcSignalSink? = null
        private set

    /** 这条链路被 `start` 时的对端 —— 用来断言 sink 绑的是谁。 */
    var remoteUserId: UserId? = null
        private set

    override fun onSignal(signal: com.cinemahall.core.model.RtcSignal) = Unit

    override fun setIceServers(servers: List<com.cinemahall.core.model.IceServer>) = Unit

    override fun setTransportPolicy(policy: TransportPolicy) = Unit

    override fun restartIce() = Unit

    override fun send(text: String): Boolean = false

    override fun refreshStats() = Unit

    override fun close() {
        closed += 1
    }

    override fun setCallEnabled(enabled: Boolean) {
        calls += enabled
    }

    override fun setCallVolume(volume: Float) = Unit

    override fun setMovieVolumeLimit(enabled: Boolean) = Unit
}

/** 只提供协调者构造与 `reconcile` 需要的四条流；`room` 由用例直接推。 */
private class FakeSignaler : Signaler {

    override val state: StateFlow<SignalState> = MutableStateFlow(SignalState.Online)

    override val room: MutableStateFlow<Room?> = MutableStateFlow(null)

    override val sessionEpoch: Long = 0L

    private val _events = MutableSharedFlow<SignalEvent>()

    override val events: SharedFlow<SignalEvent> = _events

    /** 推一条信令事件（ICE 配置到齐是建链的前置之一）。 */
    suspend fun pushEvent(event: SignalEvent) = _events.emit(event)

    override val rtcSignals: SharedFlow<com.cinemahall.core.model.RtcSignal> = MutableSharedFlow()

    override suspend fun connect(endpoint: String, userId: UserId) = Unit

    override suspend fun createRoom(nickname: String, password: String?, roomId: String?): Room =
        error("本用例不建房")

    override suspend fun joinRoom(roomId: String, nickname: String, password: String?): Room =
        error("本用例不加入房间")

    override suspend fun resume(roomId: String, userId: UserId, sessionEpoch: Long): Room =
        error("本用例不恢复会话")

    override suspend fun listRooms(): List<com.cinemahall.core.model.RoomSummary> = emptyList()

    override suspend fun send(type: String, payload: JsonObject?) = Unit

    override suspend fun sendRtcOffer(to: UserId, sdp: String) = Unit

    override suspend fun sendRtcAnswer(to: UserId, sdp: String) = Unit

    override suspend fun sendRtcIce(to: UserId, candidate: String?, sdpMid: String?, sdpMLineIndex: Int?) = Unit

    /** 每一次重协商请求的 `reason` 原样记下来（`null` 也记）—— 见「原样带出去」那条用例。 */
    val renegotiationReasons = mutableListOf<String?>()

    /** 同上，记 `to`：阶段二起 `rtc.*` 必填目标（契约 §6）。 */
    val renegotiationTargets = mutableListOf<UserId?>()

    override suspend fun requestRenegotiation(to: UserId, reason: String?) {
        renegotiationReasons += reason
        renegotiationTargets += to
    }

    override suspend fun sendRtcReset(to: UserId, reason: String?) = Unit

    override suspend fun leave() = Unit

    override suspend fun close() = Unit
}
