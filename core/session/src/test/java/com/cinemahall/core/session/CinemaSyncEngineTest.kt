package com.cinemahall.core.session

import com.cinemahall.core.media.MediaRef
import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.PlaybackTarget
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * §6 的端到端行为：假时钟 + 假播放器，逐毫秒断言。
 *
 * ── 为什么值这一套 ──────────────────────────────────────────────────────────
 * §6.6 的 `EXACT` seek、libwebrtc 的 DataChannel、ExoPlayer 的 `onPositionDiscontinuity`
 * 在本机都起不来（需要 Android 运行时）。但**同步算法本身与它们无关**：
 * 它只吃「本机单调时钟」和「播放器当前位置」两个数。把它们注入进来之后，
 * §6.3 的每一档、§6.2 的外推、§6.1 的滤波都能在这里精确钉住 ——
 * 而真机上最难观察的恰恰就是这些边界（刚过阈值的那 1ms、静默期的最后 1ms）。
 *
 * 真机上剩下的不可验证部分因此被压缩到很小：`EXACT` seek 的实际落点、
 * 变速是否真的不变调、以及 §6.8 那些阈值在 TURN 中继下的取值。
 */
class CinemaSyncEngineTest {

    private var now = 10_000L
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private lateinit var player: FakePlayer
    private lateinit var engine: CinemaSyncEngine
    private var envelopeSeq = 0L

    /**
     * 用例里对端时钟比本机快多少毫秒（**真值**，量出来的估计要逼近它）。
     *
     * 刻意不取 0：偏差为 0 时「算对了」与「根本没算」在断言上分不开。
     */
    private val HOST_OFFSET_MS = 9_900L

    /** 对端在 [HOST_START_LOCAL_MS] 这一刻播到的位置，之后以 1x 匀速推进。 */
    private val HOST_START_POSITION_MS = 300_000L

    /** 会话起点（[setUp] 里 `now` 的初值）。 */
    private val HOST_START_LOCAL_MS = 10_000L

    private val signature = MediaSignature(
        fileName = "movie.mkv",
        sizeBytes = 4L * 1024 * 1024,
        blocks = listOf("a".repeat(64)),
    )

    @Before
    fun setUp() {
        // 起点刻意不取 0：`elapsedRealtime()` 真的会在开机后一段时间内接近 0，
        // 「拿 0 当哨兵值」这种写法会在真机上间歇性地失效，而单测若从 0 开始就发现不了。
        now = 10_000L
        sent.clear()
        envelopeSeq = 0L
        player = FakePlayer { now }
        engine = CinemaSyncEngine(
            player = player,
            clock = { now },
            // 第三参是 `to`（阶段二 v2 的定向消息）。本文件验的是「发了什么」，
            // 收件人由 `CinemaHandler` 一侧决定，所以这里丢掉它。
            outbound = { type, payload, _ -> sent += type to payload },
            config = CinemaSyncConfig.DEFAULT,
        )
        // 阶段二 P4 的控制权门禁：本文件里的所有入站消息都来自 `peer-user-id`，
        // 所以把他设成房主 —— 从端「只接受房主」、房主「按策略裁决」两条判据
        // 在默认策略下都放行**房主自己**发的消息，既有用例的语义因此不变。
        // 专门验门禁的用例在 `CinemaControlPolicyTest` 里自己设策略。
        engine.setHostUserId(UserId("peer-user-id"))
        engine.setMyUserId(UserId("me"))
    }

    // ── 角色与广播（§6.4 房主侧、§6.5 主控）────────────────────────────────

    @Test
    fun 房主本地起播暂停拖动都广播并带上位置与单调时钟() {
        engine.setRole(true)
        engine.onLinkOpened()

        // 界面的调用约定是「先动播放器，再调引擎」，所以这里也照那个顺序来。
        now = 20_000L
        player.play()
        engine.onLocalPlay(1_234L)
        now = 20_100L
        player.pause()
        engine.onLocalPause(2_345L)
        now = 20_200L
        engine.onLocalSeek(3_456L)

        assertEquals(
            listOf(CinemaPlayback.TYPE_PLAY, CinemaPlayback.TYPE_PAUSE, CinemaPlayback.TYPE_SEEK),
            sent.map { it.first },
        )

        val play = CinemaPlaybackCodec.decodePosition(sent[0].second)!!
        assertEquals(1_234L, play.positionMs)
        assertEquals(20_000L, play.senderClockMs)

        val seek = CinemaPlaybackCodec.decodePosition(sent[2].second)!!
        assertEquals(3_456L, seek.positionMs)
        assertEquals(20_200L, seek.senderClockMs)
    }

    @Test
    fun 播放器未就绪时按播放不广播() {
        engine.setRole(true)
        engine.onLinkOpened()
        sent.clear()

        // 还没选片 / prepare 没完成：Media3PlaybackTarget.play() 是空操作。
        // 照广播不误的话，本机什么都没播、对端却开始放，而本机随后还会被
        // 自己的心跳判成「落后」反复硬 seek 去追一个自己放不了的位置。
        player.ready = false
        player.play()
        engine.onLocalPlay(1_000L)

        assertTrue("未就绪时不该广播", sent.isEmpty())
        assertTrue(engine.status.value.lastEvent.contains("还没就绪"))
    }

    @Test
    fun 房主的起播发生在引擎入口之外时_tick_会就地锚定() {
        // 产品路径里最常见的起播**不走** `onLocalPlay`：`--open` 与「选好片就播」
        // 都是播放器自己在组合里 `play()`，引擎是先挂上播放器、后看到它已经在播。
        // 建链时 `resetSyncState()` 把播放态按事实记成 true ⇒ `detectPlayStateChange`
        // 判「没变化」⇒ 房主的时间轴永远没人建立：面板一直 `anchored=false`，
        // 从端只能干等心跳。2026-11-06 的真机就是这个形状。
        player.play()
        now = 11_000L
        engine.setRole(true)
        engine.onLinkOpened()
        assertFalse("建链那一刻还没有任何人建立时间轴", engine.status.value.isAnchored)

        engine.onTick(now)

        val st = engine.status.value
        assertTrue("tick 之后房主的时间轴必须已经建立", st.isAnchored)
        assertEquals("锚点就是播放器此刻的位置", 1_000L, st.anchoredMediaMs)
        assertEquals("外推出去的目标与本地位置一致", 1_000L, st.targetPositionMs)
        assertTrue(st.lastEvent.contains("就地锚定"))
    }

    @Test
    fun 从端在同样情况下不自己锚定() {
        // 同一次 tick 对从端必须**什么都不做**：它的时间轴只能由房主定义。
        // 从端自己锚一次，就等于「各播各的」—— 而且不会有任何报错。
        player.play()
        now = 11_000L
        engine.setRole(false)
        engine.onLinkOpened()
        sent.clear()

        engine.onTick(now)

        assertFalse("从端不许自建时间轴", engine.status.value.isAnchored)
        assertTrue("也不许因为这件事广播任何东西", sent.isEmpty())
    }

    @Test
    fun 房主按心跳间隔广播心跳而不是每个_tick_都发() {
        engine.setRole(true)
        engine.onLinkOpened()   // 心跳基准落在此刻 = 10_000

        now += 2_499
        engine.onTick(now)
        assertEquals(0L, engine.status.value.heartbeatsSent)

        now += 1
        engine.onTick(now)
        assertEquals(1L, engine.status.value.heartbeatsSent)
        assertEquals(CinemaPlayback.TYPE_HEARTBEAT, sent.last().first)
    }

    @Test
    fun 房主收到_sync_ack_立刻回一条心跳而不是等定时器() {
        engine.setRole(true)
        engine.onLinkOpened()
        sent.clear()

        val ack = CinemaSyncAck(ackSeq = 7L, clientPositionMs = 4_000L, driftMs = 123L, rttMs = 88L)
        now += 100
        engine.onRemote(
            CinemaPlayback.ACTION_SYNC_ACK,
            env(CinemaPlayback.TYPE_SYNC_ACK, CinemaPlaybackCodec.encodeSyncAck(ack)),
        )

        // 等待定时器的话，对端量到的 RTT 里绝大部分是「定时器还差多久到点」，
        // 与路径延迟无关。这一条断言把「立刻回」这个设计钉住。
        assertEquals(listOf(CinemaPlayback.TYPE_HEARTBEAT), sent.map { it.first })
        assertEquals(123L, engine.status.value.remoteDriftMs)
        assertEquals(88L, engine.status.value.remoteRttMs)
    }

    @Test
    fun 房主不因为定时器而重复发心跳与收到_sync_ack_的那条重叠() {
        engine.setRole(true)
        engine.onLinkOpened()
        sent.clear()

        now += 100
        engine.onRemote(
            CinemaPlayback.ACTION_SYNC_ACK,
            env(
                CinemaPlayback.TYPE_SYNC_ACK,
                CinemaPlaybackCodec.encodeSyncAck(CinemaSyncAck(1L, 0L, 0L, 0L)),
            ),
        )
        // 立刻回的那条把「上次心跳时刻」推到 now，所以不到间隔不会再发
        now += 100
        engine.onTick(now)

        assertEquals(1, sent.count { it.first == CinemaPlayback.TYPE_HEARTBEAT })
    }

    @Test
    fun 房主也接受从端发来的播放暂停与拖动_但忽略倍速() {
        engine.setRole(true)
        engine.onLinkOpened()
        player.play()
        player.seekTo(300_000L)

        // 倍速仍是房主主控（§6.5）：从端界面上的倍速选择器是灰的，能发过来的
        // 只可能是改过的 APK。忽略但留一句日志 —— 静默丢掉会让「从端发的倍速
        // 没生效」被当成网络问题查。
        engine.onRemote(
            CinemaPlayback.ACTION_SPEED,
            env(CinemaPlayback.TYPE_SPEED, CinemaPlaybackCodec.encodeSpeed(CinemaSpeed(2.0f))),
        )
        assertTrue(engine.status.value.lastEvent.contains("非房主"))
        assertEquals("从端发来的倍速不该改到房主", 1.0f, player.speedNow, 1e-6f)

        // 播放 / 暂停 / 拖动本轮已放开成双向：房主收到也要照做，并重设自己的锚点。
        // 不重设的话房主那台的时间轴会停在旧位置，半小时后的诊断面板全是假的。
        engine.onRemote(
            CinemaPlayback.ACTION_SEEK,
            positionEnv(CinemaPlayback.ACTION_SEEK, 600_000L),
        )
        assertEquals("房主也要跟着从端跳", 600_000L, player.currentPositionMs)
        assertTrue("执行本地操作也要重设锚点", engine.status.value.isAnchored)

        engine.onRemote(
            CinemaPlayback.ACTION_PAUSE,
            positionEnv(CinemaPlayback.ACTION_PAUSE, 600_000L),
        )
        assertFalse("房主收到从端的暂停也要停", player.isPlaying)
        assertTrue("差 0ms，在软阈值内 → 只暂停不 seek", engine.status.value.lastEvent.contains("只暂停不 seek"))
    }

    @Test
    fun 从端本地操作照样广播_但绝不发心跳() {
        engine.setRole(false)
        engine.onLinkOpened()

        // 定时器在从端不该产生任何出站：心跳是房主的职责。
        now += 10_000
        engine.onTick(now)
        assertTrue("从端不因定时器发任何东西", sent.isEmpty())

        // 但本机操作要广播 —— 这正是本轮新放开的「所有人都能暂停/播放/快进快退」。
        player.play()
        engine.onLocalPlay(1_000L)
        player.pause()
        engine.onLocalPause(1_000L)
        engine.onLocalSeek(1_000L)

        assertEquals(
            listOf(CinemaPlayback.TYPE_PLAY, CinemaPlayback.TYPE_PAUSE, CinemaPlayback.TYPE_SEEK),
            sent.map { it.first },
        )
        assertEquals("心跳仍只由房主发", 0, sent.count { it.first == CinemaPlayback.TYPE_HEARTBEAT })
    }

    // ── 倍速（§6.5：整场属性，房主主控）────────────────────────────────────

    @Test
    fun 房主改倍速立刻生效并在连点时合并成最终值() {
        engine.setRole(true)
        engine.onLinkOpened()
        player.play()
        now += 10_000

        engine.onLocalSpeed(1.5f)
        assertEquals(listOf(CinemaPlayback.TYPE_SPEED), sent.map { it.first })
        assertEquals(1.5f, CinemaPlaybackCodec.decodeSpeed(sent.single().second)!!.speed, 1e-6f)
        assertEquals("本机播放器要立刻改，不能等合并", 1.5f, player.speedNow, 1e-6f)

        // 连点 1.5 → 2.0 → 1.25，间隔都落在节流窗口内
        now += 100
        engine.onLocalSpeed(2.0f)
        now += 100
        engine.onLocalSpeed(1.25f)
        assertEquals(
            "窗口内的连点不该逐条发出：每一条都会让从端重设一次锚点",
            1,
            sent.count { it.first == CinemaPlayback.TYPE_SPEED },
        )
        assertEquals(1.25f, player.speedNow, 1e-6f)

        // 过了节流间隔由 tick 冲刷出一条「最终值」
        now += 400
        engine.onTick(now)
        val speeds = sent.filter { it.first == CinemaPlayback.TYPE_SPEED }
        assertEquals(2, speeds.size)
        assertEquals(1.25f, CinemaPlaybackCodec.decodeSpeed(speeds.last().second)!!.speed, 1e-6f)
    }

    @Test
    fun 从端收到倍速会同时改播放器速率与时间轴斜率() {
        followerAnchoredAndPlaying(300_000L)

        now += 100
        engine.onRemote(
            CinemaPlayback.ACTION_SPEED,
            env(CinemaPlayback.TYPE_SPEED, CinemaPlaybackCodec.encodeSpeed(CinemaSpeed(2.0f))),
        )
        assertEquals(2.0f, player.speedNow, 1e-6f)
        assertEquals(2.0f, engine.status.value.playbackSpeed, 1e-6f)

        // 位置必须连续：只换斜率，不搬锚点。搬了的话 1x → 2x 那一下会凭空出现
        // 一个「(2−1) × 已过时间」的台阶，直接落在硬阈值之外 → 立刻硬 seek 一次。
        assertEquals(300_100L, engine.status.value.targetPositionMs)

        // 之后的斜率是 2x：再过 1 秒目标该前进 2000ms，而不是 1000ms。
        // 若只改播放器不改斜率，每秒会攒出 1000ms 的假漂移 → 每几百毫秒硬跳一次。
        now += 1_000
        engine.onTick(now)
        assertEquals(302_100L, engine.status.value.targetPositionMs)
        assertEquals("自己算出来的假漂移必须是 0", 0L, engine.status.value.driftMs)
    }

    @Test
    fun 倍速与漂移因子相乘_而不是互相覆盖() {
        followerAnchoredAndPlaying(300_000L)
        engine.onRemote(
            CinemaPlayback.ACTION_SPEED,
            env(CinemaPlayback.TYPE_SPEED, CinemaPlaybackCodec.encodeSpeed(CinemaSpeed(2.0f))),
        )

        // 注入 200ms 偏差 → 第 2 档变速追赶。`maxAdjustSpeed = 0.05` 描述的是
        // 「快 5%」，2x 下就该是 1.9x 而不是 1.05x —— 否则追赶幅度缩到 1/40，
        // 一条 200ms 的漂移要追几分钟。
        engine.injectDrift(200L)
        now += 500
        engine.onTick(now)

        assertEquals(CinemaSyncStatus.TIER_SPEED, engine.status.value.tier)
        assertEquals(1.9f, player.speedNow, 1e-5f)
        assertEquals("面板要能分别看到「整场倍速」与「实际速率」", 2.0f, engine.status.value.playbackSpeed, 1e-6f)
        assertEquals(1.9f, engine.status.value.appliedPlaybackSpeed, 1e-5f)

        // 收敛之后速度要回到**整场倍速**（2.0），而不是回到 1.0 —— 回到 1.0 的话
        // 相当于偷偷把倍速改掉了。
        player.seekTo(engine.status.value.targetPositionMs)
        now += 500
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_NONE, engine.status.value.tier)
        assertEquals(2.0f, player.speedNow, 1e-5f)
    }

    @Test
    fun 倍速超出可用区间会被夹住() {
        engine.setRole(true)
        engine.onLinkOpened()

        engine.onLocalSpeed(9f)
        assertEquals(CinemaSyncEngine.MAX_PLAYBACK_SPEED, engine.status.value.playbackSpeed, 1e-6f)

        engine.onLocalSpeed(0.01f)
        assertEquals(CinemaSyncEngine.MIN_PLAYBACK_SPEED, engine.status.value.playbackSpeed, 1e-6f)
    }

    @Test
    fun 状态快照带着倍速一起恢复() {
        engine.setRole(false)
        engine.onLinkOpened()

        // 断链之后从端手上没有任何倍速信息（resetSyncState 把它清成 1.0），
        // 而倍速是**推不出来**的状态：位置只能看出「在哪」，看不出「以什么速率走」。
        engine.onRemote(
            CinemaPlayback.ACTION_STATE_SNAPSHOT,
            env(
                CinemaPlayback.TYPE_STATE_SNAPSHOT,
                CinemaPlaybackCodec.encodeStateSnapshot(
                    CinemaStateSnapshot(
                        isPlaying = true,
                        positionMs = 9_000L,
                        mediaSignature = signature,
                        seq = 3L,
                        sessionEpoch = 5L,
                        playbackSpeed = 2.0f,
                    ),
                ),
            ),
        )

        assertEquals(2.0f, engine.status.value.playbackSpeed, 1e-6f)
        assertEquals("播放器也要跟着回到 2x", 2.0f, player.speedNow, 1e-6f)
        assertTrue(player.isPlaying)

        // 锚点用的是新斜率：9_000 + 1 秒 × 2 = 11_000
        now += 1_000
        engine.onTick(now)
        assertEquals(11_000L, engine.status.value.targetPositionMs)
    }

    // ── 播放态的双向与回声抑制 ─────────────────────────────────────────────

    @Test
    fun 本机播放态被系统改变时会广播出去() {
        followerAnchoredAndPlaying(300_000L)
        assertTrue(player.isPlaying)
        sent.clear()

        now += 500
        // 模拟 `setHandleAudioBecomingNoisy` 触发的自动暂停：播放器自己停了，
        // 引擎没收到任何 onLocalXxx 调用。不广播的症状是「暂停按了没用」——
        // 本机停了，2.5 秒后对端的心跳又把它播起来。
        player.pause()
        engine.onTick(now)

        assertEquals(listOf(CinemaPlayback.TYPE_PAUSE), sent.map { it.first })
        assertEquals(300_500L, CinemaPlaybackCodec.decodePosition(sent.single().second)!!.positionMs)

        // 时间轴斜率要跟着归零。不归零的话目标位置继续按 1.0 外推，
        // 下一秒就是一次硬 seek 把暂停中的画面拽到前面去。
        assertEquals(300_500L, engine.status.value.targetPositionMs)
    }

    @Test
    fun 本机刚广播过暂停时_那条旧心跳不该把画面播回去() {
        followerAnchoredAndPlaying(300_000L)
        sent.clear()

        player.pause()
        engine.onLocalPause(player.currentPositionMs)
        assertFalse(player.isPlaying)

        // 房主那条心跳是在它收到我们的暂停**之前**发出的，内容还是「在播」。
        // 没有回声闸门的话：画面停了 50ms 又自己播起来，两秒后才由下一个心跳停住。
        now += 50
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 300_100L, senderClockMs = now),
        )
        assertFalse("回声闸门必须拦下这条旧心跳", player.isPlaying)
        assertTrue("但它仍然可以刷新锚点", engine.status.value.isAnchored)

        // 宽限期之外的心跳表示对端**真的**在播（它已经收到并处理了我们的暂停，
        // 却仍然报 isPlaying=true 只可能是它自己又被按了播放）—— 那就照旧自愈。
        now += 5_000
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 305_000L, senderClockMs = now),
        )
        assertTrue("宽限期之外不再拦", player.isPlaying)
    }

    @Test
    fun 成员按播放时房主按自己的位置重新广播全场对齐() {
        // 2026-11-06 用户口径：「当有人点击播放时，把所有人的播放状态跟房主同步，
        // 比如播放进度，而不是只广播一次开始播放的命令」。
        // 旧实现把成员报上来的 positionMs 当新锚点 ⇒ 谁按播放，全场就被拉到**谁那台**
        // 的进度上，两人一按就错位一次。
        val member = UserId("member-b")
        engine.setMyUserId(UserId("me"))
        engine.setHostUserId(UserId("me"))          // 本机是房主
        engine.setControlPolicy(ControlPolicy(defaultAllow = true))
        // 「在看」是控制权的第二个条件（策略表：allow && 在看）—— 不设的话房主会把
        // 这条 play 当成「没权限的人发来的」直接丢掉，测试就测不到位置语义了。
        engine.setPeerWatching(member, true)
        engine.setRole(true)
        engine.onLinkOpened()

        now = 20_000L
        player.play()                               // 房主从 0 开始播
        now = 80_000L                               // 60 秒之后
        engine.onTick(now)
        sent.clear()

        // 成员在它自己那台的 5 秒处按了播放
        engine.onRemote(
            CinemaPlayback.ACTION_PLAY,
            Envelope(
                type = CinemaPlayback.TYPE_PLAY,
                seq = 99L,
                roomId = "AB23CD",
                from = member.value,
                sentAtMs = now,
                payload = CinemaPlaybackCodec.encodePosition(
                    CinemaPosition(positionMs = 5_000L, senderClockMs = now),
                ),
            ),
        )

        val play = sent.firstOrNull { it.first == CinemaPlayback.TYPE_PLAY }
        assertTrue("房主必须重新广播一条 play，让全场按它的进度对齐", play != null)
        assertEquals(
            "广播出去的必须是**房主**的位置（约 60 秒），不是成员报的 5 秒",
            60_000L,
            CinemaPlaybackCodec.decodePosition(play!!.second)!!.positionMs,
        )
        assertEquals("房主自己的锚点不能被成员的位置覆盖", 60_000L, engine.status.value.anchoredMediaMs)
        assertEquals("房主继续在播", true, player.isPlayRequested)
    }

    @Test
    fun 片源没就绪时绝不硬_seek() {
        // 2026-11-06 真机「windows 卡死」的形状：从端刚 `prepare()` 一部 5GB x265 MKV，
        // 位置还是 0；房主的心跳说它在 819804 ms。漂移算出 −822805 ms ⇒ 硬阈值 ⇒
        // `seekTo(819804)`。libVLC 的 `setTime` 是同步的，在这种「索引还没建好」的
        // 文件上会等几十秒，而引擎跑在 EDT 上 —— 界面、托盘、日志同时冻住。
        // 判据：没就绪时**一次 seek 都不许发**，档位要说明原因。
        engine.setRole(false)
        engine.onLinkOpened()
        player.ready = false

        now += 500
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = false, positionMs = 819_804L, senderClockMs = now),
        )
        engine.onTick(now + 500)

        assertEquals("没就绪就不许跳", 0, player.seekCount)
        assertEquals(CinemaSyncStatus.TIER_NOT_READY, engine.status.value.tier)
        assertTrue("锚点照样要建立（等就绪后再校准）", engine.status.value.isAnchored)
    }

    @Test
    fun 片源就绪后同一条件会硬_seek() {
        // 上一条的对照：就绪之后同一条心跳必须真的跳 —— 否则「跳过校准」会退化成
        // 「永远不校准」，那正是 0.1.4 之前的症状。
        engine.setRole(false)
        engine.onLinkOpened()
        player.ready = true

        now += 500
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = false, positionMs = 819_804L, senderClockMs = now),
        )
        engine.onTick(now + 500)

        assertTrue("就绪后必须跳到房主的位置", player.seekCount >= 1)
        assertEquals("这一拍就是硬 seek（静默期从下一拍开始算）", CinemaSyncStatus.TIER_SEEK, engine.status.value.tier)
    }

    @Test
    fun 从端的探测与回执发给房主而不是最后打开链路的那个成员() {
        // 三人房（两台手机 + Windows）的真实形状：`directedPeer` 是「最后打开链路的人」，
        // 而 `sync_ack` 的语义是「成员 → 房主」。用错人时房主永远收不到回执，
        // 从端的探测也永远等不到配对，`rtt` 停在 2006ms 这种超时假值上。
        val tos = mutableListOf<Pair<String, UserId?>>()
        val trio = CinemaSyncEngine(
            player = player,
            clock = { now },
            outbound = { type, _, to -> tos += type to to },
            config = CinemaSyncConfig.DEFAULT,
        )
        val me = UserId("me")
        // `heartbeatEnv` 的 `from` 固定是 `peer-user-id`（见 [env]），所以房主就是它。
        val host = UserId("peer-user-id")
        trio.setMyUserId(me)
        trio.setHostUserId(host)
        trio.setDirectedPeer(UserId("bystander"))   // 最后打开链路的是另一个成员
        trio.setRole(false)
        trio.onLinkOpened()
        tos.clear()

        now += 1_000
        trio.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 1_000L, senderClockMs = now),
        )

        val acks = tos.filter { it.first == CinemaPlayback.TYPE_SYNC_ACK }
        assertTrue("心跳之后要发起一次探测", acks.isNotEmpty())
        assertTrue(
            "sync_ack / 探测必须发给房主，实际发给了：${acks.map { it.second }}",
            acks.all { it.second == host },
        )
    }

    @Test
    fun 拖动广播在连点时会合并成最终位置() {
        engine.setRole(false)
        engine.onLinkOpened()

        now += 1_000
        engine.onLocalSeek(100_000L)
        assertEquals(listOf(CinemaPlayback.TYPE_SEEK), sent.map { it.first })

        // 连点 +10s：每一条都让对端做一次 EXACT seek（大 MKV 上数百 ms 到 1s）
        // 并进入 400ms 静默期，逐条发出去对端就是在抽搐。
        now += 50
        engine.onLocalSeek(110_000L)
        now += 50
        engine.onLocalSeek(120_000L)
        assertEquals(1, sent.count { it.first == CinemaPlayback.TYPE_SEEK })

        now += 300
        engine.onTick(now)
        val seeks = sent.filter { it.first == CinemaPlayback.TYPE_SEEK }
        assertEquals(2, seeks.size)
        assertEquals(
            "合并后发的必须是**最终位置**，不是第一条或中间那条",
            120_000L,
            CinemaPlaybackCodec.decodePosition(seeks.last().second)!!.positionMs,
        )
    }

    // ── 跳转落点确认（2026-10-25：就近同步点跳转的配套）────────────────────

    @Test
    fun 跳转落在更早的关键帧上时按实际位置重新对齐并补发一条() {
        engine.setRole(true)
        engine.onLinkOpened()
        // 模拟「就近同步点」跳转：请求 100 秒，实际落在 97 秒那个关键帧上。
        // 顺序与界面一致 —— **先动播放器，再调引擎**。
        player.landingOffsetMs = -3_000L

        now += 1_000
        player.seekTo(100_000L)
        engine.onLocalSeek(100_000L)
        assertEquals(1, sent.count { it.first == CinemaPlayback.TYPE_SEEK })

        // 过了确认窗口（max(400, 800)）再 tick：这一拍必须按**实际落点**重新对齐。
        now += 900
        engine.onTick(now)

        val seeks = sent.filter { it.first == CinemaPlayback.TYPE_SEEK }
        assertEquals("落点差 3 秒，必须补发一条实际位置给对端", 2, seeks.size)
        assertEquals(
            "补发的必须是**实际落点**，不是请求值 —— 否则从端会算出 -3s 漂移、每 500ms 硬跳一次",
            97_000L,
            CinemaPlaybackCodec.decodePosition(seeks.last().second)!!.positionMs,
        )
        assertTrue(
            "日志要能看出这件事发生过",
            engine.status.value.lastEvent.contains("按实际位置"),
        )
    }

    @Test
    fun 跳转落点与请求一致时不补发() {
        engine.setRole(true)
        engine.onLinkOpened()

        now += 1_000
        player.seekTo(100_000L)
        engine.onLocalSeek(100_000L)
        now += 900
        engine.onTick(now)

        assertEquals(
            "落点就在目标上时不该多出一条 seek",
            1,
            sent.count { it.first == CinemaPlayback.TYPE_SEEK },
        )
    }

    @Test
    fun 对端跳转落在更早的关键帧上时只在本机重新对齐不发广播() {
        engine.setRole(false)
        engine.onLinkOpened()
        player.landingOffsetMs = -2_000L

        now += 1_000
        engine.onRemote(CinemaPlayback.ACTION_SEEK, positionEnv(CinemaPlayback.ACTION_SEEK, 600_000L))
        val seeksAfterRemote = sent.count { it.first == CinemaPlayback.TYPE_SEEK }

        now += 900
        engine.onTick(now)

        assertEquals(
            "从端不发起广播 —— 重新对齐只在本地做（房主才是权威）",
            seeksAfterRemote,
            sent.count { it.first == CinemaPlayback.TYPE_SEEK },
        )
        assertTrue(
            "但锚点必须跟着实际落点",
            engine.status.value.lastEvent.contains("按实际位置"),
        )
    }

    @Test
    fun 落点读数还是_0_时不采信_否则两端会一起回到开头() {
        engine.setRole(true)
        engine.onLinkOpened()
        // 模拟「seek 刚发出、播放器还在缓冲」：请求 100 秒，此刻读回来的是 0。
        player.landingOffsetMs = -100_000L

        now += 1_000
        player.seekTo(100_000L)
        engine.onLocalSeek(100_000L)
        now += 900
        engine.onTick(now)

        assertEquals(
            "读到的 0 不可信，绝不能按它重设锚点并广播 —— 那正是「跳转后两端回到开头」",
            1,
            sent.count { it.first == CinemaPlayback.TYPE_SEEK },
        )
        assertFalse(
            "也不该写下「按实际位置重新对齐」这件事",
            engine.status.value.lastEvent.contains("按实际位置"),
        )
    }

    @Test
    fun 缓冲中读到的位置不采信() {
        engine.setRole(true)
        engine.onLinkOpened()
        player.landingOffsetMs = -3_000L
        player.buffering = true

        now += 1_000
        player.seekTo(100_000L)
        engine.onLocalSeek(100_000L)
        now += 900
        engine.onTick(now)

        assertEquals(
            "缓冲期的位置是过渡值，不能拿来改锚点",
            1,
            sent.count { it.first == CinemaPlayback.TYPE_SEEK },
        )
    }

    // ── §6.1 时钟模型 ──────────────────────────────────────────────────────

    @Test
    fun 时钟往返由_sync_ack_与_heartbeat_配对量出并按这条心跳的飞行时间补偿锚点() {
        val hostOffset = 1_000L
        engine.setRole(false)
        engine.onLinkOpened()

        // ① 房主的第一条心跳：手上没有待配对的探测 → 不产生样本，只锚定，并回一条探测
        now = 10_100L
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = false, positionMs = 0L, senderClockMs = now + hostOffset),
        )
        assertEquals(1, sent.count { it.first == CinemaPlayback.TYPE_SYNC_ACK })
        assertEquals("还没量到往返，补偿应为 0 而不是猜一个", 0L, engine.status.value.rttHalfMs)

        // ② 单程 50ms：探测飞过去，房主**立刻**回心跳（t2 = 房主时钟），应答飞回来
        now += 50
        val t2 = now + hostOffset
        now += 50
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 5_000L, senderClockMs = t2),
        )

        assertEquals(100L, engine.status.value.rttMs)
        assertEquals(50L, engine.status.value.rttHalfMs)
        assertEquals(hostOffset, engine.status.value.offsetMs)
        assertEquals(1, engine.status.value.clockSamples)

        // ③ 锚点带上了这条心跳的飞行时间补偿：5000 + 50。
        //    这条心跳是房主**立刻**回的，所以它的飞行时间恰好是单程 50ms。
        assertEquals(5_050L, engine.status.value.anchoredMediaMs)

        // ④ 防对轰闸门：房主那条「立刻回复」的心跳**不能**再触发一次探测
        //    （否则两端会以 RTT 为周期无限对轰）。间隔 100ms < syncProbeMinIntervalMs。
        assertEquals(1, sent.count { it.first == CinemaPlayback.TYPE_SYNC_ACK })
    }

    /**
     * 用户报的现象：**P2P 直连时从端不卡，切成强制中继之后从端卡顿，而且画面与音频
     * 一起卡**。根因就在锚点补偿：旧实现加的是「窗口内最小 RTT 的一半」——
     * 那是这一段时间里**最走运**的那一次往返，而补偿要回答的是「这一刻这条消息飞了
     * 多久」。中继路径上两者差得很远（排队、重传，窗口里还留着切换前的直连样本），
     * 差值直接变成目标位置的偏置；偏置越过硬阈值 300ms 时，从端每收到一条心跳就
     * 硬 seek 一次 —— 画面与声音一起断，正是用户描述的那种卡。
     */
    @Test
    fun 中继路径上按这条心跳实测的飞行时间补偿_不再把目标位置推后一个最小的半_rtt() {
        val hostOffset = 1_000L
        engine.setRole(false)
        engine.onLinkOpened()

        // ① 先在直连上量一次往返：单程 10ms ⇒ rttHalf = 10、offset = 1000。
        //    这个样本之后会一直留在窗口里 —— 正是真机上「切强制中继前刚量过直连」的样子。
        now = 10_000L
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 300_000L, senderClockMs = now + hostOffset),
        )
        now += 10
        val t2 = now + hostOffset
        now += 10
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 300_020L, senderClockMs = t2),
        )
        assertEquals(20L, engine.status.value.rttMs)
        assertEquals(10L, engine.status.value.rttHalfMs)

        // ② 切到强制中继：这条心跳真的飞了 400ms（房主在 400ms 之前发出来的）。
        //    本机此刻的真实位置就是「房主发送时的位置 + 400ms」，这正是同步的目标。
        now += 1_000
        val sentAtMs = now + hostOffset - 400
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 300_500L, senderClockMs = sentAtMs),
        )

        // 补偿用实测飞行时间：300500 + 400
        assertEquals(300_900L, engine.status.value.anchoredMediaMs)

        // ③ 本机正好站在那个位置上 ⇒ 漂移 0 ⇒ 不硬 seek。
        //    旧实现补偿的是 rttHalf = 10ms，目标位置会比真实位置**早** 390ms，
        //    而 390 ≥ hardDriftMs(300) ⇒ 这一个 tick 就会 seek 一次。
        player.seekTo(300_900L)
        engine.onTick(now)
        assertEquals("中继路径上不该每次心跳都硬 seek", 0L, engine.status.value.hardSeeks)
        assertEquals(CinemaSyncStatus.TIER_NONE, engine.status.value.tier)
        assertEquals(0L, engine.status.value.driftMs)
    }

    @Test
    fun 超过最小间隔后会再发起一次探测() {
        engine.setRole(false)
        engine.onLinkOpened()

        now = 10_100L
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 0L, senderClockMs = now),
        )
        assertEquals(1, sent.count { it.first == CinemaPlayback.TYPE_SYNC_ACK })

        // 定时心跳（2500ms 之后）应当再次触发探测
        now += 2_500
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 2_500L, senderClockMs = now),
        )
        assertEquals(2, sent.count { it.first == CinemaPlayback.TYPE_SYNC_ACK })
    }

    @Test
    fun 从端上报的_sync_ack_带着自己的位置漂移与_rtt() {
        engine.setRole(false)
        engine.onLinkOpened()

        // 摆在 10_100 这个时刻上：假播放器**会**按速度自己推进（见文件末尾），
        // 若先摆位再跨过 100ms，位置上报的就该是 7_754 —— 那测到的是「播放器在走」，
        // 而不是本用例要钉的「ack 带上的是发送那一刻的本机位置」。
        now = 10_100L
        player.seekTo(7_654L)
        // 这一句也顺带覆盖了「本机播放态被引擎之外的东西改了」：引擎会在处理这条心跳时
        // 先广播一条 cinema.play（见 detectPlayStateChange）。下面用 first{} 取 ack，
        // 所以它不影响本用例。
        player.play()

        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 7_654L, senderClockMs = now),
        )

        val ack = CinemaPlaybackCodec.decodeSyncAck(sent.first { it.first == CinemaPlayback.TYPE_SYNC_ACK }.second)!!
        assertEquals("ackSeq 要指回触发它的那条心跳", 1L, ack.ackSeq)
        assertEquals(7_654L, ack.clientPositionMs)
    }

    // ── §6.2 / §6.4 各消息的处理 ───────────────────────────────────────────

    @Test
    fun 从端未锚定时不做漂移校准() {
        engine.setRole(false)
        engine.onLinkOpened()
        val seeksBefore = player.seekCount

        now += 5_000
        engine.onTick(now)

        assertEquals(CinemaSyncStatus.TIER_IDLE, engine.status.value.tier)
        assertEquals(seeksBefore, player.seekCount)
    }

    @Test
    fun 收到_play_后建立时间轴并把播放器拉起来() {
        engine.setRole(false)
        engine.onLinkOpened()

        engine.onRemote(CinemaPlayback.ACTION_PLAY, positionEnv(CinemaPlayback.ACTION_PLAY, 300_000L))

        assertTrue(engine.status.value.isAnchored)
        assertEquals(300_000L, engine.status.value.anchoredMediaMs)
        assertTrue(player.isPlaying)
    }

    @Test
    fun 收到_seek_时无条件立即跳转并进入静默期() {
        followerAnchoredAndPlaying()
        val seeksBefore = player.seekCount

        engine.onRemote(CinemaPlayback.ACTION_SEEK, positionEnv(CinemaPlayback.ACTION_SEEK, 600_000L))

        assertEquals(seeksBefore + 1, player.seekCount)
        assertEquals(600_000L, player.currentPositionMs)
        assertEquals(CinemaSyncStatus.TIER_SETTLE, engine.status.value.tier)

        // 静默期内即使有巨大偏差也不许再动
        now += 399
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SETTLE, engine.status.value.tier)
        assertEquals(seeksBefore + 1, player.seekCount)
    }

    @Test
    fun 收到暂停时只在超过软阈值才_seek() {
        followerAnchoredAndPlaying(300_000L)

        // 差 5 秒 → 必须 seek 到锚点
        val far = 305_000L
        val seeksBefore = player.seekCount
        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, far))
        assertFalse(player.isPlaying)
        assertEquals(seeksBefore + 1, player.seekCount)
        assertEquals(far, player.currentPositionMs)

        // 差 50ms（< 软阈值 80）→ 只暂停，不 seek。否则每次暂停都会跳一下
        player.play()
        player.seekTo(far + 50L)
        val seeksBefore2 = player.seekCount
        engine.onRemote(CinemaPlayback.ACTION_PAUSE, positionEnv(CinemaPlayback.ACTION_PAUSE, far + 100L))
        assertEquals(seeksBefore2, player.seekCount)
        assertFalse(player.isPlaying)
    }

    @Test
    fun 状态快照整体重建时间轴并对齐播放态() {
        engine.setRole(false)
        engine.onLinkOpened()

        val snapshot = CinemaStateSnapshot(
            isPlaying = true,
            positionMs = 9_000L,
            mediaSignature = signature,
            seq = 3L,
            sessionEpoch = 5L,
        )
        engine.onRemote(
            CinemaPlayback.ACTION_STATE_SNAPSHOT,
            env(CinemaPlayback.TYPE_STATE_SNAPSHOT, CinemaPlaybackCodec.encodeStateSnapshot(snapshot)),
        )

        assertTrue(engine.status.value.isAnchored)
        assertEquals(9_000L, engine.status.value.anchoredMediaMs)
        assertTrue(player.isPlaying)
    }

    @Test
    fun 房主在链路刚通时下发状态快照() {
        engine.setRole(true)
        engine.setMediaSignature(signature)
        engine.setSessionEpoch(4L)
        player.seekTo(1_200L)

        engine.onLinkOpened()

        assertEquals(listOf(CinemaPlayback.TYPE_STATE_SNAPSHOT), sent.map { it.first })
        val snapshot = CinemaPlaybackCodec.decodeStateSnapshot(sent.single().second)!!
        assertEquals(1_200L, snapshot.positionMs)
        assertEquals(4L, snapshot.sessionEpoch)
        assertEquals(signature, snapshot.mediaSignature)
    }

    @Test
    fun 房主还没有片源摘要时不下发快照() {
        engine.setRole(true)
        engine.onLinkOpened()

        assertTrue(sent.isEmpty())
        assertTrue(engine.status.value.lastEvent.contains("还没算出片源摘要"))
    }

    @Test
    fun 心跳会自愈播放态不一致() {
        // 场景设定很重要：本机「停着」必须是一个**稳定的旧事实**（例如从没收到过
        // 那条 cinema.play —— 对端中途重装过 App），而不是刚刚被系统改掉的新事实。
        // 后者属于「本机变更要广播出去」那条路，见
        // `本机播放态被系统改变时会广播出去` 与 `本机刚广播过暂停时…`。
        engine.setRole(false)
        engine.onLinkOpened()
        assertFalse(player.isPlaying)

        now += 100
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = true, positionMs = 300_100L, senderClockMs = now),
        )
        assertTrue("心跳显示房主在播、本机却停着 → 已对齐起播（自愈）", player.isPlaying)

        now += 2_500
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(isPlaying = false, positionMs = 302_600L, senderClockMs = now),
        )
        assertFalse("心跳显示房主已暂停、本机却还在播 → 已对齐暂停（自愈）", player.isPlaying)
    }

    // ── §6.3 三档漂移校准 ──────────────────────────────────────────────────

    @Test
    fun 注入_200ms_偏差走变速追赶并最终回到不动() {
        followerAnchoredAndPlaying()
        engine.injectDrift(200L)

        // 静默期内不判漂移（§6.3），但漂移照实显示 —— 显示 0 会让人以为已经对齐了
        now += 100
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SETTLE, engine.status.value.tier)
        assertEquals(200L, engine.status.value.driftMs)

        // 静默期结束 → 第 2 档。200ms 超前 ⇒ 必须放慢到 0.95
        now += 400
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SPEED, engine.status.value.tier)
        assertEquals(0.95f, player.speedNow, 0.0001f)
        assertEquals(200L, engine.status.value.driftMs)

        // 5% 追 200ms ≈ 4 秒
        repeat(20) {
            now += 500
            engine.onTick(now)
        }
        assertTrue(
            "漂移应收敛进软阈值，实际 ${engine.status.value.driftMs}",
            abs(engine.status.value.driftMs) < CinemaSyncConfig.DEFAULT.softDriftMs,
        )
        assertEquals("收敛后速度必须放回 1.0，否则会一直抖", 1.0f, player.speedNow, 0.0001f)
        assertEquals(CinemaSyncStatus.TIER_NONE, engine.status.value.tier)
    }

    @Test
    fun 注入_2s_偏差走硬_seek() {
        followerAnchoredAndPlaying()
        val seeksBefore = player.seekCount

        engine.injectDrift(2_000L)
        now += 500
        engine.onTick(now)

        assertEquals(CinemaSyncStatus.TIER_SEEK, engine.status.value.tier)
        assertEquals("注入一次 + 硬 seek 一次", seeksBefore + 2, player.seekCount)
        assertEquals(1.0f, player.speedNow, 0.0001f)
        assertEquals(1L, engine.status.value.hardSeeks)
        // 面板要能看到「是 2 秒的偏差触发了硬 seek」，所以显示的是触发它的那个观测值
        assertEquals(2_000L, engine.status.value.driftMs)
    }

    @Test
    fun 硬_seek_后进入静默期期间不再动作() {
        followerAnchoredAndPlaying()
        engine.injectDrift(2_000L)
        now += 500
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SEEK, engine.status.value.tier)

        val seeksAfterHardSeek = player.seekCount
        now += 399
        engine.onTick(now)

        assertEquals(CinemaSyncStatus.TIER_SETTLE, engine.status.value.tier)
        assertEquals(seeksAfterHardSeek, player.seekCount)
    }

    @Test
    fun 缓冲期间跳过漂移校准() {
        followerAnchoredAndPlaying()
        engine.injectDrift(2_000L)
        player.buffering = true

        val seeksBefore = player.seekCount
        now += 1_000
        engine.onTick(now)

        // 缓冲时 currentPosition 停住而目标仍在按 1.0 外推，两者之差会被算成
        // 严重不同步 → 硬 seek → 重新缓冲 → 越纠越糟。所以必须整段跳过。
        assertEquals(CinemaSyncStatus.TIER_BUFFERING, engine.status.value.tier)
        assertEquals(seeksBefore, player.seekCount)
        assertEquals(1.0f, player.speedNow, 0.0001f)
    }

    @Test
    fun 漂移回到阈值内后速度必须放回_1_0() {
        followerAnchoredAndPlaying()
        engine.injectDrift(200L)
        now += 500
        engine.onTick(now)
        assertEquals(0.95f, player.speedNow, 0.0001f)

        // 把本机摆回目标上，下一 tick 就该判「不动」并复位速度
        player.seekTo(engine.status.value.targetPositionMs)
        now += 500
        engine.onTick(now)

        assertEquals(CinemaSyncStatus.TIER_NONE, engine.status.value.tier)
        assertEquals(1.0f, player.speedNow, 0.0001f)
    }

    // ── 收尾与健壮性 ───────────────────────────────────────────────────────

    @Test
    fun 链路断开清空同步状态() {
        followerAnchoredAndPlaying()
        assertTrue(engine.status.value.isAnchored)

        engine.onLinkClosed()

        assertFalse(engine.status.value.isAnchored)
        assertFalse(engine.status.value.linkUp)
    }

    @Test
    fun 会话结束要真的暂停播放器() {
        followerAnchoredAndPlaying()
        assertTrue(player.isPlaying)

        engine.endSession()

        // 用户报的「退出房间后视频还在接着播放」修的就是这一条：会话结束必须真的
        // 暂停播放器。播放器是进程级单例（§7.2），退房后没人叫它停，它就一直放下去 ——
        // 而 `onLinkClosed()` 那条路**不能**拿来干这个（§9.4 的 60s 宽限期里人还在厅里）。
        assertFalse("会话结束必须真的暂停播放器", player.isPlaying)
        assertFalse(engine.status.value.linkUp)
        assertFalse(engine.status.value.isAnchored)
        assertTrue(engine.status.value.lastEvent.contains("会话结束"))
    }

    @Test
    fun 会话结束后不再补发暂停广播() {
        engine.setRole(true)
        engine.onLinkOpened()
        now = 20_000L
        player.play()
        engine.onLocalPlay(1_234L)
        sent.clear()

        engine.endSession()
        now += 500
        engine.onTick(now)

        // 这次暂停是**我们自己**做的，不是「引擎之外的东西改了播放态」。两个播放态
        // 记账不同步的话，下一个 tick 会把它当成外来变化，再广播一条 cinema.pause ——
        // 而那时对端已经不在（甚至已经进了别的房间），这条广播只会让下一次进房的
        // 对方先停一下。**测试里 `onTick` 会照常发心跳**，所以只断言没有 pause。
        assertTrue(
            "收尾之后不该补发暂停广播，实际发了：${sent.map { it.first }}",
            sent.none { it.first == CinemaPlayback.TYPE_PAUSE },
        )
    }

    @Test
    fun 重建链路时速率要真的写回播放器而不是只清记账() {
        engine.setRole(true)
        engine.onLinkOpened()
        now = 20_000L
        player.play()
        engine.onLocalPlay(0L)
        engine.onLocalSpeed(2.0f)
        assertEquals("前置：播放器真的跑在 2x 上", 2.0f, player.speedNow, 0.0001f)

        engine.onLinkClosed()

        // 链路断开后引擎把「整场倍速」清回 1.0 —— 播放器必须跟着回去。
        // 只清记账（`appliedPlayerSpeed = 1f`）的话播放器还停在 2.0x，而去重会让
        // 随后的 `applySpeed(1f)` 直接 return（target 恰好等于刚清出来的 1.0），
        // 一个字节都没写给播放器：界面上「整场倍速 1.0x、播放器 1.0x」两栏全是假的，
        // 而影片真的跑在 2x 上 —— 弱解码能力的机器（平板）在那时会「画面几秒一停
        // 再跳、声音正常」，正是诊断页那一堆数字排不出来的那一类现象。
        assertEquals("速率必须真的写回播放器", 1.0f, player.speedNow, 0.0001f)
        assertEquals(1.0f, engine.status.value.appliedPlaybackSpeed, 0.0001f)
    }

    @Test
    fun 解不出来的载荷被忽略且不抛异常() {
        engine.setRole(false)
        engine.onLinkOpened()

        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            env(CinemaPlayback.TYPE_HEARTBEAT, buildJsonObject { put("bogus", 1) }),
        )

        assertTrue(engine.status.value.lastEvent.contains("解不出来"))
        assertFalse(engine.status.value.isAnchored)
    }

    @Test
    fun 从端收到字幕跟随消息不会崩() {
        engine.setRole(false)
        engine.onLinkOpened()

        engine.onRemote(
            CinemaPlayback.ACTION_SUBTITLE,
            env(CinemaPlayback.TYPE_SUBTITLE, CinemaPlaybackCodec.encodeSubtitle(CinemaSubtitle(3))),
        )

        assertTrue(engine.status.value.lastEvent.contains("字幕轨 3"))
    }

    @Test
    fun 漂移历史只记被真正判断过的采样() {
        followerAnchoredAndPlaying()
        engine.injectDrift(2_000L)

        // 静默期内不进历史
        now += 100
        engine.onTick(now)
        assertTrue(engine.status.value.driftHistory.isEmpty())

        now += 400
        engine.onTick(now)
        assertEquals(listOf(2_000L), engine.status.value.driftHistory)
    }

    // ── 三个「本次会话累计量」（§11 的 1.8 行）──────────────────────────────
    //
    // 它们与上面那些**链路级**读数（漂移、折线、rttHalf）不是一类东西：链路级读数
    // 在重建链路时会被 [CinemaSyncEngine.resetSyncState] 清掉，这三个不会。存在的
    // 理由只有一个：锁屏 30 分钟那一轮验收里，「这半小时最坏漂移是多少」必须能答
    // 上来 —— 而折线只装得下 20 秒（40 × 500ms），且那一轮不允许反复唤醒屏幕去重测。

    @Test
    fun 累计峰值与折线同源_静默期与缓冲期都不进样本() {
        followerAnchoredAndPlaying()
        engine.injectDrift(2_000L)

        // 静默期内不判漂移：折线与累计峰值都不该动。混进去的话，每次主动 seek 之后
        // 那段「位置还没落定」的中间值都会变成一个新的历史最大值。
        now += 100
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SETTLE, engine.status.value.tier)
        assertTrue(engine.status.value.driftHistory.isEmpty())
        assertEquals(0L, engine.status.value.maxAbsDriftMs)

        // 静默期结束 → 这一批采样被真正判断过，峰值记下来。
        // **触发硬 seek 的那一次算在内**：它正是「最坏值」要回答的问题，而只看折线
        // 是看不到它的 —— seek 之后的静默期会把后续采样一起挡掉。
        now += 400
        engine.onTick(now)
        assertEquals(2_000L, engine.status.value.maxAbsDriftMs)

        // 缓冲期同样整段跳过：期间 currentPosition 停住而目标仍在按 1.0 外推，
        // 两者之差是过渡态垃圾（见 `缓冲期间跳过漂移校准`）。
        player.buffering = true
        engine.injectDrift(9_000L)
        now += 500
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_BUFFERING, engine.status.value.tier)
        assertEquals("缓冲期的观测值不许进峰值", 2_000L, engine.status.value.maxAbsDriftMs)
    }

    @Test
    fun 累计峰值取绝对值且只增不减() {
        followerAnchoredAndPlaying()

        // 本机**落后** 2.5s：漂移是负的，而「最坏值」要的是绝对值
        engine.injectDrift(-2_500L)
        now += 500
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SEEK, engine.status.value.tier)
        assertEquals(-2_500L, engine.status.value.driftMs)
        assertEquals(2_500L, engine.status.value.maxAbsDriftMs)

        // 之后的小偏差不该把它压回去 —— 它是「最坏值」，不是「最近一次」
        engine.injectDrift(-100L)
        now += 500
        engine.onTick(now)
        assertEquals(-100L, engine.status.value.driftMs)
        assertEquals(2_500L, engine.status.value.maxAbsDriftMs)
    }

    @Test
    fun 三个累计量都不随重建链路清零() {
        followerAnchoredAndPlaying()

        // 第 3 档：硬 seek
        engine.injectDrift(-2_500L)
        now += 500
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SEEK, engine.status.value.tier)
        assertEquals(2_500L, engine.status.value.maxAbsDriftMs)

        // 第 2 档：变速追赶
        engine.injectDrift(-200L)
        now += 500
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SPEED, engine.status.value.tier)
        assertEquals(1L, engine.status.value.speedAdjustments)
        assertEquals(1L, engine.status.value.hardSeeks)

        // 重建链路：链路级读数确实被清掉了 —— 否则这个用例根本测不出「刻意不清零」
        engine.onLinkClosed()
        assertFalse(engine.status.value.isAnchored)
        assertTrue("链路级的折线要清", engine.status.value.driftHistory.isEmpty())
        assertEquals("链路级的漂移要清", 0L, engine.status.value.driftMs)

        // 但三个「本次会话累计」量必须还在：锁屏 30 分钟那一轮验收中途一旦重建链路，
        // 跟着清掉的话「这半小时最坏漂移是多少」在回到界面时就再也答不上来，
        // 而为了保活结论又不能再唤醒一次屏幕去重测。
        assertEquals(2_500L, engine.status.value.maxAbsDriftMs)
        assertEquals(1L, engine.status.value.speedAdjustments)
        assertEquals(1L, engine.status.value.hardSeeks)
    }

    @Test
    fun 房主侧不产生漂移累计() {
        engine.setRole(true)
        engine.onLinkOpened()
        player.play()

        // 房主是权威：它定义时间轴，没有「跟着谁走」这回事，所以 onTick 里根本不跑
        // runFollowerCorrection。界面上这一行对房主显示「—（房主侧不产生漂移，这一项
        // 属于从端）」，引擎侧对应的就是这里恒为 0。
        engine.injectDrift(2_000L)
        now += 500
        engine.onTick(now)

        assertEquals(0L, engine.status.value.maxAbsDriftMs)
        assertEquals(0L, engine.status.value.speedAdjustments)
        assertEquals(0L, engine.status.value.hardSeeks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 硬阈值不大于软阈值时构造即失败() {
        CinemaSyncConfig(softDriftMs = 300L, hardDriftMs = 300L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 滤波窗口为零时构造即失败() {
        CinemaSyncConfig(rttWindow = 0)
    }

    // ── 网络抖动不许变成硬 seek：2026-10-12「强制中继下从端一会卡一会不卡」──────
    //
    // 这是那条真机缺陷的端到端复现。链路上唯一会随网络波动的东西是时钟偏差：
    // `best` 是**窗口内 RTT 最小**的样本，窗口一滑动，被选中的样本就换一个，
    // 它的 offset 与上一个相差「两次路径不对称度之差的一半」—— 中继 + 蜂窝下
    // 能到几百毫秒，而 WiFi 下只有几毫秒（用户报的正是「从端在 WiFi 下缓解很多」）。
    // 偏差的跳变会一路变成目标位置的跳变，于是漂移一跳就硬 seek 一次。
    //
    // 用例里的三段往返刻意构造成「最小值被挤出窗口的那一刻偏差跳 350ms」：
    // 旧代码下这一步会当场硬 seek（画面与声音一起断），限速跟随之后只剩 40ms 的台阶。

    @Test
    fun 网络抖动换掉_min_rtt_样本时不产生硬_seek() {
        // 窗口缩到 2：真机上要 8 个样本（约 20 秒）才把旧的最小值挤出去，这里不必等。
        engine = CinemaSyncEngine(
            player = player,
            clock = { now },
            // 第三参是 `to`（阶段二 v2 的定向消息）。本文件验的是「发了什么」，
            // 收件人由 `CinemaHandler` 一侧决定，所以这里丢掉它。
            outbound = { type, payload, _ -> sent += type to payload },
            config = CinemaSyncConfig(rttWindow = 2),
        )
        // 重建出来的引擎同样要设房主（见 setUp 的说明）。
        engine.setHostUserId(UserId("peer-user-id"))
        engine.setMyUserId(UserId("me"))
        followerAnchoredAndPlaying(300_000L)
        val seeksBefore = player.seekCount

        // ① 这条心跳顺带发出第一次探测（t1 = 10_000）。它自己还没有样本可配对，
        //    只能按「先不补偿」锚定 —— 所以它的飞行时间取 0，本机与目标正好重合。
        now = 10_000L
        feedHeartbeat(backwardMs = 0L)

        // ② 第一次往返：去程 150 / 回程 150 ⇒ rtt 300、偏差正好是真值（误差 0）。
        //    这是「走运的那一次」：路径对称，它给出的偏差不带偏置。
        feedProbeReply(t1 = 10_000L, forwardMs = 150L, backwardMs = 150L)
        assertEquals(300L, engine.status.value.rttMs)
        assertEquals(HOST_OFFSET_MS, engine.status.value.offsetMs)

        // ③ 一条定时心跳，把「距上次探测 1000ms」这条闸门推到可以再发一次探测。
        now = 11_000L
        feedHeartbeat(backwardMs = 0L)

        // ④ 第二次往返：rtt 700、路径严重不对称（去程 700 / 回程 0）⇒ 偏差偏 +350。
        //    它比 ① 差，所以还轮不到它；但窗口只有 2，下一条就会把 ① 挤出去。
        feedProbeReply(t1 = 11_000L, forwardMs = 700L, backwardMs = 0L)
        assertEquals(HOST_OFFSET_MS, engine.status.value.rawOffsetMs)

        // ⑤ 再推一次探测闸门。
        now = 12_000L
        feedHeartbeat(backwardMs = 0L)

        // ⑥ 第三次往返：rtt 800 ⇒ 它比 ④ 差，于是最小值落到 ④ 上，而 ① 已经被挤出窗口。
        //    **这一刻原始偏差从 9_900 跳到 10_250（+350ms）** —— 真机上的「网络波动」。
        feedProbeReply(t1 = 12_000L, forwardMs = 800L, backwardMs = 0L)
        assertEquals(10_250L, engine.status.value.rawOffsetMs)

        // 补偿用的偏差只挪了一步（40ms = 软阈值的一半），于是注入的漂移只有 40ms。
        assertEquals(HOST_OFFSET_MS + ClockSync.MAX_OFFSET_STEP_MS, engine.status.value.offsetMs)

        engine.onTick(now)

        // 关键断言：没有硬 seek、没有进 seek 档、连变速都没有发生。
        // 旧代码在这里的漂移是 −350ms（目标位置被 +350 的偏差推走）⇒ 当场一次硬 seek。
        assertEquals("网络抖动不许变成硬 seek", 0L, engine.status.value.hardSeeks)
        assertEquals("不该真的动播放器", seeksBefore, player.seekCount)
        assertFalse(
            "档位不该是 seek，实际 ${engine.status.value.tier}",
            engine.status.value.tier == CinemaSyncStatus.TIER_SEEK,
        )
        assertEquals("单次重锚注入的漂移必须在软阈值以内", -40L, engine.status.value.driftMs)
        assertEquals("连变速都不该发生", 1f, player.speedNow, 1e-6f)
    }

    // ── 排队的外部事件（2026-10-13 第八轮缺陷①）──────────────────────────────
    //
    // 通话开关那类「引擎之外的事件」只能从界面线程排队进来：`publish()` 要读播放器，
    // 跨线程直接调它会撞「Player is accessed on the wrong thread」（见 `noteExternal`）。
    // 下面两条钉住排队的两半语义 —— 它会出现，但它**不会**顶掉更新的引擎事件。

    @Test
    fun 排队的外部事件在下一拍出现在最近事件里() {
        engine.noteExternal("通话开关：开（麦克风键）")

        engine.onTick(now + 500)

        assertEquals("通话开关：开（麦克风键）", engine.status.value.lastEvent)
    }

    @Test
    fun 引擎自己更新的那条事件会顶掉排队中的外部事件() {
        // 真实顺序：`CinemaHandler.onSessionEnded` 先关通话（排队一条），再 `endSession()`
        // （自己 note 一条）。留下的必须是后者 —— 「最近事件」= 最新发生的那一条。
        engine.noteExternal("通话开关：关（来源未标注）")

        engine.endSession()

        assertTrue(engine.status.value.lastEvent.contains("会话结束"))
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────────

    /** 让从端进入「已锚定、正在播、本机已摆到目标上」的稳态。 */
    private fun followerAnchoredAndPlaying(startPositionMs: Long = 300_000L) {
        engine.setRole(false)
        engine.onLinkOpened()
        engine.onRemote(
            CinemaPlayback.ACTION_PLAY,
            positionEnv(CinemaPlayback.ACTION_PLAY, startPositionMs),
        )
        // §6.4 里 play 只负责 play()，不负责 seek；把本机摆正由第 3 档完成。
        // 测试里直接摆正，以便只留一个变量（要测的注入偏差）。
        player.seekTo(engine.status.value.targetPositionMs)
    }

    private fun env(type: String, payload: JsonObject?): Envelope {
        envelopeSeq += 1
        return Envelope(
            type = type,
            seq = envelopeSeq,
            roomId = "AB23CD",
            from = "peer-user-id",
            sentAtMs = now,
            payload = payload,
        )
    }

    private fun positionEnv(action: String, positionMs: Long) = env(
        CinemaPlayback.typeOf(action),
        CinemaPlaybackCodec.encodePosition(CinemaPosition(positionMs = positionMs, senderClockMs = now)),
    )

    private fun heartbeatEnv(isPlaying: Boolean, positionMs: Long, senderClockMs: Long) = env(
        CinemaPlayback.TYPE_HEARTBEAT,
        CinemaPlaybackCodec.encodeHeartbeat(
            CinemaHeartbeat(isPlaying = isPlaying, positionMs = positionMs, senderClockMs = senderClockMs),
        ),
    )

    /**
     * 喂一条心跳。[backwardMs] 是它**自己的**飞行时间（对端发出 → 本机收到）。
     *
     * 对端时钟按「比本机快 [HOST_OFFSET_MS]」换算，携带的位置按「从 [HOST_START_LOCAL_MS]
     * 那一刻的 [HOST_START_POSITION_MS] 起、以 1x 匀速推进」算 —— 这样每条心跳的
     * 发送时刻、位置、本机收到时刻三者自洽，锚点补偿算出来的飞行时间正好等于
     * [backwardMs]，断言才能逐毫秒对得上。
     */
    private fun feedHeartbeat(backwardMs: Long) {
        val sendLocalMs = now - backwardMs
        engine.onRemote(
            CinemaPlayback.ACTION_HEARTBEAT,
            heartbeatEnv(
                isPlaying = true,
                positionMs = HOST_START_POSITION_MS + (sendLocalMs - HOST_START_LOCAL_MS),
                senderClockMs = sendLocalMs + HOST_OFFSET_MS,
            ),
        )
    }

    /**
     * 喂一条「应答上一次探测」的心跳：它同时完成一次时钟往返（§6.1）并重设锚点。
     *
     * 探测的节拍是「从端发 `cinema.sync_ack`（t1）→ 房主**立刻**回心跳（t2）→ 从端收到（t4）」，
     * 所以 `t4 = t1 + forwardMs + backwardMs`，量出来的 `rtt` 正好是两段之和，
     * 而这条心跳自己的飞行时间是 `backwardMs`。
     */
    private fun feedProbeReply(t1: Long, forwardMs: Long, backwardMs: Long) {
        now = t1 + forwardMs + backwardMs
        feedHeartbeat(backwardMs)
    }

    // ── 播放设置（2026-10-31）：本地校准开关 / 参数热换 / 手动同步 ─────────────

    @Test
    fun 关掉本地校准后漂移照算但一步都不动() {
        // 200ms 在默认参数下正好进「变速追赶」（软 80 / 硬 300）。
        engine.updateConfig(CinemaSyncConfig.DEFAULT.copy(localCalibrationEnabled = false))
        followerAnchoredAndPlaying(300_000L)

        // `injectDrift` 自己就是一次 seekTo，所以基准要在它之后取 —— 这里要断言的是
        // 「这一拍校准没有再动位置」，不是「从来没 seek 过」。
        engine.injectDrift(200L)
        val seeksBeforeTick = player.seekCount
        now += 500
        engine.onTick(now)

        assertEquals(CinemaSyncStatus.TIER_OFF, engine.status.value.tier)
        assertEquals("关掉校准后不许再变速", 1f, player.speedNow, 1e-6f)
        assertEquals("漂移读数要照常给出来，否则面板会说「已对齐」", 200L, engine.status.value.driftMs)
        assertEquals("也不许硬 seek", seeksBeforeTick, player.seekCount)
    }

    @Test
    fun 关掉本地校准后不会留下上一个变速因子() {
        // 先在默认参数下进入变速追赶：注 200ms → 0.95x。
        followerAnchoredAndPlaying(300_000L)
        engine.injectDrift(200L)
        now += 500
        engine.onTick(now)
        assertEquals(CinemaSyncStatus.TIER_SPEED, engine.status.value.tier)

        // 此刻关掉校准：播放器速率必须回到整场倍速（1.0），而不是留在 0.95 上跑一整场。
        engine.updateConfig(CinemaSyncConfig.DEFAULT.copy(localCalibrationEnabled = false))
        assertEquals(1f, player.speedNow, 1e-6f)
        assertEquals(1f, engine.status.value.appliedPlaybackSpeed, 1e-6f)
    }

    @Test
    fun 广播周期可以热换成五秒() {
        engine.setRole(true)
        engine.onLinkOpened()
        sent.clear()

        engine.updateConfig(CinemaSyncConfig.DEFAULT.copy(heartbeatIntervalMs = 5_000L))
        assertEquals(5_000L, engine.config.heartbeatIntervalMs)

        // 4.9 秒还不该发第二条；跨过 5 秒才发。
        now += 4_900
        engine.onTick(now)
        assertEquals(0, sent.count { it.first == CinemaPlayback.TYPE_HEARTBEAT })

        now += 200
        engine.onTick(now)
        assertEquals(1, sent.count { it.first == CinemaPlayback.TYPE_HEARTBEAT })
    }

    @Test
    fun 手动同步_房主立刻广播一条心跳且不等定时器() {
        engine.setRole(true)
        engine.onLinkOpened()
        player.play()
        player.seekTo(123_456L)
        sent.clear()

        // 时钟故意不动：位置必须是「按下去那一刻」的读数，不是过了多少毫秒之后的推算值。
        engine.requestManualSync()

        val heartbeats = sent.filter { it.first == CinemaPlayback.TYPE_HEARTBEAT }
        assertEquals(1, heartbeats.size)
        val payload = CinemaPlaybackCodec.decodeHeartbeat(heartbeats.single().second)!!
        assertEquals(123_456L, payload.positionMs)
        assertTrue(payload.isPlaying)
        assertEquals(now, payload.senderClockMs)
    }

    @Test
    fun 手动同步_从端发探测并绕开一秒下限() {
        engine.setRole(false)
        engine.onLinkOpened()
        sent.clear()

        // 先把「上一次探测」摆成刚刚发生过 —— 自动探测会被那 1 秒下限挡住。
        feedHeartbeat(0L)
        sent.clear()
        engine.requestManualSync()

        val acks = sent.filter { it.first == CinemaPlayback.TYPE_SYNC_ACK }
        assertEquals("手按一下不许被下限吞掉", 1, acks.size)
    }

    @Test
    fun 链路没通时手动同步不发任何东西() {
        engine.setRole(true)
        sent.clear()

        engine.requestManualSync()

        assertTrue("链路没通就该一条都不发", sent.isEmpty())
    }

    /**
     * 假播放器：位置按假时钟与当前速度**自行推进**。
     *
     * ── 为什么必须自己推进会动 ──────────────────────────────────────────────
     * 若 `currentPositionMs` 是一个固定值，那 §6.3 的「变速追赶」就永远追不上 ——
     * 漂移不会因为速度变化而缩小，于是「200ms 能被追平」这条验收在测试里
     * 根本不可能通过。位置必须真的按 speed 累积，测试才在测真的算法。
     */
    private class FakePlayer(private val clock: () -> Long) : PlaybackTarget {

        private var baseMs = 0L
        private var baseClockMs = clock()
        private var playing = false
        private var speed = 1f

        var buffering = false

        /**
         * 置 false 模拟「还没选片 / prepare 未完成」。
         *
         * 与 `Media3PlaybackTarget` 一样，未就绪时 [play] 是空操作 —— 引擎正是靠
         * 「调完 play() 之后 playWhenReady 到底是不是 true」来判断这次起播有没有生效。
         */
        var ready = true

        var seekCount = 0
            private set

        val speedNow: Float get() = speed

        private fun advance() {
            val t = clock()
            if (playing) baseMs += ((t - baseClockMs) * speed).toLong()
            baseClockMs = t
        }

        override val currentPositionMs: Long
            get() {
                advance()
                return baseMs
            }

        override val isPlaying: Boolean get() = playing

        /**
         * 假播放器里「意图」与「事实」是同一个变量：它不会缓冲、也不会到片尾。
         *
         * 真机上两者会分开（Media3 的 `playWhenReady` vs `isPlaying`），而那种分离
         * 在纯 JVM 单测里没法忠实重建 —— 它的症状（缓冲期被误判成用户暂停）
         * 已经写进了 [PlaybackTarget.isPlayRequested] 的注释，属于真机验收的范围。
         */
        override val isPlayRequested: Boolean get() = playing

        override val isBuffering: Boolean get() = buffering

        override val isReady: Boolean get() = ready

        override fun play() {
            if (!ready) return
            advance()
            playing = true
        }

        override fun pause() {
            advance()
            playing = false
        }


        // 2026-10-26 端口新增：选片。假播放器不真加载文件。
        override fun setMediaItem(ref: MediaRef) = Unit
        override fun seekTo(positionMs: Long) {
            advance()
            // 精确跳转也可能落不到目标上：没有可用索引 / 只有开头关键帧的片源，
            // seek 实际只能到能到的地方（实测会退到 0）。用这个偏移模拟那种情况。
            baseMs = (positionMs + landingOffsetMs).coerceAtLeast(0L)
            seekCount += 1
        }

        /**
         * 目标位置与实际落点之差（0 = 落点正好在目标上）。
         *
         * 2026-10-25 那条片源上「就近同步点」会退一个 GOP，所以一度用它模拟；
         * 现在回到精确跳转，它只用来验**落点对不上时**的兜底逻辑。
         */
        var landingOffsetMs = 0L

        override fun setSpeed(speed: Float) {
            advance()
            this.speed = speed
        }
    }
}
