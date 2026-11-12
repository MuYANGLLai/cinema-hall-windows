package com.cinemahall.core.session

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.MonotonicClock
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.network.SignalEvent
import com.cinemahall.core.network.SignalState
import com.cinemahall.core.network.Signaler
import com.cinemahall.core.protocol.Envelope
import com.cinemahall.core.protocol.EnvelopeCodec
import com.cinemahall.core.protocol.MalformedEnvelopeException
import com.cinemahall.core.protocol.MessageRouter
import com.cinemahall.core.protocol.PeerMessenger
import com.cinemahall.core.rtc.CallAudio
import com.cinemahall.core.rtc.CallAudioState
import com.cinemahall.core.rtc.LinkStats
import com.cinemahall.core.rtc.PeerDiagnostics
import com.cinemahall.core.rtc.PeerLink
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.rtc.RtcConfig
import com.cinemahall.core.rtc.RtcSignalSink
import com.cinemahall.core.rtc.TransportPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把信令与 `core:rtc` 接起来的协调者（阶段一 1.4；阶段二 2.x 改成**多链路**）。
 *
 * ── 为什么它住在 `core:session`（Phase 1 从 `app` 搬来）──────────────────────
 * 它同时认识 [Signaler]（`core:network`）和 [PeerLink]（`core:rtc`），本质上是
 * [RtcSignalSink] 的一个实现者。它不碰任何 `android.*`（时钟与线程都靠注入），
 * 因此可以搬到纯 JVM 的 `core:session`，与 `CinemaHandler` / `CinemaSyncEngine`
 * 一起受 JVM 单测覆盖 —— Phase 1 之前它住在 `app`，只因为装配点在那里；
 * 而「装配」本来就不该是它唯一能被测试的方式。
 *
 * 放进 `core:rtc` 仍会多出 `rtc ──> network` 这条 §4.1 里没有的边；
 * 放进 `core:network` 就是反方向。所以它必须住在两者之上的一层。
 *
 * ── 它负责的四件事 ──────────────────────────────────────────────────────────
 *  1. **订阅必须早于 connect**：把 `signaler.rtcSignals` 灌进对应链路的 `onSignal`。
 *     丢一条 offer 的后果是这一轮协商直接卡死（对端在等 answer），所以订阅
 *     在构造返回之前就挂上（见 [init] 里的 `UNDISPATCHED`）。
 *  2. **决定谁该有链路**：房间成员表里每个**在线的别人**一条 [PeerLink]，
 *     成员消失就收掉那一条（契约 §10「成员离开只关该链路」）。刻意**不**在链路
 *     失败后自动重试 —— 自动重试的循环会在真机上变成看不见的雪崩，而手动 [restart]
 *     只有一次。
 *  3. **DataChannel 回声**：1.4 的验收动作。收到**不是信封**的文本就回 `echo:…`，
 *     但**只回一层**（见 [onPeerMessage]），否则两台设备会互相无限对轰。
 *  4. **DataChannel 的信封桥**（1.5）：链路通了之后，这里流过的就是
 *     [EnvelopeCodec] 编出来的信封 JSON。本类负责解码并按前缀交给 [router]，
 *     同时以 [PeerMessenger] 的身份给玩法一个出站口 —— 「玩法 → 对端」这条反向
 *     路径在 1.5 才第一次被用上（`cinema.hello`）。
 *
 * ── 阶段二：一条房间 N 条链路（契约 §10）─────────────────────────────────────
 * 六个座位 ⇒ 最多 5 条 P2P 链路，**每条一个 [PeerLink] 实例**。所以本类收的是
 * 「造一条链路」的能力（[newLink]），而不是「那一条链路」：[launch] 里那个
 * `WebRtcPeerLink` 是进程级的旧写法，多人房会把 5 条链路挤进同一个
 * `PeerConnection`，症状是只有一个人能听见你。
 *
 * 每条链路配一个 [LinkSink]，把目标 peer **绑死在实例上**（契约 §6 最后一条）：
 * sink 的签名里没有 `to`，`to` 由本类在构造 sink 时给定。这样 `core:rtc` 依旧
 * 完全不认识「房间里还有谁」，它只管「我跟对面这一个」。
 *
 * `seq` 因此也变成**每条链路各自计数**（契约 §4）：一次广播会在 N 条链路上
 * 各发一份，每份带着自己那条链路的序号 —— 拿一个全局计数器去发，接收方看到的
 * 序号会跳。
 *
 * ── 为什么信封的解码放在这里，而不是放进玩法 ────────────────────────────────
 * 因为「这条消息属于哪个域」是**底座才知道的事**：`sys.*` / `rtc.*` 只走
 * WebSocket 信令，根本不该出现在 DataChannel 上；真出现了说明对端有问题，
 * 应当在这里拦掉并计数，而不是让玩法去猜。§4.2 划的正是这条线。
 *
 * ── 线程约定 ───────────────────────────────────────────────────────────────
 * 所有状态改动都在 [scope] 线程（默认主线程）上发生。[sendRtc] 用
 * `UNDISPATCHED` 正是为了**不换线程** —— `Signaler.sendRtc*` 实际是同步写 socket 的，
 * 换线程会让 `PeerLink` 承诺的「offer 先于随后的候选」重新变得不确定。
 * 因此 [notice] 只需保证那个线程上的可见性，不需要额外同步。
 */
class PeerCoordinator(
    private val myUserId: UserId,
    private val signaler: Signaler,
    /**
     * 建链工厂：**每条链路一个实例**（契约 §10）。
     *
     * 传工厂而不是实例，是因为对象数从「一条」变成「最多 N-1 条」，而 [PeerLink]
     * 的 `start` 只描述「我与对面这一个」—— 复用同一个实例给两个对端，第二次
     * `start` 会把第一条连接整个替换掉，而日志里只有一行「开始与 X 建链」。
     *
     * 生产装配给的是 `{ WebRtcPeerLink(context) }`（`core:rtc-android`）；
     * 单测给的是「返回同一个 fake」或「按 peer 记账的 fake」。
     */
    private val newLink: () -> PeerLink,
    private val router: MessageRouter,
    private val scope: CoroutineScope,
    /**
     * 某条链路的 DataChannel 刚刚 OPEN 时回调一次（每次从「非 Connected」进入
     * `Connected`）。**带 peer**：多人房间里「谁通了」是必须分开的一件事 ——
     * 不带的话，第三个人进来会让玩法以为「唯一那个对端」又通了一次。
     *
     * 玩法需要它来补发「通道可用」之后才有意义的消息 —— 1.5 的 `cinema.hello`
     * 就是第一个：`sys.members_updated` 到达时链路往往还没建好，那时发出去的 hello
     * 会直接失败且没有第二次机会。让玩法自己去轮询 [PeerLink.state] 也能做到，
     * 但那会让玩法依赖 `core:rtc` 的状态机细节，而它其实只关心「现在能发了」。
     */
    private val onPeerChannelOpened: (UserId) -> Unit = {},

    /**
     * 某条链路的 DataChannel **离开** `Connected` 时回调一次（链路断了 / 对端走了）。
     *
     * 1.6 需要它：同步引擎拿着一条「锚点 + 时钟估计」，对端不在了就没有任何东西能
     * 校准它，留着只会让诊断页显示一组没人验证过的数字。断链即清空、等下一次
     * [onPeerChannelOpened] 靠房主的 `state_snapshot` 全量恢复（§6.4）。
     */
    private val onPeerChannelClosed: (UserId) -> Unit = {},
    private val logLinesKept: Int = LOG_LINES_KEPT,

    /**
     * 凭证续签的巡检间隔（阶段二 2.1）。**测试注入点**：传 0 表示不起巡检协程，
     * 用例直接调 [checkCredentialRenewal]（否则每条用例都要真等一个巡检周期）。
     */
    private val credentialCheckIntervalMs: Long = CREDENTIAL_CHECK_INTERVAL_MS,

    /**
     * 网络事件的去抖窗口（阶段二 2.3）。**测试注入点**：传 0 让用例不必真等。
     *
     * 需要去抖是因为切网时系统会连着报好几条（`onLost(旧网)` 与 `onAvailable(新网)`，
     * 顺序还不定）。每条都重启一次 ICE 的话，一次 WiFi→蜂窝切换会打散三、四轮协商，
     * 而 ICE restart 会**清空同步锚点**（DataChannel 断开重开）。
     */
    private val networkDebounceMs: Long = NETWORK_DEBOUNCE_MS,

    /**
     * 网络中断后多久提示「可能是热点被关」（R-18）。**测试注入点**。
     */
    private val networkHintDelayMs: Long = NETWORK_HINT_DELAY_MS,

    /**
     * 两端几乎同时按「重建链路」时的去重窗口（阶段二 2.4）。**测试注入点**：传 0 = 关掉去重。
     *
     * 为什么需要它：两端同时按 ⇒ 双方都拆、都发一条 `rtc.reset`，于是 offer 方会为对端那条
     * 请求再重建一次。结果是对的（新 offer 落在对端的新连接上），只是白抖一轮、日志里多一条。
     * 这个窗口**不参与正确性**，只用来少抖一次 —— 所以它挡掉的请求只记一行日志。
     */
    private val rebuildDedupeMs: Long = REBUILD_DEDUPE_MS,

    /**
     * 单调时钟，只用于信封的 `sentAtMs`（§5.1）。
     *
     * **不要**拿它去和服务端的 `expiry` 比 —— 那是墙上时钟（见 [renewRetryNotBeforeMs]）。
     * 默认值只是单测方便；生产装配注入 `AndroidMonotonicClock`
     * （`SystemClock.elapsedRealtime()`），且与 `CinemaHandler` 拿到的是**同一个**实例。
     */
    private val clock: MonotonicClock = DefaultMonotonicClock,
) : PeerMessenger, CallAudio {

    // ── 对 UI 暴露的状态 ─────────────────────────────────────────────────────

    private val _links = MutableStateFlow<Map<UserId, PeerLink>>(emptyMap())

    /**
     * 当前**所有**链路，按对端 userId 索引（契约 §10 的 `val links: Map<UserId, PeerLink>`）。
     *
     * 这是多人房间的真相：诊断面板逐条列、`restart(peer)` 按人重建、
     * [PeerMessenger] 的广播逐条发，全部从这里取。
     *
     * 用 `StateFlow` 而不是「取一次」的普通属性，是因为界面要在「有人进来」那一刻
     * 自动多出一行 —— 而事件流式通知（`SignalEvent`）在成员表这条路上已经不再可信：
     * v2 只有全量的 `sys.members_updated`，没有逐人的 join/left。
     */
    val links: StateFlow<Map<UserId, PeerLink>> = _links.asStateFlow()

    /**
     * 聚合后的链路状态。**只要有一条通了就算 `Connected`**。
     *
     * 为什么需要聚合：[DiagnosticsScreen] / [app.ui.RtcPanel] 这些界面从 1.4 起读的就是
     * 一个 `StateFlow<PeerState>`。多人房里「通了没有」的正确口径是「有没有人和我连着」，
     * 而不是「某一条」。逐条的真值在 [links] 里，谁要看细节谁去那里拿。
     */
    private val _state = MutableStateFlow<PeerState>(PeerState.Idle)
    val state: StateFlow<PeerState> = _state.asStateFlow()

    /**
     * 「主链路」：userId 字典序最小的那一条。
     *
     * 下面几个单值访问器（[stats] / [diagnostics] / [remote] / [isOfferer] /
     * [callAudio]）是**两人房时代的兼容面**，界面还在读它们。多人房里它们只能代表
     * 一条链路，所以口径固定成「字典序最小的那个对端」，保证同一个房间里的取值稳定
     * （随 `_links` 的遍历顺序变化会让界面上的延迟数字之类每秒换一个人）。
     * 真要做逐链路诊断，那是 P5 的事：读 [links]，别再往这几个属性上加语义。
     */
    private val primary: PeerLink? get() = _links.value.entries.minByOrNull { it.key.value }?.value

    private val idleStats = MutableStateFlow<LinkStats?>(null)
    private val idleDiagnostics = MutableStateFlow(PeerDiagnostics())
    private val idleRemote = MutableStateFlow<UserId?>(null)
    private val idleIsOfferer = MutableStateFlow<Boolean?>(null)
    /** 对外发布的通话状态（由 [publishCallAudio] 维护，别直接改）。 */
    private val _callAudio = MutableStateFlow(CallAudioState())

    val stats: StateFlow<LinkStats?> get() = primary?.stats ?: idleStats

    val diagnostics: StateFlow<PeerDiagnostics> get() = primary?.diagnostics ?: idleDiagnostics

    val remote: StateFlow<UserId?> get() = primary?.remote ?: idleRemote

    /** 本端是不是 offer 方（直接转发主链路的 [PeerLink.isOfferer]）。 */
    val isOfferer: StateFlow<Boolean?> get() = primary?.isOfferer ?: idleIsOfferer

    /** 自己的操作日志（最新在上）。与首页那份「信令事件日志」分开：这一份只讲链路。 */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** §9.3 的「强制 relay-only」调试开关。 */
    private val _relayOnly = MutableStateFlow(false)
    val relayOnly: StateFlow<Boolean> = _relayOnly.asStateFlow()

    /**
     * 凭证续签的当前动作（阶段二 2.1）。null = 无事发生。
     *
     * 诊断页 ⑦ 在「凭证余期」下面显示它 —— 余期那行只说「还剩多久 / 已过期」，
     * 而「**正在重新获取**」是另一件事：R-16 要求过期时用户能看见客户端在做什么，
     * 而不是对着一行红字等。收到新配置（或索取失败）后它会被清掉/改写。
     */
    private val _credentialRenewal = MutableStateFlow<String?>(null)
    val credentialRenewal: StateFlow<String?> = _credentialRenewal.asStateFlow()

    /**
     * 网络侧的人话提示（阶段二 2.3 · R-18）。null = 无提示。
     *
     * 目前只有一条：网络中断超过 60 秒 ⇒ 「可能是手机热点已自动关闭，请检查热点设置」。
     * §9.4 明确要求这条**明示**，因为热点被省电关掉之后，客户端看到的现象与
     * 「对端退房」「服务端挂了」完全一样 —— 用户会往完全错的方向排查。
     */
    private val _networkHint = MutableStateFlow<String?>(null)
    val networkHint: StateFlow<String?> = _networkHint.asStateFlow()

    /**
     * 是否已经收到过服务端的 ICE 配置（哪怕是空的）。
     *
     * UI 显示它，是因为 [reconcile] 会**卡在这一条上不建链** —— 没有这个字段的话，
     * 「进了房间但一直不动」看起来像卡死，而实际上是在等 `sys.ice_servers`。
     */
    private val _iceConfigReceived = MutableStateFlow(false)
    val iceConfigReceived: StateFlow<Boolean> = _iceConfigReceived.asStateFlow()

    /** 服务端说有没有中继（§8.4）。null 表示还没收到配置。 */
    private val _turnAvailable = MutableStateFlow<Boolean?>(null)
    val turnAvailable: StateFlow<Boolean?> = _turnAvailable.asStateFlow()

    /**
     * 最近一次下发的 ICE 配置条数（含 STUN / TURN）。
     *
     * 连接页面 ③ 那一行原本自己订阅 `signaler.events` 数条数 —— 而 `events` 是无 replay
     * 的 `SharedFlow`，离开页面再进来就再也收不到，于是 ③ 显示「未收到 ICE 配置」、
     * 同屏 ⑦ 显示已收到，两条结论互相打脸。条数改为与 ⑦ 同源（本协调者是进程级单例，
     * `StateFlow` 会补发当前值）。
     */
    private val _iceServerCount = MutableStateFlow(0)
    val iceServerCount: StateFlow<Int> = _iceServerCount.asStateFlow()

    /** 收到对端消息就自动回一层 `echo:…`（1.4 的验收动作）。默认开。 */
    private val _autoEcho = MutableStateFlow(true)
    val autoEcho: StateFlow<Boolean> = _autoEcho.asStateFlow()

    /** 已发出的文本条数 / 已收到的条数。UI 用它们证明「真的双向通了」，而不是只看状态字。 */
    private val _sentMessages = MutableStateFlow(0)
    val sentMessages: StateFlow<Int> = _sentMessages.asStateFlow()

    private val _receivedMessages = MutableStateFlow(0)
    val receivedMessages: StateFlow<Int> = _receivedMessages.asStateFlow()

    // ── 通话音频（1.7）──────────────────────────────────────────────────────

    /**
     * 通话音频状态。**直接转发主链路** —— 这里不留第二份真相。
     *
     * ── 为什么通话的「意图」不存这一层（两人房时代的理由，如今有一半变了）──────
     * [restart] 拆掉的是 `PeerConnection`，而 [PeerLink] 这个对象**曾经**一直活着，
     * 于是「开没开、音量多少」放在它内部就天然跨得过链路重建。
     *
     * 阶段二改成「每条链路一个新实例」之后，那个前提没了：重建 = 新对象 = 意图归零。
     * 所以本层现在**确实**持有一份意图 [callIntent]（见 [setCallEnabled]）——
     * 它不是第二份真相，而是「房间级意图」，与某一条链路的落地状态是两件事：
     * 前者说「这场观影我要开麦」，后者说「这条链路此刻接没接麦克风」。
     *
     * 真正的房间级音频会话（一份采集挂 N-1 个 sender）是 P3 的 `RoomAudioSession`；
     * 在那之前，本层把同一个意图逐条下发，行为与两人房一致、多人房可用。
     */
    override val callAudio: StateFlow<CallAudioState> = _callAudio.asStateFlow()

    /**
     * 对外发布的通话状态：**意图取本层，测量值跨链路聚合**。
     *
     * ── 为什么不再直接转发主链路（2026-11-06 真机缺陷）────────────────────────
     * 原实现是 `primary?.callAudio`，而 `primary` 只是「字典序最小的那个对端」这个
     * 兼容面（见上方 KDoc）。三人房里用户按「开启通话」时，意图是**逐条**下发的，
     * 每条链路各自在自己的协程里异步发布结果；只要主链路那一条没把发布落地
     * （链路正在拆、或音频根本不走它），界面读到的 `requested` 就一直是 `false`。
     * 真机症状：「通话确实开了、也听得到，按钮却停在『开启通话』」——再点一下
     * 等于又发了一次「开」，用户永远关不掉。
     *
     * 修法沿用本文件一贯的口径：**意图只有一个来源**（[_callIntent]），链路只报测量值。
     * `sending` / `receiving` 用「任一条链路」而不是主链路，因为三人房里音频确实可能
     * 只落在另一条上；`remoteSending` 取「有一条说 true 就算 true」，免得另一个对端
     * 正常发送时状态行还说「对端没开通话」。
     */
    private fun publishCallAudio() {
        val all = _links.value.values.map { it.callAudio.value }
        val measured = _links.value.values
            .firstOrNull { it.callAudio.value.sending || it.callAudio.value.receiving }
            ?.callAudio?.value
            ?: primary?.callAudio?.value
            ?: CallAudioState()
        _callAudio.value = measured.copy(
            requested = callIntent,
            // 音量与麦克风是**用户偏好**，与 `requested` 同一档：链路只报测量值，
            // 这里一律以本层记着的意图为准（没有链路时界面读的也是它）。
            volume = callVolumeIntent,
            micVolume = micVolumeIntent,
            sending = all.any { it.sending },
            receiving = all.any { it.receiving },
            remoteSending = when {
                all.any { it.remoteSending == true } -> true
                all.any { it.remoteSending == false } -> false
                else -> null
            },
            // 包计数是 `Long?`（没读到就是 null）：有读数才相加，一条都没有时保持 null，
            // 否则界面会把「还没读过 stats」显示成「0 包」—— 那是两种完全不同的结论。
            audioPacketsSent = all.mapNotNull { it.audioPacketsSent }.takeIf { it.isNotEmpty() }?.sum(),
            audioPacketsReceived = all.mapNotNull { it.audioPacketsReceived }.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    /**
     * 开 / 关通话。**逐条下发**给当前所有链路，并把意图记下来给**将来**的链路用。
     *
     * ── 为什么这里**不**按当前值短路（1.11 修）──────────────────────────────────
     * 原来这里是 `if (rtc.callAudio.value.requested == enabled) return`。它想省的是
     * 日志（这个方法会被界面的开关反复调用），但它比的是链路**异步发布**出来的状态，
     * 不是此刻的真值。于是存在这个交错：
     *
     *  ① 用户按下「开启通话」→ 本层放行 → `setCallEnabled(true)` 只是排队，**意图还没落地**；
     *  ② 紧接着一次复位到达 → 本层读到已发布状态仍是 `false`，而 `enabled` 也是 `false`
     *     ⇒ **判定「值没变」直接 return**；
     *  ③ ① 那个动作这才跑起来，把意图置成 `true`。
     *
     * 结果是「关」被「开」吞掉。修法：**永远转发**，短路留给链路自己 —— 它那边的比较
     * 是在自己的作用域里、对着真正的意图做的，是唯一正确的短路点。
     * 本层只留一个用于去重日志的本地记忆（[callIntent]，它**参与**「新链路要不要开麦」，
     * 但不参与「这次调用要不要转发」）。
     */
    override fun setCallEnabled(enabled: Boolean) {
        if (lastCallIntent != enabled) {
            notice(
                if (enabled) {
                    "开启语音通话：建本机音轨并挂到音频发送端（不需要重新协商，音频 m 行常驻）"
                } else {
                    "关闭语音通话：摘掉音轨、释放采集、还原音频路由"
                },
            )
            lastCallIntent = enabled
        }
        // 意图先落地再下发：界面读的是本层发布的状态，不能等某条链路的协程排到。
        // 先发布这一句，正是「按了开关按钮立刻变成『关闭通话』」的来源。
        _callIntent.value = enabled
        publishCallAudio()
        for (link in _links.value.values) link.setCallEnabled(enabled)
    }

    /**
     * 设置通话音量。**刻意不记日志**：它挂在滑块上，拖一次就是几十次调用。
     * 当前值在通话状态的 `volume` 字段里，诊断面板读那里。
     */
    override fun setCallVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        callVolumeIntent = clamped
        for (link in _links.value.values) link.setCallVolume(clamped)
        // 立即重算一次发布值：**一条链路都没有时**（刚进房 / 单人调试）界面读的就是
        // 这一份。少了它，滑块会停在链路上报的默认值上，看着像「拖不动」。
        publishCallAudio()
    }

    /**
     * 麦克风**发送**音量。
     *
     * ── 为什么必须有这个方法（2026-11-07 用户报「麦克风输入控制条仍不可调节」）──────
     * `CallAudio.setMicVolume` 在接口上带默认空实现（`= Unit`），而本类之前**没有覆写它**。
     * 于是 `CinemaHandler.setMicVolume` 下发的值在这一层被静默吞掉：
     * 链路永远收不到、`publishCallAudio` 又从链路上报的读数里取值，
     * 界面读回 100% ⇒ 松手就弹回去，看起来就是「这根条不可调节」。
     * 与 [setCallVolume] 同一条规矩：**意图存在本层**，链路只报测量值。
     */
    override fun setMicVolume(volume: Float) {
        // 与 `CinemaHandler.setMicVolume` 同一个上限（桌面端 200%）。这两层任何一处
        // 写死 1f，界面上就会出现「拖过 100% 立刻弹回去」。
        val clamped = volume.coerceIn(0f, com.cinemahall.core.rtc.CallAudioState.MAX_MIC_GAIN)
        micVolumeIntent = clamped
        for (link in _links.value.values) link.setMicVolume(clamped)
        publishCallAudio()
    }

    /**
     * 通话中限制影片音量（§471 的兜底开关）。
     *
     * 记一行日志（它是一个开关，不是滑块），并把「压到多少」一起写进去 ——
     * 真机上排查「声音怎么变小了」时，这一行比翻界面快。
     */
    override fun setMovieVolumeLimit(enabled: Boolean) {
        notice(
            if (enabled) {
                "开启通话中影片音量限制：本机在发送且影片从扬声器外放时，影片音量上限 " +
                    "${(CallAudioState.MOVIE_VOLUME_LIMIT * 100).toInt()}%（§471 外放回声兜底）"
            } else {
                "关闭通话中影片音量限制：影片音量上限恢复 100%（音量本身不会自动变回去）"
            },
        )
        for (link in _links.value.values) link.setMovieVolumeLimit(enabled)
    }

    // ── 内部状态 ───────────────────────────────────────────────────────────

    /**
     * 房间级的通话意图：新链路建起来时按它决定「要不要一开始就把麦克风接进来」。
     *
     * 没有它的话，「先按开麦、后有人进房」这条路上新对端永远听不见 —— 而界面上
     * 开关明明是开着的（它读的是主链路的状态，主链路是别人）。
     */
    private val _callIntent = MutableStateFlow(false)
    /** 房间级意图的真值（[publishCallAudio] 与 [startLink] 都读它）。 */
    private val callIntent: Boolean get() = _callIntent.value

    /**
     * 通话音量的**用户偏好**（0..1）。与 [callIntent] 同一档：它是房间级的意图，
     * 新链路建起来时要照它下发（否则「先调好 30%、后有人进房」那条路上新对端是满音量）。
     */
    @Volatile
    private var callVolumeIntent: Float = CallAudioState.DEFAULT_VOLUME

    /**
     * 麦克风发送音量的用户偏好（0..1）。同上。
     *
     * ⚠️ 这个字段存在本身就是一次缺陷的证据：`CallAudio.setMicVolume` 有默认空实现，
     * 本类此前没覆写它 ⇒ 界面拖的每一个值都在这一层蒸发（见 [setMicVolume]）。
     */
    @Volatile
    private var micVolumeIntent: Float = 1f

    /**
     * 上一次**由本层下达**的通话意图，只用于去重日志。
     *
     * ⚠️ 它**不参与「这次调用要不要转发」的判断** —— 真正的短路在链路里
     * （那里比的是自己的意图，是同步真值）。拿它当短路条件会重现 1.11 修掉的那个竞态。
     */
    private var lastCallIntent: Boolean? = null

    /** 每条链路「上一条状态是不是 Connected」。用来把「离开 Connected」变成一次性回调。 */
    private val linkConnected = mutableMapOf<UserId, Boolean>()

    /** 每条链路的两个收集协程（消息 + 状态）。收链路时一并取消。 */
    private val linkJobs = mutableMapOf<UserId, List<Job>>()

    private var iceServers: List<IceServer> = emptyList()

    /**
     * 已经为**哪一个过期时刻**索取过新凭证（阶段二 2.1）。同一时刻只索取一次。
     *
     * 服务端的续签是「按同一判据推」的（`signaling.mjs` 的 5s sweep 在
     * `expiry - renewBeforeSec` 起主动推 `sys.ice_servers`），所以正常情况下
     * 客户端这一层根本轮不到动作。这个去重是为了让「服务端没推成」那条兜底路
     * **不会变成每轮巡检都打一次信令**：拿到新凭证后 `expiry` 会跳变，判据自然复位。
     */
    private var renewAskedForExpiry: Long? = null

    /**
     * 索取失败后的退避截止时刻（墙上时钟毫秒）；0 = 没有退避。
     *
     * 只在**失败**路径上生效：成功索取之后不该拦任何东西 —— 服务端随后就会回一份
     * 新配置（`expiry` 跳变、判据自然复位），而万一它回的还是临期凭证，
     * 那说明服务端那边有问题，此时再索取一次正是该做的事。
     *
     * ⚠️ 刻意用 `System.currentTimeMillis()` 而不是注入的 [clock]（单调）：
     * 时基本来就必须是墙上时钟 —— 凭证的 `expiry` 来自服务端的墙上时钟
     * （`turn.mjs` 的 `mintTurnCredential`），拿单调时钟去比它才是错的。
     */
    private var renewRetryNotBeforeMs: Long = 0L

    /** 网络事件的去抖任务：每来一条事件就取消上一个、重新计时（阶段二 2.3）。 */
    private var networkReactionJob: Job? = null

    /** R-18 的「60 秒还没恢复」计时任务。 */
    private var networkHintJob: Job? = null

    /**
     * DataChannel 出站序号，**每条链路一份**（契约 §4：删掉全局 `outboundSeq`）。
     *
     * 每条链路 = 一次独立会话，接收方按 `envelope.seq` 判断「有没有漏」；共用
     * 一个计数器会让对端看到 1、3、4、7 这样的空洞，而它没有别的办法区分
     * 「漏了一条」和「那条发给别人了」。
     */
    private val outboundSeq = mutableMapOf<UserId, Long>()

    /** 出站信令失败计数。见 [onSendFailed]：候选太多，失败不能逐条刷屏，只能计数。 */
    private var pendingSendFailures = 0

    private var sendFailureReported = false

    /**
     * 上一次「本端亲手重建**这条链路**」的时刻（[System.currentTimeMillis]，与
     * [checkCredentialRenewal] 同一口径 —— 那是墙钟；本类的单调时钟 [clock]
     * 只用于信封的 `sentAtMs`）。按 peer 分会，否则在 6 人房里「刚和 A 重建过」
     * 会把 B 的请求也一起挡掉。0 表示从没重建过。
     */
    private val lastRebuildAtMs = mutableMapOf<UserId, Long>()

    init {
        // ⚠️ 这几条订阅必须在**构造返回之前**挂上。依据 `Signaler.rtcSignals` 的 KDoc：
        // SharedFlow 在没有订阅者时发出的值直接丢弃、不补发。普通 `launch` 只是把
        // 协程排进队列 —— 那个窗口里 `connect()` 之后服务端已经把 offer 推过来了。
        // `UNDISPATCHED` 让 `collect` 在本行内就完成注册（共享流的注册是同步的）。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaler.rtcSignals.collect { signal ->
                // `rtc.reset` 是**链路生命周期**的事（拆掉 + 重建），归协调者，不进 `PeerLink`：
                // `core:rtc` 只管一条连接内部的协商，它不知道「重建之后还要重新 start」这件事
                // （`close()` 之后连 `isOfferer` 都是 null，得由这里按 userId 重新算）。
                if (signal is RtcSignal.Reset) onRtcReset(signal) else dispatchRtc(signal)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaler.events.collect { onSignalEvent(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaler.room.collect { reconcile(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // 信令一恢复就把 R-18 的提示清掉。判据刻意只看**信令**：网络中断时
            // `WsSignaler` 自己会退避重连，它重新 Online 就说明网络回来了；
            // 而链路要不要重建是另一件事（下面 [reactToNetworkChange] 会重启 ICE）。
            signaler.state.collect { state ->
                if (state == SignalState.Online && _networkHint.value != null) {
                    _networkHint.value = null
                    notice("网络已恢复，清掉热点提示（R-18）")
                }
            }
        }

        notice("RTC 协调者就绪，本机 userId = ${short(myUserId)}")

        // TURN 凭证续签巡检（阶段二 2.1）。`credentialCheckIntervalMs <= 0` 时不起 ——
        // 单测直接调 [checkCredentialRenewal]，不必为每条用例真等一个巡检周期。
        if (credentialCheckIntervalMs > 0) {
            scope.launch {
                while (isActive) {
                    delay(credentialCheckIntervalMs)
                    checkCredentialRenewal()
                }
            }
        }
    }

    // ── 给 UI 的动作 ───────────────────────────────────────────────────────

    /**
     * 丢掉链路、与对端从头再建一次 —— **两端一起**（阶段二 2.4，2026-10-14）。
     *
     * 这是「链路失败」的唯一补救入口。做成手动而不是自动重试，是因为自动重试
     * 撞上「对端也不在」时会在两端各形成一条看不到的循环，而症状只是耗电与发热。
     *
     * [peer] 为 null = **全部链路**（契约 §10 的 `restart(peer: UserId? = null)`）。
     * 6 人房里「重建全部」是界面遍历一遍，不是一个特殊动作 —— 它下面走的是同一条路。
     *
     * ── answer 方为什么曾经不能照做（2026-10-08 真机实测抓出，判据至今成立）──────
     * offer **只可能由 offer 方发出**。answer 方拆掉自己这半边之后，唯一的指望是
     * 「对端恰好会再发一个 offer」—— 而对端那个 `PeerConnection` 好好的、没有任何东西
     * 会触发它重发。结果是 answer 方永久停在「协商中（在换 SDP / ICE）」，**日志里连一条
     * 错误都没有**。真机表现：同一台设备连点四次「重建链路」，四次都在等 offer
     * （§3.5 缺陷 2 有完整证据）。
     *
     * 所以现在两端走**同一条路**，只是各自多做一件事：**先发一条 `rtc.reset`**
     * （§5.2：语义是「我这半边已经拆了，请你也拆」），再拆本端、再 `start()`。
     * 对端收到之后做同样的事（[onRtcReset]）—— 于是两端都是全新的 `PeerConnection`，
     * 由 userId 字典序定下的 offer 方发新 offer、另一方摆好新连接等 offer。
     *
     * **发信顺序是协议的一部分**：先发 `rtc.reset`、再拆再建。新 offer 因此必然排在这条
     * 消息之后到达，落在对端的新连接上；反过来（先建后发）会留下一条没人回答的 offer，
     * 症状与「一直停在协商中」同形。
     */
    fun restart(peer: UserId? = null) {
        if (peer != null) {
            if (!_links.value.containsKey(peer)) {
                notice("与 ${short(peer)} 还没有链路，无从重建")
                return
            }
            notice("手动重建与 ${short(peer)} 的链路（两端一起拆）")
            rebuildLink(peer, reason = RESET_REASON_MANUAL, notifyPeer = true)
            return
        }

        val peers = _links.value.keys.sortedBy { it.value }
        if (peers.isEmpty()) {
            notice("还没有绑定的对端，先等对端上线（或它进房）")
            return
        }
        notice("手动重建全部链路（${peers.size} 条，两端一起拆）")
        for (target in peers) rebuildLink(target, reason = RESET_REASON_MANUAL, notifyPeer = true)
    }

    /**
     * 「两端一起拆掉重来」的唯一实现 —— [restart]（本机按）与 [onRtcReset]（对端请求）都走它。
     *
     * [notifyPeer] = 先发一条 `rtc.reset` 再拆。**应 / 对端的请求时必须是 false**：
     * 回发一条就变成两端互相对拆（§5.2 的第一条硬约定）。
     */
    private fun rebuildLink(peer: UserId, reason: String, notifyPeer: Boolean) {
        if (notifyPeer) {
            sendRtc(peer, "rtc.reset", "重建请求（rtc.reset，to=${short(peer)}，reason=$reason）") {
                signaler.sendRtcReset(peer, reason)
            }
        }
        // 打点必须在 `close()` 之前：`close()` 之后本函数只剩两行，越靠前越不容易漏。
        lastRebuildAtMs[peer] = System.currentTimeMillis()
        // 拆掉**这个 peer** 那条链路（不动别人）。新链路由 [startLink] 造一个新实例：
        // 复用同一个 `PeerLink` 也能跑，但那样 `callRequested`、诊断计数、`isOfferer`
        // 都会跨重建活下来，而它们在重建后按定义都该是新的。
        teardown(peer, reason = "重建链路（${short(peer)}）", notifyClosed = false)
        startLink(peer)
    }

    /**
     * 对端请求整条链路重建（`rtc.reset`，阶段二 2.4）。
     *
     * 动作与 [restart] 相同，只有两点不一样：
     *  ① **不回发**这条消息（否则两端对拆，形成风暴）；
     *  ② **去重** —— 两端几乎同时按时，本端刚重建过就不必再来一次（那时对端也在重建，
     *     它的新 offer 正在路上）。窗口由 [rebuildDedupeMs] 给，传 0 即关掉。
     *
     * 绑定关系不成立时（对端已走、或消息来自第三人）只记一行日志：这时**不能**重建 ——
     * 那会把一条刚建好的链路拆掉。多人房里这条判据比两人房更要紧：第三个人
     * 发一条 `rtc.reset` 过来，绝不能把另外两条链路一起拆了。
     */
    private fun onRtcReset(signal: RtcSignal.Reset) {
        val peer = signal.from
        if (!_links.value.containsKey(peer)) {
            notice("收到 ${short(peer)} 的重建请求，但它不是当前链路之一 —— 忽略")
            return
        }

        val since = System.currentTimeMillis() - (lastRebuildAtMs[peer] ?: 0L)
        // `since < 0`（系统时钟被改）不挡：宁可多抖一轮，也不要因为时间跳变把请求吞掉。
        if (rebuildDedupeMs > 0 && since in 0L until rebuildDedupeMs) {
            notice("对端 ${short(peer)} 请求重建，但本端 ${since}ms 前刚重建过 —— 忽略（两端几乎同时按）")
            return
        }

        notice("对端 ${short(peer)} 请求重建链路（reason=${signal.reason ?: "-"}）：两端一起拆掉重来")
        rebuildLink(peer, reason = RESET_REASON_PEER, notifyPeer = false)
    }

    /** 显式重启 ICE。offer 方会随即发新 offer；非 offer 方改为请求对端重协商。 */
    fun restartIce() {
        if (_links.value.isEmpty()) {
            notice("还没有链路，无从重启 ICE")
            return
        }
        // §3.7.7：换了凭证**必须**走这一步才生效，libwebrtc 不会自己重取 iceServers。
        notice("显式重启 ICE（${_links.value.size} 条链路；换凭证后必须走这一步，光下发不生效）")
        for (link in _links.value.values) link.restartIce()
    }

    fun setRelayOnly(enabled: Boolean) {
        if (_relayOnly.value == enabled) return
        _relayOnly.value = enabled
        notice(
            if (enabled) {
                "已切到「强制中继」(relay-only)：此后只可能成功于 TURN 路径 —— 这是 §3.7.3 把中继当默认数据面的正面验证"
            } else {
                "已切回「全部候选」(host / srflx / relay)"
            },
        )
        val policy = if (enabled) TransportPolicy.RELAY_ONLY else TransportPolicy.ALL
        for (link in _links.value.values) link.setTransportPolicy(policy)
    }

    fun setAutoEcho(enabled: Boolean) {
        if (_autoEcho.value == enabled) return
        _autoEcho.value = enabled
        notice(if (enabled) "自动回声：开" else "自动回声：关（只收不回）")
    }

    // ── TURN 凭证续签（阶段二 2.1）─────────────────────────────────────────

    /**
     * 巡检一次「当前凭证该不该续」。**幂等**，可以在任何线程安全的时机重入。
     *
     * ── 为什么客户端还要自己管这件事（§3.7.7 必做 ①）─────────────────────────
     * 服务端已经有主动续签（`signaling.mjs` 的 5s sweep：在
     * `credentialExpiry - renewBeforeSec` 起推一条 `sys.ice_servers`），正常情况下
     * 这一层根本不会触发。但那条路有三个前提：连接还在、sweep 那一刻座位是 online 的、
     * 推送没有在抖动的链路上丢掉。任何一条不成立，凭证就会**静默**走到过期 ——
     * 而症状不是报错，是「重连时中继候选少一条」，看起来只是连得慢。
     *
     * 这里补的是「客户端自己也知道凭证什么时候过期」这半段：临期或已过期时，
     * 复用 `sys.resume`（服务端那条路会重签凭证并把 `iceServers` 一起回执，
     * 见 `signaling.mjs` 的 `handleResume`）主动索取一次。
     *
     * ── 为什么复用 resume 而不是新加一条「拉凭证」消息 ────────────────────────
     * 新增消息类型要改服务端并**重新部署**，而 `sys.resume` 这条路已经完全够用：
     * `rooms.resume` 只要求座位还在（同一连接上重发一次是幂等的），回执
     * `sys.room_joined` 本来就带 `iceServers`。代价是它会顺带派发一次
     * `sessionRestored`（玩法层 `applyHost` + `resetSyncState`）—— 这次重入是幂等的，
     * 且它就发生在一次 ICE restart 之前（旧凭证本来就要作废），可以接受。
     *
     * ── 收到新凭证之后呢 ──────────────────────────────────────────────────────
     * 不需要在这里做任何事：[onSignalEvent] 收到 `IceServersUpdated` 就会
     * 逐条链路 `setIceServers(...)`，而它内部是 `setConfiguration` + `restartIce()`
     * ——「换凭证 → 重启 ICE」这条链 1.4 起就是通的（§3.7.7 必做 ① 的后半段）。
     */
    internal suspend fun checkCredentialRenewal() {
        val expiry = IceServer.earliestExpiryEpochSeconds(iceServers)
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1_000

        if (!renewalDue(expiry, nowSec, RENEW_LEAD_SECONDS)) return
        // 服务端说没有中继（或还没收到配置）：`expiry` 本来就是 null/0，索取也拿不到东西。
        if (_turnAvailable.value != true) return
        if (signaler.state.value != SignalState.Online) return
        val room = signaler.room.value ?: return

        // 同一过期时刻只索取一次；失败后的重试受最小间隔约束（见两个字段的 KDoc）。
        if (renewAskedForExpiry == expiry) return
        if (nowMs < renewRetryNotBeforeMs) return

        renewAskedForExpiry = expiry

        val expired = nowSec >= expiry!! // renewalDue 已保证非 null
        _credentialRenewal.value = if (expired) {
            "凭证已过期，正在重新获取…"
        } else {
            "临期（还剩 ${expiry - nowSec} 秒），正在提前索取新凭证…"
        }
        notice(
            if (expired) {
                "中继凭证已过期，正在重新获取（§9.4 R-16）"
            } else {
                "中继凭证临期（还剩 ${expiry - nowSec} 秒），主动索取新凭证（§3.7.7 必做 ①）"
            },
        )

        try {
            signaler.resume(room.roomId, myUserId, signaler.sessionEpoch)
            // 成功与否由随后的 `IceServersUpdated` 决定（服务端在回执里带新配置）。
            // 这里不判定「拿到了没有」—— 那要解析回执，等于把协议抄第二遍。
        } catch (error: Exception) {
            _credentialRenewal.value = "索取失败：${error.message ?: error::class.simpleName}，稍后重试"
            notice("主动索取中继凭证失败：${error.message ?: error::class.simpleName}")
            // 放掉去重，让下一轮巡检能重试；但先退避一段时间，否则信令一断
            // 就会在每一轮巡检上各打一次（每 15s 一条失败日志）。
            renewAskedForExpiry = null
            renewRetryNotBeforeMs = nowMs + RENEW_RETRY_BACKOFF_MS
        }
    }

    // ── 网络变化（阶段二 2.3）──────────────────────────────────────────────

    /**
     * 系统网络发生了变化。**任意线程可调**（网络回调在系统线程上），内部自己切回 [scope]。
     *
     * 这里只做去抖，真正的反应在 [reactToNetworkChange]。去抖窗口内连续来的事件
     * （切网时 `onLost` 与 `onAvailable` 会连着来、顺序还不定）合并成一次反应 ——
     * 每条都反应的话，一次 WiFi→蜂窝切换会连打三、四轮 ICE restart，而每一轮都会
     * 打散 DataChannel、清空同步锚点（症状是「切个网，画面卡十几秒」）。
     */
    fun onNetworkEvent() {
        scope.launch {
            networkReactionJob?.cancel()
            networkReactionJob = scope.launch {
                delay(networkDebounceMs)
                reactToNetworkChange()
            }
        }
    }

    /**
     * 网络变化的实际反应（§9.4 的第一行）。
     *
     * ── 为什么两端都走 `restartIce()` ────────────────────────────────────────
     * §9.4 写的是「房主发起 `restartIce()` + 重新协商、从端等待新 offer」。而
     * `restartIce()` 在两端本来就不是同一件事（见 `WebRtcPeerLink.restartIceInternal`）：
     * offer 方发新 offer，非 offer 方走 `requestRenegotiation(reason = ice)` 请对端发起。
     * 后者**不拆链**，正是「从端等待新 offer」的合规实现 —— 从端自己发不出 offer，
     * 不请对端的话它就永远等不到那一个。
     *
     * ── 为什么没有链路时什么都不做 ────────────────────────────────────────────
     * 还没建链 / 对端不在：`restartIce()` 会写一条「还没有链路，无从重启 ICE」的日志，
     * 除了刷屏没有任何作用。而网络变化**必然**会伴随信令的退避重连与 resume，
     * 那条路自己会把链重新建起来。
     */
    internal fun reactToNetworkChange() {
        if (_links.value.isNotEmpty()) {
            notice("网络变化：重启 ICE 并重新协商（§9.4，${_links.value.size} 条链路）")
            for (link in _links.value.values) link.restartIce()
        } else {
            notice("网络变化：当前没有链路，等对端上线后重建（§9.4）")
        }
        startNetworkHintCountdown()
    }

    /**
     * R-18：网络中断后 60 秒仍未恢复 ⇒ 提示「可能是手机热点已自动关闭」。
     *
     * 判据只看信令是否恢复 Online：`WsSignaler` 的退避重连是「网络回来了」最可靠的
     * 证据（它比链路层更早、也更不容易被别的原因骗到）。链路层没通但不提示 ——
     * 那种情况该走「重建链路」，不是热点的问题。
     */
    private fun startNetworkHintCountdown() {
        networkHintJob?.cancel()
        networkHintJob = scope.launch {
            delay(networkHintDelayMs)
            if (signaler.state.value != SignalState.Online) {
                _networkHint.value = "网络已中断超过 60 秒，可能是手机热点被省电自动关闭，请检查热点设置"
                notice("网络已中断超过 60 秒（§9.4 R-18）：请检查手机热点设置")
            }
        }
    }

    /**
     * 发一条**纯文本**给所有对端（1.4 的验收动作）。没有任何链路 OPEN 时返回 false ——
     * 不排队、不假装成功。
     *
     * 刻意不叫 `send`：那样会与 [PeerMessenger.send]（`send(type, payload = null)`）
     * 构成重载，而 `send("hi")` 到底指哪一个将由编译器猜 —— 猜错时不会报错，
     * 只会把一条明文当成 type 发出去。名字分开是最省事的防呆。
     */
    fun sendText(text: String): Boolean {
        if (text.isBlank()) return false
        var any = false
        for (link in _links.value.values) {
            if (link.send(text)) any = true
        }
        if (any) {
            _sentMessages.value += 1
            notice("已发出 #${_sentMessages.value}：${preview(text)}")
        } else {
            notice("发送失败：DataChannel 未 OPEN（链路还没通，或对端已经走了）")
        }
        return any
    }

    fun refreshStats() {
        for (link in _links.value.values) link.refreshStats()
    }

    /**
     * 少见的「已知绝境」：不要再等，直接把链路收掉。
     *
     * [peer] 为 null = 全部（契约 §10 的 `closeLink(peer: UserId? = null)`）。
     */
    fun closeLink(peer: UserId? = null) {
        if (peer != null) {
            if (!_links.value.containsKey(peer)) return
            teardown(peer, reason = "手动收掉与 ${short(peer)} 的链路")
            return
        }
        if (_links.value.isEmpty()) return
        notice("手动收掉全部链路（${_links.value.size} 条）")
        for (target in _links.value.keys.toList()) teardown(target, reason = null)
    }

    // ── 信令事件 ───────────────────────────────────────────────────────────

    private suspend fun onSignalEvent(event: SignalEvent) {
        if (event !is SignalEvent.IceServersUpdated) return

        _turnAvailable.value = event.turnAvailable
        val first = !_iceConfigReceived.value
        val changed = event.servers != iceServers
        iceServers = event.servers
        _iceServerCount.value = event.servers.size
        _iceConfigReceived.value = true

        notice(
            if (first) {
                "收到 ICE 配置：${event.servers.size} 条，中继${if (event.turnAvailable) "可用" else "不可用"}"
            } else {
                "ICE 配置更新：${event.servers.size} 条（多半是凭证续签）"
            },
        )

        // ⚠️ `first` **必须**在这里放行，这不是多余判断 —— 删掉它的后果是「进房了、
        // 界面一切正常、就是永远连不上」，而且一条错都不报。
        //
        // ── 那条死路的完整链条（2026-11-06，桌面车道先撞到，Android 同一份代码）──
        //  ① 服务端在建房 / 进房应答里是**先写 `signaler.room`、再抛这条事件**，所以
        //     第一次 `reconcile` 必然早于本函数 ⇒ 它撞上「还没收到 ICE 配置」而
        //     `continue`，那条待办只存在于「本函数末尾再 reconcile 一次」这一步里；
        //  ② 没有中继的部署（本机 staging / 局域网 / 只有 STUN）下服务端下发的是
        //     **空数组**，而 `iceServers` 的初值也是 `[]` ⇒ `changed == false`；
        //  ③ 于是这里早退，②里那条待办 reconcile **永远没有第二个触发点**：
        //     成员表再变才会重来，而两人房里它已经不会再变了。
        // 结果是链路一条都不建，`linkUp` 永远 false，诊断页只显示「等 ICE 配置」——
        // 而配置其实早就到了，只是「到了」被当成「没变」。
        //
        // 反过来说，「配置没变」只在**已经收到过配置之后**才允许早退：那时链路该建的
        // 早建了，重复 reconcile 只是空转。所以判据是 `!changed && !first`，
        // 不是 `!changed`。
        if (!changed && !first) return

        // 续签兜底路（阶段二 2.1）：真的换了新配置 ⇒ 「正在重新获取…」这条状态不再成立。
        // 放在 `changed` 判据之后是有意的：推来一份**一模一样**的配置说明并没有换到凭证，
        // 那时清掉反而会让诊断页假装续签成功了。
        _credentialRenewal.value = null

        // §3.7.7：libwebrtc **不会**因为 iceServers 变了就自己重取。`setIceServers`
        // 内部会 `setConfiguration` + `restartIce()`；还没有链路时它只是把配置记下来，
        // 等 `start()` 用（`currentConfig()` 读的就是上面那个 `iceServers`）。
        for (link in _links.value.values) link.setIceServers(event.servers)

        // 之前因为「没收到 ICE 配置」而没建成的链，现在可以建了。
        reconcile(signaler.room.value)
    }

    // ── 房间 → 链路 ────────────────────────────────────────────────────────

    /**
     * 把「房间当前长什么样」映射成「哪几条链路该存在」。**幂等**，可以随便重入。
     *
     * 阶段二是**按成员表 diff**（契约 §10）：
     *  - 成员表里没有了 ⇒ 收掉那条链路（离开 / 被 `sweep` 摘掉）；
     *  - 成员表里有、在线、本地还没有 ⇒ 建一条；
     *  - 成员表里有但**离线** ⇒ 先不建。**已有的链路也不拆** —— §5.4 规定座位保留，
     *    对端信令断线时它只是「离线」，而那一刻已建立的 P2P 链路往往还活着
     *    （信令与媒体是两条路）。拆掉重连是纯粹的自损。
     *
     * ── 为什么不需要「谁是 offer 方」的额外约定 ─────────────────────────────────
     * 两端都会为对方建链，但 [PeerLink.start] 内部按 userId 字典序定角色
     * （小者发 offer），两端各算一次结果必然一致，于是不会出双发 offer（glare）。
     */
    private fun reconcile(room: Room?) {
        if (room == null) {
            // ── 房间没了 = 这一场会话结束：**无条件**收掉所有链路（1.11 修，阶段二推广到 N 条）──
            // 这里可以无条件收：`room == null` 只可能是「本机退房 / 房间被解散 /
            // 信令层自己清了房间视图」三种，三种都意味着这一场会话结束了。
            // 拔网**不会**走到这里（`WsSignaler` 刻意保留 `_room` 去 resume）。
            closeAllLinks("已离开房间，收掉全部链路")
            return
        }

        val seats = room.members.filter { it.userId != myUserId }
        val present = seats.mapTo(mutableSetOf()) { it.userId }

        // 1) 成员表里没有的 ⇒ 收链路。「成员离开只关该链路」（契约 §10），
        //    其余人的链路一条都不许动 —— 6 人房里一个人退房不能把整局打断。
        for (peer in _links.value.keys.toList()) {
            if (peer !in present) {
                notice("${short(peer)} 已不在成员表里，收掉这条链路")
                teardown(peer, reason = null)
            }
        }

        // 2) 新出现且在线的 ⇒ 建链。离线的不建，但每来一条 members_updated 都记一行
        //    会刷屏，所以只在「第一次看到这个人」时记（判据是 outboundSeq 里没它）。
        for (seat in seats) {
            if (_links.value.containsKey(seat.userId)) continue
            if (!seat.online) {
                if (!outboundSeq.containsKey(seat.userId)) {
                    outboundSeq[seat.userId] = 0L
                    notice("对端 ${short(seat.userId)} 当前不在线，等它上线再建链")
                }
                continue
            }
            if (!_iceConfigReceived.value) {
                // 不等它的话，第一轮 offer 是在「一条 STUN/TURN 都没有」的前提下产生的，
                // 候选只有内网地址；等配置到了又必须再 restartIce 一轮。真机上表现为
                // 「偶尔要连两次才连上」，而且第一次还会把对端的候选集合弄脏。
                //
                // ⚠️ 这里 `continue` 掉的人，**只能靠** [onSignalEvent] 末尾那次 reconcile
                // 叫醒 —— 所以那条路上「首份配置必须放行」不是可选项（见那里的注释）。
                notice("等 ICE 配置（sys.ice_servers）到齐再建链")
                continue
            }
            startLink(seat.userId)
        }
    }

    /**
     * 造一条链路并接上四条订阅。
     *
     * 订阅**必须早于 `start()`**：`PeerLink.messages` 与 `state` 都是 `SharedFlow` /
     * StateFlow，前者在没有订阅者时发出的值直接丢弃 —— 而 DTLS 一旦跑完，
     * 对端的 `cinema.hello` 就在那一刻到，晚了就永远收不到（hello 没有重发）。
     */
    private fun startLink(peer: UserId) {
        if (_links.value.containsKey(peer)) return

        val link = newLink()
        // 出站序号归零：一次链路 = 一次会话（契约 §4）。
        outboundSeq[peer] = 0L
        _links.value = _links.value + (peer to link)

        val jobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // `messages` 是 SharedFlow，同样要早订阅（见本函数 KDoc）。
                link.messages.collect { onPeerMessage(peer, it) }
            },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // `state` 是 StateFlow，会先补发当前值；`Connected` 是 data object，
                // 重复值被 StateFlow 自己去重，所以这里恰好是「每次进入 Connected 回调一次」。
                //
                // `linkConnected` 是为了让「离开 Connected」也只回调一次：其他状态之间
                // 来回变化（`IceRestarting` → `Negotiating` 之类）与链路是否通无关，
                // 每次都通知一遍会让玩法把同一件事重复清空。
                link.state.collect { state -> onLinkState(peer, state) }
            },
            // 链路自己会在协程里异步发布通话读数；对外状态由本层重算（见 publishCallAudio）。
            // 少了这一条，某条链路「开着麦」的事实永远进不了界面的聚合读数 —— 三人房里
            // 音频只落在非主链路上时，按钮就会停在「开启通话」。
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.callAudio.collect { publishCallAudio() }
            },
        )
        linkJobs[peer] = jobs

        val offerer = myUserId.value < peer.value
        notice(
            "开始与 ${short(peer)} 建链（当前 ${_links.value.size} 条）；" +
                "本端${if (offerer) "是 offer 方" else "等对方 offer"}" +
                "（角色由 userId 字典序定，与谁是房主无关）",
        )
        link.start(myUserId, peer, currentConfig(), LinkSink(peer))

        // 房间级的通话意图要跟着新链路走：没有这一句，「先按开麦、后有人进房」
        // 那条路上新对端永远听不见（而界面上的开关是开着的，因为它读的是主链路）。
        if (callIntent) link.setCallEnabled(true)
        // 音量同一条理由：新链路默认是 100% / 默认值，而用户可能早就调过。
        link.setCallVolume(callVolumeIntent)
        link.setMicVolume(micVolumeIntent)
        // 新链路一登记就重算一次：`primary` 可能换了人，按钮读的聚合状态要跟着换。
        publishCallAudio()
        if (_relayOnly.value) link.setTransportPolicy(TransportPolicy.RELAY_ONLY)
        if (iceServers.isNotEmpty()) link.setIceServers(iceServers)
    }

    /**
     * 收掉一条链路：取消订阅 → `close()` → 从 [links] 摘掉。
     *
     * [notifyClosed] 为 true 且这条链路此前是 `Connected` 时补一次 [onPeerChannelClosed]：
     * 正常拆链时 state 会走到 `Closed`、[onLinkState] 自己会触发回调；但外部
     * 主动拆（重建 / 手动收掉）之后我们**立刻**取消了订阅，状态变化可能一次都收不到。
     * 玩法那边的同步锚点必须清掉，否则会拿着一组没人验证的数字继续用。
     */
    private fun teardown(peer: UserId, reason: String?, notifyClosed: Boolean = true) {
        val link = _links.value[peer] ?: return
        if (reason != null) notice(reason)
        val wasConnected = linkConnected[peer] == true
        linkJobs.remove(peer)?.forEach { it.cancel() }
        link.close()
        _links.value = _links.value - peer
        linkConnected.remove(peer)
        recomputeState()
        // 走掉的那条可能正是主链路：按钮的聚合状态必须立刻跟着换人。
        publishCallAudio()
        if (notifyClosed && wasConnected) onPeerChannelClosed(peer)
    }

    /**
     * 收掉全部链路并清掉房间级的通话意图。
     *
     * 清意图这件事**只在这里**做：它是「这一场会话结束了」的语义，而单条链路消失
     * （别人退房）不该让本机麦克风跟着关掉 —— 6 人房里有人走，剩下的人还在看。
     */
    private fun closeAllLinks(reason: String) {
        if (_links.value.isNotEmpty()) notice("$reason（${_links.value.size} 条）")
        for (peer in _links.value.keys.toList()) teardown(peer, reason = null)
        // `callIntent` 与 `lastCallIntent` 一起归零：下一场会话从「关」开始，
        // 无论上一场是怎么结束的（1.11 那条「进房仍自动开通话」的结构性修法）。
        _callIntent.value = false
        lastCallIntent = false
        publishCallAudio()
    }

    /** 某条链路的状态变了：更新聚合状态、必要时派发「通了 / 断了」回调。 */
    private fun onLinkState(peer: UserId, state: PeerState) {
        val connected = state is PeerState.Connected
        val was = linkConnected[peer] ?: false
        linkConnected[peer] = connected
        recomputeState()
        if (connected && !was) {
            onPeerChannelOpened(peer)
        } else if (!connected && was) {
            onPeerChannelClosed(peer)
        }
    }

    /**
     * 聚合口径：**有人通就算通**。没有链路时回 `Idle`。
     *
     * 顺序是语义：`Connected` > `Negotiating` > `Failed` > `Idle`。
     * `Negotiating` 排在 `Failed` 前面，是因为多人房里「一条失败、一条正在连」
     * 的正确结论是「还在连」，而不是「失败了」。
     */
    private fun recomputeState() {
        val states = _links.value.values.map { it.state.value }
        _state.value = when {
            states.any { it is PeerState.Connected } -> PeerState.Connected
            states.any { it is PeerState.Negotiating } -> PeerState.Negotiating
            states.firstOrNull { it is PeerState.Failed } != null ->
                states.first { it is PeerState.Failed }
            states.isEmpty() -> PeerState.Idle
            else -> PeerState.Idle
        }
    }

    private fun currentConfig() = RtcConfig(
        iceServers = iceServers,
        transportPolicy = if (_relayOnly.value) TransportPolicy.RELAY_ONLY else TransportPolicy.ALL,
    )

    /**
     * 把一条入站 `rtc.*` 交给**它来自的那条链路**。
     *
     * ── 为什么「还没有链路」时要先补建一条（阶段二加）─────────────────────────────
     * `PeerLink.onSignal` 第一句就是 `if (signal.from != peerUserId) return` —— 而
     * `peerUserId` 是 [PeerLink.start] 才设的。所以给一个**没启动过**的链路喂 offer
     * 等于什么都没发生，且**没有任何日志**：症状就是「这一轮协商永远不开始」。
     *
     * 多人房里这个窗口是真实存在的：两端都在等 `sys.members_updated`，
     * 谁先看到谁是不确定的 —— 先看到的那一端可能已经把 offer 发过来了，
     * 而这一端还在一秒前的成员表上。两人房时代只有一个实例、同样会丢，但那时
     * 丢一条 offer 还有「重建链路」能救；六人房里那个按钮是按人来的，
     * 用户根本不知道该点谁。所以这里**按需补建**：座位在线、ICE 也到齐就建，
     * 建完立刻把它交给这条新链路（订阅早于 `start`，`start` 又会先把 `peerUserId` 设好）。
     *
     * 仍然丢弃的情况只有一种：这个人不在房间里（或已离线）—— 那就是真的该丢。
     */
    private fun dispatchRtc(signal: RtcSignal) {
        _links.value[signal.from]?.let { link ->
            link.onSignal(signal)
            return
        }

        val seat = signaler.room.value?.members?.firstOrNull { it.userId == signal.from }
        if (seat == null || !seat.online || !_iceConfigReceived.value) {
            // 静默丢弃：多人房里三个人互相转发候选是正常现象（旧服务端没有 `to`），
            // 为一条「发错人」的候选记一行错会让日志失真。
            return
        }

        notice("收到 ${short(signal.from)} 的信令，但本地还没有它的链路 —— 按需补建一条")
        startLink(signal.from)
        _links.value[signal.from]?.onSignal(signal)
    }

    // ── DataChannel ────────────────────────────────────────────────────────

    private suspend fun onPeerMessage(peer: UserId, text: String) {
        _receivedMessages.value += 1

        // 先试信封：1.5 起 DataChannel 上流的正是它。不是信封才退回 1.4 的纯文本回声。
        val envelope = decodeEnvelope(text)
        if (envelope != null) {
            notice("收到 #${_receivedMessages.value}：${envelope.type}（来自 ${short(peer)}，seq=${envelope.seq}）")
            routeEnvelope(envelope)
            return
        }

        notice("收到 #${_receivedMessages.value}：${preview(text)}")

        if (!_autoEcho.value) return

        // 只回一层。对端回给我的那条带着前缀，看到它就停 —— 否则两台设备会一直
        // 对轰（这条很容易写错：无条件 echo 会在验收时把日志刷到看不出别的东西）。
        if (text.startsWith(ECHO_LABEL)) {
            notice("（这是对端的回声，不再回击）")
            return
        }

        if (_links.value[peer]?.send(ECHO_LABEL + text) != true) {
            notice("自动回声失败：DataChannel 还没 OPEN")
        }
    }

    /**
     * 尝试按信封解析，失败返回 null。
     *
     * **不能把「解析失败」当成错误**：1.4 的验收页允许往通道里发任意文本，
     * 那不是协议错误。只有「以 `{` 开头却解不出信封」才值得在日志里留一条 ——
     * 那种情况基本只有一个解释：两端客户端版本错配。
     */
    private fun decodeEnvelope(text: String): Envelope? = try {
        EnvelopeCodec.decode(text)
    } catch (_: MalformedEnvelopeException) {
        if (text.trimStart().startsWith("{")) {
            notice("这段文本像 JSON 但不合信封定义（§5.1），已按普通文本处理")
        }
        null
    }

    /**
     * 把玩法域信封交给 [router]。
     *
     * 底座域（`sys.*` / `rtc.*`）在这里就被拦下：它们只走 WebSocket 信令，
     * 出现在 DataChannel 上说明对端有问题。放它们进 router 虽然也只会被记成
     * 「未知前缀」，但诊断页就会把「对端发错了通道」显示成「本机不认识这个类型」，
     * 归因正好反了。
     */
    private suspend fun routeEnvelope(envelope: Envelope) {
        when (envelope.type.substringBefore('.', missingDelimiterValue = "")) {
            "sys", "rtc" ->
                notice("DataChannel 上收到底座域消息 ${envelope.type}，已忽略（底座域只走 WebSocket）")

            else -> router.route(envelope)
        }
    }

    // ── PeerMessenger：玩法 → 对端（DataChannel 出站）────────────────────────

    /**
     * 实现 [PeerMessenger]。信封里除 `type` / `payload` / `to` 之外的字段全部由这里填 ——
     * 它们都是底座才知道的事（见 [PeerMessenger] 的注释）。
     *
     * [to] 非 null ⇒ 只发那一条链路；null ⇒ **广播给所有链路**（每条各带自己的 seq）。
     * 定向目标没有链路时返回 false：这比「悄悄改发广播」安全 —— 后者会把一条
     * 「只该给 A 的 `media_status`」发给全房间，而它带着 `matched/watching` 结论。
     */
    override fun send(type: String, payload: JsonObject?, to: UserId?): Boolean {
        val targets: List<Pair<UserId, PeerLink>> = if (to != null) {
            val link = _links.value[to]
            if (link == null) {
                // 不排队、不重试：玩法自己知道什么时候该重发（`hello` 挂在通道 OPEN 上）。
                notice("$type 没发出去：与 ${short(to)} 还没有链路")
                return false
            }
            listOf(to to link)
        } else {
            // 排序只为让日志顺序稳定，不影响语义。
            _links.value.entries.sortedBy { it.key.value }.map { it.key to it.value }
        }

        if (targets.isEmpty()) {
            notice("$type 没发出去：当前没有任何链路")
            return false
        }

        var any = false
        for ((peer, link) in targets) {
            val seq = (outboundSeq[peer] ?: 0L) + 1
            outboundSeq[peer] = seq
            val envelope = Envelope(
                type = type,
                seq = seq,
                roomId = signaler.room.value?.roomId,
                from = myUserId.value,
                // 广播时保持缺省（契约 §4：`play`/`pause`/… 的 `to` 可空），
                // 于是接收方只需要一条判据：`to != null && to != 自己` ⇒ 丢弃。
                to = to?.value,
                // §5.1：单调时钟，不是墙上时钟。两端的墙钟不可信。
                sentAtMs = clock.nowMs(),
                payload = payload,
            )
            if (link.send(EnvelopeCodec.encode(envelope))) any = true
        }

        if (any) {
            _sentMessages.value += 1
            val scope = if (to != null) "→ ${short(to)}" else "（广播 ${targets.size} 条）"
            notice("已发出 #${_sentMessages.value}：$type$scope")
        } else {
            // 不排队、不重试。玩法自己知道什么时候该重发 —— 1.5 的 hello 就挂在
            // 「通道刚 OPEN」这个回调上（见构造参数的注释）。
            notice("$type 没发出去：DataChannel 未 OPEN")
        }
        return any
    }

    // ── 出站信令的收发口 ───────────────────────────────────────────────────

    /**
     * 一条链路的出站信令口（[RtcSignalSink] 的实现）。
     *
     * **每条链路一个实例、构造时绑死 [peer]**（契约 §6 最后一条）：sink 的签名里
     * 没有 `to`，`core:rtc` 因此完全不必知道「房间里还有谁」，它只管对面这一个。
     */
    private inner class LinkSink(private val peer: UserId) : RtcSignalSink {

        override fun sendOffer(sdp: String) =
            sendRtc(peer, "rtc.offer", "offer（→ ${short(peer)}，${sdp.length} 字节）") {
                signaler.sendRtcOffer(peer, sdp)
            }

        override fun sendAnswer(sdp: String) =
            sendRtc(peer, "rtc.answer", "answer（→ ${short(peer)}，${sdp.length} 字节）") {
                signaler.sendRtcAnswer(peer, sdp)
            }

        override fun sendIce(candidate: String?, sdpMid: String?, sdpMLineIndex: Int?) =
            // 成功时**不记日志**：候选几十条很常见，全记下来会把日志刷满，
            // 真要看 offer/answer 时反而翻不到。数量从诊断页的候选分布看。
            sendRtc(peer, "rtc.ice", successNote = null) {
                signaler.sendRtcIce(peer, candidate, sdpMid, sdpMLineIndex)
            }

        override fun requestRenegotiation(reason: String?) =
            // `reason` 原样带出去（它是**行为判据**，不是日志）：`audio` = 只要一轮普通
            // re-offer，其余 = 重启 ICE。本层不认识这两个值，也不该替对端决定。
            sendRtc(
                peer,
                "rtc.renegotiate",
                "请求 ${short(peer)} 重新发起协商（reason=${reason ?: RtcSignal.RENEGOTIATE_REASON_ICE}）",
            ) { signaler.requestRenegotiation(peer, reason) }
    }

    /**
     * 把一次出站信令丢进 [scope]，并**尽力而为**地处理失败 —— 与 [RtcSignalSink] 的约定一致：
     * 信令此刻可能正断着，那是 `Signaler` 自己重连的事，不该把异常抛回 libwebrtc 的回调里
     * （那会直接崩在 native 线程上）。
     */
    private fun sendRtc(peer: UserId, action: String, successNote: String?, block: suspend () -> Unit) {
        // `UNDISPATCHED`：让协程体在**调用线程**上就地执行到第一次真挂起。`Signaler.sendRtc*`
        // 实际是同步写 socket 的（声明成 suspend 只是给将来留余量），所以在主线程上它根本
        // 不会挂起 —— 于是「offer 先写、候选后写」这个顺序被真正保住。
        // 换成普通 `launch` 就变成「先入队、稍后执行」，顺序重新交给调度器，
        // 也就是本文件开头那几条订阅要解决的同一个问题。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
                onSendSucceeded()
                if (successNote != null) notice("已发出 $successNote")
            } catch (error: Exception) {
                onSendFailed("→ ${short(peer)} $action", error)
            }
        }
    }

    private fun onSendSucceeded() {
        if (pendingSendFailures == 0) return
        notice(
            "信令已恢复；此前有 $pendingSendFailures 条出站信令没发出去。" +
                "§3.7.7：这种情况只能靠 restartIce 补救，等是等不回来的",
        )
        pendingSendFailures = 0
        sendFailureReported = false
    }

    private fun onSendFailed(action: String, error: Exception) {
        pendingSendFailures += 1
        // 只从「一直正常」跌到「失败」的那一次发声。信令一断，排队中的几十条候选
        // 会同时失败，逐条记的话整个日志只剩这一件事。
        if (sendFailureReported) return
        sendFailureReported = true
        notice(
            "$action 发送失败：${error.message ?: error::class.java.simpleName}" +
                "（后续同类失败只计数，不再重复提示）",
        )
    }

    // ── 日志 ───────────────────────────────────────────────────────────────

    /**
     * 记一行。调用方保证在 [scope] 线程上（见类注释的线程约定）。
     *
     * 时间戳用 `java.time` 而不是 `SimpleDateFormat`：后者**非线程安全**，被并发调用时
     * 不抛异常、只是给出错乱的时间，属于最难发现的那类错。
     */
    private fun notice(line: String) {
        val stamped = "${TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))}  $line"
        _log.value = (listOf(stamped) + _log.value).take(logLinesKept)
    }

    private fun preview(text: String): String =
        if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS) + "…"

    companion object {
        /**
         * 自动回声的前缀。**必须是公开的**：诊断面板的开关文案要显示它，
         * 而两边各写一份字面量迟早会不一致，那时「回声为什么停不下来」会变得很难解释。
         */
        const val ECHO_LABEL = "echo:"

        private const val LOG_LINES_KEPT = 120

        private const val PREVIEW_CHARS = 80

        /**
         * 提前多久算「临期」（阶段二 2.1）。与服务端 `TURN_RENEW_BEFORE_SEC`
         * 的默认值（300s）取同一个数：两边判据不同的话，客户端会在服务端刚要推
         * 之前抢跑，白打一次信令；比它晚又会让兜底形同虚设。
         */
        private const val RENEW_LEAD_SECONDS = 300L

        /**
         * 续签巡检间隔。凭证 TTL 是小时级，15s 一次纯粹是为了「过期后 15 秒内被发现」
         * —— 巡检本身只做两次减法，代价可以忽略。
         */
        private const val CREDENTIAL_CHECK_INTERVAL_MS = 15_000L

        /** 索取失败后的最小重试间隔，防止信令一断就在每一轮巡检上各打一次。 */
        private const val RENEW_RETRY_BACKOFF_MS = 60_000L

        /**
         * 网络事件的去抖窗口（阶段二 2.3）。
         *
         * 1.5 秒是个折中：切网时那几条事件之间通常隔几十毫秒到几百毫秒，1.5 秒
         * 足够把它们收进同一个窗口；而用户感知到的「切网后多久恢复」以秒计，
         * 多等 1.5 秒看不出来。
         */
        private const val NETWORK_DEBOUNCE_MS = 1_500L

        /** R-18 的 60 秒：网络中断超过它才提示「可能是热点被省电关闭」。 */
        private const val NETWORK_HINT_DELAY_MS = 60_000L

        /** `rtc.reset` 的 `reason`：本机按了「重建链路」。只进对端日志，不参与判据。 */
        const val RESET_REASON_MANUAL = "manual"

        /** `rtc.reset` 的 `reason`：应 / 对端的请求（本端是被动那一方）。 */
        const val RESET_REASON_PEER = "peer"

        /**
         * 两端几乎同时按「重建链路」时的去重窗口（阶段二 2.4）。
         *
         * 2 秒足够覆盖「两台设备上的人几乎同时点」这件事（真机上那次是两个人在通话里
         * 说「一起点」），又短到不会把**下一次**真的重建请求挡掉 —— 用户连点两次的间隔
         * 通常也是秒级，而那时对端已经拆过了，多拆一次没有害处。
         */
        private const val REBUILD_DEDUPE_MS = 2_000L

        /**
         * 「现在该不该索取新凭证」。抽成纯函数是为了能在 JVM 单测里钉住四个边界：
         * 没有凭证（null）、已过期、恰好落在阈值上、还早得很。
         *
         * @param expiryEpochSeconds 最早过期的凭证时刻；null = 当前没有 TURN 凭证
         * @param nowSeconds 当前墙上时钟（秒）—— 必须与凭证 `expiry` 同一时基
         * @param leadSeconds 提前量：`expiry - lead` 起就算临期
         */
        internal fun renewalDue(
            expiryEpochSeconds: Long?,
            nowSeconds: Long,
            leadSeconds: Long,
        ): Boolean = expiryEpochSeconds != null && nowSeconds >= expiryEpochSeconds - leadSeconds

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** 只取 userId 前 8 位。与首页的 `short` 同一口径，避免两处显示不一致。 */
private fun short(userId: UserId): String = userId.value.take(8)
