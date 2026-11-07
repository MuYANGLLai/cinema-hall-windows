package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinemahall.core.library.ScanBucket
import com.cinemahall.core.model.UserId
import com.cinemahall.core.network.SignalErrors
import com.cinemahall.core.network.SignalException
import com.cinemahall.core.network.SignalState
import com.cinemahall.desktop.app.AppPaths
import com.cinemahall.desktop.app.AppSettings
import com.cinemahall.desktop.app.DEFAULT_SIGNAL_ENDPOINT
import com.cinemahall.desktop.app.KeepAwake
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.PlayerController
import com.cinemahall.desktop.app.account.AccountPhase
import com.cinemahall.desktop.app.account.DesktopAccount
import com.cinemahall.desktop.app.net.DesktopModule
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.library.DesktopMovieCatalog
import com.cinemahall.desktop.library.ScanPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * 导航项。
 *
 * `icon` 是**自绘**图标（`AppIcon`），不是 emoji 也不是字体字形 —— 理由见
 * `DesktopIcons.kt` 里 `AppIcon` 的注释（emoji 是彩色位图、字形会掉成方块）。
 */
enum class Screen(val label: String, val icon: AppIcon) {
    Home("首页", AppIcon.Home),
    Cinema("影厅", AppIcon.Cinema),
    Library("影片库", AppIcon.Library),
    Settings("设置", AppIcon.Settings),
    Diagnostics("诊断", AppIcon.Diagnostics),
}

/**
 * 侧边栏上真正列出来的四格。
 *
 * `Diagnostics` 不在里面：2026-11-07 起诊断内容并进设置页，多了这一格只会让
 * 「首页 / 影厅 / 影片库 / 设置」的直觉被打破。枚举项保留是给 `--screen=diagnostics`
 * 那条门禁路径用的。
 */
private val NAV_ENTRIES = listOf(Screen.Home, Screen.Cinema, Screen.Library, Screen.Settings)

/** 快捷键的落点（窗口级按键在 Main 里接，动作在这里拿）。 */
object Shortcuts {
    var onPlayPause: () -> Unit = {}
    var onSeekBy: (Long) -> Unit = {}
    var onVolumeBy: (Float) -> Unit = {}
    var onOpenFile: () -> Unit = {}
}

/** 门禁用：进影厅页之后的自动动作。产品路径永远是 null。 */
data class CinemaAuto(val create: Boolean, val roomId: String?)

/**
 * 信令模块的持有者。
 *
 * ── 为什么要有这一层，而不是一个 `var module` ─────────────────────────────────
 * 它有两个主人：Compose（要读 `module` 触发重组）与退出路径（要**一定**关掉它）。
 * 用一个普通字段当 Compose 状态，退出时读到的会是初次组合的那个 null ——
 * 于是模块永远不会被 `close()`，而症状是「关窗口后进程还在后台、麦克风灯亮着」。
 * 用 `mutableStateOf` 做字段，两个主人读到的就是同一个值。
 */
class CinemaHolder {
    var module by mutableStateOf<DesktopModule?>(null)
        private set

    /**
     * 还允许「进影厅页自动连一次」。
     *
     * 用户按过「断开」就置 false：否则一离开再回来又自动连上，那个按钮等于假的。
     */
    var autoConnectPending: Boolean = true

    /**
     * 建模块并连信令。
     *
     * 身份优先级（W7-C7 起）：**账号的 deviceId** > `--identity` 覆盖 > `identity.txt`。
     * 第一项是硬要求：服务端在 `AUTH_MODE != off` 时用握手鉴权出来的 `deviceId`
     * 当房间身份（`ctx.userId = auth.deviceId`），本机若继续用 `identity.txt` 那个
     * 随机 id，两边对「我是谁」的答案就不一致。
     */
    fun connect(
        endpoint: String,
        nickname: String,
        identityOverride: String? = null,
        accountUserId: UserId? = null,
        authToken: () -> String? = { null },
    ): DesktopModule {
        close()
        val mine = accountUserId
            ?: identityOverride?.takeIf { it.isNotBlank() }?.let { UserId(it) }
            ?: DesktopModule.loadOrCreateUserId()
        val created = DesktopModule(endpoint = endpoint, myUserId = mine, authToken = authToken)
        // 昵称要在进房之前就交给玩法：`cinema.hello` 里带的就是它。
        created.handler.localNickname = nickname
        created.connect()
        Log.info("[cinema] 连接信令 $endpoint（本机 ${created.myUserId.value}）")
        module = created
        return created
    }

    fun disconnect() {
        autoConnectPending = false
        close()
    }

    fun close() {
        val current = module
        module = null
        runCatching { current?.close() }
    }
}

@Composable
fun AppRoot(
    controller: PlayerController,
    catalog: DesktopMovieCatalog,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onExit: () -> Unit,
    initialMovie: DesktopMovie?,
    /**
     * 信令模块的持有者。**由 `Main` 建、由 `Main.cleanup()` 关**。
     *
     * 早先由本组件 `remember` 持有并在 `DisposableEffect` 里关 —— 那条路不可靠：
     * `exitApplication()` 不保证在 JVM 退出前把组合拆完，结果 native 工厂还活着、
     * 进程以 `0xC0000005` 结束（截图门禁实测：同一份代码一次 0、一次崩）。
     * 收尾必须由「一定会被调到的那条路径」负责，这就是 `cleanup()`。
     */
    cinema: CinemaHolder,
    /**
     * 账号门（W7-C7）。**由 `Main` 建**：它要活过组合，而且 `--activate=` 这条
     * 门禁路径在窗口出现之前就要能把激活做完。
     */
    account: DesktopAccount,
    initialScreen: Screen = Screen.Home,
    /**
     * 启动时那部片到底要不要**自动起播**。
     *
     * 只有命令行 `--open` 这条门禁路径会关心它（`--no-autoplay` → false）。
     * 2026-11-06 之前这里是写死 `true` 的：`--open=… --no-autoplay` 照样会播，
     * 于是「从端是不是被引擎拉起来的」这类验证全变成了假阳性 —— 从端自己就播了。
     */
    initialAutoplay: Boolean = true,
    /**
     * 是否全屏（窗口级状态由 `Main` 的 `WindowState.placement` 承载）。
     *
     * 全屏时左栏导航整块收起 —— 与安卓端「全屏时外壳一行不留」同一条取向：
     * 留着它，画面上下那两条黑边永远补不匀，用户会以为「全屏没生效」。
     */
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    cinemaAuto: CinemaAuto? = null,
    identityOverride: String? = null,
    /** `--signal-endpoint` 的显式覆盖（null = 用 settings / 内置默认）。 */
    endpointOverride: String? = null,
    /**
     * 无边框窗口的外壳（标题栏 / 拖边缩放）。null = 没有（门禁截图、未来多窗口）。
     */
    chrome: WindowChrome? = null,
) {
    // ── 账号门（W7-C7）：没有有效 token 就不建模块、不连信令 ──────────────────
    // 生产是 `AUTH_MODE=required`：握手不带 bearer token 会被服务端直接 401，
    // 而 `WsSignaler` 把 401 当成普通断线一直退避重连 —— 用户看到的是一串
    // 「正在重连」，真正的原因（没激活）从头到尾没出现过。所以门放在最前面。
    //
    // 地址优先级：`--signal-endpoint` > settings.json > 内置默认（生产）。
    // 少这一层的话，「用本机服务端跑验证」会激活在本机、连却连到生产。
    val resolvedEndpoint = (endpointOverride ?: settings.signalEndpoint)
        .trim().ifBlank { DEFAULT_SIGNAL_ENDPOINT }
    LaunchedEffect(resolvedEndpoint) {
        // 幂等：地址没变且已 Ready 时不会把状态打回 Loading（见 `ensureReady` 的注释）。
        account.ensureReady(resolvedEndpoint)
    }

    when (account.phase) {
        AccountPhase.Loading -> {
            AccountLoadingScreen()
            return
        }

        is AccountPhase.NeedsActivation -> {
            ActivationScreen(
                account = account,
                currentEndpoint = resolvedEndpoint,
                onEndpointChange = { updated ->
                    onSettingsChange(settings.copy(signalEndpoint = updated))
                },
                // 激活成功后不需要在这里做什么：`account.phase` 变 Ready，
                // 重组自然走进下面的主界面；影厅页自己的 `LaunchedEffect`
                // 会负责首次连接。
                onActivated = {},
            )
            return
        }

        is AccountPhase.Ready -> Unit
    }

    val colors = LocalCinemaColors.current
    // 首页不再承载播放器（2026-11-07）：`--open=<file>` 这条门禁路径必须**直接进影厅**，
    // 否则启动后停在首页，而片子已经在播放器里装好了 —— 界面上看不到画面。
    var screen by remember {
        mutableStateOf(if (initialMovie != null) Screen.Cinema else initialScreen)
    }
    var current by remember { mutableStateOf(initialMovie) }
    var volume by remember { mutableStateOf(settings.volume) }
    var autoplay by remember { mutableStateOf(initialAutoplay) }
    var volumeVersion by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val movies by catalog.movies.collectAsState()
    val roots by catalog.roots.collectAsState()
    val categories by catalog.categories.collectAsState()
    val stats by catalog.stats.collectAsState()

    // ── 信令模块（W7）：进过影厅页之后才存在 ────────────────────────────────
    // 只在本地看电影的人不该被一个后台 WebSocket 打扰；而一旦建过，它就活到进程结束
    // （切到影片库、设置页都不影响房间与链路 —— 那正是"会话级"的语义）。
    // 生命周期归 [cinema] 的持有者（Main 建、Main 的 cleanup 关）。
    var endpoint by remember {
        mutableStateOf(resolvedEndpoint)
    }
    var nickname by remember {
        // 优先用**账号昵称**（激活时填的那个）：房间成员表里显示「房主A」比显示
        // 操作系统用户名「LLai」更像回事，而且两台机器用同一账号名时也分得开。
        mutableStateOf(
            account.displayNameOrNull()
                ?: settings.signalNickname.ifBlank { DesktopModule.defaultNickname() },
        )
    }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    // 窗口关闭 / 应用退出时收掉它。`DesktopModule.close()` 必须有人显式调用：
    // 它要把收尾**同步**跑在 RTC 线程上再停执行器，否则 PeerConnection 与 native
    // 工厂不释放，进程退出会以 0xC0000005 结束（C5 门禁踩过，见 W7 拆分 §C5）。
    // 真正的收尾在 `Main.cleanup()`，这里只保留一条兜底：万一组合被单独拆掉
    // （开发态热重载、未来多窗口），也不至于把模块漏在那里。
    DisposableEffect(cinema) { onDispose { runCatching { cinema.close() } } }

    fun connectCinema() {
        if (connecting) return
        val resolved = endpoint.trim().ifBlank { DEFAULT_SIGNAL_ENDPOINT }
        val who = nickname.trim().ifBlank { DesktopModule.defaultNickname() }
        connecting = true
        connectError = null
        scope.launch {
            // 地址被改过（设置页 / 激活页）：先让账号层跟着换 —— 否则会把 A 服务器的
            // token 发给 B 服务器（结果是一串 401，看起来像「账号失效」）。
            if (resolved != account.signalEndpoint) {
                account.ensureReady(resolved)
            }
            // 门在这一刻仍然是硬条件：`--activate` 之后账号可能因为换地址而变回
            // 「需要激活」，那就不要建模块（否则又会拿空 token 去握手）。
            if (account.phase !is AccountPhase.Ready) {
                connecting = false
                return@launch
            }
            val created = runCatching {
                cinema.connect(
                    endpoint = resolved,
                    nickname = who,
                    identityOverride = identityOverride,
                    // 激活态下身份以账号的 deviceId 为准（服务端就是这么绑的）。
                    accountUserId = account.userIdOrNull(),
                    // 握手时同步取一次；`WsSignaler` 只读不写，token 不进日志。
                    authToken = { account.tokenOrNull() },
                )
            }
            created.onFailure { connectError = it.message ?: it::class.java.simpleName }
            // `connect()` 只是发起：它不等连上，连不上也会自己退避重连。界面不能
            // 一直转圈，所以这里给它 6 秒窗口，拿 `SignalState.Online` 当判据。
            val online = withTimeoutOrNull(6_000) {
                cinema.module?.signaler?.state?.first { it is SignalState.Online }
            } != null
            connecting = false
            if (created.isSuccess && !online) {
                // 连不上有两种可能：网络/地址错，或者 token 已经不能用了（401）。
                // 后者在 `WsSignaler` 眼里只是「连接异常（HTTP 401）」+ 退避重连，
                // 所以用账号接口做一次权威判定：真失效就清凭据、停模块、回激活页，
                // **不要**对着服务端无限重连。
                if (!account.revalidate()) {
                    runCatching { cinema.close() }
                    connectError = null
                    return@launch
                }
                connectError = "6 秒内没连上 $resolved（服务端没起？地址不对？）"
            } else if (online) {
                onSettingsChange(settings.copy(signalEndpoint = resolved, signalNickname = who))
            }
        }
    }

    // 第一次进影厅页时自动连一次。用户主动按过「断开」之后不再自动重连 ——
    // 否则那个按钮等于按了个假的。
    LaunchedEffect(screen, account.phase) {
        val ready = account.phase is AccountPhase.Ready
        Log.info(
            "[cinema] 自动连接判定：screen=$screen module=${cinema.module != null} " +
                "pending=${cinema.autoConnectPending} ready=$ready",
        )
        // 必须在 **Ready 的那一刻**才消费 `autoConnectPending`：早先只看 screen，
        // 于是「激活后 state 短暂回到 Loading」会让这次消费白扔，之后再也不连。
        if (screen == Screen.Cinema && cinema.module == null && cinema.autoConnectPending && ready) {
            cinema.autoConnectPending = false
            connectCinema()
        }
    }

    // 门禁用：连上之后自动建房/进房，这样「影厅页在房里长什么样」可以靠截图验证，
    // 而不需要有人手点四处。产品路径 `cinemaAuto == null`，这段整个不跑。
    LaunchedEffect(cinema.module, cinemaAuto) {
        val action = cinemaAuto ?: return@LaunchedEffect
        val module = cinema.module ?: return@LaunchedEffect
        if (module.signaler.room.value != null) return@LaunchedEffect
        withTimeoutOrNull(12_000) { module.signaler.state.first { it is SignalState.Online } }
            ?: return@LaunchedEffect
        val who = nickname.ifBlank { DesktopModule.defaultNickname() }
        module.handler.localNickname = who
        val id = action.roomId
        runCatching {
            if (action.create) {
                module.signaler.createRoom(who, null, id)
            } else if (id != null) {
                // 门禁里两个进程往往同时起，房主那边可能还没把房间建出来 ——
                // 只对「房间不存在」重试；密码错、满员这些重试也没用。
                //
                // ⚠️ 先等 0.6 s 再开始试：实测「join 紧跟在 create 回执的同一毫秒内到达」时，
                // 房主侧会漏掉这条 `peer_joined`（房间视图停在 1 人，于是永远不建链，
                // 而加入方一切正常）。那是 `core:network` 的收口时机问题，不属于本轮能改的
                // 范围（已按约定报给车道负责人）；门禁这里只是避开它，不掩盖它。
                delay(600)
                var lastError: Throwable? = null
                for (attempt in 0 until 20) {
                    val result = runCatching { module.signaler.joinRoom(id, who, null) }
                    if (result.isSuccess) {
                        lastError = null
                        break
                    }
                    lastError = result.exceptionOrNull()
                    if ((lastError as? SignalException)?.code != SignalErrors.ROOM_NOT_FOUND) break
                    delay(300)
                }
                lastError?.let { throw it }
            }
        }.onFailure { connectError = "自动进房失败：${it.message}" }
            .onSuccess { Log.info("[cinema] 自动进房完成 room=${module.signaler.room.value?.roomId}") }
    }

    fun setVolume(value: Float) {
        volume = value
        controller.target?.setVolume(value)
        onSettingsChange(settings.copy(volume = value))
    }

    /**
     * 选片并跳到播放页。
     *
     * [startPlaying] 默认 **false**：2026-11-06 起「选片」与「起播」分成两件事 ——
     * 选完只把片子装进播放器、停在首帧，等用户自己按播放（房主按下播放才会广播给成员）。
     * 只有命令行 `--open` 这类「明确要放」的入口才传 true。
     */
    fun openFile(movie: DesktopMovie, startPlaying: Boolean = false) {
        current = movie
        // 播放器只在影厅页（2026-11-07 用户口径：首页不再承载播放）。选完片就直接
        // 送进影厅 —— 否则「影片库里点了一部片，界面回到一个没有播放器的首页」。
        screen = Screen.Cinema
        autoplay = startPlaying
        onSettingsChange(settings.copy(lastMoviePath = movie.path))
        // 真正的 open 由 [PlayerPane] 的 LaunchedEffect 做：那里才知道画面是否已经挂上，
        // 也避免同一部片被"外层 + 播放区"开两次。
    }

    /** 影厅页里选片：**留在影厅页**、不自动起播（片源摘要由影厅页那条 effect 重算）。 */
    fun selectForHall(movie: DesktopMovie) {
        current = movie
        autoplay = false
        onSettingsChange(settings.copy(lastMoviePath = movie.path))
        Log.info("[cinema] 影厅页选片：${movie.path}（不自动播放）")
    }

    val chooseFile: () -> Unit = {
        chooseFile("选择影片")?.let { file ->
            openFile(
                DesktopMovie(
                    path = file.absolutePath,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    modifiedAtMs = file.lastModified(),
                    durationMs = 0L,
                ),
            )
        }
    }

    // 键盘快捷键是**全局**的（播放页也生效），所以这里必须自己判一次控制权：
    // 在房间里且本机不是房主时，方向键拖动会把成员那台拖离房主的时间轴 —— 界面上的
    // 进度条已经锁了，快捷键是同一件事的另一个入口，不能漏。
    fun localControlAllowed(): Boolean {
        val module = cinema.module ?: return true
        val room = module.signaler.room.value ?: return true
        return room.hostUserId == module.myUserId
    }

    Shortcuts.onPlayPause = {
        if (localControlAllowed()) {
            controller.target?.let { if (it.isPlaying) it.pause() else it.play() }
        } else {
            Log.info("[cinema] 成员端不响应播放快捷键（控制权在房主）")
        }
    }
    Shortcuts.onSeekBy = { delta ->
        if (localControlAllowed()) {
            controller.target?.let { it.seekTo((it.currentPositionMs + delta).coerceAtLeast(0)) }
        } else {
            Log.info("[cinema] 成员端不响应拖动快捷键（控制权在房主）")
        }
    }
    Shortcuts.onVolumeBy = { delta ->
        volume = (volume + delta).coerceIn(0f, 1f)
        controller.target?.setVolume(volume)
        onSettingsChange(settings.copy(volume = volume))
    }
    Shortcuts.onOpenFile = chooseFile

    // 无边框窗口的骨架：最上面一条自绘标题栏（全屏时不画），下面是原来的「侧栏 + 内容」。
    // 拖边缩放的 8 个热区叠在最外层，全屏时关掉（全屏不该再被拉小）。
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
    if (!fullscreen && chrome != null) {
        DesktopTitleBar(chrome, "cinema-hall · Windows")
    }
    Row(Modifier.weight(1f).fillMaxWidth().background(colors.background)) {
        // ── 左侧导航（全屏时收起）─────────────────────────────────────────────
        if (!fullscreen) {
            Column(
                Modifier
                    .width(196.dp)
                    .fillMaxHeight()
                    .background(colors.surface2)
                    .padding(horizontal = 12.dp, vertical = 18.dp),
            ) {
            Text("影厅", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Text("Windows 端", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
            Spacer(Modifier.height(22.dp))
            // 「诊断」不再单独占一格（2026-11-07 用户口径）：它已经并进设置页，
            // 侧边栏留着它只会让四个入口变成五个。`Screen.Diagnostics` 仍然保留 ——
            // `--screen=diagnostics` 那条门禁路径还要用它。
            NAV_ENTRIES.forEach { entry ->
                val selected = screen == entry
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(
                            if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { screen = entry }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconView(
                        entry.icon,
                        16.dp,
                        if (selected) colors.accent else colors.ink3,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        entry.label,
                        color = if (selected) colors.accent else colors.ink2,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(if (AppPaths.packaged) "打包版" else "开发版", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
            Text(AppPaths.appRoot.absolutePath, style = MaterialTheme.typography.labelSmall, color = colors.ink3)
            }
        }

        // ── 内容 ─────────────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    onGoCinema = { screen = Screen.Cinema },
                )

                Screen.Cinema -> CinemaScreen(
                    module = cinema.module,
                    endpoint = endpoint,
                    onEndpointChange = { endpoint = it },
                    nickname = nickname,
                    connecting = connecting,
                    connectError = connectError,
                    onConnect = { connectCinema() },
                    onDisconnect = { cinema.disconnect() },
                    controller = controller,
                    movie = current,
                    volume = volume,
                    autoplay = autoplay,
                    onVolumeChange = { setVolume(it) },
                    onOpenFile = chooseFile,
                    movies = movies,
                    onSelectMovie = { selectForHall(it) },
                    onClearMovie = {
                        // 「清除影片」：把当前片从界面与播放器上一起卸掉。
                        // 只置 null 不暂停，声音会继续走而画面没了 —— 用户会以为程序卡了。
                        current = null
                        autoplay = false
                        controller.target?.pause()
                    },
                    fullscreen = fullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                )

                Screen.Library -> LibraryScreen(
                    catalog = catalog,
                    roots = roots,
                    movies = movies,
                    categories = categories,
                    scanning = stats.phase == ScanPhase.FAST || stats.phase == ScanPhase.PROBING,
                    stats = stats,
                    onAddRoot = { dir ->
                        when (val result = catalog.addRoot(dir)) {
                            is DesktopMovieCatalog.AddRootResult.Added -> {
                                Log.info("添加根目录：${result.path}")
                                scope.launch { catalog.scan() }
                            }
                            DesktopMovieCatalog.AddRootResult.AlreadyPresent -> Unit
                            is DesktopMovieCatalog.AddRootResult.Rejected -> Log.warn(result.reason)
                        }
                    },
                    onRemoveRoot = { catalog.removeRoot(it) },
                    onScan = { scope.launch { catalog.scan() } },
                    onCancel = { catalog.cancel() },
                    onSelect = { openFile(it) },
                    onAddCategory = { catalog.addCategory(it) },
                    onRenameCategory = { id, name -> catalog.renameCategory(id, name) },
                    onDeleteCategory = { catalog.deleteCategory(it) },
                    onRenameMovie = { path, title -> catalog.renameMovie(path, title) },
                    onMoveMovies = { paths, categoryId -> catalog.moveMovies(paths, categoryId) },
                    onRemoveMovies = { catalog.removeMovies(it) },
                    onAddFiles = { files, categoryId -> catalog.addFiles(files, categoryId) },
                )

                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    onChange = onSettingsChange,
                    account = account,
                    volume = volume,
                    onVolume = {
                        volume = it
                        controller.target?.setVolume(it)
                        onSettingsChange(settings.copy(volume = it))
                    },
                    vlcPath = controller.vlcDir?.absolutePath,
                    initError = controller.initError,
                    onExit = onExit,
                    diagnostics = {
                        DiagnosticsContent(
                            controller = controller,
                            stats = stats,
                            movieCount = movies.size,
                            roots = roots,
                        )
                    },
                )

                // `--screen=diagnostics` 这条门禁路径仍然直达诊断；
                // 产品界面上它已经并进设置页（侧边栏不再有这一格）。
                Screen.Diagnostics -> Box(Modifier.fillMaxSize()) {
                    DiagnosticsScreenLegacy(
                        controller = controller,
                        stats = stats,
                        movieCount = movies.size,
                        roots = roots,
                    )
                }
            }
        }
    }
    }
    chrome?.let { ResizeHandles(it, enabled = !fullscreen) }
}
}


// ── 首页 ────────────────────────────────────────────────────────────────────

/**
 * 首页：功能入口的方块卡片墙（复刻安卓端 `HomeScreen`）。
 *
 * ── 为什么首页不再放播放器（2026-11-07 用户口径）────────────────────────────
 * 桌面端原来的首页就是「播放器 + 一行选片按钮」。它有两个后果：①一进程序就占着
 * 一条视频输出，切页时那块 AWT Canvas 被换掉，画面黑掉、进度归 0；②「首页」和
 * 「影厅」变成同一个东西，导航栏里那一项没有存在的意义。
 *
 * 现在播放器只在影厅页，首页回到「入口」的本分：一张方卡，宽度固定为窗宽的 1/5。
 * 「继续看」那一块已经按用户口径删掉（2026-11-07 追加）—— 首页只做入口，
 * 不重复影片库已经能做的事。
 */
@Composable
private fun HomeScreen(onGoCinema: () -> Unit) {
    val colors = LocalCinemaColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text("首页", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text("线上影厅 · 桌面端", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
        Spacer(Modifier.height(20.dp))
        Text("功能入口", style = MaterialTheme.typography.titleSmall, color = colors.ink2)
        Spacer(Modifier.height(10.dp))
        // 卡片宽度定成窗宽的 **1/5**（2026-11-07 用户口径）：现在只有「影厅」一个入口，
        // 但格子数量已经按五个排好 —— 以后加入口直接往这一行里塞，宽度不会跟着变。
        // 空位用等宽 Spacer 占着，否则一张卡会横向拉满整屏。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            EntryCard(
                entry = HomeEntry("影厅", "创建或加入房间，和对方看同一部片", AppIcon.Cinema, onGoCinema),
                modifier = Modifier.weight(1f),
            )
            repeat(4) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private class HomeEntry(
    val title: String,
    val caption: String,
    val icon: AppIcon,
    val onClick: () -> Unit,
)

@Composable
private fun EntryCard(entry: HomeEntry, modifier: Modifier) {
    val colors = LocalCinemaColors.current
    Column(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface2)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = entry.title, onClick = entry.onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            AppIconView(entry.icon, 22.dp, colors.accent)
        }
        Spacer(Modifier.weight(1f))
        Text(entry.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        Text(
            entry.caption,
            style = MaterialTheme.typography.labelSmall,
            color = colors.ink3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 设置 ────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    /** 账号面板（2026-11-07 用户口径：与安卓端「账号」同一套能力）。 */
    account: com.cinemahall.desktop.app.account.DesktopAccount,
    volume: Float,
    onVolume: (Float) -> Unit,
    vlcPath: String?,
    initError: String?,
    onExit: () -> Unit,
    /** 诊断内容（2026-11-07 起并进设置页，不再单独占一格导航）。 */
    diagnostics: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalCinemaColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Spacer(Modifier.height(14.dp))

        // 账号排在最前：它是「我是谁」的唯一来源（房间里显示的名字就从这里取）。
        Panel(title = "账号") {
            AccountPanel(account)
        }

        Spacer(Modifier.height(12.dp))
        Panel(title = "外观") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("日间模式", Modifier.weight(1f), color = colors.ink2)
                Switch(
                    checked = settings.theme == "day",
                    onCheckedChange = { onChange(settings.copy(theme = if (it) "day" else "night")) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel(title = "播放") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("默认音量", color = colors.ink2)
                Slider(
                    value = volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("${(volume * 100).toInt()}%", color = colors.ink2)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放时阻止系统休眠", Modifier.weight(1f), color = colors.ink2)
                Switch(
                    checked = settings.keepAwake,
                    onCheckedChange = {
                        onChange(settings.copy(keepAwake = it))
                        if (it) KeepAwake.enable() else KeepAwake.disable()
                    },
                )
            }
            Text(
                "防休眠：${if (KeepAwake.available) if (KeepAwake.active) "已开启" else "未开启" else "本机不可用"}",
                color = colors.ink3,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel(title = "路径") {
            KeyValue("程序目录", AppPaths.appRoot.absolutePath)
            KeyValue("数据目录", AppPaths.dataDir.absolutePath)
            KeyValue("libVLC", vlcPath ?: "未找到")
            if (initError != null) Text(initError, color = colors.danger, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openInExplorer(AppPaths.dataDir) }) { Text("打开数据目录") }
                OutlinedButton(onClick = { openInExplorer(File(Log.filePath()).parentFile) }) { Text("打开日志目录") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onExit) { Text("退出") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel(title = "诊断", subtitle = "运行时 · 影片库 · 日志") {
            diagnostics()
        }
        Spacer(Modifier.height(16.dp))
    }
}


// ── 诊断（内容，嵌在设置页里）───────────────────────────────────────────────

@Composable
private fun DiagnosticsContent(
    controller: PlayerController,
    stats: com.cinemahall.desktop.library.ScanStats,
    movieCount: Int,
    roots: List<String>,
) {
    val colors = LocalCinemaColors.current
    var lines by remember { mutableStateOf(Log.tail(120)) }
    LaunchedEffect(Unit) {
        while (true) {
            lines = Log.tail(120)
            kotlinx.coroutines.delay(1_000)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        KeyValue("模式", AppPaths.describe())
        KeyValue("libVLC", controller.vlcDir?.absolutePath ?: "未找到")
        KeyValue("可播放", if (controller.canPlay) "是" else "否：${controller.initError}")
        KeyValue("Java", System.getProperty("java.version") + " · " + System.getProperty("java.vendor"))
        KeyValue("OS", System.getProperty("os.name") + " " + System.getProperty("os.version"))
        KeyValue("防休眠", if (KeepAwake.active) "已开启" else "未开启")
        Spacer(Modifier.height(10.dp))
        KeyValue("根目录", roots.size.toString())
        KeyValue("影片", movieCount.toString())
        KeyValue("扫描阶段", stats.phase.name)
        KeyValue("耗时", "快扫 ${stats.fastElapsedMs}ms · 解析 ${stats.probeElapsedMs}ms")
        KeyValue(
            "跳过",
            "扩展名 ${stats.skippedExtension} · 隐藏 ${stats.skippedHidden} · 系统目录 ${stats.skippedSystemDirs}",
        )
        Spacer(Modifier.height(10.dp))
        Text(Log.filePath(), color = colors.ink3, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(6.dp))
        // 固定高度而不是 weight：这段内容在设置页的滚动容器里，weight 在无限高里没有意义。
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lines) { line ->
                    Text(
                        line,
                        color = colors.ink3,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

// ── 诊断（整页版，只给 `--screen=diagnostics` 用）────────────────────────────

@Composable
private fun DiagnosticsScreenLegacy(
    controller: PlayerController,
    stats: com.cinemahall.desktop.library.ScanStats,
    movieCount: Int,
    roots: List<String>,
) {
    val colors = LocalCinemaColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("诊断", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Spacer(Modifier.height(14.dp))
        DiagnosticsContent(controller = controller, stats = stats, movieCount = movieCount, roots = roots)
    }
}

// ── 小零件 ──────────────────────────────────────────────────────────────────

@Composable
private fun Panel(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCinemaColors.current
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, colors.line2, RoundedCornerShape(12.dp))
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.ink)
            if (subtitle != null) {
                Spacer(Modifier.width(10.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.ink3)
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    val colors = LocalCinemaColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, Modifier.width(120.dp), color = colors.ink3, style = MaterialTheme.typography.labelMedium)
        Text(value, color = colors.ink2, style = MaterialTheme.typography.bodySmall)
    }
}

// ── 系统对话框 ──────────────────────────────────────────────────────────────

// 同包的 `LibraryPane.kt` 也要用它（影片库页的「选择根目录…」）。
internal fun chooseDirectory(): File? {
    var result: File? = null
    val action = {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "选择要扫描的根目录"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile
    }
    if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    return result
}

private fun chooseFile(title: String): File? {
    var result: File? = null
    val action = {
        val chooser = JFileChooser().apply { dialogTitle = title }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile
    }
    if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    return result
}

private fun openInExplorer(file: File?) {
    if (file == null) return
    runCatching { Desktop.getDesktop().open(file) }
}
