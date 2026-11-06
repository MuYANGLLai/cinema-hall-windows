package com.cinemahall.core.session

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络变化的反应（阶段二 2.3 · §9.4 异常矩阵第一行）。
 *
 * ── 钉的是什么 ──────────────────────────────────────────────────────────────
 * 「网络变化 ⇒ 重启 ICE 并重新协商」。在此之前全仓**一处网络监听都没有**：
 * 切 WiFi 之后能不能恢复，靠的是 libwebrtc 的 `GATHER_CONTINUALLY` 加上用户
 * 自己想起来点「重启 ICE」。
 *
 * ── 为什么断言「重启了几次」而不是「链路恢复了」 ────────────────────────────
 * 恢复与否由 libwebrtc 与对端共同决定，JVM 里没有真的 PeerConnection 可验。
 * 这一层能负责、也必须负责的只有**动作**：网络变了要主动重启一次 ICE。
 * 重启之后的事（发新 offer / 请求对端重协商）由 `WebRtcPeerLink` 自己的用例守着。
 *
 * ⚠️ 去抖那条用例（[切网时的连串事件只反应一次]）要真等 250ms 真实时间 ——
 * `delay` 在 `Dispatchers.Unconfined` 上仍然走默认延时执行器，不是虚拟时间。
 */
class PeerCoordinatorNetworkTest {

    @Test
    fun 有链路时网络变化会重启ICE() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator()
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 3_000)))
        signaler.room.value = roomWithPeer("AB12CD")
        assertEquals("前置：链路必须先建起来（boundPeer 非空）", 1, rtc.started)

        coordinator.reactToNetworkChange()

        assertEquals("网络变了 ⇒ 重启 ICE（offer 方发新 offer、非 offer 方请求对端重协商）", 1, rtc.restartIceCount)
    }

    @Test
    fun 没有链路时网络变化不重启ICE() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator()
        // 房间里只有自己：还没建链，重启 ICE 除了刷一条日志没有任何作用。
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 3_000)))
        signaler.room.value = roomAlone("AB12CD")

        coordinator.reactToNetworkChange()

        assertEquals(0, rtc.restartIceCount)
    }

    /**
     * 切网时系统会连着报好几条（`onLost(旧网)` + `onAvailable(新网)`，顺序还不定），
     * 去抖要把它们合并成**一次**反应。
     *
     * 为什么必须合并：每多反应一次就多一轮 ICE restart，而每一轮都会打散
     * DataChannel、清空同步锚点（§6.4）。一次 WiFi→蜂窝切换打三、四轮的话，
     * 用户看到的是「切个网，画面卡十几秒」。
     */
    @Test
    fun 切网时的连串事件只反应一次() = runBlocking {
        val (coordinator, signaler, rtc) = newCoordinator(debounceMs = 30)
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 3_000)))
        signaler.room.value = roomWithPeer("AB12CD")

        coordinator.onNetworkEvent()
        coordinator.onNetworkEvent()
        coordinator.onNetworkEvent()

        delay(250)

        assertEquals("三条事件落在同一个去抖窗口里 ⇒ 只重启一次", 1, rtc.restartIceCount)
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    private fun newCoordinator(debounceMs: Long = 0): Triple<PeerCoordinator, NetworkRecordingSignaler, CountingPeerLink> {
        val signaler = NetworkRecordingSignaler()
        val rtc = CountingPeerLink()
        val coordinator = PeerCoordinator(
            myUserId = ME,
            signaler = signaler,
            newLink = { rtc },
            router = MessageRouter(handlers = emptyList()),
            scope = CoroutineScope(Dispatchers.Unconfined),
            credentialCheckIntervalMs = 0,
            // 0 = 去抖窗口内立即反应（`delay(0)` 不挂起）⇒ 用例不必等。
            networkDebounceMs = debounceMs,
            // 0 = 提示计时立刻到期；假信令始终是 Online，所以永远不会真的提示出来
            // （要验提示本身得让信令处于断开态，那是另一条用例的事）。
            networkHintDelayMs = 0,
        )
        return Triple(coordinator, signaler, rtc)
    }

    private fun roomWithPeer(roomId: String) = Room(
        roomId = roomId,
        hostUserId = ME,
        mode = "cinema",
        members = listOf(
            Member(ME, "我", online = true),
            Member(PEER, "对方", online = true),
        ),
        modeState = null,
    )

    private fun roomAlone(roomId: String) = Room(
        roomId = roomId,
        hostUserId = ME,
        mode = "cinema",
        members = listOf(Member(ME, "我", online = true)),
        modeState = null,
    )

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1_000

    private fun turnEntry(expiryEpochSeconds: Long) = IceServer(
        urls = listOf("turn:203.0.113.9:3478?transport=udp"),
        username = "$expiryEpochSeconds:me",
        credential = "test-only",
    )

    private companion object {
        val ME = UserId("me")
        val PEER = UserId("peer")
    }
}

/** 只记录索取动作的假信令；[pushIce] 模拟服务端下发 `sys.ice_servers`。 */
private class NetworkRecordingSignaler : Signaler {

    override val state: StateFlow<SignalState> = MutableStateFlow(SignalState.Online)

    override val room: MutableStateFlow<Room?> = MutableStateFlow(null)

    override val sessionEpoch: Long = 7L

    override val events: MutableSharedFlow<SignalEvent> = MutableSharedFlow()

    override val rtcSignals: SharedFlow<com.cinemahall.core.model.RtcSignal> = MutableSharedFlow()

    suspend fun pushIce(servers: List<IceServer>) {
        // `MutableSharedFlow()` 没有缓冲 ⇒ `emit` 会一直挂到协调者把它消费完；
        // 协调者在构造时就订阅了（UNDISPATCHED），所以这里返回时它已经处理过了。
        events.emit(SignalEvent.IceServersUpdated(servers, turnAvailable = true))
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

    override suspend fun sendRtcReset(to: UserId, reason: String?) = Unit

    override suspend fun leave() = Unit

    override suspend fun close() = Unit
}

/** 记录建链次数与重启 ICE 次数的假链路。 */
private class CountingPeerLink : PeerLink {

    override val state: StateFlow<PeerState> = MutableStateFlow(PeerState.Idle)

    override val stats: StateFlow<LinkStats?> = MutableStateFlow(null)

    override val diagnostics: StateFlow<PeerDiagnostics> = MutableStateFlow(PeerDiagnostics())

    override val messages: SharedFlow<String> = MutableSharedFlow()

    override val remote: StateFlow<UserId?> = MutableStateFlow(null)

    override val isOfferer: StateFlow<Boolean?> = MutableStateFlow(null)

    override val callAudio: StateFlow<CallAudioState> = MutableStateFlow(CallAudioState())

    var started = 0
        private set

    var restartIceCount = 0
        private set

    override fun start(myUserId: UserId, remoteUserId: UserId, config: RtcConfig, sink: RtcSignalSink) {
        started += 1
    }

    override fun onSignal(signal: com.cinemahall.core.model.RtcSignal) = Unit

    override fun setIceServers(servers: List<IceServer>) = Unit

    override fun setTransportPolicy(policy: TransportPolicy) = Unit

    override fun restartIce() {
        restartIceCount += 1
    }

    override fun send(text: String): Boolean = false

    override fun refreshStats() = Unit

    override fun close() = Unit

    override fun setCallEnabled(enabled: Boolean) = Unit

    override fun setCallVolume(volume: Float) = Unit

    override fun setMovieVolumeLimit(enabled: Boolean) = Unit
}
