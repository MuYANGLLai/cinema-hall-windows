package com.cinemahall.core.session

import com.cinemahall.core.media.MediaRef
import com.cinemahall.core.media.PlaybackTarget
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制权（契约 §7，阶段二 P4）。
 *
 * ── 这一批用例钉的是什么 ──────────────────────────────────────────────────────
 * 修之前，`CinemaSyncEngine.onRemote` 对 `play` / `pause` / `seek` **没有任何来源判据**：
 * 六人房里**任何一个成员**按下播放都会广播给所有人，而房主也照做。两人房看不出来
 * （只有一个对端，而它多半是房主），三人房里就变成「随便谁都能拖进度条」——
 * 这正是 P4 之前那条已知缺陷，也是三端门禁会「假绿」的原因。
 *
 * 钉三条：
 *  1. 真值表六行（`ControlPolicyTable`）；
 *  2. 从端只接受 `from == 房主`（`speed` / `subtitle` 更是永远只认房主）；
 *  3. `probeId` 配对 —— 六人房里房主会对每个成员回一条心跳，只有**带我这个编号**的
 *     那条才能用于时钟采样。
 */
class CinemaControlPolicyTest {

    private val me = UserId("me")
    private val host = UserId("host")
    private val member = UserId("member")
    private val other = UserId("other")

    // ── 真值表（契约 §7 的六行）────────────────────────────────────────────

    @Test
    fun `真值表六行逐行对齐契约`() {
        // defaultAllow / override / 能否控制
        val cases = listOf(
            Triple(false, null, false),
            Triple(false, true, true),
            Triple(false, false, false),
            Triple(true, null, true),
            Triple(true, false, false),
            Triple(true, true, true),
        )

        for ((defaultAllow, override, expected) in cases) {
            val policy = ControlPolicy(
                defaultAllow = defaultAllow,
                overrides = if (override == null) emptyMap() else mapOf(member to override),
            )
            assertEquals(
                "defaultAllow=$defaultAllow override=$override",
                expected,
                ControlPolicyTable.canControl(policy, host, member, watching = true),
            )
        }
    }

    @Test
    fun `房主永远有控制权_哪怕策略把所有人关掉`() {
        assertTrue(ControlPolicyTable.canControl(ControlPolicy.HOST_ONLY, host, host, watching = false))
    }

    @Test
    fun `不在看的人即使被授权也不能控制`() {
        // 契约 §7 的公式里 `isWatching` 是**与**条件：片源不匹配的人不给控制权，
        // 否则他拖进度条会把自己那份错误的片源拖到别人的时间轴上。
        val policy = ControlPolicy(defaultAllow = true)
        assertFalse(ControlPolicyTable.canControl(policy, host, member, watching = false))
    }

    @Test
    fun `策略的线上形态能往返`() {
        val policy = ControlPolicy(defaultAllow = true, overrides = mapOf(member to false))

        val wire = policy.toWire()

        assertEquals(true, wire.defaultAllow)
        assertEquals(mapOf("member" to false), wire.overrides)
        assertEquals(policy, ControlPolicy.fromWire(wire))
    }

    @Test
    fun `旧包没有 controlPolicy 时按房主独占解释`() {
        assertEquals(ControlPolicy.HOST_ONLY, ControlPolicy.fromWire(null))
        assertFalse(ControlPolicy.HOST_ONLY.defaultAllow)
    }

    // ── 从端：只认房主 ─────────────────────────────────────────────────────

    @Test
    fun `从端忽略成员发来的播放控制`() {
        val (engine, player) = newEngine(isHost = false)
        player.play()
        player.seekTo(300_000L)

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = member))

        assertTrue("成员发来的 pause 不该停本机", player.isPlaying)
        assertTrue(engine.status.value.lastEvent.contains("从端只接受房主"))
        assertEquals("位置也不该动", 300_000L, player.currentPositionMs)
    }

    @Test
    fun `从端接受房主发来的播放控制`() {
        val (engine, player) = newEngine(isHost = false)
        player.play()

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = host))

        assertFalse("房主的 pause 必须执行", player.isPlaying)
    }

    @Test
    fun `从端永远不听成员发来的倍速与字幕`() {
        val (engine, player) = newEngine(isHost = false)

        engine.onRemote(CinemaPlayback.ACTION_SPEED, speedEnv(2.0f, from = member))
        engine.onRemote(CinemaPlayback.ACTION_SUBTITLE, subtitleEnv(3, from = member))

        assertEquals("倍速不该变", 1.0f, player.speedNow, 1e-6f)
        assertEquals("字幕轨不该变", 0, player.subtitleTrack)
    }

    // ── 房主：按策略裁决成员的控制 ──────────────────────────────────────────

    @Test
    fun `房主默认拒绝成员的控制`() {
        val (engine, player) = newEngine(isHost = true)
        player.play()

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = member))

        assertTrue("默认策略是房主独占，成员的 pause 不该生效", player.isPlaying)
        assertTrue(engine.status.value.lastEvent.contains("没有控制权"))
    }

    @Test
    fun `房主在开启全员控制后接受成员的控制`() {
        val (engine, player) = newEngine(isHost = true)
        engine.setPeerWatching(member, watching = true)
        engine.setControlPolicy(ControlPolicy(defaultAllow = true))
        player.play()

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = member))

        assertFalse("已授权 ⇒ 必须执行", player.isPlaying)
    }

    @Test
    fun `单人授权能压过全局关闭`() {
        val (engine, player) = newEngine(isHost = true)
        engine.setPeerWatching(member, watching = true)
        engine.setControlPolicy(ControlPolicy(defaultAllow = false, overrides = mapOf(member to true)))
        player.play()

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = member))

        assertFalse("override=true 只放开这一个人", player.isPlaying)
    }

    @Test
    fun `单人关闭能压过全局放开`() {
        val (engine, player) = newEngine(isHost = true)
        engine.setPeerWatching(member, watching = true)
        engine.setPeerWatching(other, watching = true)
        engine.setControlPolicy(
            ControlPolicy(defaultAllow = true, overrides = mapOf(member to false)),
        )
        player.play()

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = member))
        assertTrue("被单独踢掉的人不许控制", player.isPlaying)

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = other))
        assertFalse("其他人跟随全局开关", player.isPlaying)
    }

    @Test
    fun `不在看的人即使全局放开也控制不了`() {
        val (engine, player) = newEngine(isHost = true)
        engine.setControlPolicy(ControlPolicy(defaultAllow = true))
        player.play()

        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L, from = member))

        assertTrue("没标记成在看 ⇒ 不批", player.isPlaying)
    }

    @Test
    fun `房主收到失败风格的倍速仍然忽略`() {
        val (engine, player) = newEngine(isHost = true)
        player.play()

        engine.onRemote(CinemaPlayback.ACTION_SPEED, speedEnv(2.0f, from = member))

        assertEquals("倍速永远是房主的", 1.0f, player.speedNow, 1e-6f)
    }

    // ── probeId 配对（契约 §7）────────────────────────────────────────────

    @Test
    fun `只有带同一个_probeId_的心跳才用于时钟采样`() {
        val (engine, _) = newEngine(isHost = false)
        engine.onLinkOpened()
        // 从端发一次探测：它会带一个自增编号。
        engine.requestManualSync()
        assertTrue("探测必须真的发出去", engine.status.value.linkUp)

        val probes = sent.filter { it.first == CinemaPlayback.TYPE_SYNC_ACK }
            .map { CinemaPlaybackCodec.decodeSyncAck(it.second) }
        assertEquals(1, probes.size)
        val probeId = probes.single()!!.probeId
        assertTrue("探测必须带编号", probeId != null)

        // 不配对的（别人的回执）：只锚定，不采样。
        engine.onRemote(CinemaPlayback.ACTION_HEARTBEAT, heartbeatEnv(probeId = (probeId!! + 99)))
        assertEquals("错配的心跳不该产生时钟样本", 0, engine.status.value.clockSamples)

        // 配对的那条：采样。
        engine.onRemote(CinemaPlayback.ACTION_HEARTBEAT, heartbeatEnv(probeId = probeId))
        assertTrue("配对的心跳必须产生样本", engine.status.value.clockSamples > 0)
    }

    @Test
    fun `探测超时按同一个编号重试_三次后放弃并记一次诊断`() {
        val (engine, _) = newEngine(isHost = false)
        engine.onLinkOpened()
        engine.requestManualSync()
        val firstId = CinemaPlaybackCodec.decodeSyncAck(
            sent.first { it.first == CinemaPlayback.TYPE_SYNC_ACK }.second,
        )!!.probeId

        now += CinemaSyncEngine.PROBE_TIMEOUT_MS + 1
        engine.onTick(now)

        val retries = sent.filter { it.first == CinemaPlayback.TYPE_SYNC_ACK }
            .map { CinemaPlaybackCodec.decodeSyncAck(it.second)!! }
        assertEquals("超时后要重试", 2, retries.size)
        assertEquals("重试必须用同一个编号（否则对端的回执配不上）", firstId, retries.last().probeId)

        repeat(CinemaSyncEngine.PROBE_MAX_RETRIES) {
            now += CinemaSyncEngine.PROBE_TIMEOUT_MS + 1
            engine.onTick(now)
        }

        assertTrue(
            "三次之后放弃并记录：${engine.status.value.lastEvent}",
            engine.status.value.lastEvent.contains("超时"),
        )
    }

    // ── 本机「能不能控制」（UI 灰按钮的判据）─────────────────────────────────

    @Test
    fun `不在房间时本机可以控制`() {
        // 本地放片（没进房间）：根本不存在「控制权」这回事，灰掉会被当成播放器坏了。
        val (engine, _) = newEngine(isHost = false)
        assertTrue(engine.canControlLocally)
    }

    @Test
    fun `在房间但房主未定时本机不能控制`() {
        // 三端门禁里撞到的那一条：房主把「允许成员控制」关掉之后，从端的播放键仍然亮着。
        // 根因是「不在房间」与「房主未知」被合成了一个 null —— 两者的结论正好相反。
        val (engine, _) = newEngine(isHost = false)
        engine.setRoomContext(myUserId = member, hostUserId = null, inRoom = true)

        assertFalse("不知道听谁的，就不能让他按", engine.canControlLocally)
    }

    @Test
    fun `在房间且被授权时本机可以控制`() {
        val (engine, _) = newEngine(isHost = false)
        engine.setRoomContext(myUserId = member, hostUserId = host, inRoom = true)
        engine.setControlPolicy(ControlPolicy(defaultAllow = true))

        assertFalse("房主还没判定我在看", engine.canControlLocally)

        engine.setSelfWatching(true)
        assertTrue("在看 + 策略允许 ⇒ 可以控制", engine.canControlLocally)
    }

    @Test
    fun `房主在房间里永远能控制`() {
        val (engine, _) = newEngine(isHost = true)
        engine.setRoomContext(myUserId = host, hostUserId = host, inRoom = true)
        engine.setControlPolicy(ControlPolicy.HOST_ONLY)

        assertTrue(engine.canControlLocally)
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    private var now = 10_000L
    private val sent = mutableListOf<Pair<String, JsonObject>>()

    private fun newEngine(isHost: Boolean): Pair<CinemaSyncEngine, StubPlayer> {
        now = 10_000L
        sent.clear()
        val player = StubPlayer()
        val engine = CinemaSyncEngine(
            player = player,
            clock = { now },
            outbound = { type, payload, _ -> sent += type to payload },
            config = CinemaSyncConfig.DEFAULT,
        )
        engine.setRole(isHost)
        engine.setMyUserId(if (isHost) host else member)
        engine.setHostUserId(host)
        return engine to player
    }

    private var seq = 0L

    private fun env(type: String, payload: JsonObject, from: UserId): Envelope {
        seq += 1
        return Envelope(type = type, seq = seq, roomId = "AB23CD", from = from.value, sentAtMs = now, payload = payload)
    }

    private fun positionEnv(action: String, positionMs: Long, from: UserId) = env(
        CinemaPlayback.typeOf(action),
        CinemaPlaybackCodec.encodePosition(CinemaPosition(positionMs = positionMs, senderClockMs = now)),
        from,
    )

    private fun speedEnv(speed: Float, from: UserId) = env(
        CinemaPlayback.TYPE_SPEED,
        CinemaPlaybackCodec.encodeSpeed(CinemaSpeed(speed)),
        from,
    )

    private fun subtitleEnv(index: Int, from: UserId) = env(
        CinemaPlayback.TYPE_SUBTITLE,
        CinemaPlaybackCodec.encodeSubtitle(CinemaSubtitle(index)),
        from,
    )

    private fun heartbeatEnv(probeId: Long?) = env(
        CinemaPlayback.TYPE_HEARTBEAT,
        CinemaPlaybackCodec.encodeHeartbeat(
            CinemaHeartbeat(
                isPlaying = false,
                positionMs = 0L,
                senderClockMs = now,
                probeId = probeId,
            ),
        ),
        host,
    )

    /** 只记「有没有被指挥」的最小播放器。 */
    private class StubPlayer : PlaybackTarget {
        override var currentPositionMs: Long = 0L
        override var isPlaying: Boolean = false
        override var isPlayRequested: Boolean = false
        override val isBuffering: Boolean = false
        override val isReady: Boolean = true
        var speedNow: Float = 1.0f
        var subtitleTrack: Int = 0

        override fun play() {
            isPlaying = true
            isPlayRequested = true
        }

        override fun pause() {
            isPlaying = false
            isPlayRequested = false
        }

        override fun seekTo(positionMs: Long) {
            currentPositionMs = positionMs
        }

        override fun setMediaItem(ref: MediaRef) = Unit

        override fun setSpeed(speed: Float) {
            speedNow = speed
        }

        override fun selectSubtitleTrack(trackIndex: Int) {
            subtitleTrack = trackIndex
        }
    }
}
