package com.cinemahall.desktop.app.net

import com.cinemahall.core.media.PlaybackTarget
import com.cinemahall.core.model.MonotonicClock
import com.cinemahall.core.model.UserId
import com.cinemahall.core.network.WsSignaler
import com.cinemahall.core.protocol.MessageRouter
import com.cinemahall.core.session.CinemaHandler
import com.cinemahall.core.session.CinemaSyncEngine
import com.cinemahall.core.session.DefaultMonotonicClock
import com.cinemahall.core.session.PeerCoordinator
import com.cinemahall.desktop.app.AppPaths
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.app.rtc.Command
import com.cinemahall.desktop.rtc.AecChain
import com.cinemahall.desktop.rtc.DesktopPeerLink
import com.cinemahall.desktop.rtc.ProcessAudioHelper
import com.cinemahall.desktop.rtc.SingleThreadDispatcher
import com.cinemahall.desktop.rtc.WebRtcBackend
import com.cinemahall.desktop.rtc.WebRtcEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * W7-C5 的装配中枢：把 `core:*` 里那几个纯 JVM 的部件接成一条可用的链路。
 *
 * ── 与 Android 的差异（只有三处，其余逐个照搬）────────────────────────────
 * 1. **没有 Service**。Android 用前台服务保证「退出界面但会话不死」；桌面没有这个
 *    需求（窗口关了就是不用了），所以会话宿主是这个对象本身 —— 由 `Main` 持有，
 *    活到进程结束。tick 由 `CinemaSyncEngine` 自己的协程跑，不需要额外的记账进程。
 * 2. **`engineDispatcher` 用 `Dispatchers.Swing`**。`CinemaHandler` 的注释明确要求
 *    「代表 UI 线程、且已在其中时不额外排队」的 dispatcher；桌面就是 Swing 的 EDT。
 *    （不能用 `desktop:rtc` 的 `SingleThreadDispatcher` —— 那是 `LinkDispatcher`，
 *    只有 `execute(Runnable)`，不是 `CoroutineDispatcher`。）
 * 3. **网络变化没有源**。Android 有 `ConnectivityManager`，桌面没有等价物；所以
 *    `PeerCoordinator.onNetworkEvent()` 这条「切网自动重启 ICE」的能力在桌面上
 *    不会自动触发。这是**已知限制**，写进了 `docs/Windows-W7-拆分.md`。
 *
 * ── 顺序是硬的 ────────────────────────────────────────────────────────────
 * `PeerCoordinator` 的构造里用 `CoroutineStart.UNDISPATCHED` 订阅了 `rtcSignals` /
 * `events` / `messages` 三个**无 replay 的 SharedFlow**。所以必须**先建协调者、
 * 再 `connect`**：反过来的话第一轮 `rtc.offer` 会直接丢掉，症状是「两端都 Online、
 * 但永远不建链」，且没有任何报错。
 */
class DesktopModule(
    /** 信令服务地址（`ws://host:port` 或 `wss://host:port/ws`）。 */
    private val endpoint: String,
    /** 本机身份。激活态 = 账号的 `deviceId`；`AUTH_MODE=off` 时回退到持久化设备 id。 */
    val myUserId: UserId,
    /**
     * 握手用的 bearer token（W7-C7）。
     *
     * `WsSignaler` 只会在**建连那一刻**同步取一次（`openSocket` 里贴
     * `Authorization: Bearer …`），所以这里给的是「同步读内存」的函数，
     * 不是 `suspend` 的凭据读取 —— 一次握手不该去解密磁盘。
     *
     * 默认 `null` 时**不贴头**，供 `AUTH_MODE=off` 的本地开发与既有门禁沿用。
     * 生产是 `AUTH_MODE=required`：没有这个头会被 `verifyClient` 直接 401
     * （客户端表现为 `Connecting → Reconnecting` 的退避循环，永远到不了建房）。
     */
    private val authToken: () -> String? = { null },
    /**
     * 选定的麦克风输入源（`Mixer.Info.name`，null = 系统默认）。来自 `settings.json`。
     *
     * 每条链路各自一个采集子进程，所以这里要**记住它**并在 [newLink] 里下发给 helper ——
     * 否则用户在设置里选了设备，下一次建链又回到系统默认。
     */
    private val micInput: String? = null,
) {

    /** 全进程同源的那条单调时间轴（`System.nanoTime()/1e6`，`core:model` 就是这么定义的）。 */
    val clock: MonotonicClock = DefaultMonotonicClock

    /**
     * 会话作用域。**必须与 `CinemaHandler.engineDispatcher` 是同一条线程**：
     * 引擎内部不加锁，靠「所有状态改动都在同一线程」来保证一致性。
     */
    private val engineContext = CoroutineExceptionHandler { _, error ->
        Log.warn("[session] 协程异常：${error.javaClass.simpleName}: ${error.message}")
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing + engineContext)

    val handler = CinemaHandler(engineDispatcher = Dispatchers.Swing, clock = clock)

    init {
        // ⚠️ 必须显式把 EDT 拉起来。`Dispatchers.Swing` 的派发走 `SwingUtilities.invokeLater`，
        // 而 EDT 是**惰性启动**的：门禁自检那条路径（`--session-test`）不建窗口、不碰 AWT，
        // 于是 `PeerCoordinator` 里那些 `scope.launch { collect }` 永远等不到恢复 ——
        // 症状是「协调者打了『就绪』就再无下文，链路永远停在 Negotiating」。
        // 产品路径本来就有窗口，这一句是给无窗口路径兜底的。
        runCatching { javax.swing.SwingUtilities.invokeLater { Log.info("[module] EDT 已启动") } }
        // 门禁用：证明 `Dispatchers.Swing` 真的在调度（只看前 3 次，不刷日志）。
        scope.launch {
            repeat(3) {
                delay(1_000)
                Log.info("[module] swing 心跳 #$it")
            }
        }
        // 播放同步的节拍器。放在构造期而不是 `connect()`：它与「连没连上服务端」无关，
        // 而引擎要等影厅页组合后才出现 —— 早起的循环每轮重读一次，晚到也不漏。
        ensureSyncTicker()
    }

    val router = MessageRouter(listOf(handler))

    val signaler = WsSignaler(
        router = router,
        // 与 `CinemaHandler` / `PeerCoordinator` 同一个实例：信封的 `sentAtMs` 与
        // 同步引擎的时间轴必须是同一条，否则量出来的 RTT 里混着两种时钟的差值。
        monotonicMs = { clock.nowMs() },
        // 生产（`AUTH_MODE=required`）在握手阶段就要这枚 token，见 ServerSignaling
        // 的 `verifyClient`。回调是同步的：`openSocket()` 里直接贴头。
        authToken = authToken,
    )

    private val linkDispatcher = SingleThreadDispatcher("desktop-rtc")

    /**
     * 真正的一条 WebRTC 链路。`PeerCoordinator` 只认 `PeerLink` 这个端口。
     *
     * ── 为什么从「一条实例」变成「一个工厂」（阶段二 P2 合并后）────────────────
     * `PeerCoordinator` 在 3 人以上房间里是**每个成员一条链路**，所以它的构造参数
     * 从 `rtc: PeerLink` 改成了 `newLink: () -> PeerLink`。桌面今天只有 1:1 的玩法，
     * 但协调者对每个座位都会要一条，所以这里接工厂。
     *
     * ── 为什么要「认领」而不是各建一条 ────────────────────────────────────────
     * 界面与自检读的是 [link]（最近一条），而协调者是在 [connect] 时才向工厂要链路。
     * 如果界面先摸过 `link`（影厅页一打开就会订阅它的 `state`），而工厂又新建一条，
     * 那就是**两个对象**：协调者在那条上建链，界面盯着这条永远 `Idle`，
     * 症状与「压根没建链」一模一样。所以先被摸到的那条会被记成 `pendingLink`，
     * 工厂优先把它交出去；之后再有新成员才另建，并把 [link] 指向最新那条。
     */
    private val linkLock = Any()

    @Volatile
    private var pendingLink: DesktopPeerLink? = null

    @Volatile
    private var currentLink: DesktopPeerLink? = null

    /**
     * **唯一的**链路装配点：界面先摸（[link]）与协调者来要（[createLink]）都必须走这里。
     *
     * ── 为什么这条注释值得留着 ──────────────────────────────────────────────────
     * 2026-11-06 真机第二次报「本机没有接入音频采集」：`0.1.2` 只在 `createLink` 的
     * `else` 分支里补了 `uplink`，而 [link] 的空值分支仍旧是
     * `DesktopPeerLink(rtcBackend, linkDispatcher)`（`uplink` 默认 null）。界面一打开
     * 影厅页就读 `module.link`（`CinemaScreen` 订阅 `link.state`），于是造出的那条
     * **空壳链路**先被记成 `pendingLink`，协调者随后认领的正是它 ⇒ 房间里的链路天生
     * 没有音频上行，点开麦克风只能落到「没有接入音频采集」那一支，收发永远是 `-`。
     *
     * `0.1.2` 的语音门禁之所以通过，是因为 `--rtc-call-test` 是**直接**
     * `DesktopPeerLink(..., uplink = …)` 构造的，绕过了这两条入口 —— 门禁测到了
     * 「链路能用麦克风」，没测到「产品路径把麦克风接上去」。所以现在两份构造收敛成
     * 一个函数，并由 `DesktopModuleAudioUplinkTest` 钉住「界面先摸的那条也带上行」。
     *
     * 为什么**每条链路**一份上行：`DesktopPeerLink` 是一条 PeerConnection 的 owner，
     * 采集到的 PCM 要各自 `pushCapturePcm` 进自己的 backend。房间级共享采集
     * （一份采集挂 N-1 个 sender）是后续 `RoomAudioSession` 的事；在那之前三人房里
     * 会有 2 个 helper 子进程，这是已知代价（每人一份系统麦克风会话）。
     *
     * ⚠️ **backend 也必须每条链路一份**（2026-11-06 真机「安卓之间正常、和 Windows 两边都没声」）：
     * `WebRtcBackend` 的 `pc` / `audioTrack` / `audioTransceiver` / `downlink` / 下行 sink
     * 全是**单实例状态**。共用一份时，第二个成员 `start()` 就会把这些字段覆盖成新的那条，
     * 于是前一条的音频找不到自己的设备与轨道 —— 四人房（桌面 3 条链路）里音频全乱，
     * 而 1:1 房永远看不出问题（只有一条链路）。原生 `PeerConnectionFactory` 仍是进程级
     * 单例（`WebRtcEngine.acquire()`），所以多份 backend 不额外建工厂；`WebRtcBackend.close()`
     * 只释放自己的轨道与设备，不碰工厂。
     */
    private fun newLink(): DesktopPeerLink =
        DesktopPeerLink(
            WebRtcBackend(),
            linkDispatcher,
            uplink = ProcessAudioHelper(Command.audioHelper()).also {
                // 采集设备在建链时就定下来：helper 是子进程，起它的那一刻才知道用哪个麦。
                it.setPreferredInput(selectedMicInput)
            },
            // AEC 是**原生 APM**，而这条装配现在可能发生在「界面第一次摸链路」的时候 ——
            // 那一刻 native 未必已经加载（`WebRtcEngine.acquire()` 还没跑）。起不来就退回
            // 「无 AEC」：麦克风照用、只是没有回声消除，绝不因为它的初始化把页面打死。
            aec = runCatching { AecChain() }
                .onFailure {
                    Log.warn("[rtc] AEC 初始化失败，本条链路无回声消除：${it.javaClass.simpleName}: ${it.message}")
                }
                .getOrNull(),
        ).also { currentLink = it }

    /** 当前选定的麦克风（运行期会被 [setAudioInput] 改掉；建链时下发给 helper）。 */
    @Volatile
    private var selectedMicInput: String? = micInput

    /**
     * 换麦克风输入源（2026-11-12 用户口径：通话卡里的「麦克风」下拉）。
     *
     * 只改「记住的那个名字」+ 通知当前链路；持久化由调用方（`AppRoot`）写 `settings.json` ——
     * 模块不该知道设置文件在哪。
     */
    fun setAudioInput(name: String?) {
        selectedMicInput = name?.takeIf { it.isNotBlank() }
        currentLink?.setAudioInput(selectedMicInput)
        Log.info("[rtc] 麦克风输入源 = ${selectedMicInput ?: "系统默认"}")
    }

    /** 当前选定的麦克风输入源（界面用它显示选中态）。 */
    val audioInput: String? get() = selectedMicInput

    val link: DesktopPeerLink
        get() = currentLink ?: synchronized(linkLock) {
            // 先摸到的那条会被记成 `pendingLink`，由 `createLink` 认领 —— 这样界面盯着的
            // 与协调者建链的必须是**同一个对象**（见类注释里「为什么要认领」那一段）。
            currentLink ?: newLink().also { pendingLink = it }
        }

    private fun createLink(): DesktopPeerLink = synchronized(linkLock) {
        val claimed = pendingLink
        if (claimed != null) {
            pendingLink = null
            // 认领之后 `currentLink` 必须指回它：界面读的是 `currentLink`，指错就又成了两个对象。
            currentLink = claimed
            claimed
        } else {
            newLink()
        }
    }

    @Volatile
    var coordinator: PeerCoordinator? = null
        private set

    /** 门禁用：`ensureCoordinator` 真正新建了几次。大于 1 = 并发/重入缺陷。 */
    @Volatile
    var coordinatorCreations = 0
        private set

    private var rtcLogJob: kotlinx.coroutines.Job? = null

    /** 信令状态日志的订阅；只建一次（重复 `connect()` 不该叠加）。 */
    private var stateLogJob: kotlinx.coroutines.Job? = null

    /**
     * 收发计数 / 链路统计的采样循环；同样只建一次。
     *
     * 没有它，面板上的 `本机发送 / 收到` 会**永远是 `-`**：`DesktopPeerLink` 的包计数来自
     * `getStats` 快照（`backend.audioRtp()`），而桌面这一侧没有任何东西会自己去 `getStats`
     * —— Android 至少还有 ⑦ 面板上的「刷新统计」按钮，桌面连那个按钮都没有。
     * 2026-11-06 真机就是「红字消失了、按钮显示 `关闭麦克风`，两个数还是 `-`」。
     */
    private var statsJob: kotlinx.coroutines.Job? = null

    /**
     * 播放同步的节拍器：每 `localTickMs`（默认 500ms）调一次 `CinemaSyncEngine.onTick`。
     *
     * ── 为什么必须有它（2026-11-06 真机：两个方向的播放同步都不动）──────────────────
     * 引擎**自己不持有协程**（见 `CinemaSyncEngine` 的类注释：所有输入都由外部驱动，
     * 这样 §6.3 的每一档才能在单测里用假时钟逐毫秒断言）。定时驱动于是成了装配点的责任：
     * Android 那条在 `CinemaSessionService` 的循环里（`syncEngine?.onTick(now)`），
     * 而桌面这一侧**一直没有对应的循环** —— `onTick` 一次都没被调用过。四个后果同时出现：
     *
     *  1. 房主不发心跳（心跳只在 `onTick` 里发）⇒ 从端没有任何东西可用来对齐；
     *  2. `detectPlayStateChange` 从不运行 ⇒ 本机起播 / 暂停一次都没被广播出去；
     *  3. 从端 `runFollowerCorrection` 从不运行 ⇒ 漂移不计算、硬 seek 不发生；
     *  4. 从端的时钟探测（`sync_ack`）从不发起 ⇒ RTT 恒为 0、`rttHalf` 恒为 0。
     *
     * 面板上看到的是「链路已通、角色正确、`anchored=false`、两个方向一动不动」——
     * 与「同步算法坏了」完全同形，所以这一条必须先于任何算法改动被查出来。
     *
     * ── 为什么每轮重新读 ──────────────────────────────────────────────────────
     * 引擎是界面在影厅页首次组合时才建的（`attachPlayback`），节拍器若在构造期抓一个
     * 引用，抓到的就是 null 且永不恢复；`localTickMs` 也能在「设置 → 播放设置」里改，
     * 缓存它就等于让那条设置不生效。
     *
     * 线程：`scope` 是 `Dispatchers.Swing`，与 `CinemaHandler.engineDispatcher` 同一条
     * —— 引擎内部不加锁，全部状态改动必须落在同一线程。
     */
    private var syncTickJob: kotlinx.coroutines.Job? = null

    private fun ensureSyncTicker() {
        if (syncTickJob?.isActive == true) return
        syncTickJob = scope.launch {
            while (true) {
                val engine = handler.syncEngine
                if (engine != null) {
                    runCatching { engine.onTick(clock.nowMs()) }
                        .onFailure { Log.warn("[sync] tick 异常：${it.javaClass.simpleName}: ${it.message}") }
                }
                delay(handler.syncConfig.localTickMs)
            }
        }
    }

    /**
     * 建协调者并把它接到玩法上。**可以重复调用**（界面重建、换账号），已存在时直接返回。
     *
     * 必须在 [connect] 之前调用一次。
     */
    fun ensureCoordinator(): PeerCoordinator {
        coordinator?.let { return it }
        synchronized(this) {
            coordinator?.let { return it }
            val created = PeerCoordinator(
                myUserId = myUserId,
                signaler = signaler,
                newLink = ::createLink,
                router = router,
                scope = scope,
                // 通道刚 OPEN 时补发 hello + 让房主下发快照。**在链路通的那一刻**才算数：
                // `sys.peer_joined` 到达时链路往往还没建好，那时发的 hello 会直接失败。
                //
                // 阶段二 P2 起这两个回调都要带 `peer`：三人以上时「哪条链路通了」必须点名。
                // 不点名的话，房主会把某一个成员的 hello 当成「对端」的唯一结论，其余成员
                // 全被盖上同一个人的片源判断 —— 这正是 P2 之前一对一的残留。
                onPeerChannelOpened = { peer -> handler.onPeerChannelOpened(peer, signaler.sessionEpoch) },
                onPeerChannelClosed = { peer -> handler.onPeerChannelClosed(peer) },
                clock = clock,
            )
            // 后置绑定：协调者建好之后玩法才知道「怎么发」和「怎么开关麦」。
            handler.bindPeerMessenger(created)
            handler.bindCallAudio(created)
            coordinator = created
            coordinatorCreations++
            Log.info("[module] 协调者已建（第 $coordinatorCreations 次）")
            // 把链路日志镜像到 app.log —— 桌面上没有 logcat，出问题时只有这个文件。
            //
            // ⚠️ 这里有两个坑，都在同一天把排障带偏过两次：
            //  1. `log` 是**有上限**的列表：满了之后每条新消息都「加一条、挤掉一条」，
            //     `size` 恒定。按长度判「有没有新行」会在缓冲满之后永久静默。
            //  2. 它还是**最新在前**（`PeerCoordinator.notice` 用的是 prepend），
            //     所以新行在上次那行**之前**，不是之后。
            // 正确做法：按内容定位上次打到哪里，打印它前面那一段（反序还原时间顺序）。
            rtcLogJob = scope.launch {
                var lastSeen: String? = null
                created.log.collect { lines ->
                    val known = lastSeen?.let { lines.indexOf(it) } ?: -1
                    // -1 = 上次那行已被挤出缓冲：整段重放一次，宁可重复也不漏。
                    val fresh = if (known >= 0) lines.take(known) else lines
                    fresh.asReversed().forEach { Log.info("[link] $it") }
                    lastSeen = lines.firstOrNull() ?: lastSeen
                }
            }
            return created
        }
    }

    /** 连服务端。必须先 [ensureCoordinator]。 */
    fun connect() {
        ensureCoordinator()
        // 状态迁移留痕（W7-C7）：生产开着 `AUTH_MODE=required`，握手被 401 时
        // `WsSignaler` 会一直退避重连，界面上只是「正在重连」。这一行是判断
        // 「到底连上没有」的唯一客户端证据 —— `Online` 出现即握手通过。
        if (stateLogJob == null) {
            stateLogJob = scope.launch {
                signaler.state.collect { state -> Log.info("[signal] state=$state endpoint=$endpoint") }
            }
        }
        // 统计采样：每秒一次，让面板的 `本机发送 / 收到` 有数可读（见 [STATS_POLL_MS]）。
        // 房间为空时 `refreshStats()` 是个空循环，不必额外判条件。
        if (statsJob == null) {
            statsJob = scope.launch {
                while (true) {
                    delay(STATS_POLL_MS)
                    runCatching { coordinator?.refreshStats() }
                }
            }
        }
        scope.launch {
            runCatching { signaler.connect(endpoint, myUserId) }
                .onFailure { Log.warn("[signal] 连接 $endpoint 失败：${it.message}") }
        }
    }

    /**
     * 影厅页首次组合时调一次。同一个播放器只建一个引擎。
     *
     * 建引擎的同时确保节拍器在跑：`onTick` 是引擎唯一的定时输入，少了它这条引擎
     * 就只是个不会动的状态机（见 [ensureSyncTicker]）。
     */
    fun attachPlayback(player: PlaybackTarget): CinemaSyncEngine {
        val engine = handler.attachPlayback(player)
        ensureSyncTicker()
        return engine
    }


    /**
     * 房间视图变了（进房 / 退房 / 成员上下线）。由 UI 层订阅 `signaler.room` 后调用。
     *
     * 判据必须是 `Room.hostUserId`（服务端给的），不能拿协商角色（谁是 offer 方）代替：
     * 后者由 userId 字典序决定，两者成因无关。
     */
    suspend fun onRoomChanged() = withContext(Dispatchers.Swing) {
        val room = signaler.room.value
        handler.onRoomUpdated(room, myUserId)
        // 排障（W7-C7）：房间视图每次变化留一行。桌面上没有 logcat，用户报
        // 「对方看不到我」时，这一行是区分「压根没进房」与「进房了但对端没上线」
        // 的唯一依据；`members`/`online` 两个数也是本轮验收要看的读数。
        if (room == null) {
            Log.info("[room] 已离开（self=${myUserId.value}）")
        } else {
            Log.info(
                "[room] id=${room.roomId} members=${room.members.size} online=${room.onlineCount} " +
                    "host=${room.hostUserId.value == myUserId.value} self=${myUserId.value}",
            )
        }
    }

    /** 界面用：重发一次本机片源摘要（对端重启过、或它的接收状态丢过一次时用）。 */
    fun resendSignature(): Boolean = handler.resendHello()

    /** 界面用：退房。**连接保留** —— 退房之后还能再建房/加入，不必重连。 */
    fun leaveRoom() {
        scope.launch {
            runCatching { signaler.leave() }
                .onFailure { Log.warn("[cinema] 退房失败：${it.message}") }
        }
    }

    fun close() {
        runCatching { rtcLogJob?.cancel() }
        runCatching { stateLogJob?.cancel() }
        runCatching { statsJob?.cancel() }
        statsJob = null
        stateLogJob = null
        val created = coordinator
        coordinator = null
        // 玩法先收自己那一份（可能还要调链路：停通话、清定时器），它内部自己切线程。
        runCatching { handler.onSessionEnded() }
        runCatching { created?.closeLink() }
        // ⚠️ 收尾必须**同步**跑在 dispatcher 线程上，再停执行器。
        // `SingleThreadDispatcher.close()` 走 `shutdownNow()`，会丢弃排队任务：
        // 早先这里是 `link.close()`（只投一条任务）+ 立刻 `linkDispatcher.close()`，
        // 结果 PC 与 native 工厂都没释放，进程带着活的 native 线程退出 —— 门禁实测
        // 就是 0xC0000005（访问违规），且发生时 `done` 已经打完了，很容易误判成"链路问题"。
        // ⚠️ 这里**不能**读 `link`：它的 getter 在空的时候会顺手新建一条，
        // 而关闭路径上凭空造一条没人用的链路，只会让 `WebRtcEngine.shutdown()`
        // 多留一个活着的对象。只收已经建出来的那条。
        val linkRef = currentLink
        runCatching {
            linkDispatcher.runBlocking {
                if (linkRef != null) runCatching { linkRef.closeNow() }
                // 工厂是进程级单例：dispose 必须在建它的那条线程上（acquire 的约定）。
                runCatching { WebRtcEngine.shutdown() }
            }
        }
        runCatching { linkDispatcher.close() }
        runCatching { scope.cancel() }
    }

    companion object {

        /**
         * 统计采样周期（毫秒）。
         *
         * 1 秒是「面板上的数看起来在动」与「别为一次 getStats 长期占 cpu」之间的折中：
         * 语音一包 20 ms，1 秒的粒度足够让人看出「在发 / 在收」，也不至于抖动。
         */
        private const val STATS_POLL_MS = 1_000L

        /**
         * 本机身份（桌面没有账号系统）。
         *
         * 存在 `data/identity.txt` 里而不是每次随机：换一个 id 就等于换一个人，
         * 服务端的座位、对端的协商角色（字典序）全都会变 —— 而症状只是
         * 「重开之后对方那边多了一个再也不会回来的成员」。
         */
        fun loadOrCreateUserId(): UserId {
            val file = File(AppPaths.dataDir, "identity.txt")
            runCatching {
                if (file.isFile) {
                    val existing = file.readText().trim()
                    if (existing.isNotEmpty()) return UserId(existing)
                }
            }
            val created = "desktop-${UUID.randomUUID().toString().take(8)}"
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(created)
            }
            return UserId(created)
        }

        /** 大厅默认昵称：本机用户名（拿不到就退化成「本机」）。 */
        fun defaultNickname(): String =
            System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "本机"
    }
}
