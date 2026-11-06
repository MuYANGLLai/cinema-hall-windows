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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TURN 凭证续签的**客户端兜底路**（阶段二 2.1 · §3.7.7 必做 ①）。
 *
 * ── 这一层到底在补什么 ──────────────────────────────────────────────────────
 * 服务端已经有主动续签：`signaling.mjs` 的 5s sweep 会在
 * `credentialExpiry - renewBeforeSec` 起推一条 `sys.ice_servers`。那条路是主路径，
 * 但它要求「推送那一刻连接还在、座位 online、包没丢」。三条里断任何一条，凭证就会
 * **静默**走到过期 —— 症状不是报错，是重连时中继候选少一条（看起来只是连得慢）。
 * 本文件钉的是兜底：客户端自己算出「临期/已过期」，然后主动索取一次。
 *
 * ── 为什么断言的是「有没有发出索取」而不是「凭证换到了没有」 ─────────────────
 * 「换到」由服务端决定，而这条路的**动作**只有一件：发一次 `sys.resume`
 * （服务端在 `handleResume` 里重签并回执 `iceServers`）。拿到之后的事
 * （`setIceServers` → `setConfiguration` → `restartIce`）1.4 起就有，
 * 且由 `WebRtcPeerLink` 自己的用例守着。把断言放在这一层，失败时能立刻分清
 * 「客户端没要」和「要了但服务端没给」。
 */
class PeerCoordinatorCredentialTest {

    @Test
    fun 凭证临期时主动索取新凭证() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        signaler.room.value = room("AB12CD")
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 100)))

        coordinator.checkCredentialRenewal()

        assertEquals(
            "剩余 100 秒 < 300 秒提前量 ⇒ 必须主动索取一次（复用 sys.resume）",
            listOf("AB12CD"),
            signaler.resumed,
        )
        val renewal = coordinator.credentialRenewal.value
        assertNotNull("索取期间诊断页要能看到「正在重新获取」", renewal)
        assertTrue("临期文案要说清还剩多久：$renewal", renewal!!.contains("临期"))
    }

    @Test
    fun 凭证还早时不索取() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        signaler.room.value = room("AB12CD")
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 3_000)))

        coordinator.checkCredentialRenewal()

        assertEquals("还剩 3000 秒，远没到续签点", emptyList<String>(), signaler.resumed)
        assertEquals("没动作就不该显示任何续签状态", null, coordinator.credentialRenewal.value)
    }

    /**
     * R-16 的正身：凭证**已经过期**时重连（或刚回到前台）要能自己救回来。
     *
     * 这条与上一条的区别不只是文案 —— 过期时 `renewalDue` 的判据落在 `expiry` 的
     * 另一侧，而「还剩 0 秒」这种边界最容易被 `>` / `>=` 写反而静默漏掉。
     */
    @Test
    fun 凭证已过期时也要索取() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        signaler.room.value = room("AB12CD")
        signaler.pushIce(listOf(turnEntry(nowSeconds() - 10)))

        coordinator.checkCredentialRenewal()

        assertEquals(listOf("AB12CD"), signaler.resumed)
        val renewal = coordinator.credentialRenewal.value
        assertTrue("过期文案要对上 R-16 的说法：$renewal", renewal!!.contains("已过期"))
    }

    /**
     * 同一份临期凭证只索取一次。
     *
     * 没有这条的话，15 秒一轮的巡检会在服务端还没来得及回执之前连打四次
     * —— 每次 `sys.resume` 服务端都会给对端发一条 `sys.peer_joined`，
     * 对端日志会被刷成一片。
     */
    @Test
    fun 同一份临期凭证只索取一次() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        signaler.room.value = room("AB12CD")
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 100)))

        coordinator.checkCredentialRenewal()
        coordinator.checkCredentialRenewal()
        coordinator.checkCredentialRenewal()

        assertEquals("三次巡检、同一份凭证 ⇒ 只索取一次", 1, signaler.resumed.size)
    }

    @Test
    fun 服务端说没有中继时不索取() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        signaler.room.value = room("AB12CD")
        // 服务端只给了 STUN（coturn 探不通）：此时索取也拿不到中继，白打一次信令。
        signaler.pushIce(
            listOf(IceServer(urls = listOf("stun:203.0.113.9:3478"))),
            turnAvailable = false,
        )

        coordinator.checkCredentialRenewal()

        assertEquals(emptyList<String>(), signaler.resumed)
    }

    @Test
    fun 不在房间里时不索取() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        // 房间视图是空的（还没进房 / 已经退房）：没有 roomId 可 resume。
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 100)))

        coordinator.checkCredentialRenewal()

        assertEquals(emptyList<String>(), signaler.resumed)
    }

    /**
     * 拿到新凭证之后判据要**自己复位**：下一次临期（新 expiry）还得能再索取一次。
     *
     * 这条钉的是去重字段的语义 —— 它记的是「哪一个过期时刻」，不是「索取过没有」。
     * 若写成后者（一个布尔），第一次续签之后这条兜底路就永久失效了，
     * 而症状要等一小时后的第二次续签才显形。
     */
    @Test
    fun 换到新凭证后下一次临期仍能索取() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        signaler.room.value = room("AB12CD")
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 100)))

        coordinator.checkCredentialRenewal()
        // 服务端回执里的新凭证（expiry 跳变），随后它自己又走到临期
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 200)))
        coordinator.checkCredentialRenewal()

        assertEquals("两次不同的 expiry ⇒ 索取两次", 2, signaler.resumed.size)
    }

    /**
     * 索取失败：放掉去重（好让下一轮能重试），但先退避 —— 否则信令一断，
     * 每一轮巡检都会各打一次失败日志，把链路日志刷满。
     */
    @Test
    fun 索取失败后要退避而不是每轮重试() = runBlocking {
        val (coordinator, signaler) = newCoordinator()
        signaler.room.value = room("AB12CD")
        signaler.pushIce(listOf(turnEntry(nowSeconds() + 100)))
        signaler.resumeFailure = IllegalStateException("信令断开")

        coordinator.checkCredentialRenewal()
        coordinator.checkCredentialRenewal()

        assertEquals("失败后 60 秒内不许重试", 1, signaler.resumeAttempts)
        val renewal = coordinator.credentialRenewal.value
        assertTrue("失败原因要留在诊断页上：$renewal", renewal!!.contains("索取失败"))
    }

    /**
     * 判据本身（纯函数）的四个边界。抽出来单测是因为 `>=` 与 `>` 差一个等号时，
     * 症状是「恰好落在阈值上的那一秒不续签」—— 在真机上永远复现不了。
     */
    @Test
    fun 续签判据的四个边界() {
        assertFalse("没有凭证（服务端没下发中继）⇒ 不索取", PeerCoordinator.renewalDue(null, 1_000, 300))
        assertFalse("还早（阈值前一秒）⇒ 不索取", PeerCoordinator.renewalDue(1_300, 999, 300))
        assertTrue("恰好落在阈值上 ⇒ 索取", PeerCoordinator.renewalDue(1_300, 1_000, 300))
        assertTrue("已过期 ⇒ 索取", PeerCoordinator.renewalDue(900, 1_000, 300))
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    private fun newCoordinator(): Pair<PeerCoordinator, RenewRecordingSignaler> {
        val signaler = RenewRecordingSignaler()
        val coordinator = PeerCoordinator(
            myUserId = ME,
            signaler = signaler,
            newLink = { RenewStubPeerLink() },
            router = MessageRouter(handlers = emptyList()),
            // 与 `PeerCoordinatorCallTest` 同一个理由：JVM 上没有主线程，
            // Unconfined 让 `room.value = …` 与 `events.emit(…)` 在**调用这一行**
            // 里同步跑完，断言不必等。
            scope = CoroutineScope(Dispatchers.Unconfined),
            // 关掉定时巡检：本文件直接调 `checkCredentialRenewal()`，
            // 留着定时器只会让用例多一个后台协程。
            credentialCheckIntervalMs = 0,
        )
        return coordinator to signaler
    }

    private fun room(roomId: String) = Room(
        roomId = roomId,
        hostUserId = ME,
        mode = "cinema",
        members = listOf(Member(ME, "我", online = true)),
        modeState = null,
    )

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1_000

    /** 一条中继配置。username 的格式照服务端 `mintTurnCredential`：`<expiry>:<userId>`。 */
    private fun turnEntry(expiryEpochSeconds: Long) = IceServer(
        urls = listOf("turn:203.0.113.9:3478?transport=udp"),
        username = "$expiryEpochSeconds:me",
        credential = "test-only",
    )

    private companion object {
        val ME = UserId("me")
    }
}

/** 记录索取动作的假信令；[pushIce] 模拟服务端下发 `sys.ice_servers`。 */
private class RenewRecordingSignaler : Signaler {

    override val state: StateFlow<SignalState> = MutableStateFlow(SignalState.Online)

    override val room: MutableStateFlow<Room?> = MutableStateFlow(null)

    override val sessionEpoch: Long = 7L

    override val events: MutableSharedFlow<SignalEvent> = MutableSharedFlow()

    override val rtcSignals: SharedFlow<com.cinemahall.core.model.RtcSignal> = MutableSharedFlow()

    /** 成功索取到的 roomId 列表。 */
    val resumed = mutableListOf<String>()

    /** 尝试次数（含失败）—— 退避用例要区分「没试」与「试了但失败」。 */
    var resumeAttempts = 0
        private set

    var resumeFailure: Exception? = null

    suspend fun pushIce(servers: List<IceServer>, turnAvailable: Boolean = true) {
        events.emit(SignalEvent.IceServersUpdated(servers, turnAvailable))
    }

    override suspend fun connect(endpoint: String, userId: UserId) = Unit

    override suspend fun createRoom(nickname: String, password: String?, roomId: String?): Room =
        error("本用例不建房")

    override suspend fun joinRoom(roomId: String, nickname: String, password: String?): Room =
        error("本用例不加入房间")

    override suspend fun resume(roomId: String, userId: UserId, sessionEpoch: Long): Room {
        resumeAttempts += 1
        resumeFailure?.let { throw it }
        resumed += roomId
        return room.value ?: error("没有房间视图")
    }

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

/** 续签逻辑不碰链路的任何动作 —— 全部记空实现，只求能把协调者构造出来。 */
private class RenewStubPeerLink : PeerLink {

    override val state: StateFlow<PeerState> = MutableStateFlow(PeerState.Idle)

    override val stats: StateFlow<LinkStats?> = MutableStateFlow(null)

    override val diagnostics: StateFlow<PeerDiagnostics> = MutableStateFlow(PeerDiagnostics())

    override val messages: SharedFlow<String> = MutableSharedFlow()

    override val remote: StateFlow<UserId?> = MutableStateFlow(null)

    override val isOfferer: StateFlow<Boolean?> = MutableStateFlow(null)

    override val callAudio: StateFlow<CallAudioState> = MutableStateFlow(CallAudioState())

    override fun start(myUserId: UserId, remoteUserId: UserId, config: RtcConfig, sink: RtcSignalSink) = Unit

    override fun onSignal(signal: com.cinemahall.core.model.RtcSignal) = Unit

    override fun setIceServers(servers: List<IceServer>) = Unit

    override fun setTransportPolicy(policy: TransportPolicy) = Unit

    override fun restartIce() = Unit

    override fun send(text: String): Boolean = false

    override fun refreshStats() = Unit

    override fun close() = Unit

    override fun setCallEnabled(enabled: Boolean) = Unit

    override fun setCallVolume(volume: Float) = Unit

    override fun setMovieVolumeLimit(enabled: Boolean) = Unit
}
