package com.cinemahall.core.session

import com.cinemahall.core.model.IceServer
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段二 2.4「链路对称重建」的编排（2026-10-14）。
 *
 * 这一层能验、也必须验的是**动作与顺序**：
 *
 *  - 点「重建链路」= 先发 `rtc.reset`、再拆本端、再 `start()`。**顺序是协议的一部分**：
 *    先发后拆，对端收到请求时本端的新 offer 还没发出去，那条 offer 才一定落在对端的
 *    **新**连接上；反过来会留下一条没人回答的 offer，症状与「一直停在协商中」同形。
 *  - 收到对端的 `rtc.reset`：动作相同，但**绝不回发**一条 —— 否则两端互相对拆。
 *  - 两端几乎同时按时，去重窗口只让本端少抖一轮（不参与正确性）。
 *
 * 真机那一半（拆完多久回到 `Connected`、新连接是不是真的新）验不了：JVM 里没有
 * `PeerConnection`。`WebRtcPeerLink` 内部的行为（`close()` 清 `isOfferer`、`start()`
 * 重新按 userId 定角色）由 `core:rtc` 自己的用例与那两处的 KDoc 守着。
 */
class PeerCoordinatorRebuildTest {

    @Test
    fun 点重建链路是先发reset再拆再建() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator()
        signaler.bindPeer()
        rtc.trace.clear()

        coordinator.restart()

        assertEquals(
            "顺序必须是 reset → close → start（协议的一部分）",
            listOf("reset:manual", "close", "start"),
            rtc.trace,
        )
    }

    @Test
    fun answer方点重建链路也拆本端() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator()
        signaler.bindPeer()
        rtc.isOffererFlow.value = false
        rtc.trace.clear()

        coordinator.restart()

        assertEquals("answer 方不再「保留本端连接」", listOf("reset:manual", "close", "start"), rtc.trace)
    }

    @Test
    fun 收到对端请求时拆掉重建但不回发reset() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator()
        signaler.bindPeer()
        rtc.trace.clear()

        signaler.pushRtc(RtcSignal.Reset(from = PEER, reason = "manual"))

        assertEquals("§5.2 的第一条硬约定：收到它的一方绝不回发", emptyList<String>(), signaler.resetReasons)
        assertEquals("拆掉本端 + 重新 start（新 offer 由对端发）", listOf("close", "start"), rtc.trace)
    }

    @Test
    fun 刚重建过就忽略对端那条请求() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator(dedupeMs = 5_000)
        signaler.bindPeer()
        rtc.trace.clear()

        coordinator.restart()
        val afterOwnRebuild = rtc.trace.toList()

        signaler.pushRtc(RtcSignal.Reset(from = PEER, reason = "manual"))

        assertEquals("去重窗口内不再拆一次", afterOwnRebuild, rtc.trace)
        assertTrue(
            "要留一行日志说明这条请求被挡了",
            coordinator.log.value.any { it.contains("忽略") },
        )
    }

    @Test
    fun 去重窗口为零时不忽略() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator(dedupeMs = 0)
        signaler.bindPeer()
        rtc.trace.clear()

        coordinator.restart()
        signaler.pushRtc(RtcSignal.Reset(from = PEER, reason = "manual"))

        assertEquals(
            "关掉去重 ⇒ 每一条请求都照做（自己那一次 + 对端那一次）",
            listOf("reset:manual", "close", "start", "close", "start"),
            rtc.trace,
        )
    }

    @Test
    fun 没有绑定对端时点重建只提示不发消息() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator()
        rtc.trace.clear()

        coordinator.restart()

        assertEquals("房间里只有自己 ⇒ 一条信令都不该发", emptyList<String>(), signaler.resetReasons)
        assertEquals(emptyList<String>(), rtc.trace)
        assertTrue(coordinator.log.value.any { it.contains("还没有绑定的对端") })
    }

    @Test
    fun 请求来自非绑定对端时忽略() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator()
        signaler.bindPeer()
        rtc.trace.clear()

        signaler.pushRtc(RtcSignal.Reset(from = UserId("third"), reason = "manual"))

        assertEquals("不能把一条好链路拆掉", emptyList<String>(), rtc.trace)
        // 阶段二：链路从「一条」变成「按 peer 索引的若干条」，所以措辞跟着改了 ——
        // 判据仍是「这个人不在我的链路表里 ⇒ 一律不动」。
        assertTrue(
            coordinator.log.value.any { it.contains("不是当前链路") },
        )
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    private fun newCoordinator(dedupeMs: Long = 0): Triple<PeerCoordinator, RebuildSignaler, RebuildPeerLink> {
        // 两条假实现**共用一个事件表**：这一批用例最要紧的判据是「reset 与 close 谁先」，
        // 各记各的就永远看不出先后。
        val trace = mutableListOf<String>()
        val signaler = RebuildSignaler(trace)
        val rtc = RebuildPeerLink(trace)
        val coordinator = PeerCoordinator(
            myUserId = ME,
            signaler = signaler,
            newLink = { rtc },
            router = MessageRouter(handlers = emptyList()),
            scope = CoroutineScope(Dispatchers.Unconfined),
            credentialCheckIntervalMs = 0,
            networkDebounceMs = 0,
            networkHintDelayMs = 0,
            // 0 = 去重窗口关掉（默认 2000ms 只用来挡「两端几乎同时按」，见上面那条用例）。
            rebuildDedupeMs = dedupeMs,
        )
        return Triple(coordinator, signaler, rtc)
    }

    /**
     * 让协调者绑上对端。
     *
     * ⚠️ 顺序不能反：`reconcile` 要求 ICE 配置先到齐才建链（否则会停在「等 ICE 配置」）。
     * 另外这两步各自会走一次 `reconcile(null)`（`room` 的初值就是 null），于是 `close()`
     * 会被调两次 —— 那是既有设计（「房间没了 = 这场会话结束，无条件收链路」），
     * 所以调用方在动手之前要 `trace.clear()`。
     */
    private fun RebuildSignaler.bindPeer() {
        runBlocking {
            pushIce(listOf(turnEntry()))
            room.value = roomWithPeer()
        }
    }

    private fun roomWithPeer() = Room(
        roomId = "AB12CD",
        hostUserId = ME,
        mode = "cinema",
        members = listOf(
            Member(ME, "我", online = true),
            Member(PEER, "对方", online = true),
        ),
        modeState = null,
    )

    private fun turnEntry() = IceServer(
        urls = listOf("turn:203.0.113.9:3478?transport=udp"),
        username = "9999999999:me",
        credential = "test-only",
    )

    private companion object {
        val ME = UserId("me")
        val PEER = UserId("peer")
    }
}

/** 记录 `rtc.reset` 的假信令；[pushRtc] 模拟服务端把对端的 `rtc.*` 转发过来。 */
private class RebuildSignaler(private val trace: MutableList<String>) : Signaler {

    override val state: StateFlow<SignalState> = MutableStateFlow(SignalState.Online)

    override val room: MutableStateFlow<Room?> = MutableStateFlow(null)

    override val sessionEpoch: Long = 7L

    override val events: MutableSharedFlow<SignalEvent> = MutableSharedFlow()

    override val rtcSignals: MutableSharedFlow<RtcSignal> = MutableSharedFlow()

    /** 本端发出过的 `rtc.reset` 的 reason，按发生顺序。 */
    val resetReasons = mutableListOf<String?>()

    suspend fun pushIce(servers: List<IceServer>) {
        events.emit(SignalEvent.IceServersUpdated(servers, turnAvailable = true))
    }

    suspend fun pushRtc(signal: RtcSignal) {
        rtcSignals.emit(signal)
    }

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
        resetReasons += reason
        trace += "reset:$reason"
    }

    override suspend fun leave() = Unit

    override suspend fun close() = Unit
}

/** 假链路：把 `close` / `start` 按发生顺序记进共用的事件表。 */
private class RebuildPeerLink(val trace: MutableList<String>) : PeerLink {

    override val state: StateFlow<PeerState> = MutableStateFlow(PeerState.Idle)

    override val stats: StateFlow<LinkStats?> = MutableStateFlow(null)

    override val diagnostics: StateFlow<PeerDiagnostics> = MutableStateFlow(PeerDiagnostics())

    override val messages: SharedFlow<String> = MutableSharedFlow()

    override val remote: StateFlow<UserId?> = MutableStateFlow(null)

    override val isOfferer: StateFlow<Boolean?> = MutableStateFlow(null)

    override val callAudio: StateFlow<CallAudioState> = MutableStateFlow(CallAudioState())

    /** 让用例扮演 answer 方（`⑦` 那行说明按它分岔，本批用例不必断言它）。 */
    val isOffererFlow: MutableStateFlow<Boolean?> get() = isOfferer as MutableStateFlow<Boolean?>

    override fun start(myUserId: UserId, remoteUserId: UserId, config: RtcConfig, sink: RtcSignalSink) {
        trace += "start"
    }

    override fun onSignal(signal: RtcSignal) = Unit

    override fun setIceServers(servers: List<IceServer>) = Unit

    override fun setTransportPolicy(policy: TransportPolicy) = Unit

    override fun restartIce() = Unit

    override fun send(text: String): Boolean = false

    override fun refreshStats() = Unit

    override fun close() {
        trace += "close"
    }

    override fun setCallEnabled(enabled: Boolean) = Unit

    override fun setCallVolume(volume: Float) = Unit

    override fun setMovieVolumeLimit(enabled: Boolean) = Unit
}
