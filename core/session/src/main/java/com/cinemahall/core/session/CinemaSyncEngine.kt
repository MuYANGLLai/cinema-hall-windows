package com.cinemahall.core.session

import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.PlaybackTarget
import com.cinemahall.core.media.VideoDecoderStats
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * 同步引擎对外的一整屏状态。诊断页与验收都要它（§9.3 的字段就是从这里取的）。
 *
 * 全部做成派生量的快照，**不留可变字段**：界面拿到的每一帧都是一个自洽的瞬间。
 * 若把 `driftMs` 与 `targetPositionMs` 分成两个独立的流，界面上就可能出现
 * 「漂移 0ms，但目标位置比本地位置小 2 秒」这种自相矛盾的组合 —— 而验收时
 * 人会把它当成算法 bug 去查。
 */
data class CinemaSyncStatus(
    val isHost: Boolean = false,
    val roleText: String = "未进房",
    val linkUp: Boolean = false,
    val isAnchored: Boolean = false,
    /** 锚点处的影片位置（已含 rttHalf 补偿）。 */
    val anchoredMediaMs: Long = 0L,
    /** 由锚点外推出来的当前目标位置（§6.2）。 */
    val targetPositionMs: Long = 0L,
    val localPositionMs: Long = 0L,
    /** 本地位置 − 目标位置。为正 = 本机跑在前面。 */
    val driftMs: Long = 0L,
    /** 当前落在 §6.3 的哪一档（人话，不是枚举名）。 */
    val tier: String = TIER_NONE,
    val rttMs: Long = 0L,
    val rttHalfMs: Long = 0L,
    val offsetMs: Long = 0L,
    /**
     * **限速跟随之前**的时钟偏差（min-RTT 样本直接给出的那个）。
     *
     * 与 [offsetMs] 成对展示，是这一条真机缺陷唯一能在界面上量出来的证据：两者相差
     * 多大，就说明链路抖动有多大、限速跟随吃掉了多少。WiFi 下这两个数字应该几乎一样；
     * 强制中继 + 蜂窝下 [rawOffsetMs] 会来回跳而 [offsetMs] 每次只挪一小步。
     */
    val rawOffsetMs: Long = 0L,
    val clockSamples: Int = 0,
    val heartbeatsSent: Long = 0L,
    val heartbeatsReceived: Long = 0L,
    val speedAdjustments: Long = 0L,
    val hardSeeks: Long = 0L,
    /**
     * 播放器认为当前媒体项能不能跳（2026-10-26）。**false = 拖进度条不会有反应**，
     * 不是我们的逻辑错，而是这部片在 ExoPlayer 眼里不可跳（索引缺失 / 不被识别）。
     */
    val isSeekable: Boolean = true,
    /**
     * 当前播放实现能不能给解码器计数（2026-10-26）。libVLC 没有 ExoPlayer 的
     * `videoDecoderCounters` 等价物 —— 那时这两行必须显示 `-`，而不是 `0`。
     */
    val decoderStatsAvailable: Boolean = true,
    /**
     * 本次会话观测到的最大漂移绝对值（§11 的 1.8 行要求补的三个「本次会话累计量」之一）。
     *
     * 它回答的是折线答不了的那个问题：`driftHistory` 只有 40 个采样 × 500ms = **20 秒**，
     * 锁屏 30 分钟之后回到界面，「这半小时里最坏漂移是多少」在上面根本读不出来 ——
     * 而那一轮验收又不允许中途反复唤醒屏幕（会重置 Doze 计时、削弱保活测试本身）。
     *
     * 房主侧恒为 0：漂移只在从端的 `runFollowerCorrection` 里算（房主定义时间轴，
     * 没有「跟着谁走」这回事）。
     */
    val maxAbsDriftMs: Long = 0L,
    /**
     * 本机此刻有没有播放控制权（契约 §7）。房主恒为 true；从端看
     * `overrides[我] ?: defaultAllow` 且「在看」。
     *
     * 界面用它灰掉播放键 —— 与房主执法用的是同一份策略（`ControlPolicyTable`）。
     */
    val canControl: Boolean = true,

    /** 当前策略的全局开关（从端界面上说明「为什么我不能控」时要用）。 */
    val controlDefaultAllow: Boolean = false,
    /**
     * 整场倍速（房主设定、从端跟随，§6.5）。1.0 = 原速。
     *
     * 它与本地音量不同：音量是**每台设备自己的事**，倍速是**整场的** ——
     * 两台机器以不同速率在同一部片子上跑，位置必然越差越远。
     */
    val playbackSpeed: Float = 1f,
    /**
     * 播放器此刻**实际**收到的速率 = 整场倍速 × §6.3 的漂移因子。
     *
     * 单看 [playbackSpeed] 会漏掉正在发生的追赶：验收时看到「倍速 1.0、
     * 漂移却在缩小」是无法解释的，而真相是播放器正跑在 0.95。所以两栏都给。
     */
    val appliedPlaybackSpeed: Float = 1f,
    /** 房主侧看到的：从端上报的漂移 / RTT。从端侧恒为 null。 */
    val remoteDriftMs: Long? = null,
    val remoteRttMs: Long? = null,
    /**
     * 视频解码器计数（本次起播以来；2026-10-12 加）。
     *
     * ── 为什么「同步状态」这一屏里要放解码器的数 ─────────────────────────────────
     * 真机报的「从端画面几秒一停再跳、音频正常」在**这一屏的数字上完全看不出来**：
     * 档位可能一直是「不动」、漂移很小、`hardSeeks` 一个都不涨 —— 因为问题不在
     * 校准，而在解码器。判据只有 [VideoDecoderStats.droppedToKeyframe] 这一个：
     * 它涨就说明 `MediaCodecVideoRenderer` 在反复「丢到关键帧 + 重建解码器」，
     * 那正是定格的机制（详见 [VideoDecoderStats] 的 KDoc）。
     *
     * 与 `hardSeeks` 一起看能直接分岔：**两个一起涨** = 我们的硬 seek 引发了
     * 丢到关键帧（修校准）；**只有 `droppedToKeyframe` 涨** = 解码器自己跟不上
     * （修码率 / 倍速 / 解码器）；两个都不涨 = 卡顿另有原因。
     *
     * 注意它是「本次起播以来」而不是「整场会话以来」：`stop()` 之后读不到（全 0），
     * 换片重播会归零（详见 [VideoDecoderStats]）。
     */
    val videoDecoderStats: VideoDecoderStats = VideoDecoderStats.EMPTY,
    /** 最近若干次漂移采样（旧→新）。§9.3 的 60 点折线取它。 */
    val driftHistory: List<Long> = emptyList(),
    val lastEvent: String = "尚未开始播放同步",
) {
    companion object {
        const val TIER_NONE = "不动"
        const val TIER_SPEED = "变速追赶"
        const val TIER_SEEK = "硬 seek"
        const val TIER_SETTLE = "静默期（seek 后）"
        const val TIER_BUFFERING = "缓冲中（跳过校准）"
        /** 片源还没装载好（选片后的一两秒 / 换片重开）——不校准，见 [runFollowerCorrection]。 */
        const val TIER_NOT_READY = "未就绪（跳过校准）"
        const val TIER_OFF = "校准已关闭"
        const val TIER_IDLE = "—"
    }
}

/**
 * 播放同步引擎（《技术方案评审》§6.1–§6.6）。
 *
 * ── 角色（§6.5 修订版）──────────────────────────────────────────────────────
 * 房主（`Room.hostUserId`）仍然是**整场属性的权威**：它按 `heartbeatIntervalMs`
 * 广播心跳，并且只有它能改整场倍速与字幕。
 *
 * 但 `play` / `pause` / `seek` 三条**是双向的**：两个人正看着同一部片子，谁都会想
 * 立刻按下暂停；把这个动作变成「请对方帮你按」是没有意义的。冲突语义就是
 * 「后按的生效」，不需要任何额外协商 —— 心跳每 2.5s 带一次播放态与位置，
 * 任何一次冲突都会在一个心跳周期内收敛到同一个状态上（见 [handleHeartbeat]）。
 *
 * 于是 `PLAY` / `PAUSE` / `SEEK` 的入站分支里**不再有角色分岔**，而 `SPEED` 与
 * `SUBTITLE` 仍然只在房主那一侧生效（见 [ignoreNonHostControl]）。
 *
 * **这里刻意不用协商角色（谁是 offer 方）当判据。** 两者的成因无关：协商角色由
 * userId 字典序定（见 `PeerCoordinator.reconcile`），房主由「谁建的房」定。
 * 拿协商角色当主控会让「后进房的人反而是房主」这种完全正常的情况变成
 * 「两端都以为自己主控」，而症状只是「两边各播各的」，没有任何报错。
 *
 * ── 为什么引擎自己不持有协程 ────────────────────────────────────────────────
 * 所有输入（本地操作、对端消息、定时 tick）都由外部驱动，引擎只提供
 * 「给定现在这个时刻，我该做什么」的纯函数式步骤。这样 §6.3 的每一档都可以在
 * 单测里用假时钟 + 假播放器逐毫秒断言；引擎内部起协程的话，测试就得去等真实的
 * 500ms —— 于是没人会去测那些最容易错的边界（刚过阈值的那 1ms）。
 *
 * ── 线程约定 ────────────────────────────────────────────────────────────────
 * 与 [PeerCoordinator] 相同：所有方法都必须在同一线程上调用（RTC 那个
 * `Dispatchers.Main.immediate` 作用域），因此内部不加锁。
 */
class CinemaSyncEngine(
    private val player: PlaybackTarget,
    /** 单调时钟（Android 装配传 `SystemClock.elapsedRealtime()`）。注入是为了单测能用假时钟。 */
    private val clock: () -> Long,
    /**
     * 出站口。签名是 `PeerMessenger.send` 的**放宽版**：多一个 `to`。
     *
     * ── 为什么出站要带 `to`（契约 §4 / §7，阶段二 v2）────────────────────────────
     * `cinema.*` 是**混合**的：`play` / `pause` / `seek` / `speed` / `subtitle` /
     * `heartbeat` 是广播（`to` 可空），而 `state_snapshot` 与 `sync_ack`（探测）
     * 是**定向**的 —— 前者「只发给匹配且在看的成员」，后者「成员发给房主」。
     * 广播用 null 表示，和 [PeerMessenger.send] 的缺省语义一致。
     *
     * 这一层不判断「谁是房主」也不记 userId：调用点各自的语义已经足够清楚，
     * 而引擎里已经有一份 `host`，再存一份 peer 关系就是第二份真相。
     */
    private val outbound: (type: String, payload: JsonObject, to: UserId?) -> Unit,

    /**
     * 定向消息（快照 / 探测）的目标。null = 走广播。
     *
     * ── 为什么现在还是一个单值（P4 之前）─────────────────────────────────────────
     * 引擎目前是**一对一**的同步器：一份锚点、一条探测往返。多人房里正确做法是
     * 「每个成员一份媒体状态与锚点」（P4），那是把 [isHost] 那一整套判定都按成员
     * 复制一遍的大改。在它落地之前，这里取「最近一次打开链路的那条链路的对端」，
     * 于是：
     *  - 两人房（当前验收目标）：行为与 1.6 完全一致；
     *  - 三人以上：房主的快照会发给最后进来的那个人，其余人靠广播心跳对齐 ——
     *    能看、能同步，但**不是**契约 §7 的最终形态。已在交付说明里记为 P4 待办。
     */
    private var directedPeer: UserId? = null,
    config: CinemaSyncConfig = CinemaSyncConfig.DEFAULT,
) {

    /**
     * 当前生效的参数。**可以在运行期换**（设置 → 播放设置），所以是 `var` 而不是构造期的 `val`。
     *
     * 换它必须走 [updateConfig]：`corrector` 是照着旧参数建出来的，只改这份引用会让
     * 「软/硬阈值」与「漂移因子」分处两套参数，而症状只是「阈值改了却还按旧的判」。
     */
    var config: CinemaSyncConfig = config
        private set

    private val clockSync = ClockSync(config.rttWindow)
    private val timeline = MasterTimeline()
    private var corrector = DriftCorrector(config)

    private val _status = MutableStateFlow(CinemaSyncStatus())
    val status: StateFlow<CinemaSyncStatus> = _status.asStateFlow()

    // ── 角色与素材 ─────────────────────────────────────────────────────────

    private var host = false

    /** 本机片源摘要。房主用它填 `cinema.state_snapshot`（§5.3）。 */
    private var mediaSignature: MediaSignature? = null

    private var sessionEpoch = 0L

    // ── 同步循环的中间状态 ─────────────────────────────────────────────────

    private var linkUp = false

    private var lastHeartbeatSentAtMs = 0L

    /**
     * 待配对的时钟探测（§6.1 的 t1 + 契约 §7 的 `probeId`）。
     *
     * null 表示「手上没有待配对的探测」。**不用 `0L` 当哨兵值**：
     * `elapsedRealtime()` 从开机起算，设备刚启动的几十秒内它真的可能接近 0，
     * 那时哨兵值和真实值会撞上，症状是「开机后第一分钟内同步偶尔不收敛」。
     *
     * `id` 从 1 起自增，**只增不复用**：复用会让一条迟到的旧心跳被当成新探测的应答。
     */
    private var pendingProbe: PendingProbe? = null

    private data class PendingProbe(var id: Long, var t1Ms: Long, var retries: Int = 0)

    /** 下一个探测编号。 */
    private var probeCounter = 0L

    /** 超时 3 次记一次诊断之后归零用的计数（契约 §7）。 */
    private var probeTimeoutBursts = 0L

    private var lastProbeSentAtMs = 0L

    private var settleUntilMs = 0L

    /**
     * 整场最近一次确定的播放态。`cinema.seek` 不带 isPlaying（§5.3），收到它时只能沿用。
     *
     * 双向控制之后它不再是「房主最近一次的播放态」：谁都可能改，所以它是**全场事实**。
     * 唯一的时序要求是「在收到那条 seek 之前，它已经反映了引起这次拖动的播放态」——
     * 拖动自己不改变播放态，所以这个要求天然成立。
     */
    private var lastKnownPlaying = false

    /**
     * 整场倍速（§6.5：**倍速仍是房主主控**）。房主设定并广播；从端由 `cinema.speed`
     * 与 `state_snapshot` 跟随。
     *
     * 它同时是两个东西的基数：[MasterTimeline] 的外推斜率，以及播放器速率的底数
     * （最终速度 = 本值 × §6.3 的漂移因子，见 [applySpeed]）。两处必须用同一个数 ——
     * 只改播放器不改斜率的话，2x 播放时目标位置每秒比真实推进**少**一秒，
     * 从端每几百毫秒就会硬 seek 一次。
     */
    private var playbackSpeed = 1f

    /** §6.3 最近一次算出的漂移因子（相对乘子）。只用于展示。 */
    private var appliedFactor = 1f

    /**
     * 上一次**真正写进播放器**的速率（= 整场倍速 × 漂移因子）。
     *
     * 去重必须比它，不能比因子：整场倍速从 1.0 改成 2.0 时因子恒为 1.0，
     * 按因子去重会把这次改动整个吞掉 —— 症状是「房主把倍速调成 2x，自己那台纹丝不动」。
     */
    private var appliedPlayerSpeed = 1f

    /**
     * 本机**意图**上的播放态（最后被谁改成了什么）。
     *
     * [onTick] 拿它与 [PlaybackTarget.isPlayRequested] 比较，用来发现「引擎之外的东西
     * 改了本机播放态」（拔耳机自动暂停、音频焦点丢失…，见 [PlaybackTarget.isPlayRequested]）。
     * 本节每一次**主动**改播放态（本机操作、收到对端控制、心跳自愈）都要同步它 ——
     * 漏一处，那次改动就会在下一个 tick 被当成「用户操作」再广播一遍，两端互相回弹。
     */
    private var expectedPlayRequested = false

    /**
     * 本机最近一次广播播放态的时刻，用于心跳自愈的**回声闸门**（见 [playStateEchoGraceMs]）。
     *
     * 初值取 `Long.MIN_VALUE / 2` 而不是 `0L`：`elapsedRealtime()` 从开机起算，
     * 设备刚启动的头几百毫秒里 `now - 0` 会小于宽限期，那时闸门会莫名其妙地关上。
     * 减半是为了让后面那次减法不溢出。
     */
    private var lastLocalPlayStateBroadcastAtMs = Long.MIN_VALUE / 2

    /** 待广播的倍速（连点合并用）。null = 没有待发。 */
    private var pendingSpeedBroadcast: Float? = null
    private var lastSpeedBroadcastAtMs = 0L

    /** 待广播的拖动目标位置（连点 ±10s 合并用）。null = 没有待发。 */
    private var pendingSeekBroadcast: Long? = null
    private var lastSeekBroadcastAtMs = 0L

    private var lastDriftMs = 0L
    private var tier = CinemaSyncStatus.TIER_IDLE
    private var lastHeartbeatSeq = 0L
    private var lastEvent = "尚未开始播放同步"

    /**
     * 排队等着写进 [lastEvent] 的一条**引擎之外**的事件（2026-10-13 第八轮缺陷①）。
     *
     * `@Volatile` 是必需的、不是保险：写入方 [noteExternal] 在界面线程，消费方
     * [publish] 在引擎线程。少了它，这一行可能永远看不见 —— 而「看不见」正是这一轮
     * 在修的那一类症状。
     */
    @Volatile
    private var pendingExternalEvent: String? = null

    private var heartbeatsSent = 0L
    private var heartbeatsReceived = 0L
    /**
     * 三个「本次会话累计」量（§11 的 1.8 行）：`maxAbsDriftMs` 与本段这两个计数。
     *
     * **它们刻意不随 [resetSyncState] 清零** —— 这一点是这三个量存在的全部理由：
     * 锁屏 30 分钟那一轮验收里，中途一旦发生重建链路，链路级的量会被清掉，于是
     * 「这 30 分钟里最坏漂移是多少」在回到界面时就再也答不上来，而为了保活结论
     * 又不能再唤醒一次屏幕去重测。会话 = 本次 App 进程运行期间。
     */
    private var speedAdjustments = 0L
    private var hardSeeks = 0L
    private var maxAbsDriftMs = 0L

    private var remoteDriftMs: Long? = null
    private var remoteRttMs: Long? = null

    private val driftHistory = ArrayDeque<Long>()

    val isHost: Boolean get() = host

    // ── 外部设置 ───────────────────────────────────────────────────────────

    /**
     * 设置角色。由装配点按 `Room.hostUserId == 本机 userId` 判定后调用。
     *
     * 换角色时把同步状态整体重置：从端变房主之后，它原来那条「跟着别人走」的
     * 时间轴立刻就是错的（现在该由它定义位置），留着会让它在新角色下
     * 继续按旧锚点校准自己。
     */
    fun setRole(isHost: Boolean) {
        if (host == isHost) return
        host = isHost
        resetSyncState()
        note(if (isHost) "本端成为房主（主控）：播放控制由这一台广播" else "本端成为从端（跟随）：播放控制已交给房主")
        publish(clock())
    }

    /** 本机片源摘要（1.5 算出）。房主下发状态快照时会带上它。 */
    fun setMediaSignature(signature: MediaSignature?) {
        mediaSignature = signature
    }

    /**
     * 换一套同步参数（设置 → 播放设置保存之后调用）。幂等。
     *
     * ── 为什么要重建 `corrector` ────────────────────────────────────────────────
     * 它是照着旧 `softDriftMs` / `hardDriftMs` / `maxAdjustSpeed` 建出来的。只换上面
     * 那份 `config` 的话，阈值判定仍按旧参数走 —— 而界面上显示的新参数会让人以为
     * 「已经按新值在跑」，症状极难归因。参数里只有 `rttWindow` 是**不能**热换的
     * （`ClockSync` 的窗口在构造期定了），那一项也只影响估计平滑，不重建它。
     *
     * ── 关掉本地校准时要顺手把速率放回 1.0 ──────────────────────────────────────
     * 关掉的那一刻可能正跑在 0.95 / 1.05 上（上一拍刚决定「变速追赶」）。不清的话，
     * 从端会用一个 ±5% 的偏移一直播下去，而「校准已关闭」这句话解释了位置为什么
     * 不再被纠正，却解释不了声音为什么不是原速。
     */
    fun updateConfig(updated: CinemaSyncConfig) {
        if (updated == config) return
        config = updated
        corrector = DriftCorrector(updated)
        if (!updated.localCalibrationEnabled) applySpeed(1f)
        // 立刻推一次状态：`applySpeed` 只改播放器，而面板读的是 `status` —— 不推的话，
        // 关掉校准之后那一栏还会显示旧的「实际速率 0.95x」，直到下一拍 tick 才纠回来。
        publish(clock())
        note(
            "同步参数已更新：房主广播 ${updated.heartbeatIntervalMs} ms、本地校准" +
                "${if (updated.localCalibrationEnabled) "开启（${updated.localTickMs} ms）" else "关闭"}",
        )
    }

    /**
     * 手动同步一次（控制栏那颗「手动同步」键）。
     *
     * ── 为什么不新增一条消息 ────────────────────────────────────────────────────
     * 两条现成的路刚好各自够用，而且都已经被真机验证过：
     *  - **房主**：立刻广播一条 `cinema.heartbeat`。它带的就是「位置 + 是否在播 +
     *    我的时钟」，从端收到即重设时间轴（§6.4）。
     *  - **从端**：立刻发一条 `cinema.sync_ack`。房主收到后**本来就会立刻回一条
     *    heartbeat**（见 `ACTION_SYNC_ACK` 分支：那条回复是量 RTT 所必需的，
     *    所以它必定不等定时器）⇒ 从端随即重锚。
     * 新增一个 `cinema.resync` 只会多一种要两端同时升级才能用的消息，收益为零。
     *
     * ── 为什么从端那条要绕开 `syncProbeMinIntervalMs` ──────────────────────────
     * 那道 1 秒下限是给**自动探测**用的（防止「收到就探测」自持对轰）。用户手按一下
     * 是一个离散事件，不该因为「刚好在 1 秒内」被静默吞掉 —— 那会变成「点了没反应」。
     */
    fun requestManualSync() {
        if (!linkUp) {
            note("手动同步：链路还没通，这一下没有发出去")
            return
        }
        if (host) {
            sendHeartbeat()
            note(
                "手动同步（房主）：已立刻广播 ${CinemaPlayback.TYPE_HEARTBEAT}" +
                    "（positionMs=${player.currentPositionMs}、isPlaying=${player.isPlayRequested}）",
            )
            return
        }
        sendProbe(clock())
        note("手动同步（从端）：已请求房主立刻回一条 ${CinemaPlayback.TYPE_HEARTBEAT}，收到后重设时间轴")
    }

    fun setSessionEpoch(epoch: Long) {
        sessionEpoch = epoch
    }

    /**
     * 定向消息（`state_snapshot` / `sync_ack`）发给谁。null = 退回广播。
     *
     * 由 `CinemaHandler` 在「某条链路刚 OPEN / 刚断」时设：多人房里哪一条链路是
     * 「当前同步对象」是玩法层的事，引擎自己不该去读成员表（那是底座的模型）。
     *
     * 清成 null 的时机很重要：对端走了之后还留着它，房主下一次换片就会把快照
     * 定向发给一个**不在房间里的人**，`PeerMessenger` 返回 false，而快照就此丢失
     * —— 其余成员等不到「全量恢复」，界面上表现为「换片之后从端一直停在旧画面」。
     */
    fun setDirectedPeer(peer: UserId?) {
        directedPeer = peer
    }

    /** 当前定向目标（只读）。调用方用它判「断掉的是不是同步对象那一条链路」。 */
    val directedPeerOrNull: UserId? get() = directedPeer

    // ── 控制权（契约 §7，阶段二 P4）─────────────────────────────────────────

    /**
     * 本机 userId。只用于「本机能不能控制」这一件事（UI 灰按钮）。
     *
     * 不参与执法：执法看的是**发消息的那一方**，那是 [mayApply] 的事。
     */
    private var myUserId: UserId? = null

    /** 房主 userId。null = 还没进房（或还没解析出房主），此时只认「房主发的」为假。 */
    private var hostUserId: UserId? = null

    /**
     * 本机此刻**在不在房间里**。与 [myUserId] / [hostUserId] 分开，是因为「房主未知」
     * 有两种完全相反的含义，合成一个 null 就会把其中一种判反：
     *
     *  - 不在房间（本地放片）：这台机器就是自己的房主 ⇒ **可以**控制；
     *  - 在房间但还不知道房主是谁（`sys.room_joined` 还没到 / 装配点漏了这一步）：
     *    这时任何「可以控制」的结论都是猜的 ⇒ **不可以**控制，宁可灰一下。
     *
     * 判反的症状正是三端门禁里撞到的那个：房主把「允许成员控制」关掉之后，从端的
     * 播放键仍然是亮的 —— 按下去什么都不会发生，而界面上没有任何线索说明为什么。
     */
    private var inRoom: Boolean = false

    /** 当前控制策略。**引擎是唯一持有者**：房主改它、从端从消息里读它、UI 从它取。 */
    var controlPolicy: ControlPolicy = ControlPolicy.HOST_ONLY
        private set

    /** 已经「匹配且在看」的成员（房主侧才有内容）。 */
    private val watchingPeers = mutableSetOf<UserId>()

    /**
     * 本机自己算不算「匹配且在看」（从端侧）。
     *
     * 由 `cinema.media_status` 的入站结果决定 —— 那是**房主的裁决**，不是本机自判：
     * 片源一致但房主还没开播（`watching=false`）时本机确实没有控制权。
     */
    private var selfWatching = false

    fun setSelfWatching(watching: Boolean) {
        if (selfWatching == watching) return
        selfWatching = watching
        publish(clock())
    }

    fun setMyUserId(userId: UserId?) {
        myUserId = userId
    }

    /**
     * 一次把「我在哪、谁是房主」写清楚。**这是唯一的正规入口** —— 分开调三个 setter
     * 很容易漏掉 [inRoom]，而漏掉它的症状是「从端按钮永远亮着」。
     */
    fun setRoomContext(myUserId: UserId?, hostUserId: UserId?, inRoom: Boolean) {
        this.myUserId = myUserId
        this.hostUserId = hostUserId
        this.inRoom = inRoom
        publish(clock())
    }

    fun setHostUserId(userId: UserId?) {
        hostUserId = userId
    }

    fun setControlPolicy(policy: ControlPolicy) {
        controlPolicy = policy
        publish(clock())
    }

    /**
     * 标记某个成员是否「匹配且在看」。
     *
     * 由 `CinemaHandler` 在 `cinema.hello` 比对出结论后调用 —— 那件事它才知道
     * （它拿着本机摘要与比对器）。引擎只需要「这个人算不算在看」这一个布尔。
     */
    fun setPeerWatching(peer: UserId, watching: Boolean) {
        val changed = if (watching) watchingPeers.add(peer) else watchingPeers.remove(peer)
        if (changed) publish(clock())
    }

    /** 某个人现在有没有控制权（房主自己永远有）。 */
    fun canControl(user: UserId): Boolean =
        ControlPolicyTable.canControl(controlPolicy, hostUserId, user, watchingPeers.contains(user))

    /** 本机此刻能不能控制（UI 用它决定按钮灰不灰）。 */
    val canControlLocally: Boolean
        get() = when {
            host -> true
            // 不在房间里：这台机器自己就是自己的房主，灰掉只会被当成「播放器坏了」。
            !inRoom -> true
            else -> {
                // 在房间里：必须**确定**自己在看且策略允许。认不出自己 / 不知道房主是谁
                // 时一律不给 —— 这时「允许」是猜的，而猜错的代价是按下去毫无反应。
                val me = myUserId ?: return false
                hostUserId != null && selfWatching && controlPolicy.allowedFor(me)
            }
        }

    /**
     * 这条入站消息该不该被执行（契约 §7 的两条硬约定）。
     *
     * - **从端只接受 `from == hostUserId`**：成员发来的一律不听（`speed` / `subtitle` 更是
     *   永远只有房主）；
     * - **房主收到成员控制**：先按 [canControl] 判，不通过直接忽略 —— 忽略而不是回一条
     *   「你没权限」，因为从端的按钮本来就该是灰的，能发出来的只可能是改过的包。
     *
     * `cinema.sync_ack` 例外：它是**探测回执**不是控制。把它也拦掉的话，房主的时钟
     * 估计永远拿不到样本，而症状只是「RTT 一直是 0」。
     */
    private fun mayApply(action: String, from: UserId): Boolean {
        if (action == CinemaPlayback.ACTION_SYNC_ACK) return true
        if (from == hostUserId) return true
        if (action != CinemaPlayback.ACTION_PLAY &&
            action != CinemaPlayback.ACTION_PAUSE &&
            action != CinemaPlayback.ACTION_SEEK
        ) {
            // `speed` / `subtitle` / `heartbeat` / `state_snapshot` 只可能来自房主。
            return false
        }
        // 成员发来的播放控制：只有房主有资格裁决，且必须被授权。
        return host && canControl(from)
    }

    /**
     * DataChannel 变成 OPEN。
     *
     * 房主在这里下发一次 `cinema.state_snapshot`（§6.4 的「全量恢复」）——
     * 这是「对端中途重装 App / 链路断过一次」之后唯一的自动恢复路径：
     * 从端不需要重新选片，也不需要请房主再点一次播放。
     *
     * 从端在这里把估计整体清空：上一条链路的 RTT 与锚点对新路径没有参考价值，
     * 留着会让第一秒的漂移判断建立在一个错误的 rttHalf 上。
     */
    fun onLinkOpened() {
        resetSyncState()
        linkUp = true

        if (host) {
            // 心跳基准落在「现在」而不是 0：`resetSyncState()` 把它清成 0，
            // 而 0 意味着「距离上次心跳已经过了整个开机时长」，于是链路上通后的
            // 第一个 tick 就会立刻补发一条心跳。那一条的位置没什么意义（房主
            // 往往还没起播），且和紧接着的 `state_snapshot` 语义重复。
            lastHeartbeatSentAtMs = clock()

            val signature = mediaSignature
            if (signature == null) {
                note("链路已通，但本机还没算出片源摘要，暂不下发状态快照（选好片后会补）")
            } else {
                sendStateSnapshot(signature)
            }
        } else {
            note("链路已通；等房主的 cinema.play / heartbeat 建立时间轴")
        }
        publish(clock())
    }

    fun onLinkClosed() {
        if (!linkUp) return
        linkUp = false
        resetSyncState()
        note("链路已断，同步状态已清空（等重连后由房主的状态快照恢复）")
        publish(clock())
    }

    /**
     * **会话结束**（退出房间 / 房间被解散 / 换服务器 / 恢复失败）：停播放并清空同步状态。
     *
     * ── 与 [onLinkClosed] 的差别只有一条：要不要停播放 ──────────────────────────
     * 链路断（拔网、对端闪断）时**不能**停播放：§9.4 给了 60s 宽限期，人还在厅里，
     * 片子应当停在原地等对端回来 —— 所以 [onLinkClosed] 只清估计量，画面不动。
     * 退房是另一件事：这场观影结束了，片子必须停下来。
     *
     * ── 为什么必须有一个显式的入口（2026-10-12 补）──────────────────────────────
     * 在这之前，退房只做了一件事：`Signaler.leave()` 把 `Room` 置空。播放器却
     * **没有任何人负责**：它是进程级单例（§7.2），退房后没人叫它停 —— 于是
     * 「退出房间后视频还在接着播放」。这一条不是漏了一行 `pause()`，而是缺一个
     * 语义位置：**「这场会话结束了」这件事在引擎里原本无处表达**。
     *
     * ── 为什么暂停之后还要把两个播放态记账一起改掉 ────────────────────────────────
     * 与 [onPeerUnavailable] 同一个理由：[expectedPlayRequested] 不同步的话，
     * 下一个 tick 会把这次暂停当成「引擎之外的东西改了播放态」，再广播一条
     * `cinema.pause` 出去 —— 而那时对端已经不在（甚至已经进了别的房间），
     * 这条广播只会让下一次进房的对方先停一下。
     */
    fun endSession() {
        if (player.isPlayRequested) player.pause()
        linkUp = false
        resetSyncState()
        // 这两条要在 resetSyncState() **之后**覆盖：它按「播放器此刻的事实」取值，
        // 而这里要的是明确的「停」。虽然 pause() 之后两者恰好一致，但依赖那个巧合
        // 会让将来「pause 改成异步」时静默出错（记账说在播、播放器已经停了）。
        expectedPlayRequested = false
        lastKnownPlaying = false
        note("会话结束（退出房间 / 房间解散）：已暂停本机播放并清空同步状态")
        publish(clock())
    }

    /**
     * 对端**账号级**离场（`sys.peer_left`，§9.4「对端掉线」）：本地立即暂停，
     * 宽限期（60s）内不退出房间。
     *
     * ── 为什么与 [onLinkClosed] 分开 ────────────────────────────────────────────
     * 两件事的时机与含义都不同：`sys.peer_left` 是**信令**层的事，它到达时 P2P 链路
     * 往往还活着（§5.4 刻意保留座位就是这个原因）；而 [onLinkClosed] 是 DataChannel
     * 真的没了。把前者也当成后者处理，就会在「信令抖了一下、链路还好好的」这种情况下
     * 把同步状态整体清掉 —— 于是本来只是日志里多一行，变成了要等 2.5s 重新锚定。
     *
     * ── 为什么房主也要暂停 ──────────────────────────────────────────────────────
     * §9.4 写的是「本地立即暂停」，没有按角色分岔。对端不在时继续放，只是让两人
     * 回来时差得更远。
     *
     * 这一侧**不广播**这次暂停（对端已经不在，广播也没人收），但必须把播放态的记账
     * 一起改掉（[expectedPlayRequested] 与 [lastKnownPlaying]）：后者是 `cinema.seek`
     * 的「isPlaying 沿用值」，留着 true 会让对端回来之后的第一次拖动把本机重新判成
     * 「在播」；前者若不同步，下一个 tick 会看到「本机还在播」并把这条暂停当成用户
     * 操作补播出去 —— 对端重连回来时就会先播一下再停。
     */
    fun onPeerUnavailable() {
        // 判据用 isPlayRequested 而不是 isPlaying：缓冲期间 isPlaying 是 false 而
        // playWhenReady 仍是 true，用前者会漏掉这次暂停 —— 于是下一个 tick 把
        // 「本机在播」当成一次用户操作广播出去，而对端已经不在，这条广播只会让
        // 它重连回来时先播一下再停。
        if (player.isPlayRequested) player.pause()
        expectedPlayRequested = false
        lastKnownPlaying = false
        note("对端已离开（sys.peer_left）：已暂停本机播放，等待它重连（§9.4，宽限 60s）")
        publish(clock())
    }

    // ── 本地操作广播（播放 / 暂停 / 拖动 = 双向；倍速 = 房主主控）──────────
    //
    // 这一节里 `play` / `pause` / `seek` 三条**不再有** `if (!host) return`：
    // 本轮把这三条放开成双向（§6.5 修订版）。倍速那条仍然有 —— 倍速与字幕
    // 留在房主手里，理由是它们不像「暂停」那样是两人都会想立刻按的动作，
    // 而是一个**整场属性**：两端各按一次就成了最后一次按的说了算的竞态，
    // 没有任何一方能看出「现在到底是谁说了算」。
    //
    // ── 为什么「本机自己发起的操作」也必须重设锚点 ──────────────────────────────
    // 这是双向控制最容易漏掉的一步，漏了的症状很反直觉：从端按了 +10s，画面确实
    // 跳过去了，但本机的时间轴还锚在**旧**位置 → 下一个 tick 算出一个
    // 「本机超前 10 秒」的漂移 → 吃满硬阈值 → 一次 seek 把自己拉回原位。
    // 用户看到的是「快进按钮闪了一下，画面跳回去了」。
    //
    // 暂停同理：不重设的话时间轴斜率还是 1.0，目标位置继续往前走而画面停着，
    // 漂移每秒长 1000ms —— 一秒之后就是一次硬 seek 把画面拽到前面去。
    //
    // 旧版本为什么不需要：那时从端根本没有可广播的本地操作（它永远只是房主
    // 消息的执行者），而房主不做漂移校准，它的时间轴不参与任何决策。

    /**
     * 本机起播。**双向**：从端也会广播（§6.5 修订版）。
     *
     * ── 为什么要先确认「本机真的播起来了」────────────────────────────────────
     * 界面的调用约定是「先动播放器，再调引擎」（见 `CinemaPlaybackSection`），
     * 而 `Media3PlaybackTarget.play()` 在播放器未就绪时直接 return，什么都没改。
     * 那时若照广播不误，会出现「本机什么都没播、对端却开始放」—— 而且对端放起来
     * 之后，本机还会被它自己的心跳判成「落后」，反复硬 seek 去追一个自己放不了的
     * 位置。没生效就不广播，只记一句可查的日志。
     */
    fun onLocalPlay(positionMs: Long) {
        val now = clock()
        if (!player.isPlayRequested) {
            note("本机按了播放，但播放器还没就绪（未选片 / prepare 未完成），本次不广播")
            // 这一句 `publish` 不能省：`note()` 只写内部字段，状态流要等下一次 `publish`
            // 才会更新。少了它，用户按下播放键之后面板上什么都没变 —— 看起来像按钮失灵，
            // 而真相是「引擎正确地决定不广播，但没人把这句话放到界面上」。
            publish(now)
            return
        }
        // 锚点与广播共用同一个 `now`：两者代表的是同一瞬间，各取一次时钟会让
        // 「广播时刻」比「锚点时刻」晚几毫秒，回声闸门于是从略晚的那点起算。
        anchorLocal(now, positionMs, isPlaying = true)
        broadcastPlayState(playing = true, atMs = now, positionMs = positionMs, reason = "本机起播")
        publish(now)
    }

    /** 本机暂停。**双向**：从端也会广播（§6.5 修订版）。 */
    fun onLocalPause(positionMs: Long) {
        val now = clock()
        anchorLocal(now, positionMs, isPlaying = false)
        broadcastPlayState(playing = false, atMs = now, positionMs = positionMs, reason = "本机暂停")
        publish(now)
    }

    /**
     * 本机拖到 [positionMs]。**双向**，且广播按 [CinemaSyncConfig.seekBroadcastThrottleMs] 合并。
     *
     * 静默期与远端 seek 走同一条：`seekTo` 返回后 `currentPosition` 还要过一会儿才落在
     * 目标上，中途读到的中间值会被算成几百毫秒的漂移（§6.3 的 `seekSettleMs`）。
     */
    fun onLocalSeek(positionMs: Long) {
        val now = clock()
        // 拖动不改变播放态，所以沿用播放器此刻的**意图**（不是 isPlaying：缓冲期它会翻 false，
        // 那会把时间轴斜率误设成 0，于是目标位置冻在拖动前的位置上）。
        anchorLocal(now, positionMs, isPlaying = player.isPlayRequested)
        settleUntilMs = now + config.seekSettleMs
        armSeekConfirmation(now, isLocal = true)
        pendingSeekBroadcast = positionMs
        if (!flushPendingSeekBroadcast(now)) {
            note(
                "本机拖动 → ${CinemaPlayback.TYPE_SEEK}（positionMs=$positionMs）已合并，" +
                    "等待下一次 tick 发出（连点 ±10s 时对端只收到一条，不必连续硬跳）",
            )
        }
        publish(now)
    }

    // ── 跳转落点确认（2026-10-25，「就近同步点」跳转的配套）────────────────────

    /** 待确认的跳转何时到期（0 = 没有待确认的跳转）。 */
    private var seekConfirmAtMs = 0L

    /** 这次待确认的跳转是不是本机发起的（决定要不要补发一条 seek）。 */
    private var seekConfirmIsLocal = false

    private fun armSeekConfirmation(nowClockMs: Long, isLocal: Boolean) {
        seekConfirmAtMs = nowClockMs + maxOf(config.seekSettleMs, config.seekConfirmDelayMs)
        seekConfirmIsLocal = isLocal
    }

    /**
     * 用户跳转落在一个**更早的关键帧**上时，把时间轴按实际落点重新对齐。
     *
     * ── 为什么必须有这一步 ──────────────────────────────────────────────────────
     * 用户跳转走 `CLOSEST_SYNC`（见 `PlaybackTarget.seekToNearbySync`）—— 为了不再
     * 「解码丢弃到目标」而卡住画面。代价是实际位置比请求值早（实测这条片的 GOP 最长
     * 10.4 秒）。若时间轴仍按请求值走，从端算出的漂移就是「−落点偏移」，而它
     * ≥ `hardDriftMs` ⇒ **每 500ms 硬跳一次**，比原来的卡顿更糟。
     *
     * ── 判据为什么用「时间轴目标」而不是「请求值」──────────────────────────────
     * 播放中回读位置时，这期间播放又推进了同样多秒 —— 直接拿它跟请求值比会被推进
     * 污染。而 `targetPositionMs(now)` 与 `currentPositionMs` 在同一斜率上外推，
     * **两者之差恰好是落点偏移**，与推进多少无关。
     *
     * ── 为什么本机要补发一条 seek ──────────────────────────────────────────────
     * 对端的锚点来自我们那条 seek（请求值）。它自己也会做同样的确认，但两条链路
     * 各读各的，补发一条「实际落点」能让两端的锚点**显式**一致 —— 代价只有一条
     * 几十字节的消息，而且只在本机跳转时发。
     */
    private fun confirmSeekLanding(nowClockMs: Long) {
        if (seekConfirmAtMs == 0L) return
        if (nowClockMs < seekConfirmAtMs) return
        seekConfirmAtMs = 0L
        val isLocal = seekConfirmIsLocal
        seekConfirmIsLocal = false
        if (!timeline.isAnchored) return

        val actual = player.currentPositionMs
        val expected = timeline.targetPositionMs(nowClockMs)
        val offset = actual - expected

        // ── 读数可信吗（2026-10-25 真机事故后补的护栏）────────────────────────────
        // `seekTo` 之后有一段时间 `currentPosition` 还是 0 / 还是旧值：播放器在缓冲、
        // 或 seek 还没 apply。把它当成「实际落点」采信，就会把锚点重设到 0 再补发给
        // 对端 —— 症状正是「一拖进度条两端一起回到开头」。实测出问题的那部片**索引
        // 完好、GOP 最长 15 秒**，根本不可能退到 0，所以那种读数只能是没落定。
        //
        // 三种不可信一律跳过：没就绪 / 在缓冲 / 退得比任何真实 GOP 都多。
        // 跳过时**保留请求值**（请求值本来就是两端一致的），最坏结果是少一次修正。
        if (!player.isReady || player.isBuffering) return
        if (actual == 0L && expected > 5_000L) return
        if (actual < expected - config.maxPlausibleLandingBackMs) return

        if (offset > -config.seekLandingToleranceMs && offset < config.seekLandingToleranceMs) {
            return
        }
        anchorLocal(nowClockMs, actual, isPlaying = player.isPlayRequested)
        if (isLocal) {
            pendingSeekBroadcast = actual
            flushPendingSeekBroadcast(nowClockMs)
        }
        note(
            "跳转落在更早的关键帧上：时间轴按实际位置 $actual ms 重新对齐" +
                "（相对原目标差 $offset ms）" +
                if (isLocal) "，已把实际位置补发给对端" else "",
        )
    }

    /**
     * 房主设定**整场倍速**（§6.5：倍速仍是房主主控，从端调用会被忽略）。
     *
     * ── 为什么本机播放器也由这里改，而不是界面自己设 ────────────────────────────
     * 播放器最终速率是「整场倍速 × 漂移因子」（见 [applySpeed]），界面只知道前一半。
     * 界面自己 `setSpeed(2.0)` 的话：① 从端正跑在 0.95 的追赶速度上，那个乘子会被
     * 抹掉；② 引擎的去重表（[appliedPlayerSpeed]）与实际不符 —— 下一次漂移判断算出的
     * 目标速率恰好等于界面刚设的那个值时，引擎会以为「已经设过了」而不动手，
     * 追赶就此永远不生效。所以速率只有一个写入口，就是引擎。
     *
     * ── 为什么广播要节流 ──────────────────────────────────────────────────────
     * 倍速选择器是可以连点的（1.5 → 2.0 → 1.5），而每一条 `cinema.speed` 都会让从端
     * 把锚点按旧斜率外推一次再换斜率。单次无害，但连点会在两秒内挤出十几条，
     * 从端的位置曲线被反复打折。合并成「只发最终值」即可 —— 本机画面不受影响，
     * 播放器是立刻改的。
     */
    fun onLocalSpeed(speed: Float) {
        if (!host) return
        val now = clock()
        playbackSpeed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        applySpeed(appliedFactor)
        pendingSpeedBroadcast = playbackSpeed
        if (!flushPendingSpeedBroadcast(now)) {
            note(
                "本机倍速 → ${playbackSpeed}x（播放器 ${appliedPlayerSpeed}x）；" +
                    "广播已合并，等待下一次 tick 发出",
            )
        }
        // 面板上的「整场倍速」由 publish 出来。少了它，用户点 2.0x 之后选择器旁边
        // 那行读数还是 1.0x（画面其实已经是 2x）—— 最容易被当成「倍速没生效」。
        publish(now)
    }

    /**
     * 广播本机当前的播放态，并同步内部的两个记账字段。
     *
     * 三件事必须一起做，少一件都会出问题：
     *  ① 出站；
     *  ② [expectedPlayRequested] 跟着改成**播放器此刻的真实值** —— 否则下一个 tick
     *     会把这次改动当成「引擎之外的东西改的」再广播一遍，两端互相回弹；
     *  ③ 记下时刻（[lastLocalPlayStateBroadcastAtMs]）—— 让紧跟着飞来的那条旧心跳
     *     不反向覆盖本机，见 [playStateEchoGraceMs]。
     */
    private fun broadcastPlayState(playing: Boolean, atMs: Long, positionMs: Long, reason: String) {
        expectedPlayRequested = player.isPlayRequested
        lastKnownPlaying = expectedPlayRequested
        lastLocalPlayStateBroadcastAtMs = atMs
        sendPosition(
            if (playing) CinemaPlayback.TYPE_PLAY else CinemaPlayback.TYPE_PAUSE,
            positionMs,
        )
        note("$reason → 广播 cinema.${if (playing) "play" else "pause"}（positionMs=$positionMs）")
    }

    /** 房主切换内封字幕轨（§5.3 依据 Q8）。从端跟随，避免两人字幕语言不同。 */
    fun onLocalSubtitleTrackChanged(trackIndex: Int) {
        if (!host) return
        outbound(
            CinemaPlayback.TYPE_SUBTITLE,
            CinemaPlaybackCodec.encodeSubtitle(CinemaSubtitle(trackIndex = trackIndex)),
            // §7：`speed` / `subtitle` **永远房主广播**，从端不接受成员发来的,
            // 所以这两条不需要目标。
            null,
        )
        note("本机切换字幕轨 → 广播 ${CinemaPlayback.TYPE_SUBTITLE}（trackIndex=$trackIndex）")
    }

    /**
     * 人工注入一个偏差（**验收专用**，§11 的 1.6 口径要求「注入 200ms 偏差能被变速追平、
     * 注入 2s 偏差走硬 seek」）。
     *
     * 实现方式是**只动播放器、不动时间轴** —— 等价于「影片本身跑偏了」，
     * 而不是「房主跳到了别处」。这正是要测的东西：注入后引擎必须靠自己
     * 把它追回来。若改成同时移动锚点，就变成测「锚点更新」而不是测校准算法了。
     */
    fun injectDrift(deltaMs: Long) {
        val from = player.currentPositionMs
        val to = (from + deltaMs).coerceAtLeast(0L)
        player.seekTo(to)
        settleUntilMs = clock() + config.seekSettleMs
        note(
            if (host) {
                "（验收）人工注入偏差 ${deltaMs} ms：本地位置 $from → $to。" +
                    "**但本端是房主，房主不做漂移校准**（它是权威，只广播）—— " +
                    "要验 §6.3 的三档，请在**从端**那台注入"
            } else {
                "（验收）人工注入偏差 ${deltaMs} ms：本地位置 $from → $to。静默 ${config.seekSettleMs} ms 后开始校准"
            },
        )
        publish(clock())
    }

    // ── 定时驱动 ───────────────────────────────────────────────────────────

    /**
     * 每 [CinemaSyncConfig.localTickMs] 调一次（房主与从端都调）。
     *
     * 顺序有讲究：
     *  1. [detectPlayStateChange] 最前 —— 本机播放态若被系统改了（拔耳机），
     *     它得先变成一条广播出去，后面的校正是拿「本机在播」这个前提在算的；
     *  2. 两条 flush 合并掉连点产生的冗余广播（倍速、拖动）；
     *  3. 房主只发心跳，从端做 §6.3 的三档校准。
     */
    fun onTick(nowClockMs: Long) {
        detectPlayStateChange(nowClockMs)
        // 房主的时间轴若还没建立，就地按播放器此刻的事实锚一次（见 [anchorHostIfNeeded]）。
        // 排在 detectPlayStateChange 之后：那一条若已经发现变更并锚过，这里就什么都不做。
        anchorHostIfNeeded(nowClockMs)
        // 跳转后的落点确认排在两条 flush 之后、校之前：它可能改动锚点并补发一条
        // seek，必须在校准读到漂移**之前**完成，否则那一拍会先按旧锚点硬跳一次。
        confirmSeekLanding(nowClockMs)
        flushPendingSpeedBroadcast(nowClockMs)
        flushPendingSeekBroadcast(nowClockMs)

        if (host) {
            if (nowClockMs - lastHeartbeatSentAtMs >= config.heartbeatIntervalMs) {
                sendHeartbeat()
            }
            publish(nowClockMs)
            return
        }
        runFollowerCorrection(nowClockMs)
        // 探测超时（契约 §7）：只在**从端**有意义 —— 探测是它发起的，回执是房主回的。
        checkProbeTimeout(nowClockMs)
        publish(nowClockMs)
    }

    /**
     * 发现「本机播放态被引擎之外的东西改了」，并把这场变更广播出去。
     *
     * ── 触发者是哪些 ──────────────────────────────────────────────────────────
     * 拔耳机 / 蓝牙断开（`setHandleAudioBecomingNoisy`，见 [Media3PlaybackTarget]）、
     * 系统音频焦点丢失。不广播的症状是「暂停按了没用」：本机停了，2.5 秒后对端的
     * 心跳又把它播起来 —— 用户只会怀疑按钮坏了。
     *
     * ── 判据为什么是 isPlayRequested 而不是 isPlaying ─────────────────────────
     * 缓冲期间 `isPlaying` 会翻 false 而 `playWhenReady` 不变。用前者会让每一次
     * 网络抖动都变成一条广播出去的 pause，两端被反复停播。
     *
     * ── 为什么还必须顺手重设锚点 ──────────────────────────────────────────────
     * 这次改动改变了「时间轴该不该继续外推」：暂停之后斜率必须归 0，否则目标位置
     * 继续往前走，下一个 tick 就是一个「本机落后 N 秒」的假漂移 → 硬 seek。
     */
    private fun detectPlayStateChange(nowClockMs: Long) {
        if (!linkUp) return
        val actual = player.isPlayRequested
        if (actual == expectedPlayRequested) return
        expectedPlayRequested = actual
        lastKnownPlaying = actual
        lastLocalPlayStateBroadcastAtMs = nowClockMs
        val position = player.currentPositionMs
        sendPosition(
            if (actual) CinemaPlayback.TYPE_PLAY else CinemaPlayback.TYPE_PAUSE,
            position,
        )
        note(
            "检测到本机播放态被系统改变（拔耳机 / 音频焦点丢失 / 片尾）→ 已" +
                "${if (actual) "播" else "暂停"}到 $position ms 并广播，避免被心跳自愈回原状",
        )
        anchorLocal(nowClockMs, position, isPlaying = actual)
    }

    /**
     * 房主侧「就地锚定」：时间轴还没建过、而播放器已经装好片源时，直接按播放器此刻的
     * 事实建一次锚点。
     *
     * ── 为什么需要它（2026-11-06 真机：房主那台面板一直是 `anchored=false`）──────────
     * 房主的时间轴原本只在**本机做了一次操作**（play / pause / seek）时建立
     * （[anchorLocal]）。而产品路径里最常见的起播根本不走那三个入口：
     * `--open` 与「选好片自动播放」都是播放器自己在组合里 `play()`，引擎是**先挂上
     * 播放器、后看到它已经在播**。此时 `expectedPlayRequested` 在建引擎/建链时已由
     * [resetSyncState] 按事实记成 true，于是 [detectPlayStateChange] 判「没变化」，
     * 一次都不广播、也不锚定 —— 面板永远 `anchored=false`，从端只能等心跳，
     * 而心跳要等下一个 `heartbeatIntervalMs` 才发第一条。
     *
     * 只对房主做：从端的时间轴必须由房主定义，它自己锚一次就是「各播各的」。
     * 判据用 `isReady` 而不是 `durationMs > 0`：libVLC 在 `Opening` 阶段时长可能还是 0，
     * 而 `onTrack`（`isReady`）才是「这条媒体真的装载了」的时刻。
     */
    private fun anchorHostIfNeeded(nowClockMs: Long) {
        if (!host) return
        if (timeline.isAnchored) return
        if (!player.isReady) return
        val position = player.currentPositionMs
        expectedPlayRequested = player.isPlayRequested
        lastKnownPlaying = expectedPlayRequested
        anchorLocal(nowClockMs, position, isPlaying = expectedPlayRequested)
        note(
            "房主时间轴就地锚定：$position ms（" +
                (if (expectedPlayRequested) "播放中" else "暂停") +
                "）—— 本机起播没走引擎入口，由这里补上",
        )
    }

    /**
     * 本机广播播放态之后要放行心跳自愈多久。见 [lastLocalPlayStateBroadcastAtMs]。
     *
     * ── 为什么需要这个闸门（不加会看到的画面）──────────────────────────────────
     * 从端按暂停 → 本机立刻停住并广播；但房主那边的**定时心跳**可能正好在它收到
     * 这条广播之前就发出去了，内容还是「在播」。那条心跳一到，自愈逻辑就按它把
     * 从端重新播起来；再过 2.5 秒，房主收到暂停并把状态改掉，从端才真正停住。
     * 用户看到的是「按了暂停，画面停了半秒又自己播起来，两秒后才停」。
     *
     * ── 取值 ──────────────────────────────────────────────────────────────────
     * 「一个来回 + 一个 tick」：这是「我们那条广播还没来得及到达对端、更没来得及
     * 反映到它发出的心跳里」的时间尺度。留得再长就会开始无条件忽略对端真实的
     * 播放态改动，而那是另一个方向的错误。
     *
     * 再夹一道 `heartbeatIntervalMs` 上限：rttHalf 是从**最近一次**探测量出来的，
     * 而链路刚恢复、或探测被卡住时它可能量出一个好几秒的假值 —— 那时不夹的话，
     * 闸门会一直关着，对面真实的播放态改动要被吞掉好几条心跳。上限取「一个心跳
     * 周期」正好对上「冲突在一个心跳周期内收敛」这条已经定下的语义。
     */
    private fun playStateEchoGraceMs(): Long =
        (2 * clockSync.rttHalfMs + config.localTickMs).coerceAtMost(config.heartbeatIntervalMs)

    /**
     * §6.3 的三档校准。**从端专属**。
     *
     * 顺序（跳过条件的先后有讲究）：静默期 → 缓冲 → 漂移分档。
     * 缓冲检查放在漂移之前，是因为缓冲时算出来的漂移**一定是**错的
     * （见 [PlaybackTarget.isBuffering]），让它先于分档被拦掉。
     */
    private fun runFollowerCorrection(nowClockMs: Long) {
        if (!timeline.isAnchored) {
            tier = CinemaSyncStatus.TIER_IDLE
            return
        }

        // 漂移**先算出来用于展示**，再决定要不要动它。
        // 静默期与缓冲期都跳过校准，但如果把漂移一并显示成 0，看面板的人会以为
        // 「已经对齐了」—— 而真相是「这一刻的数字不可信」。档位那一栏负责说明
        // 为什么没有动作，漂移那一栏照实显示观测值。
        val target = timeline.targetPositionMs(nowClockMs)
        val local = player.currentPositionMs
        lastDriftMs = local - target

        if (nowClockMs < settleUntilMs) {
            tier = CinemaSyncStatus.TIER_SETTLE
            return
        }
        if (player.isBuffering) {
            tier = CinemaSyncStatus.TIER_BUFFERING
            return
        }
        // 片源还没装载好（选片后的一两秒 / 换片重开）：**不许校准**。
        //
        // 这段时间里的 `currentPositionMs` 是 0 或旧值，算出来的漂移必然是天量（现场
        // 真实数字：−822805 ms），而它正好会触发一次硬 seek —— 在 libVLC 上等于把一个
        // 刚打开的 5GB 文件强行跳到 13 分钟处，输入线程要重建索引，几十秒都不回来。
        // 桌面端这条 seek 原本跑在 EDT 上，于是整个界面冻住（2026-11-06 真机「卡死」）。
        // 引擎在这里挡住，两个平台都受益：没准备好就不该有位置判断。
        if (!player.isReady) {
            tier = CinemaSyncStatus.TIER_NOT_READY
            return
        }

        // 只有真正被判断过的漂移才进历史（§9.3 的折线）。静默期与缓冲期的观测值
        // 是过渡态垃圾，混进折线会让「同步是否在收敛」这件事看起来无规律地抖。
        // 累计峰值取**同一批采样**（见 [recordDriftPeak]）。
        recordDriftPeak(lastDriftMs)
        pushDrift(lastDriftMs)

        // 本地校准被关掉时：漂移照算、照显示，但**一个动作都不做**（§16.23）。
        // 放在 settle / buffering 之后，是为了让档位那一栏仍然能说清「这一拍为什么没动」——
        // 否则关掉校准之后，静默期里显示的会是「校准已关闭」，而真实原因是被静默期挡住的。
        if (!config.localCalibrationEnabled) {
            tier = CinemaSyncStatus.TIER_OFF
            // 速率必须回 1.0（见 `updateConfig` 的同一条理由）：构造时就是关的话，
            // 没有一次「切换」可用来触发那次清理，这里兜住。去重让它是零成本的。
            applySpeed(1f)
            return
        }

        when (val action = corrector.decide(lastDriftMs, player.isPlaying, target)) {
            DriftAction.None -> {
                tier = CinemaSyncStatus.TIER_NONE
                // 漂移回到软阈值内就把**因子**放回 1.0（播放器速率于是回到整场倍速）。
                // **这一步不能省**：留着 ±5% 不改的话，速度偏差会继续把位置推向另一侧，
                // 于是漂移在阈值附近来回穿越，速率在 0.95/1.05 之间反复跳 ——
                // 症状是「明明对齐了，声音却一直在抖」。
                applySpeed(1f)
            }

            is DriftAction.AdjustSpeed -> {
                tier = CinemaSyncStatus.TIER_SPEED
                applySpeed(action.speedFactor)
                speedAdjustments += 1
            }

            is DriftAction.SeekTo -> {
                tier = CinemaSyncStatus.TIER_SEEK
                player.seekTo(action.positionMs)
                // 因子放回 1.0：seek 已经把位置对上，再叠一个追赶速度就是过冲。
                applySpeed(1f)
                settleUntilMs = nowClockMs + config.seekSettleMs
                hardSeeks += 1
                note(
                    "漂移 ${lastDriftMs} ms 达到硬阈值 ${config.hardDriftMs} ms → 直接 seek 到 " +
                        "${action.positionMs} ms，进入 ${config.seekSettleMs} ms 静默期",
                )
            }
        }
    }

    /**
     * 把「整场倍速 × 漂移因子」写进播放器。
     *
     * ── 为什么去重不能省 ──────────────────────────────────────────────────────
     * `setPlaybackParameters` 每次调用都会重建 Sonic 的时间伸缩状态，而 §6.3 的
     * 校准每 500ms 就问一次「要不要变速」。不去重的话，声音会一直在重建处咔嗒。
     *
     * ── 为什么去重比的是「写进播放器的那个数字」而不是因子 ──────────────────────
     * 整场倍速变了而因子没变（1x → 2x，因子恒为 1.0）时，按因子去重会把这次改动
     * 整个吞掉 —— 症状是「房主把倍速调成 2x，自己那台纹丝不动」，而且日志里
     * 一切正常。
     */
    private fun applySpeed(factor: Float) {
        appliedFactor = factor
        val target = (playbackSpeed * factor).coerceIn(MIN_PLAYER_SPEED, MAX_PLAYER_SPEED)
        if (appliedPlayerSpeed == target) return
        appliedPlayerSpeed = target
        player.setSpeed(target)
    }

    // ── 入站 ───────────────────────────────────────────────────────────────

    /**
     * 处理一条玩法域消息。**已剥离前缀的 [action]**，与 `PlayModeHandler.onMessage` 同形。
     *
     * 房主与从端收到的其实是同一批消息（对端不会按角色挑着发），所以两支都在这里，
     * 由 [host] 分岔。这样「谁处理什么」只有一处可看，而不是散在两个 handler 里。
     */
    fun onRemote(action: String, env: Envelope) {
        // 控制权门禁（契约 §7，阶段二 P4）。放在 `when` **之前**：一条不该执行的消息
        // 连解码都不该做 —— 解码失败会走 `malformed()`，那会把「你没权限」记成
        // 「对端发了坏消息」，归因正好反了。
        val sender = UserId(env.from)
        if (!mayApply(action, sender)) {
            note(
                "忽略来自 ${env.from.take(8)} 的 ${CinemaPlayback.typeOf(action)}：" +
                    if (!host) {
                        "从端只接受房主的控制（房主=${hostUserId?.value?.take(8) ?: "未定"}）"
                    } else if (controlPolicy.overrides.containsKey(sender)) {
                        "按控制策略它没有控制权（defaultAllow=${controlPolicy.defaultAllow}，" +
                            "override=${controlPolicy.overrides[sender]}）"
                    } else {
                        "按控制策略它没有控制权（defaultAllow=${controlPolicy.defaultAllow}，无 override）"
                    },
            )
            // 必须 publish：这一行是「别人的控制被我拦了」的唯一证据。只写 lastEvent
            // 不推状态的话，诊断页永远读不到它 —— 那时人会以为「消息根本没到」。
            publish(clock())
            return
        }

        when (action) {
            CinemaPlayback.ACTION_PLAY -> {
                val payload = CinemaPlaybackCodec.decodePosition(env.payload) ?: return malformed(action)
                val now = clock()
                if (host) {
                    // ── 房主是**进度**的权威（2026-11-06 用户口径）────────────────────
                    // 旧做法把 `payload.positionMs` 当新锚点：于是任何一台成员按下播放，
                    // 全场都会被拉到**它那台**的进度上 —— 用户看到的是「各自播各自的」，
                    // 而且每次有人按播放就会跳一次。
                    // 现在的语义：成员按播放只表达「开始吧」，位置取房主这台；房主随即
                    // 再广播一次，按下播放的那台与其它成员一起跟到房主的时间轴上。
                    if (!player.isPlayRequested) player.play()
                    val position = player.currentPositionMs
                    anchorLocal(now, position, isPlaying = true)
                    broadcastPlayState(
                        playing = true,
                        atMs = now,
                        positionMs = position,
                        reason = "成员按了播放，按房主位置对齐",
                    )
                    note(
                        "收到 ${CinemaPlayback.TYPE_PLAY}（对端报 ${payload.positionMs} ms）：" +
                            "以本机 $position ms 为准重新广播，全场跟房主",
                    )
                    return
                }
                anchorFromHost(now, payload.positionMs, isPlaying = true, senderClockMs = payload.senderClockMs)
                if (!player.isPlayRequested) player.play()
                noteCurrentPlayState()
                note("收到 ${CinemaPlayback.TYPE_PLAY}：positionMs=${payload.positionMs}，目标 ${timeline.targetPositionMs(now)} ms")
            }

            CinemaPlayback.ACTION_PAUSE -> {
                val payload = CinemaPlaybackCodec.decodePosition(env.payload) ?: return malformed(action)
                val now = clock()
                anchorFromHost(now, payload.positionMs, isPlaying = false, senderClockMs = payload.senderClockMs)
                player.pause()
                noteCurrentPlayState()
                val gap = abs(player.currentPositionMs - timeline.anchoredMediaMs)
                if (gap >= config.softDriftMs) {
                    player.seekTo(timeline.anchoredMediaMs)
                    settleUntilMs = now + config.seekSettleMs
                    tier = CinemaSyncStatus.TIER_SETTLE
                    note("收到 ${CinemaPlayback.TYPE_PAUSE}：本机差 ${gap} ms（≥ 软阈值），已 seek 到 ${timeline.anchoredMediaMs} ms")
                } else {
                    note("收到 ${CinemaPlayback.TYPE_PAUSE}：本机差 ${gap} ms，在软阈值内，只暂停不 seek")
                }
            }

            CinemaPlayback.ACTION_SEEK -> {
                val payload = CinemaPlaybackCodec.decodePosition(env.payload) ?: return malformed(action)
                val now = clock()
                anchorFromHost(now, payload.positionMs, isPlaying = lastKnownPlaying, senderClockMs = payload.senderClockMs)
                // 精确跳转：位置准确优先（「就近同步点」对单关键帧 / 无索引片源会跳回 0）。
                // 落点若与请求值不一致（无索引片源上可能发生），由 [confirmSeekLanding] 兜住。
                player.seekTo(timeline.anchoredMediaMs)
                armSeekConfirmation(now, isLocal = false)
                // 因子放回 1.0：位置已经直接对上了，再叠追赶速度就是过冲。
                applySpeed(1f)
                settleUntilMs = now + config.seekSettleMs
                // 档位当场改成「静默期」：它在整段静默期里都是事实，而若留成上一档
                // （多半是「不动」），面板会显示「不动 + 2 秒的漂移」，看起来像算法坏了。
                tier = CinemaSyncStatus.TIER_SETTLE
                note(
                    "收到 ${CinemaPlayback.TYPE_SEEK}：**无条件立即** seek 到 ${timeline.anchoredMediaMs} ms" +
                        "（positionMs=${payload.positionMs} + 飞行时间=" +
                        "${timeline.anchoredMediaMs - payload.positionMs} ms），" +
                        "进入 ${config.seekSettleMs} ms 静默期，不等漂移判断（§6.4）",
                )
            }

            CinemaPlayback.ACTION_SPEED -> {
                // 倍速是房主主控（§6.5）—— 从端手里那条倍速选择器是灰的，
                // 走到这条分支只可能是对端用了改过的 APK，忽略并留一句日志。
                if (host) return ignoreNonHostControl(action)
                val payload = CinemaPlaybackCodec.decodeSpeed(env.payload) ?: return malformed(action)
                val now = clock()
                playbackSpeed = payload.speed
                // **只改斜率、不重设锚点位置**：直接 anchor 会把位置钉在这条消息的
                // 时刻上，而那条锚点比真实的播放位置晚了一个 RTT。见 MasterTimeline。
                timeline.setPlaybackSpeed(now, payload.speed.toDouble())
                applySpeed(appliedFactor)
                note(
                    "收到 ${CinemaPlayback.TYPE_SPEED}：整场倍速 → ${payload.speed}x" +
                        "（播放器实际 ${appliedPlayerSpeed}x）；时间轴斜率已同步改掉，" +
                        "否则每秒会攒出 ${(payload.speed - 1f) * 1000} ms 的假漂移",
                )
            }

            CinemaPlayback.ACTION_HEARTBEAT -> {
                if (host) return
                val payload = CinemaPlaybackCodec.decodeHeartbeat(env.payload) ?: return malformed(action)
                handleHeartbeat(env, payload)
            }

            CinemaPlayback.ACTION_SYNC_ACK -> {
                if (!host) return
                val payload = CinemaPlaybackCodec.decodeSyncAck(env.payload) ?: return malformed(action)
                remoteDriftMs = payload.driftMs
                remoteRttMs = payload.rttMs
                pushDrift(payload.driftMs)
                note("对端上报：漂移 ${payload.driftMs} ms、链路 RTT ${payload.rttMs} ms（§9.3 的漂移历史来源）")

                // ⚠️ 这里**必须立刻回一条 heartbeat**，不能等自己的定时器。
                // 对端那条 sync_ack 就是它记下的 t1；它的 RTT 由「t4 − t1」量出，
                // 而 t2 就是这条 heartbeat 的 senderClockMs。等到定时器（可能还差 2 秒）
                // 才回的话，量出来的是「定时器还剩多久」，与路径延迟无关 —— 时钟估计
                // 会变成一串噪声，而症状只是「同步总在追赶、偶尔抖一下」，极难归因。
                //
                // §7：这条回执是**定向**的，并把 `probeId` 原样带回去 —— 六人房里
                // 五个人各自的探测必须能分辨，否则每个人的 RTT 里都混着别人的往返。
                val from = UserId(env.from)
                if (payload.probeId != null) {
                    sendHeartbeat(to = from, probeId = payload.probeId)
                } else {
                    // 旧包没有编号：没法配对，只能按老办法广播（两人房里等价）。
                    sendHeartbeat()
                }
            }

            CinemaPlayback.ACTION_STATE_SNAPSHOT -> {
                if (host) return
                val payload = CinemaPlaybackCodec.decodeStateSnapshot(env.payload) ?: return malformed(action)
                val now = clock()
                // 倍速要先落回引擎，`anchorFromHost` 才会把新斜率一并写进锚点。
                // 顺序反了的话锚点仍按旧速率外推 —— 而那正是这条消息要修的东西。
                playbackSpeed = payload.playbackSpeed
                // §7：快照里的 `controlPolicy` 是**迟到者的第一份权限真相**。
                // 缺省（旧包）时不覆盖本地策略：那说明对端根本没有这个概念。
                payload.controlPolicy?.let { controlPolicy = ControlPolicy.fromWire(it) }
                anchorFromHost(now, payload.positionMs, isPlaying = payload.isPlaying)
                if (payload.isPlaying != player.isPlayRequested) {
                    if (payload.isPlaying) player.play() else player.pause()
                }
                noteCurrentPlayState()
                // 速率也要跟着回到快照里那个：链路断过一次之后 `appliedPlayerSpeed`
                // 已经被清成 1.0，而播放器可能还停在断开前的 2.0x 上。
                applySpeed(appliedFactor)
                note(
                    "收到 ${CinemaPlayback.TYPE_STATE_SNAPSHOT}：sessionEpoch=${payload.sessionEpoch}、" +
                        "positionMs=${payload.positionMs}、isPlaying=${payload.isPlaying}、" +
                        "playbackSpeed=${payload.playbackSpeed}x —— 已整体重建时间轴",
                )
            }

            CinemaPlayback.ACTION_SUBTITLE -> {
                if (host) return ignoreNonHostControl(action)
                val payload = CinemaPlaybackCodec.decodeSubtitle(env.payload) ?: return malformed(action)
                player.selectSubtitleTrack(payload.trackIndex)
                note("收到 ${CinemaPlayback.TYPE_SUBTITLE}：跟随切到字幕轨 ${payload.trackIndex}")
            }
        }
        publish(clock())
    }

    /**
     * 处理心跳：§6.1 的时钟往返 + §6.4 的重设锚点，外加一次自愈的播放态对齐。
     *
     * ── 为什么心跳也要对齐 play/pause，而不只是重设锚点 ────────────────────────
     * §6.4 把「对齐播放态」写在 `play` / `pause` 两行里。但 DataChannel 是可靠有序的，
     * 真正会丢事件的是**对端重启过 App**、或链路断过一次之后又通：那时 `cinema.play`
     * 早就过去了，从端手上只有心跳。心跳里本来就带 `isPlaying`（§5.3），
     * 用它把播放态对齐过去，代价为零，收益是「任何一次状态丢失都会在 2.5 秒内自愈」。
     * 不做的话，症状是「对端重装 App 后，两端画面再也对不上，除非房主暂停再播一次」。
     */
    private fun handleHeartbeat(env: Envelope, payload: CinemaHeartbeat) {
        heartbeatsReceived += 1
        lastHeartbeatSeq = env.seq
        val now = clock()

        // ① 时钟往返：只认**配对的那一条**（契约 §7 的 `probeId`）。
        //
        //    六人房里，一次探测之后房主会对**每个**成员回一条心跳（它们都是广播或定向
        //    到别人），而从端只记着「我发过一次探测」。不带配对的话，量出来的 RTT 里
        //    混着别人的往返，时钟估计变成噪声，症状只是「同步总在追、偶尔抖一下」。
        //    配对不上的心跳**照样用于锚定**（那是 ③ 之后的事）—— 采样与锚定是两件事。
        val probe = pendingProbe
        if (probe != null && payload.probeId == probe.id) {
            pendingProbe = null
            val sample = clockSync.onRoundTrip(t1 = probe.t1Ms, t2 = payload.senderClockMs, t4 = now)
            if (sample == null) {
                note("时钟往返样本被丢弃（t4 < t1，只可能是拿墙钟和单调钟混算了）")
            }
        } else if (probe != null && payload.probeId == null) {
            // 旧包（`probeId` 缺省）：没有配对信息可用，只能按「上一条探测」解释 ——
            // 两人房里这是对的，六人房里会退化，但总比完全采样不到强。
            pendingProbe = null
            clockSync.onRoundTrip(t1 = probe.t1Ms, t2 = payload.senderClockMs, t4 = now)
        }

        // ② 先处理「本机播放态被系统改过」这件事。
        //
        //    它排在这里而不是只等 onTick，是因为它比手上这条心跳**新**：心跳是
        //    对端在 2.5 秒前拍的。不先做的话，那条心跳会把手动暂停/拔耳机造成的
        //    暂停悄悄覆盖回去，而本机那次变更**一次都不会被广播** —— 症状是
        //    「拔了耳机，本机自己播起来了」。
        detectPlayStateChange(now)

        // ③ 「本机到底算不算在播」必须先定下来，再拿它去锚定。
        //
        //    顺序反了的症状很难猜：若照 payload.isPlaying 去锚定，而回声闸门又拦下了
        //    那次对齐，时间轴就以为「在播」并继续外推，可本机画面停着 —— 漂移每秒长
        //    1000ms，一秒之后就是一次硬 seek 把画面拽到前面去。两个决定必须来自同一份事实。
        val echoed = now - lastLocalPlayStateBroadcastAtMs < playStateEchoGraceMs()
        val effectivePlaying = if (echoed) expectedPlayRequested else payload.isPlaying

        // ④ 重设锚点（§6.2 + §6.4）。
        anchorFromHost(now, payload.positionMs, isPlaying = effectivePlaying, senderClockMs = payload.senderClockMs)

        // ⑤ 自愈的播放态对齐（§6.4）。
        if (echoed) {
            // 回声闸门：本机刚广播过播放态，这条心跳是在我们那条广播到达对端**之前**
            // 发出来的，它描述的必然是旧状态。此时保留本机意图 —— 对端收到我们那条
            // 广播之后会跟上来。不拦的话症状是「按了暂停，画面停了半秒又自己播起来」。
            lastKnownPlaying = expectedPlayRequested
        } else if (payload.isPlaying != expectedPlayRequested) {
            if (payload.isPlaying) player.play() else player.pause()
            noteCurrentPlayState()
            note(
                "心跳显示对端${if (payload.isPlaying) "在播" else "已暂停"}、本机相反 → " +
                    "已对齐${if (payload.isPlaying) "起播" else "暂停"}（自愈）",
            )
        } else {
            lastKnownPlaying = payload.isPlaying
        }

        // ⑥ 发起下一次探测。受 syncProbeMinIntervalMs 限制 —— 它同时是
        //    「房主立刻回心跳」这个设计的防对轰闸门（见 CinemaSyncConfig 的注释）。
        maybeSendProbe(now)
    }

    /**
     * 本机自己发起的操作也要重设锚点。
     *
     * `flightMs = 0`：这是**本机自己的**位置，没有任何网络延迟要补偿。补偿了的话
     * 目标位置会平白比本机多出一段，于是「刚按完快进」紧接着就是一次硬 seek。
     */
    private fun anchorLocal(nowClockMs: Long, positionMs: Long, isPlaying: Boolean) {
        timeline.anchor(
            nowClockMs = nowClockMs,
            positionMs = positionMs,
            flightMs = 0L,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed.toDouble(),
        )
    }

    /**
     * 刚收到/执行过一条影响播放态的控制之后调用：把「本机播放态」的当前事实记下来。
     *
     * 之所以读播放器而不是把「我们想让它变成的值」写进去：`play()` 在未就绪时
     * 直接 return，什么都没改。若那时把意图记成 true，下一个 tick 就会看到
     * 「意图 true、实际 false」并广播一条 pause —— 用户什么都没按，对端却收到暂停。
     *
     * 它**不**碰 [lastLocalPlayStateBroadcastAtMs]：那道闸门只针对我们自己发出去的广播，
     * 收到的控制没有回声问题（对端那条消息到达我们这里时，它自己早就改完了状态）。
     */
    private fun noteCurrentPlayState() {
        expectedPlayRequested = player.isPlayRequested
        lastKnownPlaying = expectedPlayRequested
    }

    /**
     * 按对端报告的权威位置重设锚点（§6.2 的补偿 + §6.4）。
     *
     * ── 补偿用「这条消息实测飞了多久」，而不是「窗口内最小 RTT 的一半」──────────
     * 见 [ClockSync.flightMsOf]：后者是「这一段时间里最走运的那一次」，而补偿要回答的是
     * 「**这一刻**这条消息飞了多久」。中继路径上两者差得很远，差值直接变成目标位置的
     * 偏置 —— 偏置越过 `hardDriftMs`(300ms) 时，从端每收到一条心跳就硬 seek 一次，
     * 画面与声音一起断。真机报的「P2P 直连不卡、切强制中继从端就卡」正是这一条。
     *
     * @param senderClockMs 对端发这条消息时的单调时钟。给了就按实测飞行时间补偿；
     *   没给（`cinema.state_snapshot` 的载荷里没有这个字段）就退回 [ClockSync.rttHalfMs]，
     *   那是个**偏小**的估计 —— 但那是这条恢复路径一直以来的行为，而紧随其后的心跳
     *   会立刻用真实飞行时间重锚一次，偏置活不过 2.5 秒。
     */
    private fun anchorFromHost(
        nowClockMs: Long,
        positionMs: Long,
        isPlaying: Boolean,
        senderClockMs: Long? = null,
    ) {
        // 补偿与锚点必须来自**同一个时刻**：`flightMsOf` 拿 `nowClockMs` 去换算
        // 「对端那一刻是本机哪一刻」，锚点也写 `nowClockMs`。错开一帧的话，
        // 算出来的补偿与曲线起点就对不上（差值 = 那一帧，通常几十毫秒）。
        val flightMs = senderClockMs
            ?.let { clockSync.flightMsOf(senderClockMs = it, nowLocalMs = nowClockMs) }
            ?: clockSync.rttHalfMs
        timeline.anchor(
            nowClockMs = nowClockMs,
            positionMs = positionMs,
            flightMs = flightMs,
            isPlaying = isPlaying,
            // 倍速不随每条消息传（只有 cinema.speed 与 state_snapshot 带它），
            // 所以这里取引擎当前认定的那个值。它变的时候上面两处已经把它改过了，
            // 而心跳每 2.5s 一次的作用正是「用最新斜率重新外推一遍」。
            playbackSpeed = playbackSpeed.toDouble(),
        )
    }

    // ── 出站 ───────────────────────────────────────────────────────────────

    private fun sendPosition(type: String, positionMs: Long) {
        val now = clock()
        outbound(type, CinemaPlaybackCodec.encodePosition(CinemaPosition(positionMs, now)), null)
    }

    /**
     * 房主发心跳。位置与播放态取自**播放器此刻的事实**。
     *
     * 心跳里不带倍速：倍速由 `cinema.speed` 随改随发（它是低频动作，不需要搭心跳的车），
     * 而心跳只负责「持续刷新位置与播放态」。这也让旧版对端能照常收心跳。
     *
     * ── 两种心跳（契约 §7）──────────────────────────────────────────────────
     *  - 定时那条：**广播**（[to] = null），让所有人一起锚定；
     *  - 探测回执：**定向**给发来 `sync_ack` 的那个人，并把 [probeId] 原样带回 ——
     *    他从端靠它把「这条是不是我那次探测的应答」认出来，否则六人房里五个人的
     *    回执会互相混淆，RTT 采样变成噪声。
     */
    private fun sendHeartbeat(to: UserId? = null, probeId: Long? = null) {
        val now = clock()
        lastHeartbeatSentAtMs = now
        heartbeatsSent += 1
        val position = player.currentPositionMs
        outbound(
            CinemaPlayback.TYPE_HEARTBEAT,
            CinemaPlaybackCodec.encodeHeartbeat(
                CinemaHeartbeat(
                    // 用 isPlayRequested（意图）而不是 isPlaying：心跳的用途是
                    // 「让对端对齐我的播放态」。缓冲期间 isPlaying 是 false，
                    // 发出去就会让对端跟着暂停，几百毫秒后再跟着播 —— 一抖一抖。
                    isPlaying = player.isPlayRequested,
                    positionMs = position,
                    senderClockMs = now,
                    probeId = probeId,
                ),
            ),
            // 定时心跳是**广播**：§4 把它列在「`to` 可空」那一组，而它的语义正是
            // 「让所有在看的人一起对齐」。把心跳发成定向会有一个看不见的后果 ——
            // 没收到心跳的从端没有任何东西能校正锚点，而它自己不会主动来要。
            to,
        )
    }

    /**
     * 给某个刚匹配上的成员补发一份快照（契约 §7：「匹配者重新收 snapshot」）。
     *
     * 多人房里「谁刚进来」不再等于「唯一那个对端」，所以快照不能只按
     * [directedPeer] 发 —— 那会把后进来的人的恢复路径交给「他刚好是最后一个打开链路的人」
     * 这种偶然。
     */
    fun sendSnapshotTo(peer: UserId) {
        val signature = mediaSignature ?: return
        sendStateSnapshot(signature, to = peer)
    }

    private fun sendStateSnapshot(signature: MediaSignature, to: UserId? = directedPeer) {
        outbound(
            CinemaPlayback.TYPE_STATE_SNAPSHOT,
            CinemaPlaybackCodec.encodeStateSnapshot(
                CinemaStateSnapshot(
                    isPlaying = player.isPlayRequested,
                    positionMs = player.currentPositionMs,
                    mediaSignature = signature,
                    // §5.3 的 seq：本机出站序号的当前值。给对端一个「快照对应到哪一条」的锚点。
                    seq = heartbeatsSent,
                    sessionEpoch = sessionEpoch,
                    // 倍速是**推不出来**的状态（位置只能看出「在哪」，看不出「以什么速率走」），
                    // 所以快照必须带上它，否则重连后从端会用一个错的斜率外推。
                    playbackSpeed = playbackSpeed,
                    // §7：快照要带 `controlPolicy`，否则「迟到者」进来后要过一阵
                    // 才从广播里知道自己的权限（那段时间他的按钮是灰的，看起来像卡了）。
                    controlPolicy = controlPolicy.toWire(),
                ),
            ),
            // §7：`state_snapshot` **只发给匹配且在看的那一个成员**。
            to,
        )
        note(
            "已下发 ${CinemaPlayback.TYPE_STATE_SNAPSHOT}（positionMs=${player.currentPositionMs}、" +
                "isPlaying=${player.isPlayRequested}、playbackSpeed=${playbackSpeed}x）",
        )
    }

    /** 待发的倍速发出去（如果已经过了节流间隔）。返回「这次有没有发」。 */
    private fun flushPendingSpeedBroadcast(nowClockMs: Long): Boolean {
        val pending = pendingSpeedBroadcast ?: return false
        if (nowClockMs - lastSpeedBroadcastAtMs < config.speedBroadcastThrottleMs) return false
        pendingSpeedBroadcast = null
        lastSpeedBroadcastAtMs = nowClockMs
        outbound(
            CinemaPlayback.TYPE_SPEED,
            CinemaPlaybackCodec.encodeSpeed(CinemaSpeed(speed = pending)),
            null,
        )
        note("广播 ${CinemaPlayback.TYPE_SPEED}（speed=$pending）；本机播放器实际 ${appliedPlayerSpeed}x")
        return true
    }

    /** 待发的拖动位置发出去（如果已经过了节流间隔）。返回「这次有没有发」。 */
    private fun flushPendingSeekBroadcast(nowClockMs: Long): Boolean {
        val pending = pendingSeekBroadcast ?: return false
        if (nowClockMs - lastSeekBroadcastAtMs < config.seekBroadcastThrottleMs) return false
        pendingSeekBroadcast = null
        lastSeekBroadcastAtMs = nowClockMs
        sendPosition(CinemaPlayback.TYPE_SEEK, pending)
        note("广播 ${CinemaPlayback.TYPE_SEEK}（positionMs=$pending，连点已合并为最终位置）")
        return true
    }

    private fun maybeSendProbe(nowClockMs: Long) {
        if (nowClockMs - lastProbeSentAtMs < config.syncProbeMinIntervalMs) return
        sendProbe(nowClockMs)
    }

    /**
     * 「成员 → 房主」那条定向消息该发给谁（`sync_ack` / 探测 / 回执）。
     *
     * ── 为什么不能用 [directedPeer]（2026-11-06 真机，两台手机 + Windows）──────────
     * [directedPeer] 的含义是「最近一次打开链路的那条链路」，它是 P4 之前一对一的残留。
     * 三人房里它**不是房主**：现场日志里 Windows 从端每 2.5 秒把 `cinema.sync_ack`
     * 发给另一个**成员**（`→ dv_VPINR`），房主一条也收不到 —— 于是
     *   1. 从端的探测永远等不到配对回执，`rtt` 停在 2006 ms 这种「超时后的假值」；
     *   2. `rttHalf` 跟着错，[anchorFromHost] 的飞行时间补偿整体偏掉；
     *   3. 房主侧也看不到成员的漂移上报（诊断面板那两栏永远是 `-`）。
     *
     * 房主自己（`hostUserId == myUserId`）没有上游，仍回落到 [directedPeer]：
     * 那是它给最近加入的成员下发快照的既有行为，不在本次修复范围内。
     */
    private fun ackTarget(): UserId? =
        hostUserId?.takeIf { it != myUserId } ?: directedPeer

    /**
     * 真的发一条 `cinema.sync_ack` 出去。
     *
     * 与 [maybeSendProbe] 分开，是因为「手动同步」要**绕开**那个 1 秒下限：
     * 下限是给自动探测防对轰用的，而用户手按一下是离散事件，被吞掉就成了「点了没反应」。
     */
    private fun sendProbe(nowClockMs: Long) {
        lastProbeSentAtMs = nowClockMs
        // 新探测 = 新编号。**复用编号是错的**：一条迟到的旧回执会被当成这次的应答，
        // 量出来的 RTT 里混着上一轮的飞行时间（而且它看起来完全正常）。
        probeCounter += 1
        val probe = PendingProbe(id = probeCounter, t1Ms = nowClockMs)
        pendingProbe = probe
        outbound(
            CinemaPlayback.TYPE_SYNC_ACK,
            CinemaPlaybackCodec.encodeSyncAck(
                CinemaSyncAck(
                    ackSeq = lastHeartbeatSeq,
                    clientPositionMs = player.currentPositionMs,
                    driftMs = lastDriftMs,
                    rttMs = clockSync.rttMs,
                    probeId = probe.id,
                ),
            ),
            // §7：`sync_ack` 是**成员 → 房主**的定向消息（它带的是「我这一端的
            // 时刻与漂移」，广播出去只会让别人的探测配对错乱）。
            // 收件人取 [ackTarget]：三人以上房间里「房主」与「最后打开链路的成员」不是同一个人。
            ackTarget(),
        )
    }

    /**
     * 探测超时（契约 §7）：3 秒没收到配对心跳就重试，**超时 3 次记一次诊断**。
     *
     * ── 为什么要重试而不是等下一次定时探测 ──────────────────────────────────────
     * 定时探测有 1 秒下限、心跳 2.5 秒一条，一次丢包本来就要等好几秒；而十六人房里
     * 房主的回执要走它自己的队列，偶发排队是常态。重试让「一次抖动」不会升级成
     * 「时钟估计卡在旧值上半分钟」。
     *
     * 三次之后**放弃这一轮**并记一次诊断：继续无限重试的话，一个真的不通的方向会
     * 每 3 秒打两条消息，而症状是「耗电、发热、日志刷屏」，归因更难。
     */
    private fun checkProbeTimeout(now: Long) {
        val probe = pendingProbe ?: return
        if (now - probe.t1Ms < PROBE_TIMEOUT_MS) return

        if (probe.retries >= PROBE_MAX_RETRIES) {
            pendingProbe = null
            probeTimeoutBursts += 1
            note(
                "时钟探测连续 ${PROBE_MAX_RETRIES + 1} 次超时（probeId=${probe.id}）：" +
                    "本轮放弃，等下一次定时探测（契约 §7；累计 $probeTimeoutBursts 次）",
            )
            return
        }

        probe.retries += 1
        note("时钟探测超时 ${now - probe.t1Ms}ms ⇒ 重试第 ${probe.retries} 次（probeId=${probe.id}）")
        // 重试用**同一个编号**：对端回的是哪一次探测，从端必须能认出来是同一轮。
        outbound(
            CinemaPlayback.TYPE_SYNC_ACK,
            CinemaPlaybackCodec.encodeSyncAck(
                CinemaSyncAck(
                    ackSeq = lastHeartbeatSeq,
                    clientPositionMs = player.currentPositionMs,
                    driftMs = lastDriftMs,
                    rttMs = clockSync.rttMs,
                    probeId = probe.id,
                ),
            ),
            // 重试也必须发给房主，理由同 [ackTarget]。
            ackTarget(),
        )
        // 重试之后把计时起点推到「现在」，否则下一次 tick 会立刻再判一次超时。
        probe.t1Ms = now
        pendingProbe = probe
    }

    // ── 状态 ───────────────────────────────────────────────────────────────

    private fun resetSyncState() {
        clockSync.reset()
        timeline.reset()
        pendingProbe = null
        lastProbeSentAtMs = 0L
        lastHeartbeatSentAtMs = 0L
        settleUntilMs = 0L
        playbackSpeed = 1f
        appliedFactor = 1f
        // 速率必须**真的写回播放器**，不能只把记账清成 1.0（2026-10-12 修）。
        //
        // 之前只清了记账：`appliedPlayerSpeed = 1f` 而播放器还停在上一次的
        // 0.95 / 1.05 / 2.0x 上。后果不是「差一点点」，而是**去重把修复也吞掉了** ——
        // 紧接着那次 `applySpeed(1f)` 算出的 target 恰好等于刚清出来的 1.0，
        // 于是直接 return，一个字节都没写给播放器。两端就这样各自跑在一个
        // 界面读数看不见的速率上：诊断页「整场倍速 1.0x、播放器 1.0x」两栏都是假的，
        // 而真相是影片正跑在 2.0x —— 弱解码能力的机器（平板）在那时会出现
        // 「画面几秒一停再跳、声音正常」，正是 §9.3 排不出来的那一类现象。
        //
        // 放在这里而不是 `applySpeed` 里改判据：`resetSyncState()` 的语义就是
        // 「这一场从零开始」，而「从零开始」的速率就是 1.0。让播放器跟上这个语义
        // 比让去重去猜「播放器现在到底是什么速率」更直接（端口上也没有读速率的入口）。
        appliedPlayerSpeed = 1f
        player.setSpeed(1f)
        pendingSpeedBroadcast = null
        lastSpeedBroadcastAtMs = 0L
        pendingSeekBroadcast = null
        lastSeekBroadcastAtMs = 0L
        // 播放态的记账基线取**播放器此刻的事实**，而不是写死 false：
        // 链路刚通时若记成 false 而本机其实在播（换链路、对端重装后重连），
        // 下一个 tick 就会凭空广播一条 play 出去。
        expectedPlayRequested = player.isPlayRequested
        lastKnownPlaying = expectedPlayRequested
        // `Long.MIN_VALUE / 2`：见字段注释 —— 0 会在设备刚开机的那几百毫秒里误关闸门。
        lastLocalPlayStateBroadcastAtMs = Long.MIN_VALUE / 2
        lastDriftMs = 0L
        tier = CinemaSyncStatus.TIER_IDLE
        lastHeartbeatSeq = 0L
        remoteDriftMs = null
        remoteRttMs = null
        driftHistory.clear()
        // 三个「本次会话累计」量（`maxAbsDriftMs` / `speedAdjustments` / `hardSeeks`）
        // **刻意不在这里清零** —— 见 `maxAbsDriftMs` 的字段注释。这一条不是漏写：
        // 重建链路之后，前面那些「最坏值」仍然是这次观影的一部分，抹掉它正好会让
        // 锁屏 30 分钟那一轮验收读到假的好数字。
    }

    /**
     * 记一个漂移采样（§9.3 的折线）。
     *
     * 0 被挡在外面：`lastDriftMs` 在「还没量过」和「刚重置」时就是 0，于是链路刚通的
     * 那一刻对端会收到一条 `driftMs=0` 的上报，折线上就是一次假的「瞬间完美对齐」——
     * 而它恰好出现在重连之后，看的人会以为同步算法在那时莫名变好了。
     * 真漂移恰好为 0 的概率可以忽略，少一个点无所谓。
     */
    private fun pushDrift(value: Long) {
        if (value == 0L) return
        driftHistory.addLast(value)
        while (driftHistory.size > DRIFT_HISTORY_SIZE) driftHistory.removeFirst()
    }

    /**
     * 记一次漂移峰值（§11 的 1.8 行：`最大漂移绝对值`）。
     *
     * 取**与折线同一批采样**：静默期与缓冲期的观测值不算（理由见 [pushDrift]）。
     * 于是折线是它的形状、它是折线的极值，两个读数是同一口径 —— 不会出现
     * 「折线上最大 300ms、累计最大值却是 3s」这种让人怀疑数据的组合。
     *
     * 触发硬 seek 的那一次**算在内**：它在进入静默期之前就被算出来了，而那正是
     * 「最坏值」要回答的问题（偏差真的到过多大）。只看折线是看不到它的 ——
     * seek 之后的静默期会把后续采样一起挡掉。
     */
    private fun recordDriftPeak(value: Long) {
        val magnitude = abs(value)
        if (magnitude > maxAbsDriftMs) maxAbsDriftMs = magnitude
    }

    /**
     * 把一条**引擎之外**的事件写进「最近事件」（2026-10-13 第八轮缺陷①）。
     *
     * ── 为什么是「排队」而不是直接赋值 + `publish()` ──────────────────────────────
     * `publish()` 里要读 `player.currentPositionMs` 与 `player.videoDecoderStats`
     * （见 :1206 / :1226），而播放器有线程亲和性 —— 从界面线程直接调它，撞的正是本文件
     * 上面记的那一类 `IllegalStateException`「Player is accessed on the wrong thread」。
     * 于是这里只排队：真正的赋值与发布留给引擎自己的线程 —— 房间会话期间
     * `CinemaSessionService` 每 `localTickMs`（500ms）tick 一次，每次都走 `publish`。
     *
     * ── 顺序规则：谁更新，谁留下 ──────────────────────────────────────────────────
     * 「最近事件」的含义是**最新发生的那一条**，所以排队不等于插队：
     *  - 排队之后引擎自己又 `note()` 了一条（引擎线程）⇒ 那条更新，排队的这条作废
     *    （`note()` 里清掉）。真实场景：`onSessionEnded` 先关通话、再写「会话结束」，
     *    留下的必须是后者 —— `CinemaSessionEndTest` 钉的就是它；
     *  - 排队之后没有引擎事件 ⇒ 下一拍 `publish` 显示它。房间会话期间
     *    `CinemaSessionService` 每 `localTickMs`（500ms）tick 一次，每次都走 `publish`。
     *
     * ⚠️ 代价写在明处：它可能在 500ms 内被一条引擎事件顶掉（比如按下开关的同时有人在
     * 暂停）。所以它**不是**唯一证据 —— 同一件事在链路日志里另有一份（`PeerCoordinator`），
     * 这里补的是「让人一眼看见」。
     *
     * 调用方：`CinemaHandler.setCallEnabled`（界面线程）。引擎还没绑上时（播放器都没接）
     * 这一行落不下来 —— 那种时候诊断页也还不存在。
     */
    fun noteExternal(line: String) {
        pendingExternalEvent = line
    }

    private fun note(line: String) {
        lastEvent = line
        // 引擎自己刚产生了一条**更新**的事件 ⇒ 排队中的那条外部事件作废（见 [noteExternal]）。
        // 少了这一句，「会话结束」会被它前面那一句「通话开关：关」顶掉 —— 而收尾那一路
        // （`CinemaHandler.onSessionEnded` 先关通话、再 `endSession`）每次都走这个顺序，
        // `CinemaSessionEndTest` 钉的正是它。
        pendingExternalEvent = null
    }

    // 下面两条是 `onRemote` 的**提前返回**分支（`return malformed(...)`），
    // 走它们就不会执行方法末尾那句 `publish(clock())`。所以它们必须自己收尾 ——
    // 否则 `note()` 只改了内部字段，界面上仍是上一条事件，症状是
    // 「对端发了一条坏消息、日志里什么也没发生」，而真相是日志被覆盖了。
    private fun malformed(action: String) {
        note("收到一条解不出来的 cinema.$action 载荷，已忽略（§4.2：坏消息不打断接收链路）")
        publish(clock())
    }

    private fun ignoreNonHostControl(action: String) {
        note("收到非房主发来的 cinema.$action，已忽略（§6.5：倍速与字幕仍由房主主控）")
        publish(clock())
    }

    private fun publish(nowClockMs: Long) {
        // 排队中的外部事件：到这一刻还没有引擎事件顶掉它，它就是最新的那一条
        // （顺序规则见 [noteExternal]）。写进 [lastEvent] 再整份发布出去。
        pendingExternalEvent?.let {
            lastEvent = it
            pendingExternalEvent = null
        }
        _status.value = CinemaSyncStatus(
            isHost = host,
            roleText = when {
                !linkUp -> "未进房 / 链路未通"
                host -> "房主（主控）"
                else -> "从端（跟随）"
            },
            linkUp = linkUp,
            isAnchored = timeline.isAnchored,
            anchoredMediaMs = timeline.anchoredMediaMs,
            targetPositionMs = if (timeline.isAnchored) timeline.targetPositionMs(nowClockMs) else 0L,
            localPositionMs = player.currentPositionMs,
            driftMs = lastDriftMs,
            tier = tier,
            rttMs = clockSync.rttMs,
            rttHalfMs = clockSync.rttHalfMs,
            offsetMs = clockSync.offsetMs,
            rawOffsetMs = clockSync.rawOffsetMs,
            clockSamples = clockSync.sampleCount,
            heartbeatsSent = heartbeatsSent,
            heartbeatsReceived = heartbeatsReceived,
            speedAdjustments = speedAdjustments,
            hardSeeks = hardSeeks,
            isSeekable = player.isSeekable,
            decoderStatsAvailable = player.supportsDecoderStats,
            maxAbsDriftMs = maxAbsDriftMs,
            playbackSpeed = playbackSpeed,
            appliedPlaybackSpeed = appliedPlayerSpeed,
            remoteDriftMs = remoteDriftMs,
            remoteRttMs = remoteRttMs,
            // 每次 publish 都现读一遍解码器计数。它只有几个 int 的读取成本，而
            // 「卡完之后才翻到诊断页」是常态 —— 累计量必须是**最新的**，否则读到的
            // 是卡顿之前那一刻的快照，等于什么都没看见。
            videoDecoderStats = player.videoDecoderStats,
            driftHistory = driftHistory.toList(),
            lastEvent = lastEvent,
            // 控制权（契约 §7）。UI 靠它决定播放控件灰不灰 —— 而这个判据必须与
            // 「房主执法」用的是**同一份**策略，否则会出现「按钮亮着但按下去没反应」。
            canControl = canControlLocally,
            controlDefaultAllow = controlPolicy.defaultAllow,
        )
    }

    companion object {
        /** §9.3 要「最近 60 点折线」。引擎留 40 点：再多在手机上已经看不出形状了。 */
        const val DRIFT_HISTORY_SIZE = 40

        /**
         * 整场倍速的可用区间（界面上的选项只能落在这里面）。
         *
         * ── 上限为什么是 2.0，而不是「越大越好」──────────────────────────────────
         * ① Media3 的变速走 Sonic 做时间伸缩，超过 2x 之后人声会明显发毛发脆；
         * ② §6.3 的追赶幅度 `maxAdjustSpeed = 0.05` 是**相对**的，2x 下就是 2.1x ——
         *    再往上叠，一次追赶就把音质推过了那个边界；
         * ③ 这是一个「两个人一起看一部电影」的场景，4x 快进不是它要解决的问题
         *    （要跳进度有 ±10s 与进度条）。
         * 下限 0.5 同理：低于它人声变成拖长的嗡鸣，不如暂停。
         */
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f

        /** 探测超时（契约 §7：3s 重试）。 */
        const val PROBE_TIMEOUT_MS = 3_000L

        /** 超时重试次数上限；超过就放弃这一轮并记一次诊断（契约 §7：超时 3 次记一次）。 */
        const val PROBE_MAX_RETRIES = 3

        /**
         * 交给播放器的速率的上下限（= 整场倍速 × 漂移因子之后）。
         *
         * 它比倍速区间宽，是留给追赶余量的：0.5x 配 0.95 的因子要能跑出 0.475x，
         * 要是把这里也夹在 0.5，那条追赶就永远不生效（引擎以为设过了、播放器没变）。
         * 上限 4.0 只是防止「倍速 × 因子」因为将来的改动算出一个荒唐的值。
         */
        const val MIN_PLAYER_SPEED = 0.25f
        const val MAX_PLAYER_SPEED = 4.0f
    }
}
