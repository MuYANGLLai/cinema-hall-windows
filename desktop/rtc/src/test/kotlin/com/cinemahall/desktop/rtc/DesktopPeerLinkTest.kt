package com.cinemahall.desktop.rtc

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.rtc.LinkStats
import com.cinemahall.core.rtc.PeerDiagnostics
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.rtc.RtcConfig
import com.cinemahall.core.rtc.RtcSignalSink
import com.cinemahall.core.rtc.TransportPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 的门禁：**状态机在纯 JVM 上跑通，一个 native 符号都不加载**。
 *
 * 用 [FakeBackend] 把 native 换掉之后，这里能钉住的正是最容易错、又最难在真机上看见的
 * 那几条：谁是 offer 方、第二轮 offer 什么时候补、候选为什么必须排队、旧连接的回调为什么
 * 必须丢。这些错了的表现都很像 ——「有时连不上」，日志里什么都没有。
 */
class DesktopPeerLinkTest {

    private val alice = UserId("device-a")
    private val bob = UserId("device-b")

    // ── offerer 判定 ────────────────────────────────────────────────────────

    @Test
    fun `userId 小的那端是 offer 方，并在 start 后立刻发首轮`() {
        val backend = FakeBackend()
        val sink = RecordingSink()
        val link = DesktopPeerLink(backend, InlineDispatcher)

        link.start(alice, bob, RtcConfig(), sink)

        assertEquals(true, link.isOfferer.value)
        assertEquals(true, backend.offerer)
        assertEquals(listOf("createOffer(false)"), backend.offers())
        // 本地描述落地之后必须真的发给对端（否则对端永远在等）
        assertEquals(listOf("offer:local-offer"), sink.events)
    }

    @Test
    fun `userId 大的那端不是 offer 方，也不主动发 offer`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)

        link.start(bob, alice, RtcConfig(), RecordingSink())

        assertEquals(false, link.isOfferer.value)
        assertEquals(false, backend.offerer)
        // 非 offer 方只能等对端 offer（它自己新增不了 m 行）。
        assertTrue(backend.offers().isEmpty())
        assertEquals(PeerState.Negotiating, link.state.value)
    }

    @Test
    fun `角色判定只看 userId 字典序，与谁是房主无关`() {
        // 同一个远端，换个本机 userId，角色必须跟着变 —— 这条钉的是「不许第二份实现」。
        assertEquals(true, offererOf(UserId("a"), UserId("b")))
        assertEquals(false, offererOf(UserId("z"), UserId("b")))
    }

    // ── 协商流程 ────────────────────────────────────────────────────────────

    @Test
    fun `answer 方收到 offer 先补齐排队候选，再回 answer`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(bob, alice, RtcConfig(), RecordingSink())

        // 候选先到（真实链路上很常见：对端 ICE 比它的 offer 先到本机）
        link.onSignal(RtcSignal.Ice(alice, "cand-1", "audio", 0))
        assertTrue("远端描述还没落地时不许投候选", backend.addedCandidates.isEmpty())

        link.onSignal(RtcSignal.Offer(alice, "remote-offer"))

        // 顺序：setRemote → 补投候选 → createAnswer
        val order = backend.calls.filter {
            it.startsWith("setRemote") || it.startsWith("addIce") || it == "createAnswer"
        }
        assertEquals(
            listOf("setRemote(OFFER)", "addIce(cand-1)", "createAnswer"),
            order,
        )
    }

    @Test
    fun `offer 方收到 answer 后补投候选并结束这一轮`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())

        link.onSignal(RtcSignal.Ice(bob, "cand-9", "video", 0))
        link.onSignal(RtcSignal.Answer(bob, "remote-answer"))

        assertEquals(1, backend.addedCandidates.size)
        assertEquals("cand-9", backend.addedCandidates.single())
    }

    @Test
    fun `远端描述落地前收到的候选全部排队，一条都不丢`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(bob, alice, RtcConfig(), RecordingSink())

        repeat(3) { link.onSignal(RtcSignal.Ice(alice, "cand-$it", "audio", 0)) }
        assertTrue(backend.addedCandidates.isEmpty())

        link.onSignal(RtcSignal.Offer(alice, "remote-offer"))
        assertEquals(listOf("cand-0", "cand-1", "cand-2"), backend.addedCandidates)
    }

    @Test
    fun `end-of-candidates 不算坏消息，也不投给后端`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(bob, alice, RtcConfig(), RecordingSink())
        link.onSignal(RtcSignal.Offer(alice, "remote-offer"))

        link.onSignal(RtcSignal.Ice(alice, null, null, null))

        assertTrue(backend.addedCandidates.isEmpty())
        assertEquals(PeerState.Negotiating, link.state.value)
    }

    @Test
    fun `offer 方收到 offer 判为角色冲突，而不是硬接`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())

        link.onSignal(RtcSignal.Offer(bob, "stray-offer"))

        val state = link.state.value
        assertTrue("应进入 Failed，实际 $state", state is PeerState.Failed)
        assertTrue((state as PeerState.Failed).reason.contains("角色冲突"))
    }

    @Test
    fun `数据通道 OPEN 才算连通，关了退回协商中`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())

        backend.callbacks.onDataChannelOpen()
        assertEquals(PeerState.Connected, link.state.value)

        backend.callbacks.onDataChannelClosed()
        assertEquals(PeerState.Negotiating, link.state.value)
    }

    // ── 第二轮 offer 的挂起与补发 ────────────────────────────────────────────

    @Test
    fun `首轮还没走完时，第二次 offer 挂起，回到 STABLE 再补`() {
        // 手控完成时机：默认的「自动落地」会把首轮立刻结掉，测不出挂起。
        val backend = FakeBackend().apply { autoCompleteOffers = false }
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())

        // 首轮已在飞（start 发的那一条），此时再要求一轮必须挂起
        link.restartIce()
        assertEquals(1, backend.offers().size)

        // 首轮落地，但信令还没 STABLE（模拟 have-local-offer）
        backend.stable = false
        backend.callbacks.onLocalDescription(DescriptionKind.OFFER, "local-offer-1")
        backend.callbacks.onSignalingStateStable()
        assertEquals("非 STABLE 时补发必须继续等", 1, backend.offers().size)

        backend.stable = true
        backend.callbacks.onSignalingStateStable()
        assertEquals(listOf("createOffer(false)", "createOffer(true)"), backend.offers())
    }

    @Test
    fun `非 offer 方重启 ICE 只能请求对端发起`() {
        val backend = FakeBackend()
        val sink = RecordingSink()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(bob, alice, RtcConfig(), sink)

        link.restartIce()

        assertEquals(listOf("renegotiate:ice"), sink.events)
        assertTrue(backend.offers().isEmpty())
    }

    @Test
    fun `renegotiate 的 reason 是行为判据 —— audio 不重启 ICE`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())
        backend.callbacks.onLocalDescription(DescriptionKind.OFFER, "o1")
        backend.callbacks.onSignalingStateStable()

        link.onSignal(RtcSignal.Renegotiate(bob, RtcSignal.RENEGOTIATE_REASON_AUDIO))
        link.onSignal(RtcSignal.Renegotiate(bob, RtcSignal.RENEGOTIATE_REASON_ICE))

        assertEquals(listOf("createOffer(false)", "createOffer(false)", "createOffer(true)"), backend.offers())
    }

    @Test
    fun `非 offer 方收到 renegotiate 不跟着发 offer`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(bob, alice, RtcConfig(), RecordingSink())

        link.onSignal(RtcSignal.Renegotiate(alice, RtcSignal.RENEGOTIATE_REASON_ICE))

        assertTrue(backend.offers().isEmpty())
    }

    // ── 诊断与代次 ──────────────────────────────────────────────────────────

    @Test
    fun `被拒的候选自己计数 —— addIceCandidate 失败不抛异常也不打日志`() {
        val backend = FakeBackend()
        backend.iceAccepted = false
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(bob, alice, RtcConfig(), RecordingSink())
        link.onSignal(RtcSignal.Offer(alice, "remote-offer"))

        link.onSignal(RtcSignal.Ice(alice, "bad-1", "audio", 0))
        link.onSignal(RtcSignal.Ice(alice, "bad-2", "audio", 0))

        assertEquals(2, link.diagnostics.value.rejectedIceCandidates)
    }

    @Test
    fun `后端诊断快照不会覆盖本机数的被拒候选`() {
        val backend = FakeBackend()
        backend.iceAccepted = false
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(bob, alice, RtcConfig(), RecordingSink())
        link.onSignal(RtcSignal.Offer(alice, "remote-offer"))
        link.onSignal(RtcSignal.Ice(alice, "bad", "audio", 0))

        backend.callbacks.onDiagnostics(PeerDiagnostics(iceConnectionState = "checking"))

        assertEquals("checking", link.diagnostics.value.iceConnectionState)
        assertEquals(1, link.diagnostics.value.rejectedIceCandidates)
    }

    @Test
    fun `关闭之后旧连接的回调一律丢弃`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())
        val staleCallbacks = backend.callbacks

        link.close()
        assertEquals(PeerState.Closed, link.state.value)

        // 旧连接迟到的一条 FAILED / OPEN：不许改动新状态
        staleCallbacks.onFailure("stale failure")
        staleCallbacks.onDataChannelOpen()
        assertEquals(PeerState.Closed, link.state.value)
        assertNull(link.remote.value)
        assertNull(link.isOfferer.value)
    }

    @Test
    fun `关闭会清掉通话意图，音量偏好保留`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())
        link.setCallVolume(0.3f)
        link.setCallEnabled(true)
        assertTrue(link.callAudio.value.requested)

        link.close()

        assertFalse("重进房不许通话自己复活", link.callAudio.value.requested)
        assertEquals(0.3f, link.callAudio.value.volume, 0.0001f)
    }

    // ── 其它 ────────────────────────────────────────────────────────────────

    @Test
    fun `ICE 配置变了会换配置并重启`() {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(alice, bob, RtcConfig(), RecordingSink())
        backend.callbacks.onLocalDescription(DescriptionKind.OFFER, "o1")
        backend.callbacks.onSignalingStateStable()

        link.setIceServers(listOf(IceServer(urls = listOf("turn:127.0.0.1:3478"))))
        link.setTransportPolicy(TransportPolicy.RELAY_ONLY)

        assertEquals(listOf("apply", "apply"), backend.calls.filter { it == "apply" })
        // 每次换配置都该触发一轮 renegotiation（libwebrtc 不会自己重取 iceServers）
        assertEquals(3, backend.offers().size)
    }

    @Test
    fun `没有链路时 send 返回 false，不排队也不假装成功`() {
        val link = DesktopPeerLink(FakeBackend(), InlineDispatcher)
        assertFalse(link.send("x"))

        val backend = FakeBackend()
        val started = DesktopPeerLink(backend, InlineDispatcher)
        started.start(alice, bob, RtcConfig(), RecordingSink())
        backend.channelOpen = false
        assertFalse(started.send("x"))
        backend.channelOpen = true
        assertTrue(started.send("x"))
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun offererOf(my: UserId, remote: UserId): Boolean {
        val backend = FakeBackend()
        val link = DesktopPeerLink(backend, InlineDispatcher)
        link.start(my, remote, RtcConfig(), RecordingSink())
        return link.isOfferer.value == true
    }
}

/** 记录调用顺序，并允许测试手动触发回调（真实实现里它们来自 native 线程）。 */
private class FakeBackend : RtcBackend {

    val calls = mutableListOf<String>()
    val addedCandidates = mutableListOf<String>()

    var offerer: Boolean? = null
    var stable = true
    var iceAccepted = true
    var channelOpen = false

    /**
     * 默认「自动落地」：`createOffer` 之后立刻回调 `onLocalDescription(OFFER)`，
     * 模拟真实 backend 里 setLocalDescription 成功。关掉它才能测「挂起补发」。
     */
    var autoCompleteOffers = true
    lateinit var callbacks: RtcBackendCallbacks

    fun offers(): List<String> = calls.filter { it.startsWith("createOffer") }

    override fun start(config: RtcConfig, offerer: Boolean, callbacks: RtcBackendCallbacks) {
        this.offerer = offerer
        this.callbacks = callbacks
        calls += "start"
    }

    override fun createOffer(iceRestart: Boolean) {
        calls += "createOffer($iceRestart)"
        if (autoCompleteOffers) {
            callbacks.onLocalDescription(DescriptionKind.OFFER, "local-offer")
        }
    }

    override fun createAnswer() {
        calls += "createAnswer"
    }

    override fun isStable(): Boolean = stable

    override fun setRemoteDescription(kind: DescriptionKind, sdp: String) {
        calls += "setRemote($kind)"
        // 真实实现是在 setRemoteDescription 成功回调里通知的；这里同步模拟。
        callbacks.onRemoteDescriptionSet(kind)
    }

    override fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int): Boolean {
        calls += "addIce($candidate)"
        if (iceAccepted) addedCandidates += candidate
        return iceAccepted
    }

    override fun applyConfiguration(config: RtcConfig) {
        calls += "apply"
    }

    override fun restartIce() {
        calls += "restartIce"
    }

    override fun sendText(text: String): Boolean = channelOpen

    override fun isChannelOpen(): Boolean = channelOpen

    override fun snapshotStats(): LinkStats? = null

    override fun pushCapturePcm(pcm: ByteArray): Boolean {
        calls += "push"
        return true
    }

    override fun audioRtp(): com.cinemahall.core.rtc.AudioRtpCounters? = null

    override fun downlinkReadout(): DownlinkReadout = DownlinkReadout()

    override fun setAudioSending(enabled: Boolean): Boolean {
        calls += "audioSending($enabled)"
        return true
    }

    /** 门禁用：记下装配点有没有把 AEC 参考接进来（null = 这条链路没有下行播放）。 */
    var renderSink: ((ByteArray) -> Unit)? = null
        private set

    override fun setRenderSink(sink: ((ByteArray) -> Unit)?) {
        renderSink = sink
        calls += "renderSink(${sink != null})"
    }

    var remoteVolume: Float? = null
        private set

    override fun setRemoteVolume(volume: Float): Boolean {
        remoteVolume = volume
        calls += "remoteVolume($volume)"
        return true
    }

    override fun snapshotDiagnostics(): PeerDiagnostics = PeerDiagnostics()

    override fun close() {
        calls += "close"
    }
}

private class RecordingSink : RtcSignalSink {

    val events = mutableListOf<String>()

    override fun sendOffer(sdp: String) {
        events += "offer:$sdp"
    }

    override fun sendAnswer(sdp: String) {
        events += "answer:$sdp"
    }

    override fun sendIce(candidate: String?, sdpMid: String?, sdpMLineIndex: Int?) {
        events += "ice:$candidate"
    }

    override fun requestRenegotiation(reason: String?) {
        events += "renegotiate:$reason"
    }
}
