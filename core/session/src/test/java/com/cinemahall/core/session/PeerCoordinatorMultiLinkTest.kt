package com.cinemahall.core.session

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.network.SignalEvent
import com.cinemahall.core.network.SignalState
import com.cinemahall.core.network.Signaler
import com.cinemahall.core.protocol.Envelope
import com.cinemahall.core.protocol.EnvelopeCodec
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段二 P2：**一条房间 N 条链路**（契约 §10）。
 *
 * 这一层能验、也必须验的是「哪几条链路该存在」与「一句话该发到几条链路上」——
 * 真机那一半（5 条 `PeerConnection` 同时跑、每条的 ICE 状态）JVM 里验不了，
 * 由 `core:rtc` 自己的用例与三端验收守着。
 *
 * 钉住的四条约定：
 *  1. **按成员表 diff 建链**：在线且有座位 ⇒ 一条；成员消失 ⇒ **只关那一条**；
 *     离线但座位还在 ⇒ 不新建、也**不拆已有的**（§5.4 宽限期，P2P 往往还活着）；
 *  2. **房间没了 ⇒ 全部收掉**，并清掉房间级的通话意图（1.11 那条缺陷的延续）；
 *  3. **`seq` 每条链路各自计数**（§4 删掉了全局 `outboundSeq`）；
 *  4. **广播 vs 定向**：`send(type, payload, to = null)` 发给所有链路，
 *     `to != null` 只发那一条，且信封的 `to` 逐字带出去。
 */
class PeerCoordinatorMultiLinkTest {

    @Test
    fun 成员表里两个在线对端就建两条链路() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()

        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        assertEquals("两个在线对端 ⇒ 两条链路", setOf(ALICE, BOB), coordinator.links.value.keys)
        assertEquals("每条链路各自被 start 一次，且目标是它自己", setOf(ALICE, BOB), links.started.toSet())
    }

    @Test
    fun 成员离开只关那一条链路() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        // BOB 退房：服务端在 v2 里发的是**全量** members_updated，成员表里不再有他。
        signaler.room.value = room(members = listOf(ME to true, ALICE to true))

        assertEquals("只该剩 ALICE", setOf(ALICE), coordinator.links.value.keys)
        assertEquals("BOB 那条被 close，ALICE 那条一动没动", setOf(BOB), links.closed.toSet())
    }

    @Test
    fun 有人掉线但座位还在时不拆已有链路() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true))
        links.closed.clear()

        // 信令断开：服务端把座位标 offline 并发全量成员表（§5 时机 3）。
        signaler.room.value = room(members = listOf(ME to true, ALICE to false))

        assertEquals("信令断不等于 P2P 断 —— 链路要留着（§5.4 座位保留）", setOf(ALICE), coordinator.links.value.keys)
        assertTrue("不该有人被 close", links.closed.isEmpty())
    }

    @Test
    fun 离线的人上线之后才建链() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()

        signaler.room.value = room(members = listOf(ME to true, ALICE to false))
        assertTrue("离线座位不该建链", coordinator.links.value.isEmpty())

        signaler.room.value = room(members = listOf(ME to true, ALICE to true))
        assertEquals("上线之后才建", setOf(ALICE), coordinator.links.value.keys)
        assertEquals(1, links.started.size)
    }

    @Test
    fun 首份ICE配置是空数组时仍然要建链() = runBlocking {
        // 这条钉的是一个**一条错都不报**的死路（2026-11-06，桌面车道先撞到）：
        // 进房应答是「先写 room、再抛 ice_servers」，所以第一次 reconcile 一定撞上
        // 「等 ICE 配置」而空转；而没有中继的部署下发的正是**空数组**，与初值相等
        // ⇒ 旧代码在 `if (!changed) return` 那一行早退，待办 reconcile 再也没有
        // 第二个触发点，链路一条都不建。真机表现是「进房了、界面正常、永远连不上」。
        val (coordinator, signaler, links) = newCoordinator()

        // ① 房间先到（等价于 `room_joined` 写 `signaler.room`）。
        signaler.room.value = room(members = listOf(ME to true, ALICE to true))
        assertTrue("还没收到 ICE 配置时先不建链", coordinator.links.value.isEmpty())

        // ② 空配置随后到：它「没变」，但它**是第一次**。
        signaler.pushIce()

        assertEquals(
            "首份配置即使是空数组，也必须把待办的那次 reconcile 顶起来",
            setOf(ALICE),
            coordinator.links.value.keys,
        )
        assertEquals("链路必须真的 start 过（offer 由它发出）", listOf(ALICE), links.started)
    }

    @Test
    fun 重复推送同一份ICE配置不会重复建链() = runBlocking {
        // 上一条的反面：`first` 只放行**第一次**。后续「配置没变」继续早退，
        // 省掉一次必然空转的 reconcile；同时钉住「放行 first 不能变成『每次都放行』」
        // —— 那会让每一次凭证巡检都重跑一遍 diff。
        val (coordinator, signaler, links) = newCoordinator()

        signaler.room.value = room(members = listOf(ME to true, ALICE to true))
        signaler.pushIce()
        signaler.pushIce()

        assertEquals("只有一条", setOf(ALICE), coordinator.links.value.keys)
        assertEquals("start 只被调过一次", listOf(ALICE), links.started)
    }
    @Test
    fun 房间没了就收掉全部链路() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        signaler.room.value = null

        assertTrue("房间没了 ⇒ 一条都不留", coordinator.links.value.isEmpty())
        assertEquals("两条都被 close", setOf(ALICE, BOB), links.closed.toSet())
    }

    @Test
    fun 广播发给每一条链路且_seq_各自从1开始() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        assertTrue(coordinator.send("cinema.play", payload(), to = null))
        assertTrue(coordinator.send("cinema.pause", payload(), to = null))

        // 每条链路各两条，**各自** 1、2 —— 契约 §4：seq 每条 DataChannel 链路各自计数。
        for (peer in listOf(ALICE, BOB)) {
            val sent = links.of(peer).sent.map { EnvelopeCodec.decode(it) }
            assertEquals("$peer 那条链路该有两条", listOf(1L, 2L), sent.map { it.seq })
            assertEquals("广播的 to 必须缺省", listOf(null, null), sent.map { it.to })
            assertEquals(listOf("cinema.play", "cinema.pause"), sent.map { it.type })
        }
    }

    @Test
    fun 定向消息只发给那一条链路并带上_to() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        assertTrue(coordinator.send("cinema.state_snapshot", payload(), to = BOB))

        assertEquals("只有 BOB 那条收到", 1, links.of(BOB).sent.size)
        assertTrue("ALICE 那条一条都没有", links.of(ALICE).sent.isEmpty())
        assertEquals("信封的 to 必须是 BOB", BOB.value, EnvelopeCodec.decode(links.of(BOB).sent.single()).to)
    }

    @Test
    fun 定向消息发给没有链路的人时如实失败而不是改成广播() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true))

        assertFalse("没有链路 ⇒ false，绝不悄悄改发广播", coordinator.send("cinema.media_status", payload(), to = BOB))
        assertTrue(links.of(ALICE).sent.isEmpty())
    }

    @Test
    fun 对端请求重建只拆它自己那一条() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))
        links.closed.clear()

        signaler.pushRtc(RtcSignal.Reset(from = BOB, reason = "manual"))

        assertEquals("只拆 BOB 那条（自己再 start 一次）", setOf(BOB), links.closed.toSet())
        assertEquals("ALICE 那条没有重新 start", 1, links.started.count { it == ALICE })
        // §5.2 的第一条硬约定：**收到**请求的一方绝不回发 —— 回发就变成两端互相对拆。
        assertTrue("对端请求时不该回发 rtc.reset", signaler.resetCalls.isEmpty())
    }

    @Test
    fun 通话意图会跟着新链路走() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true))

        coordinator.setCallEnabled(true)
        // 第二个人进来：他的链路也必须开麦，否则「先按开麦、后有人进房」那条路上
        // 新对端永远听不见（而界面上的开关是开着的）。
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        assertTrue("BOB 的链路要继承房间级的开麦意图", links.of(BOB).callCalls.contains(true))
    }

    @Test
    fun 退房把通话意图清零() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true))
        coordinator.setCallEnabled(true)

        signaler.room.value = null
        // 再进一场：新链路不许自己把麦克风接回来（旧链路已经全被收掉了，
        // 所以这里只能看**新**链路自己的记录 —— 它一开始就是空的）。
        signaler.room.value = room("8B2M4T", members = listOf(ME to true, BOB to true))

        assertTrue("新链路上不该出现 setCallEnabled(true)", links.of(BOB).callCalls.none { it })
    }

    @Test
    fun 聚合状态是有人通就算通() = runBlocking {
        val (coordinator, signaler, links) = newCoordinator()
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        links.of(ALICE).moveTo(PeerState.Connected)
        assertEquals(PeerState.Connected, coordinator.state.value)

        // 只剩一条在协商、另一条失败：结论应该还在「连」，不是「失败」。
        links.of(ALICE).moveTo(PeerState.Negotiating)
        links.of(BOB).moveTo(PeerState.Failed("x"))
        assertEquals(PeerState.Negotiating, coordinator.state.value)

        links.of(ALICE).moveTo(PeerState.Failed("y"))
        assertTrue(coordinator.state.value is PeerState.Failed)
    }

    @Test
    fun 通道通了与断了都按_peer_回调() = runBlocking {
        val opened = mutableListOf<UserId>()
        val closed = mutableListOf<UserId>()
        val (_, signaler, links) = newCoordinator(opened = opened, closed = closed)
        signaler.pushIce()
        signaler.room.value = room(members = listOf(ME to true, ALICE to true, BOB to true))

        links.of(ALICE).moveTo(PeerState.Connected)
        links.of(BOB).moveTo(PeerState.Connected)
        links.of(ALICE).moveTo(PeerState.Closed)

        assertEquals("两个人各自通了一次", listOf(ALICE, BOB), opened)
        assertEquals("只有 ALICE 断了", listOf(ALICE), closed)
    }

    @Test
    fun 没有链路时_stats_与_remote_给空值而不是崩() {
        val (coordinator, _, _) = newCoordinator()

        assertNull(coordinator.stats.value)
        assertNull(coordinator.remote.value)
        assertEquals("-", coordinator.diagnostics.value.iceConnectionState)
        assertEquals(PeerState.Idle, coordinator.state.value)
        assertEquals(CallAudioState(), coordinator.callAudio.value)
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    private fun newCoordinator(
        opened: MutableList<UserId>? = null,
        closed: MutableList<UserId>? = null,
    ): Triple<PeerCoordinator, MultiSignaler, LinkBook> {
        val links = LinkBook()
        val signaler = MultiSignaler()
        val coordinator = PeerCoordinator(
            myUserId = ME,
            signaler = signaler,
            newLink = links::newLink,
            router = MessageRouter(handlers = emptyList()),
            // 与其余 PeerCoordinator 用例同一个理由：JVM 上没有主线程，Unconfined 让
            // `room.value = …` 在**赋值这一行**里同步跑完 `reconcile`。
            scope = CoroutineScope(Dispatchers.Unconfined),
            onPeerChannelOpened = { opened?.add(it) },
            onPeerChannelClosed = { closed?.add(it) },
            credentialCheckIntervalMs = 0,
        )
        return Triple(coordinator, signaler, links)
    }

    private fun room(roomId: String = "4F7K2Q", members: List<Pair<UserId, Boolean>>) = Room(
        roomId = roomId,
        hostUserId = ME,
        mode = "cinema",
        members = members.map { (id, online) -> Member(id, id.value, online) },
        modeState = null,
    )

    private fun payload(): JsonObject = buildJsonObject { put("positionMs", 0) }

    private companion object {
        val ME = UserId("aaaaaaaa-me")
        val ALICE = UserId("bbbbbbbb-alice")
        val BOB = UserId("cccccccc-bob")
    }

    /** 按 peer 分发「造链路」：谁要链路就给谁一条，并记住它。 */
    private class LinkBook {
        private val byPeer = mutableMapOf<UserId, RecordingLink>()
        val started = mutableListOf<UserId>()
        val closed = mutableListOf<UserId>()

        fun newLink(): PeerLink = RecordingLink(this)

        fun of(peer: UserId): RecordingLink =
            byPeer[peer] ?: error("没有为 $peer 建过链路（已建：${byPeer.keys}）")

        fun register(peer: UserId, link: RecordingLink) {
            byPeer[peer] = link
            started += peer
        }

        fun markClosed(peer: UserId) {
            closed += peer
        }
    }

    /** 记下 `start` 时绑的 sink、发出去的原文、以及通话开关。 */
    private class RecordingLink(private val book: LinkBook) : PeerLink {

        private val _state = MutableStateFlow<PeerState>(PeerState.Idle)
        override val state: StateFlow<PeerState> get() = _state

        /** 把这条链路推到某个状态（用例用它制造「通了 / 断了 / 协商中」）。 */
        fun moveTo(state: PeerState) {
            _state.value = state
        }

        override val stats: StateFlow<LinkStats?> = MutableStateFlow(null)

        override val diagnostics: StateFlow<PeerDiagnostics> = MutableStateFlow(PeerDiagnostics())

        private val _messages = MutableSharedFlow<String>()
        override val messages: SharedFlow<String> = _messages

        override val remote: StateFlow<UserId?> = MutableStateFlow(null)

        override val isOfferer: StateFlow<Boolean?> = MutableStateFlow(null)

        override val callAudio: StateFlow<CallAudioState> = MutableStateFlow(CallAudioState())

        /** 这条链路上发出的所有信封原文。 */
        val sent = mutableListOf<String>()

        val callCalls = mutableListOf<Boolean>()

        private var peer: UserId? = null

        override fun start(myUserId: UserId, remoteUserId: UserId, config: RtcConfig, sink: RtcSignalSink) {
            peer = remoteUserId
            book.register(remoteUserId, this)
        }

        override fun onSignal(signal: RtcSignal) = Unit

        override fun setIceServers(servers: List<IceServer>) = Unit

        override fun setTransportPolicy(policy: TransportPolicy) = Unit

        override fun restartIce() = Unit

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun refreshStats() = Unit

        override fun close() {
            peer?.let(book::markClosed)
        }

        override fun setCallEnabled(enabled: Boolean) {
            callCalls += enabled
        }

        override fun setCallVolume(volume: Float) = Unit

        override fun setMovieVolumeLimit(enabled: Boolean) = Unit
    }

    /** 只提供协调者要用的四条流；房间与信令由用例直接推。 */
    private class MultiSignaler : Signaler {

        override val state: StateFlow<SignalState> = MutableStateFlow(SignalState.Online)

        override val room: MutableStateFlow<Room?> = MutableStateFlow(null)

        override val sessionEpoch: Long = 1L

        private val _events = MutableSharedFlow<SignalEvent>()
        override val events: SharedFlow<SignalEvent> = _events

        private val _rtc = MutableSharedFlow<RtcSignal>()
        override val rtcSignals: SharedFlow<RtcSignal> = _rtc

        /** `(目标, reason)`：`rtc.reset` 发给谁、为什么。 */
        val resetCalls = mutableListOf<Pair<UserId, String?>>()

        /** ICE 配置到齐 —— 建链的前置之一（`reconcile` 会卡在这条上）。 */
        suspend fun pushIce() = _events.emit(SignalEvent.IceServersUpdated(emptyList(), turnAvailable = false))

        suspend fun pushRtc(signal: RtcSignal) = _rtc.emit(signal)

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

        override suspend fun requestRenegotiation(to: UserId, reason: String?) = Unit

        override suspend fun sendRtcReset(to: UserId, reason: String?) {
            resetCalls += to to reason
        }

        override suspend fun leave() = Unit

        override suspend fun close() = Unit
    }
}
