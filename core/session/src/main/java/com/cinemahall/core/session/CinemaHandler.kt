package com.cinemahall.core.session

import com.cinemahall.core.media.MediaCheck
import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.MediaSignatureComparator
import com.cinemahall.core.media.PlaybackTarget
import com.cinemahall.core.model.MonotonicClock
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import com.cinemahall.core.protocol.PeerMessenger
import com.cinemahall.core.protocol.PlayModeHandler
import com.cinemahall.core.rtc.CallAudio
import com.cinemahall.core.rtc.CallAudioState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 影厅玩法的处理器 —— [PlayModeHandler] 的实现（《技术方案评审》§4.1、§4.2）。
 *
 * 已落地：
 *  - 1.2 接缝连通（`cinema.*` 按前缀路由到这里）；
 *  - 1.5 片源一致性校验（[mediaCheck]，消息即 `cinema.hello`，规则见 §7）；
 *  - 1.6 播放同步（[syncEngine]，§6.1–§6.6）；
 *  - 1.7 内置语音通话（[callAudio] / [setCallEnabled] / [setCallVolume]）。
 *
 * 注意这里**没有**任何 `position` / `isPlaying` 字段对外暴露给底座 ——
 * 播放状态属于本模块私有，底座只看到不透明的 `Room.modeState`。
 *
 * ── 本类在 1.6 里的角色：**接线员**，不含任何同步算法 ────────────────────────
 * 算法全在 [CinemaSyncEngine] 里（它是纯逻辑、可在 JVM 上逐毫秒测）。这里只做四件事：
 * 把底座的消息转给引擎、把引擎的出站接到 [PeerMessenger]、把「本端是不是房主」判出来、
 * 把播放器与时钟交给引擎。这样「同步为什么不对」永远只需要看一个文件。
 *
 * ── 1.7 的语音通话同样是**转发**，不是实现 ──────────────────────────────────
 * 音频的能力面是 [CallAudio]（`core:rtc` 里声明，由 `app` 的 `PeerCoordinator` 实现）。
 * 本类把它**后置绑定**进来再转手给界面 —— 与 [messenger] 完全同一个理由（构造时机
 * 对不上）与同一种手法。界面因此只需要 `handler.setCallEnabled(...)`，而不必认识
 * `core:rtc` 的任何东西。
 *
 * ── 线程：底座的回调**不止一条来路**，引擎只有一条 ──────────────────────────────
 * [CinemaSyncEngine] 的线程约定是硬的（它内部不加锁，且播放器拒绝跨线程访问），
 * 而底座把消息送过来的线程有两条：
 *  - `cinema.*` 走 DataChannel ⇒ `PeerCoordinator` 的 `rtcScope`（主线程）；
 *  - `sys.peer_joined` / `sys.peer_left` / `resume` 走 WebSocket ⇒
 *    `WsSignaler` 的 `receivePath`（`Dispatchers.IO.limitedParallelism(1)`）。
 * 所以凡是碰引擎的底座回调都必须经 [onEngineThread] 收口（2026-10-12 真机缺陷的修复）。
 */
class CinemaHandler(
    /**
     * 底座回调 → 引擎的线程。
     *
     * ── 为什么没有默认值（Phase 1 搬出 `feature:cinema` 时改的）────────────────
     * 默认值原本是 `Dispatchers.Main.immediate`。那在 Android 上成立，但本模块是
     * 纯 JVM：`kotlinx-coroutines-core` 里没有 Main 实现，一旦有人用默认值构造，
     * 第一次 dispatch 就会抛 `MissingMainCoroutineDispatcher`。而「哪个线程是主线程」
     * 本来就是平台的装配知识 —— 由调用点显式给（生产是 `Dispatchers.Main.immediate`，
     * 单测是 `Dispatchers.Unconfined`），本类只依赖抽象。
     *
     * 传 `Dispatchers.Main.immediate` 而不是 `Dispatchers.Main` 的理由没变：已经在
     * 主线程时 `isDispatchNeeded` 为假，`cinema.*` 热路径不额外排队。
     */
    private val engineDispatcher: CoroutineDispatcher,

    /**
     * 单调时钟（`MonotonicClock`，契约 §8.1）。
     *
     * 默认值只是单测方便；**生产必须注入与本进程其余单调时间同源的那个实现**
     * （Android 上是 `AndroidMonotonicClock` = `SystemClock.elapsedRealtime()`）。
     * 它与 `PeerCoordinator.send` 填的信封 `sentAtMs` 必须是同一条时间轴，
     * 否则量出来的 RTT 里混着两种时钟的差值，看起来只是一个偏大的网络延迟。
     */
    private val clock: MonotonicClock = DefaultMonotonicClock,
) : PlayModeHandler, CallAudio {

    override val mode: String = "cinema"

    private val _receivedActions = MutableStateFlow<List<String>>(emptyList())

    /** 收到过的玩法动作，按到达顺序。1.2 的接缝自检读它。 */
    val receivedActions: StateFlow<List<String>> = _receivedActions

    // ── 1.5：片源一致性校验的状态 ───────────────────────────────────────────

    private val _mediaCheck = MutableStateFlow(CinemaMediaCheck())

    val mediaCheck: StateFlow<CinemaMediaCheck> = _mediaCheck

    // ── 逐成员媒体结论（阶段二 P4，契约 §7）────────────────────────────────

    /**
     * 一个成员的片源结论。房主侧才有内容：它拿自己的摘要与对方 `hello` 里的比对。
     *
     * [watching] 与 [matched] 分开，是因为契约 §7 把它们当三种状态用：
     * `matched=false` ⇒ 旁听；`matched=true, watching=false` ⇒ 等房主开播；
     * 两者都 true ⇒ 进入同步组。合成一个布尔，界面就只能说「不行」，说不出「为什么」。
     */
    data class MemberMedia(
        val nickname: String = "",
        val matched: Boolean = false,
        val watching: Boolean = false,
        val reason: String? = null,

        /**
         * 这个人**最后一次**报上来的摘要。房主把它留着只为了一件事：
         * 自己换片 / 清空选择之后，不必等对方重发 hello 也能重算裁决
         * （见 [recomputeMemberVerdicts]）。不参与 `equals` 的界面语义 ——
         * 界面上看得到的是 [matched] / [watching] / [reason]。
         */
        val signature: MediaSignature? = null,
    )

    private val _members = MutableStateFlow<Map<UserId, MemberMedia>>(emptyMap())

    /** 房主视角：每个成员匹配了没有。从端为空（它只关心自己那一条 `media_status`）。 */
    val members: StateFlow<Map<UserId, MemberMedia>> = _members

    private val _selfMedia = MutableStateFlow<CinemaMediaStatus?>(null)

    /**
     * 从端视角：房主对我的裁决（契约 §7 的三态）。
     *
     * null = 还没收到裁决。**不能默认成 matched=true** —— 那会让一个片源不对的人
     * 以为自己在同步组里，看到的是「别人都在动、我停在原地」。
     */
    val selfMedia: StateFlow<CinemaMediaStatus?> = _selfMedia

    private val _controlPolicy = MutableStateFlow(ControlPolicy.HOST_ONLY)

    /** 当前控制策略（房主改它，从端从消息里读它，UI 拿它画开关）。 */
    val controlPolicy: StateFlow<ControlPolicy> = _controlPolicy

    /**
     * 本机昵称。只作为 `cinema.hello` 的展示字段发出去（§5.3 的 payload 里就有它），
     * 由界面在建房 / 加入时上报。
     */
    @Volatile
    var localNickname: String = ""

    /**
     * 出站口。**刻意做成可后置绑定**，而不是构造参数。
     *
     * 原因是生命周期顺序：`AppModule.handlers` 在进程启动时就要建好（§4.2 要求
     * `MessageRouter` 一存在就有 handler），而承载 DataChannel 的 `PeerCoordinator`
     * 需要 `Context` 与本机 userId，只能等到界面起来才能建。硬要在构造时注入，
     * 就得把 `Context` 拖进玩法层。
     *
     * 未绑定时 [sendHello] 返回 false —— 与「链路还没通」是同一个行为，
     * 不需要额外分支：两种情况对玩法来说都是「现在发不出去」。
     */
    @Volatile
    private var messenger: PeerMessenger? = null

    fun bindPeerMessenger(messenger: PeerMessenger?) {
        this.messenger = messenger
    }

    // ── 1.7：语音通话 ───────────────────────────────────────────────────────

    /**
     * 通话音频的命令面（`core:rtc` 的 [CallAudio]）。**后置绑定**，与 [messenger]
     * 同一个理由、同一个时机（见 [bindPeerMessenger]）。
     */
    @Volatile
    private var callSource: CallAudio? = null

    /**
     * 还没绑上时的空状态。
     *
     * 为什么需要它：界面在「协调者还没建出来」时也可能组合影厅页（用户从大厅直接
     * 进厅）。那一刻通话状态必须是「没开」而不是崩 —— 空状态恰好就是这个语义。
     */
    private val idleCallAudio = MutableStateFlow(CallAudioState())

    fun bindCallAudio(source: CallAudio?) {
        callSource = source
    }

    /**
     * ⚠️ 必须是 `get()`，**不能**在构造时把 `callSource.callAudio` 取出来存着。
     *
     * 绑定发生在界面起来**之后**（`AppModule.peerCoordinator` 要 Context 与 userId，
     * 只能等外壳启动）。存下来的话界面会永远订阅那个空流，而症状是「按了开关，
     * 状态一动不动、按钮一直显示『开启通话』」—— 开关本身也没坏，最难查的那一类。
     */
    override val callAudio: StateFlow<CallAudioState>
        get() = callSource?.callAudio ?: idleCallAudio

    /** 开 / 关本机通话音频。没绑上时是空操作（= 还没有链路，开不了）。 */
    override fun setCallEnabled(enabled: Boolean) {
        // 接口这一面拿不到来源：如实写「来源未标注」，不编一个出来。
        setCallEnabled(enabled, source = "来源未标注")
    }

    /**
     * 带来源的开关入口（2026-10-13 第八轮缺陷①）。
     *
     * ── 为什么界面每一处都要报上自己是哪一处 ────────────────────────────────────
     * 缺陷①要回答的问题是「没人按，通话怎么自己开了」。`callRequested` 全进程只有两处
     * 写入（`WebRtcPeerLink.setCallEnabled` 与它的 `close()`），所以只要把**每一次界面
     * 请求连同它的来源**都写进「最近事件」，用户截图时「开关自己开了」与「有人按过」
     * 当场就能分开 —— 而第八轮之前这个痕迹只在链路日志里（120 行、还会被
     * `PeerCoordinator` 的本地去重记忆挡掉一部分），要翻页才看得到。
     *
     * 记的是**请求**、不是**结果**：`WebRtcPeerLink.setCallEnabled` 走的是 `post`，
     * 同步读不到结果；而且「请求了却没生效」恰恰是缺陷①的另一半证据（开关读着关、
     * 麦克风却在采集，见 `callStatusText` 的第一档）。
     *
     * 没链路时也照样记（`callSource` 为 null）：那时开关点了等于没点，而「点了没反应」
     * 本身就是要能看出来的东西。
     */
    fun setCallEnabled(enabled: Boolean, source: String) {
        engine?.noteExternal(if (enabled) "通话开关：开（$source）" else "通话开关：关（$source）")
        callSource?.setCallEnabled(enabled)
    }

    /** 通话音量 0f..1f。没绑上时是空操作。 */
    override fun setCallVolume(volume: Float) {
        callSource?.setCallVolume(volume)
    }

    /** 通话中限制影片音量（§471 兜底开关）。没绑上时是空操作。 */
    override fun setMovieVolumeLimit(enabled: Boolean) {
        callSource?.setMovieVolumeLimit(enabled)
    }

    // ── 1.6：同步引擎 ───────────────────────────────────────────────────────

    @Volatile
    private var engine: CinemaSyncEngine? = null

    /** 同步引擎。null = 播放器还没接上（界面还没起来）。 */
    val syncEngine: CinemaSyncEngine? get() = engine

    /**
     * 播放同步的可调参数（设置 → 播放设置）。**装配点在界面组合之前就读盘并写进来**，
     * 因为引擎是在影厅页首次组合时才建的（[attachPlayback]），那时再读盘就晚了：
     * 第一条心跳会按旧周期发出去。
     */
    @Volatile
    var syncConfig: CinemaSyncConfig = CinemaSyncConfig.DEFAULT
        private set

    /**
     * 换一套同步参数。引擎已经建好时**立刻**生效（不必退出影厅再进），没建好就只记着。
     *
     * 线程：调用点在界面线程，而 Android 装配给 [engineDispatcher] 的正是
     * `Dispatchers.Main.immediate` ——
     * 两者通常是同一条线程，`withContext` 于是就地执行。但仍然走 [onEngineThread] 收口，
     * 因为「引擎只在一条线程上被碰」这条约定不该靠「调用点碰巧在主线」来维持。
     */
    fun applySyncConfig(updated: CinemaSyncConfig) {
        syncConfig = updated
        val current = engine ?: return
        actionScope.launch { onEngineThread { current.updateConfig(updated) } }
    }

    /**
     * 手动同步一次（控制栏那颗键）。没绑上播放器时是空操作。
     *
     * 与 [applySyncConfig] 同一条线程纪律：引擎的方法只能在它那条线程上被调。
     */
    fun requestManualSync() {
        val current = engine ?: return
        actionScope.launch { onEngineThread { current.requestManualSync() } }
    }

    /**
     * 「用户按了一下」这类离散动作用的短命作用域。
     *
     * 不用 `MainScope()`：那会起一条独立的主线程作用域，而引擎要求的线程纪律是
     * **同一个** [engineDispatcher]；也不与 RTC 那个长活作用域共用 —— 这边全是
     * 一次性动作，`SupervisorJob` 保证了「一次配置写失败」不会连坐别的动作。
     */
    private val actionScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    @Volatile
    private var myUserId: UserId? = null

    /**
     * 把播放器接上，并**首次调用时**建好同步引擎。幂等。
     *
     * ── 为什么是「后置绑定 + 幂等」而不是构造参数 ────────────────────────────────
     * 与 [bindPeerMessenger] 完全同一个理由：引擎需要一个真正的播放器，而播放器需要
     * `Context`（`ExoPlayer.Builder(context)`）—— 那只能等到界面起来。所以由界面把
     * 播放器交进来，这里建引擎。
     *
     * 时钟用注入的 [clock]：Android 装配传 `SystemClock.elapsedRealtime()`，
     * 与信封的 `sentAtMs`（§5.1）、以及 `foundation` 层的所有单调时钟是**同一个时基**。
     * §6.1 那三个时刻必须同源，否则量出来的 RTT 里混着两种时钟的差值，
     * 而它看起来只是一个偏大的网络延迟。
     */
    fun attachPlayback(player: PlaybackTarget): CinemaSyncEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }

            val created = CinemaSyncEngine(
                player = player,
                clock = { clock.nowMs() },
                outbound = { type, payload, to -> sendToPeer(type, payload, to) },
                config = syncConfig,
            )
            // 摘要可能已经在 1.5 算好了（用户选完片才进播放页），也可能之后才算 ——
            // 两条路都要接上，否则房主在链路刚通时下发的是「没有片源摘要」的空快照。
            _mediaCheck.value.local?.let { created.setMediaSignature(it) }
            engine = created
            // **整个房间上下文**补一次，不只是角色。
            //
            // ── 为什么「只补角色」是错的（2026-11-06 真机：从端收到心跳却不锚定）──────
            // 引擎是界面在影厅页首次组合时才建的，而房间可能**更早**就到了（自动进房
            // 跑在页面之外）。那一路上 `applyHost()` 已经跑过一遍，但它调用时 `engine`
            // 还是 null，什么都灌不进去。等引擎建出来，只补 `setRole` 的话：
            //   · 角色对了 → 界面显示「从端（跟随）」，看起来一切正常；
            //   · `hostUserId` 是 null → `mayApply()` 把房主发来的 `cinema.heartbeat`
            //     与 `cinema.state_snapshot` 全部按「从端只接受房主的控制（房主=未定）」
            //     丢掉 → 从端永远 `anchored=false`、画面一动不动，**且没有任何报错**。
            // 这是纯时序竞态：引擎先建就正常，后建就永久失效 —— 于是表现为「有时能同步，
            // 有时不能」，极易被当成网络问题。
            pushRoomContext(created)
            return created
        }
    }

    /**
     * 把「当前房间里的事实」一次性灌进引擎。
     *
     * **唯一装配点**：房间变化（[applyHost]）与引擎晚建（[attachPlayback]）都走它。
     * 两处各写一份必然漏字段 —— 2026-11-06 漏的正是 `hostUserId`，而它的症状
     * （从端静默忽略房主的一切）与网络无关，只在引擎晚建时出现。
     *
     * 顺序：先角色（它内部会重置同步状态），再上下文，最后策略与「在看」——
     * 后两者只影响控制权判定，与锚点无关。
     */
    private fun pushRoomContext(engine: CinemaSyncEngine) {
        engine.setRole(isHostLocally)
        engine.setRoomContext(myUserId, hostUserId, inRoom = lastRoom != null)
        engine.setControlPolicy(_controlPolicy.value)
        engine.setSelfWatching(_selfMedia.value?.let { it.matched && it.watching } ?: false)
    }

    // ── 1.6：角色判定（房主主控，§6.5）─────────────────────────────────────

    @Volatile
    private var isHostLocally: Boolean = false

    /**
     * 本房间的房主。`control_policy` 的**收包判据**要用它（见 [onMessage]）。
     *
     * 与 [isHostLocally] 分开存：从端也必须知道「谁是房主」，否则它没法判断
     * 「这条策略到底该不该听」—— 而分不清的结果正是「房主开了全员控制，从端还是灰的」。
     */
    @Volatile
    private var hostUserId: UserId? = null

    /**
     * 最近一次看到的房间。**只用于「补一次 [applyHost]」**，不是第二份真相。
     *
     * 为什么要留它：`cinema.*` 全部走 DataChannel，而链路刚 OPEN 是这个进程里
     * **最可靠的一次「我现在确实在房间里」**。若 `onRoomUpdated` 那一路因为任何理由
     * 漏掉（状态流去重、装配点还没拿到 selfUserId、页面切换的时序），引擎就会带着
     * 「房主未知」一直跑下去 —— 而症状只是「从端的播放键按下去没反应」。
     */
    @Volatile
    private var lastRoom: Room? = null

    /**
     * 房间变了（进房 / 退房 / 成员变化）。由装配点在 `Signaler.room` 上调用。
     *
     * ── 判据为什么必须是 `Room.hostUserId` ──────────────────────────────────────
     * §6.5 的主控权来自「谁建的房」，也就是服务端在 `sys.room_created` 里给的
     * `hostUserId`。**不能拿协商角色（谁是 offer 方）代替**：协商角色由 userId 的
     * 字典序定（见 `PeerCoordinator.reconcile`），两者成因无关。混用会让「后进房的
     * 人反而是房主」这种完全正常的情况变成「两端都以为自己主控」——
     * 而症状只是各播各的，没有任何报错、没有任何一条日志异常。
     */
    fun onRoomUpdated(room: Room?, myUserId: UserId) {
        this.myUserId = myUserId
        applyHost(room)

        if (room != null) {
            // 房号变了 = **换了一场会话**，上一场的通话意图不许跟过来（2026-10-12 补）。
            //
            // ── 为什么判据是「房号变了」而不是「进了房间」────────────────────────────
            // `onRoomUpdated` 的来路是 `Signaler.room` 这个 StateFlow，它在**成员上下线**
            // 时也会发一个新值（`markMemberOnline` 每次都 `copy` 出一个新 `Room`）。
            // 按「进了房间」判，会在对端掉线的那一刻把正在进行的通话关掉 —— 而那一刻
            // 恰恰是两人最不希望掉麦的时候。
            //
            // ── 为什么非要有这一条 ──────────────────────────────────────────────────
            // `callRequested`（通话意图）活在进程级的 `WebRtcPeerLink` 里，**刻意**跨链路
            // 重建存活（那是「断一下线通话不该静默消失」要的）。代价是它也可能活过整场
            // 会话：只要任何一条退房来路漏掉复位（用户报的正是这一条），下一次进房建链时
            // `openPeerConnection()` 就会按它把麦克风接回来 —— 用户没按过任何开关。
            // 复位挂在「退房」上是**依赖某条路径一定跑到**；挂在「新房号」上则是结构性的：
            // 一场新会话的第一帧里，通话意图必然是关的，无论上一场是怎么结束的。
            //
            // 同一房号重进（`sys.resume` 恢复会话）不会走到这里 —— 断线期间 `lastRoomId`
            // 一直是那个房号，通话照旧保留，这正是 §9.4 宽限期要的。
            //
            // ── 这条判据**不充分**（2026-10-12 真机缺陷「进房仍自动开通话」）──────────
            // `Room` 是 StateFlow，它**按相等去重**：「退房之后很快重进同一个房号」
            // （`null` → 同一个 `Room`）在收集方来不及跑时会被合并成「没有变化」，
            // 于是本方法一次都不会被调到。所以复位还有第二条路：[onRoomEntered]，
            // 那是底座在应答路径上直接派发的**事件**，状态流去重不了它。
            if (lastRoomId != room.roomId) setCallEnabled(false)
            lastRoomId = room.roomId
            // 进了房间 ⇒ 这是一场**新的**会话，收尾标记归位（见 [onSessionEnded] 的幂等守卫）。
            sessionEnded = false
            return
        }
        // `Room` 变 null 而**我们确实进过**一个房间 = 一场会话结束了。
        if (lastRoomId == null) return
        lastRoomId = null
        // 收尾放在最后：`applyHost(null)` 会把角色改掉并写一行「本端成为从端」，
        // 那一行在「刚退房」这个语境里是误导的 —— 让下面那条更准确的事件盖掉它。
        onSessionEnded()
    }

    /**
     * 本机进过的最后一个房间号。只用来判「这一次 `Room` 变 null 是不是一次真的退房」。
     *
     * 为什么要这个判据：App 刚起来时 `Room` 本来就是 null（还没进过任何房间），
     * 那时不该走一遍会话收尾 —— 收尾本身是幂等的，但它会往事件行里写一条
     * 「会话结束」，让第一次看日志的人以为出过事。反过来，只要**进过**房间，
     * `Room` 变 null 就一定是会话级的结束（见 [onSessionEnded] 的 KDoc）。
     */
    @Volatile
    private var lastRoomId: String? = null

    /**
     * 本次会话是否已经收过尾。
     *
     * 初值 `true`：进程刚起来时本来就**没有**会话可收，而 `Room` 也还是 null ——
     * 这时若有人调 [onSessionEnded]（比如前台服务刚起来读到一个空房间），
     * 不该往事件行里写一条「会话结束」让第一次看日志的人以为出过事。
     *
     * 进房时归位（[onRoomEntered] / [onRoomUpdated] / [onSessionRestored]），收尾时置位。
     */
    @Volatile
    private var sessionEnded: Boolean = true

    /**
     * **会话结束**：把这一场观影在本机留下的东西收干净（2026-10-12 补）。
     *
     * ── 为什么需要一个显式的收尾入口 ──────────────────────────────────────────────
     * 在这之前，退房只做了一件事：`Signaler.leave()` 把 `Room` 置空。而影厅这边的
     * 三样东西**没有任何人负责**：
     *  ① **播放器** —— 它是进程级单例（§7.2），退房后没人叫它停，片子一直放下去。
     *     用户报的正是这一条：「退出房间后视频还在接着播放」。
     *  ② **通话** —— 通话开关的状态（`requested`）属于链路对象，链路被拆掉时它
     *     不会自己回到 false。于是界面上仍是「通话中」（而麦克风其实已经放了），
     *     而且**重新进房建链时通话会自己复活** —— 用户没按过任何开关。
     *  ③ **对端片源摘要** —— 它属于上一场会话。留着的话，下一次进房在对方的
     *     `cinema.hello` 到达之前会先显示一次上一场的「已匹配」。
     *
     * ── 为什么由 [onRoomUpdated] 触发，而不是在退房按钮那两处各写一遍 ────────────────
     * `Room` 变 null 是**会话结束的唯一可靠信号**，而它覆盖的路不止用户按按钮那一条：
     * 服务端解散房间（`sys.room_closed`）、换服务器（`Signaler` 自己清房间视图）、
     * `resume` 失败（宽限期已过）都会走到这里。挂在这一个漏斗上，四条路一次收干净；
     * 挂在按钮上，另外三条继续漏 —— 而它们各自都有一模一样的症状。
     *
     * 反过来说，**拔网不会**让 `Room` 变 null（`WsSignaler` 刻意保留它去 `resume`），
     * 所以这里不会把「网络抖了一下」误判成退房。那条路走的是 [onPeerChannelClosed]
     * 与 [onPeerUnavailable]，语义完全不同（§9.4 的 60s 宽限期）。
     *
     * ── 1.8 起它多了一个调用者，于是必须幂等 ────────────────────────────────────
     * 1.7 时这个入口只有一条来路（上面那条漏斗），「别重复调」靠的是 [lastRoomId] 那个判据。
     * 1.8 的前台服务也要走同一条收尾（划掉最近任务、以及「房间变 null 而界面已经不在了」），
     * 而它与这条漏斗之间**没有共享的「收过没有」**：`leave()` 一执行 `Room` 就变 null，
     * 界面那侧的 `LaunchedEffect` 会再调一次。收尾里的
     * `CinemaSyncEngine.endSession()` 每次都会往事件行里写一条「会话结束」——
     * 重复调用在诊断页上看起来像出过两次事，而验收要读的恰好是那条。
     * 所以守卫放在这里，而不是在调用方各写一遍（三个调用方各写一遍必然漏一个）。
     */
    fun onSessionEnded() {
        // 通话复位**必须**在幂等守卫之前（2026-10-12 修）。
        //
        // 守卫的语义是「事件行里的『会话结束』别写两遍」，它不该管「通话关不关」。
        // 1.9 把它放在了守卫后面，于是只要 `sessionEnded` 因为任何原因停在 true
        // （最典型的是：这一轮 `Room` 的变化被 StateFlow 去重掉，而收尾标记已经在
        // 上一场被置位过），通话就再也关不掉了 —— 而它正是「重新进房建链时通话
        // 自己复活」的那颗种子：`callRequested` 留在 true 上，`openPeerConnection()`
        // 一建 PC 就把麦克风接回来，用户没按过任何开关。
        //
        // 这一句重复调是零代价的：`PeerCoordinator.setCallEnabled` 与
        // `WebRtcPeerLink.setCallEnabled` 都带「值没变就直接 return」的短路，
        // 连日志都不会多写一行。
        //
        // 通话先关：它要在链路还认得出音轨的时候走完整的「摘音轨 / 释放采集 /
        // 还原音频路由」，否则整机的通信设备与外放标志位会留在我们设过的值上
        // （见 `CallAudioRouting.exit`）。关掉开关 = 停止采集，这是 1.7 定下的语义。
        setCallEnabled(false)
        if (sessionEnded) return
        sessionEnded = true
        engine?.endSession()
        _mediaCheck.update { it.withoutRemote() }
    }

    private fun applyHost(room: Room?) {
        val mine = myUserId
        val isHost = room != null && mine != null && room.hostUserId == mine
        isHostLocally = isHost
        hostUserId = room?.hostUserId
        lastRoom = room
        // 退房时先把「房主对我的裁决」清掉，再灌上下文 —— 顺序反了的话，
        // `pushRoomContext` 会把上一场残留的 `matched && watching` 带进新引擎状态。
        if (room == null) _selfMedia.value = null
        // **唯一的上下文装配点**，见 [pushRoomContext]。
        engine?.let(::pushRoomContext)
        // 换了房间 / 退房：逐成员结论与「房主对我的裁决」都不再成立。
        // 不清的话，下一场里新成员会先看到上一场的「已匹配」。
        if (room == null || room.members.none { it.userId != mine }) {
            if (_members.value.isNotEmpty()) _members.value = emptyMap()
        }
    }

    /**
     * 本机选好文件、算完摘要：记下来，并立刻向对端宣告一次。
     *
     * 返回是否真的发出去了。发不出去**不是**错误 —— 链路可能还没建好，
     * [onPeerChannelOpened] 会补发。
     */
    fun onLocalSignatureReady(signature: MediaSignature): Boolean {
        _mediaCheck.update { it.withLocal(signature) }
        // 引擎也要一份：房主在链路刚通时要靠它下发 `cinema.state_snapshot`（§6.4）。
        engine?.setMediaSignature(signature)
        // 房主换片：**已经报过 hello 的人要重算**。不重算的话，裁决停在「我换片
        // 那一刻」之前算出来的那一份 —— 真实路径是「成员先选、房主后选」，房主
        // 一选完，成员那边仍显示「旁听（本机还没选片）」，而屏幕上明明两端一致。
        recomputeMemberVerdicts()
        return sendHello()
    }

    /** 只清掉本机一侧（用户重选文件时用）。对端摘要保持有效，不必等它重发。 */
    fun clearLocalSignature() {
        _mediaCheck.update { it.withoutLocal() }
        engine?.setMediaSignature(null)
        // 清空 = 我这边没片了，所有人对「一致」的结论都不再成立。
        recomputeMemberVerdicts()
    }

    /**
     * 本机摘要变过之后，按**每个人最后一次 hello 的摘要**重算一遍逐成员裁决，
     * 并把变了的那些重新定向推给他们自己。
     *
     * 只在房主侧有效（`_members` 是房主视角，见 [members]）。成员表为空时它
     * 一个字段都不动 —— 房主刚进房、还没人报 hello 时它会以「无变化」直接返回。
     */
    private fun recomputeMemberVerdicts() {
        if (!isHostLocally) return
        val current = _members.value
        if (current.isEmpty()) return

        val updated = current.mapValues { (_, media) ->
            val (matched, reason) = judgeRemote(media.signature)
            media.copy(matched = matched, watching = matched, reason = reason)
        }
        val changed = updated.filter { (peer, media) -> current[peer] != media }
        if (changed.isEmpty()) return

        _members.value = updated
        changed.forEach { (peer, media) ->
            publishMediaStatus(peer)
            // 非挂起上下文（选片回调里调的），和 `applyControlPolicy` 同一手法：
            // 起一次就返回，不等引擎那一帧。
            actionScope.launch { onEngineThread { engine?.setPeerWatching(peer, media.matched) } }
        }
    }

    /**
     * 「本机摘要 vs 某个远端摘要」→ 匹配与否 + 给人看的原因。
     *
     * 两处调用（收到 hello、房主自己换片）**必须**用同一份判定，否则同一个人
     * 会在两种进入顺序下得到两种结论，而用户只能看到后到的那一个。
     */
    private fun judgeRemote(remote: MediaSignature?): Pair<Boolean, String?> {
        val local = _mediaCheck.value.local
        val verdict = if (local == null || remote == null) {
            null
        } else {
            MediaSignatureComparator.compare(local, remote)
        }
        val matched = verdict == MediaCheck.Match
        val reason = when (verdict) {
            null -> "本机还没选片"
            MediaCheck.Match -> null
            else -> "片源摘要不一致（$verdict）"
        }
        return matched to reason
    }

    /**
     * DataChannel 刚 OPEN 时由底座通知，用来补发一次 hello、并让引擎下发状态快照。
     *
     * 为什么需要这一步：`sys.peer_joined` 是**信令**通道上的事件，它到达时
     * WebRTC 链路往往还没建好（SDP 交换、ICE、DTLS 都还没跑完）。若只在选文件的
     * 那一刻发一次 hello，而那一刻链路刚断过，这条消息就永远丢了，表现为
     * 「两端都选好了文件，界面却一直停在等待对端」。补发的代价是一次
     * 几十字节的写入。
     *
     * [sessionEpoch] 在这一刻从底座读一次，直接填进即将发出的 `state_snapshot`：
     * 它是**会话级**信息（§5.1），只有底座知道；而快照恰好只在「链路刚通」时发，
     * 所以这里读到的必然就是这一条链路的代次。
     */
    fun onPeerChannelOpened(peer: UserId, sessionEpoch: Long = 0L): Boolean {
        // 房主身份若还没推定，在这里补一次 —— 链路 OPEN 是「确实在房间里」最硬的证据
        // （理由见 [lastRoom]）。补不出来就照旧，不要把猜测写进引擎。
        if (hostUserId == null) lastRoom?.let { applyHost(it) }
        // 定向目标跟着「最后一条打通的链路」走（见 `CinemaSyncEngine.setDirectedPeer`
        // 的 KDoc：引擎在 P4 之前仍是一对一的）。
        engine?.setDirectedPeer(peer)
        // 房主侧：这个人的媒体结论还空着（他可能还没发 hello），先把「未匹配」的
        // 结论发过去，让他的界面立刻显示「等待」，而不是停在「已连接、什么也没有」。
        if (isHostLocally) publishMediaStatus(peer)
        val hello = sendHello(peer)
        engine?.let {
            it.setSessionEpoch(sessionEpoch)
            it.onLinkOpened()
        }
        return hello
    }

    /**
     * 某条链路的 DataChannel 没了（链路断开 / 对端走了）。清掉同步状态，等下一次快照恢复。
     *
     * 只有「断的正好是当前同步对象」才清定向目标：三人房里 A 走了不该让房主
     * 对 B 的快照也一起失效（那会让 B 停在旧画面上，而两条链路本身都没事）。
     */
    fun onPeerChannelClosed(peer: UserId) {
        engine?.let { engine ->
            if (engine.directedPeerOrNull == peer) engine.setDirectedPeer(null)
            engine.onLinkClosed()
        }
    }

    /**
     * 手动重发一次摘要。与 [onPeerChannelOpened] 是同一件事。
     *
     * 给它一个界面入口，是因为对端可能中途重启过 App、或它的接收状态丢过一次，
     * 而这种情况下让用户「再喊一次」远比「请重新选一遍影片」便宜。
     *
     * [to] 缺省 = 广播给所有链路：手动重发是一个「谁收到都不嫌多」的动作，
     * 而界面那一侧并不知道房间里有谁。
     */
    fun resendHello(to: UserId? = null): Boolean = sendHello(to)

    /**
     * 发 `cinema.hello`。
     *
     * ── `to` 为什么在链路打通时是必填的（契约 §4 / §7）──────────────────────────
     * `hello` 是**成员 → 房主**的定向消息。两人房里发成广播也对（只有一个人收），
     * 但六人房里发成广播意味着**每个成员都在向每个成员**自我介绍，而房主收到
     * 五份来自不同人的 `hello` 时会依次覆盖自己那份「对端摘要」—— 最后一个人的
     * 片子决定了房主显示的匹配结论。所以打通那一刻必须点名给房主。
     *
     * 房主自己不需要发：`hello` 的方向就是「成员告诉房主我这边是什么片子」，
     * 房主的信息靠 `state_snapshot` 下发（§7）。
     */
    private fun sendHello(to: UserId? = null): Boolean {
        val target = messenger ?: return false
        val signature = _mediaCheck.value.local ?: return false
        val hello = CinemaHello(mediaSignature = signature, nickname = localNickname)
        return target.send(CinemaHello.TYPE, CinemaHelloCodec.encode(hello), to)
    }

    /**
     * 引擎的出站口 —— 与 `PeerMessenger.send` 同形（多一个 `to`），直接绑到
     * [CinemaSyncEngine] 的 `outbound` 上。发不出去时**什么都不做**：
     * 引擎的下一次心跳会把状态重新带过去，为一条心跳加一套重试队列不划算。
     */
    private fun sendToPeer(type: String, payload: JsonObject, to: UserId?): Boolean =
        messenger?.send(type, payload, to) ?: false

    // ── PlayModeHandler ─────────────────────────────────────────────────────

    /**
     * 把底座回调搬回**引擎的线程**再执行。
     *
     * ── 为什么非有这一步不可（2026-10-12 真机缺陷）──────────────────────────────
     * `CinemaSyncEngine` 的线程约定是硬的（见它的类注释：所有方法必须落在同一个线程上，
     * 内部不加锁）。而底座把回调送过来的线程有两条 —— `cinema.*` 走 DataChannel，
     * 在 `PeerCoordinator` 的 `rtcScope`（主线程）上；`sys.peer_joined` / `sys.peer_left` /
     * `resume` 走 WebSocket，在 `WsSignaler` 的 `receivePath`
     * （`Dispatchers.IO.limitedParallelism(1)`）上。
     *
     * 少了这一步，`sys.peer_left` 会把 `CinemaSyncEngine.onPeerUnavailable()` 直接跑到
     * IO 线程上，而它第一句就读播放器（`player.isPlayRequested`）—— Media3 的
     * `verifyApplicationThread()`（`ExoPlayerImpl`）会抛
     * `IllegalStateException`「Player is accessed on the wrong thread」，
     * 而这条协程没人接这个异常，进程当场被杀。用户看到的就是
     * **「两端通话打开时，一端退出房间，另一端闪退」**。
     *
     * ── 两个实现选择都不是随手写的 ──────────────────────────────────────────────
     *  - 用 `Dispatchers.Main.immediate`（Android 装配给 [engineDispatcher] 的值）而不是
     *    `Dispatchers.Main`：已经在主线程时 `isDispatchNeeded` 为假，**不额外排队**，
     *    于是 `cinema.*` 那条热路径（每次 play / pause / seek）一点延迟都不加。
     *  - 用 `withContext` 而不是 `launch`：WebSocket 的收报路径是串行的
     *    （`parallelism = 1`，为的是 `rtc.offer` 不越过 `rtc.ice`），用 `launch` 会把
     *    「先 peer_joined 后 peer_left」这个顺序丢掉。这里等它做完再收下一条。
     */
    private suspend fun onEngineThread(block: () -> Unit) {
        withContext(engineDispatcher) { block() }
    }

    override suspend fun onPeerJoined(userId: UserId, nickname: String) {
        // 1.6 刻意**不**在这里下发状态快照。快照的时机挂在「DataChannel 真的 OPEN」
        // （见 onPeerChannelOpened）上：`sys.peer_joined` 到达时链路往往还没建好，
        // 那一刻发出去的快照会直接失败，而对端不会主动来要第二次。
        //
        // 本方法因此**不碰引擎**（所以没有 [onEngineThread]）。哪天要在这里碰，
        // 必须一并搬过去 —— 这条来路也在 WebSocket 的 IO 线程上。
    }

    override suspend fun onPeerLeft(userId: UserId) {
        // §9.4「对端掉线」：本地立即暂停，UI 显示「对方已离开，等待重连」，
        // 宽限期内不退出房间（退房由服务端在宽限超时后广播 sys.room_closed）。
        //
        // [onEngineThread] 是**必需**的，不是保险：这条来路在 WebSocket 的 IO 线程上，
        // 而引擎第一句就要读播放器。
        //
        // 阶段二 P4：顺手撤掉这个人的控制权（`watchingPeers` 与逐成员结论）。
        // 不撤的话，一个已经掉线的人留着一个「在看」的标记，房主那一列会一直显示
        // 「可以控制」—— 而房间座位表上他明明是灰的，两块界面互相矛盾。
        onEngineThread {
            engine?.setPeerWatching(userId, false)
            engine?.onPeerUnavailable()
        }
        _members.update { it - userId }
    }

    override suspend fun onMessage(action: String, env: Envelope) {
        _receivedActions.update { it + action }

        when {
            action == CinemaHello.ACTION -> {
                val hello = CinemaHelloCodec.decode(env.payload) ?: return
                onPeerHello(UserId(env.from), hello)
            }

            // §7 的两条新消息在**玩法层**消化（它们要逐成员状态，引擎只认一条链路）。
            action == CinemaPlayback.ACTION_CONTROL_POLICY -> {
                val policy = CinemaPlaybackCodec.decodeControlPolicy(env.payload) ?: return
                // 只有房主能定策略。判据必须是**发送方是不是房主**，不是「我是不是房主」：
                // 写成后者的话从端会把自己的权限更新整条丢掉（它本来就是从端），
                // 症状是「房主开了全员控制，从端那颗播放键还是灰的」，而两边都没有报错。
                if (hostUserId?.value != env.from) {
                    onEngineThread { engine?.noteExternal("忽略来自 ${env.from.take(8)} 的 control_policy：只有房主能改") }
                    return
                }
                applyControlPolicy(ControlPolicy.fromWire(policy), broadcast = false)
            }

            action == CinemaPlayback.ACTION_MEDIA_STATUS -> {
                val status = CinemaPlaybackCodec.decodeMediaStatus(env.payload) ?: return
                _selfMedia.value = status
                onEngineThread {
                    engine?.setSelfWatching(status.matched && status.watching)
                    engine?.noteExternal(
                        "房主裁决：matched=${status.matched}、watching=${status.watching}" +
                            (status.reason?.let { "（$it）" } ?: ""),
                    )
                }
            }

            // §5.3 的播放动作全部交给引擎。房主 / 从端的分岔在引擎内部
            // （`onRemote` 的每一条分支上），这里不做角色判断 —— 否则「谁处理什么」
            // 会散在两个文件里，而漏掉一条的症状是静默失效。
            action in CinemaPlayback.ACTIONS -> onEngineThread { engine?.onRemote(action, env) }
        }
    }

    /**
     * 收到某个成员的 `hello`：算出「匹配 / 不匹配」，房主再**定向**回一条 `media_status`。
     *
     * ── 为什么不再写进单一的 [_mediaCheck] 就完事 ────────────────────────────────
     * 两人房里「对端」只有一个，一份结论够了；六人房里五个成员各有各的结论，
     * 而 `media_status` 是**逐人**的（§7）。单一那份仍然留着（界面上「对端摘要」
     * 那一栏不想为了多人改版式），它跟随**最后一个**发 hello 的人。
     */
    private suspend fun onPeerHello(peer: UserId, hello: CinemaHello) {
        _mediaCheck.update { it.withRemote(hello) }

        val (matched, reason) = judgeRemote(hello.mediaSignature)

        // 房主侧：更新逐成员结论，并让引擎知道「这个人算不算在看」——
        // 控制权判据（§7）里的 `isWatching` 就是它。
        //
        // ⚠️ **这张表只属于房主**（[members] 的 KDoc 早就这么写：「从端为空」）。
        // 从端也会收到 hello —— `onPeerChannelOpened` 两侧都发 —— 不设守卫的话
        // 房主会被写成一条「成员裁决」，而那条裁决是用**从端当时的摘要**比出来的：
        // 从端还没选片时比出 `matched=false`，界面就把房主显示成「旁听（本机还没
        // 选片）」。2026-11-06 在三台模拟器上就是这么撞见的：房主明明在播，
        // 从端这一行却说他旁听，而且从端再选片也不会刷新它（它只跟着 hello 走）。
        if (isHostLocally) {
            _members.update {
                it + (peer to MemberMedia(
                    nickname = hello.nickname,
                    matched = matched,
                    watching = matched,
                    reason = reason,
                    signature = hello.mediaSignature,
                ))
            }
        }
        onEngineThread { engine?.setPeerWatching(peer, matched) }

        if (isHostLocally) {
            publishMediaStatus(peer)
            // §7：匹配上的成员要**立刻**拿到一份快照（迟到者恢复路径）。
            if (matched) onEngineThread { engine?.sendSnapshotTo(peer) }
        }
    }

    /** 房主 → 某成员：这一条 `media_status` 是他的全部裁决。 */
    private fun publishMediaStatus(peer: UserId) {
        val media = _members.value[peer]
        val status = CinemaMediaStatus(
            matched = media?.matched == true,
            watching = media?.watching == true,
            reason = media?.reason,
        )
        messenger?.send(
            CinemaPlayback.TYPE_MEDIA_STATUS,
            CinemaPlaybackCodec.encodeMediaStatus(status),
            peer,
        )
    }

    // ── 控制策略（房主侧 UI 的入口）────────────────────────────────────────

    /**
     * 改全局开关（房主）。会**立刻广播**一条 `cinema.control_policy`。
     *
     * 从端调用是空操作：策略只有房主能定（§7）。
     */
    fun setControlDefaultAllow(allow: Boolean) {
        if (!isHostLocally) return
        applyControlPolicy(_controlPolicy.value.copy(defaultAllow = allow), broadcast = true)
    }

    /**
     * 单独授权 / 撤销某个人（房主）。[allow] = null 表示**撤掉 override**，
     * 让他重新跟随全局开关 —— 这三态正是契约真值表要的。
     */
    fun setMemberAllowed(userId: UserId, allow: Boolean?) {
        if (!isHostLocally) return
        val current = _controlPolicy.value
        val overrides = if (allow == null) {
            current.overrides - userId
        } else {
            current.overrides + (userId to allow)
        }
        applyControlPolicy(current.copy(overrides = overrides), broadcast = true)
    }

    private fun applyControlPolicy(policy: ControlPolicy, broadcast: Boolean) {
        _controlPolicy.value = policy
        // 引擎只能在它那条线程上被碰（见类注释的线程约定）。这里不 `withContext` 等结果：
        // 调用点在界面上，等它反而会卡一帧。
        actionScope.launch { onEngineThread { engine?.setControlPolicy(policy) } }
        if (broadcast && isHostLocally) {
            messenger?.send(
                CinemaPlayback.TYPE_CONTROL_POLICY,
                CinemaPlaybackCodec.encodeControlPolicy(policy.toWire()),
                // 广播：所有人都要知道自己的权限（`to` 可空，§4）。
                null,
            )
            actionScope.launch {
                onEngineThread {
                    engine?.noteExternal(
                        "控制策略已更新：defaultAllow=${policy.defaultAllow}、" +
                            "overrides=${policy.overrides.size}",
                    )
                }
            }
        }
    }

    override suspend fun onSessionRestored(room: Room) {
        // 1.3：断线恢复后重建本地视图。角色要跟着重判 —— 服务端可能在这段时间里
        // 把房主换成了别人（原房主宽限超时被置离线时不会换，但 Room 是权威，照读不亏）。
        //
        // 这条来路是 `sys.resume` 的应答，同样在 WebSocket 的 IO 线程上；而
        // `applyHost` → `CinemaSyncEngine.setRole` 会经 `resetSyncState()` 写播放器
        // （`player.setSpeed(1f)`），所以必须搬回引擎线程。
        //
        // **刻意不复位通话**：回到的是原来那个房间、原来那场会话（§9.4 的宽限期），
        // 断线前开着通话的人回来时通话必须还在。复位只发生在「进新房」（[onRoomEntered]）
        // 与「会话结束」（[onSessionEnded]）两条路上。
        onEngineThread {
            applyHost(room)
            // 恢复成功 = 会话还在（见 [onSessionEnded] 的幂等守卫）。
            sessionEnded = false
        }
    }

    /**
     * **进房事件**：底座确认本机进了一个新房间（建房 / 加入那一条应答的成功路径）。
     *
     * ── 为什么复位必须也挂在这一条上（2026-10-12 真机缺陷「进房仍自动开通话」）──────
     * 1.9 把复位挂在 [onRoomUpdated] 的「房号变了」这个判据上，而那条路的来路是
     * `Signaler.room` 这个 StateFlow —— 它**会按相等去重**。「退房之后很快重进同一个
     * 房号」的一轮变化（`null` → 同一个 `Room`）在收集方来不及跑时会被合并成
     * 「没有变化」，于是 [onRoomUpdated] 一次都不会被调到：`lastRoomId` 停在旧值、
     * 通话意图停在上一场的 true。下一次建链时 `openPeerConnection()` 就把麦克风
     * 接回来了 —— 用户没按过任何开关。
     *
     * 复位挂在**事件**上就没有这个洞：事件是底座在应答路径上直接派发的（见
     * `WsSignaler.onResponseEnvelope`），状态流去重不了它。
     *
     * ── 两条判据为什么并存 ──────────────────────────────────────────────────────
     * 不是冗余：[onRoomUpdated] 的「房号变了」还管着「在房间里直接换到另一个房号」
     * 那条路（它不是一次进房，`_room` 上不会出现 `null`，所以走不到本方法）。
     * 而本方法管着「底座确认进房了但状态流没送出变化」这一条。
     *
     * 同一房号重进（断线恢复）走 [onSessionRestored]，**不**到这里 —— 通话照旧保留，
     * 这正是 §9.4 宽限期要的。
     */
    override suspend fun onRoomEntered(room: Room) {
        // 这条来路在 WebSocket 的 IO 线程上（`WsSignaler` 的应答路径），而
        // `applyHost` 会经 `setRole` 写播放器，`setCallEnabled` 也会碰链路状态 ——
        // 与 [onSessionRestored] 同一个理由，整段搬回引擎线程。
        onEngineThread {
            // 新的一场会话：通话意图从「关」开始。幂等，见 [onSessionEnded] 的说明。
            setCallEnabled(false)
            // 记下来：`Room` 变 null 时靠它判「这一次是不是真的退房」。
            // 在这一条上记而不是只等 [onRoomUpdated]，是因为后者可能根本收不到
            // 这一轮变化（上面那段去重）—— 那时「退房」会连收尾都不做。
            lastRoomId = room.roomId
            sessionEnded = false
            // 角色重判。`setRole` 自身幂等（值没变就直接 return），所以
            // 「刚进房时 myUserId 还没送到」这一次调用不会写出误导性的角色行。
            applyHost(room)
        }
    }

    /**
     * **进入影厅页**（房间画面）：麦克风意图归零。
     *
     * ── 为什么复位还得挂在「页面」上（2026-10-13 真机缺陷「进房仍自动开通话」）────────
     * 上面三条复位路（[onRoomEntered] / [onRoomUpdated] 的房号判据 / `PeerCoordinator`
     * 那条无条件的 `rtc.close()`）**全部挂在信令事实上**。而「进影厅」这个动作
     * 可以**一条信令都不产生**：
     *
     *  `MainActivity` 的返回键从影厅退回大厅时**不调** `Signaler.leave()`（大厅那张卡片
     *  仍然写着「已在房间里」—— 房间、链路、通话意图一样都没变），而大厅里的
     *  「进入影厅」按钮只做 `homePage = HOME_HALL`。于是「影厅 → 大厅 → 影厅」这一轮里：
     *  `Room` 一次都没变（[onRoomUpdated] 看不到）、没有进房应答（[onRoomEntered]
     *  不会跑）、`rtc.close()` 也不会被调 —— 麦克风在用户完全没按过开关的情况下
     *  **一直是开着的**，绿点从大厅一路亮回影厅。用户报的正是这一条。
     *
     *  这一轮之所以特别容易撞上：大厅那张「已在房间里」的卡片上就有「复制房间号」，
     *  而「进入影厅」就在它旁边 —— 建房之后回头抄一次房号再进厅，是最自然的一串动作。
     *
     * ── 口径为什么是「进影厅页」，而不是「离开影厅页就关」──────────────────────────
     * 通话在本项目的模型里是**会话级**的：切到设置页看一眼再回来，房间还在、链路还在、
     * 对端还在说话，那时掐麦是纯损失（本方法**不会**在那条路上被调到 —— 切底栏不动
     * `homePage`）。而「进入影厅」是一次明确的**在场**开始，用户的要求也正是这一句：
     * 「进房间只建立通话链路，麦克风输入默认禁用」。
     *
     * 复位的是 `requested`（= 本机麦克风接不接进链路），**链路本身不受影响**：
     * 音频 m 行与收听照旧常驻，见 `planCallAudio` 的语义说明。
     *
     * 与其余三条复位路重复调用是零代价的（`WebRtcPeerLink.setCallEnabled` 带
     * 「值没变就直接 return」的短路）。
     */
    fun onHallEntered() {
        setCallEnabled(false)
    }
}
