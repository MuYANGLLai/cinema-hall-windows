package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

enum class Screen(val label: String, val glyph: String) {
    Home("首页", "▶"),
    Cinema("影厅", "◉"),
    Library("影片库", "▤"),
    Settings("设置", "⚙"),
    Diagnostics("诊断", "◎"),
}

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
    var screen by remember { mutableStateOf(initialScreen) }
    var current by remember { mutableStateOf(initialMovie) }
    var volume by remember { mutableStateOf(settings.volume) }
    var autoplay by remember { mutableStateOf(initialAutoplay) }
    var volumeVersion by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val movies by catalog.movies.collectAsState()
    val roots by catalog.roots.collectAsState()
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
        screen = Screen.Home
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

    Row(Modifier.fillMaxSize().background(colors.background)) {
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
            Screen.entries.forEach { entry ->
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
                    Text(entry.glyph, color = if (selected) colors.accent else colors.ink3)
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
                    controller = controller,
                    movie = current,
                    volume = volume,
                    autoplay = autoplay,
                    onVolumeChange = {
                        volume = it
                        onSettingsChange(settings.copy(volume = it))
                    },
                    onOpenFile = chooseFile,
                    onGoLibrary = { screen = Screen.Library },
                    onOpenFromLibrary = { openFile(it) },
                    movies = movies,
                    fullscreen = fullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                )

                Screen.Cinema -> CinemaScreen(
                    module = cinema.module,
                    endpoint = endpoint,
                    onEndpointChange = { endpoint = it },
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
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
                    fullscreen = fullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                )

                Screen.Library -> LibraryScreen(
                    catalog = catalog,
                    roots = roots,
                    movies = movies,
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
                )

                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    onChange = onSettingsChange,
                    volume = volume,
                    onVolume = {
                        volume = it
                        controller.target?.setVolume(it)
                        onSettingsChange(settings.copy(volume = it))
                    },
                    vlcPath = controller.vlcDir?.absolutePath,
                    initError = controller.initError,
                    onExit = onExit,
                )

                Screen.Diagnostics -> DiagnosticsScreen(
                    controller = controller,
                    stats = stats,
                    movieCount = movies.size,
                    roots = roots,
                )
            }
        }
    }
}

// ── 首页 ────────────────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
    onGoLibrary: () -> Unit,
    onOpenFromLibrary: (DesktopMovie) -> Unit,
    movies: List<DesktopMovie>,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    Column(Modifier.fillMaxSize()) {
        // 全屏时把标题行也收起来：全屏的字面意思就是「只剩画面」，留着一条
        // 通栏标题会让画面上下的黑边永远补不匀（安卓端同样是「外壳一行不留」）。
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("首页", style = MaterialTheme.typography.titleLarge, color = colors.ink)
                    Text(
                        if (movie == null) "还没选片" else movie.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.ink3,
                    )
                }
                OutlinedButton(onClick = onGoLibrary) { Text("影片库") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onOpenFile) { Text("打开文件") }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = if (fullscreen) 0.dp else 16.dp),
        ) {
            if (movie == null) {
                EmptyHome(movies = movies, onOpen = onOpenFromLibrary, onGoLibrary = onGoLibrary)
            } else {
                PlayerPane(
                    controller = controller,
                    movie = movie,
                    volume = volume,
                    autoplay = autoplay,
                    onVolumeChange = onVolumeChange,
                    onOpenFile = onOpenFile,
                    fullscreen = fullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                )
            }
        }
    }
}

@Composable
private fun EmptyHome(
    movies: List<DesktopMovie>,
    onOpen: (DesktopMovie) -> Unit,
    onGoLibrary: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("线上影厅 · 桌面端", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "先在影片库添加根目录并扫描，或直接打开一个视频文件。",
                color = colors.ink2,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGoLibrary) { Text("去影片库") }
            if (movies.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("最近可播（${movies.size}）", color = colors.ink3)
                Spacer(Modifier.height(8.dp))
                movies.take(5).forEach { movie ->
                    TextButton(onClick = { onOpen(movie) }) {
                        Text("${movie.title} · ${formatDuration(movie.durationMs)}", color = colors.accent)
                    }
                }
            }
        }
    }
}

// ── 影片库 ──────────────────────────────────────────────────────────────────

@Composable
private fun LibraryScreen(
    catalog: DesktopMovieCatalog,
    roots: List<String>,
    movies: List<DesktopMovie>,
    scanning: Boolean,
    stats: com.cinemahall.desktop.library.ScanStats,
    onAddRoot: (File) -> Unit,
    onRemoveRoot: (String) -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (DesktopMovie) -> Unit,
) {
    val colors = LocalCinemaColors.current
    // `⋮` 里「粘贴路径…」用的临时输入。与「选择根目录…」走同一个 `onAddRoot`，
    // 只是给系统选择器不好用的场景留一条路（网络盘、超长路径、脚本里抄来的路径）。
    var pastePath by remember { mutableStateOf("") }
    var pasteOpen by remember { mutableStateOf(false) }
    var rootMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        // ── 标题行：与安卓端「标题 + 右上角操作」同一形状 ─────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("影片库", style = MaterialTheme.typography.titleLarge, color = colors.ink)
                Text(
                    "多根目录递归扫描 · 快扫先列片，时长后台补",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink3,
                )
            }
            OutlinedButton(onClick = { chooseDirectory()?.let(onAddRoot) }) { Text("选择根目录…") }
            Spacer(Modifier.width(8.dp))
            if (scanning) {
                OutlinedButton(onClick = onCancel) { Text("取消扫描") }
            } else {
                Button(onClick = onScan, enabled = roots.isNotEmpty()) { Text("开始扫描") }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 根目录卡片：**只有一行** ──────────────────────────────────────────
        // 安卓端那张卡是一整块（分类列表 + 添加按钮）。桌面端根目录是扁平的 N 条路径，
        // 照搬会变成一屏文字，所以压成一行：左边一行摘要（路径用 ` · ` 连起来，超出省略），
        // 右边「管理」；逐条移除与粘贴路径都收进这一颗的下拉里，不再各占一行。
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface2)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("根目录", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
            Spacer(Modifier.width(10.dp))
            Text(
                if (roots.isEmpty()) {
                    "还没添加根目录 —— 点右侧「选择根目录…」挑一个文件夹"
                } else {
                    roots.joinToString("   ·   ")
                },
                Modifier.weight(1f),
                color = if (roots.isEmpty()) colors.ink3 else colors.ink2,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (roots.size > 1) {
                Text(
                    "${roots.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Box {
                TextButton(onClick = { rootMenu = true }) { Text("管理", color = colors.ink2) }
                DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("粘贴路径…") },
                        onClick = {
                            rootMenu = false
                            pastePath = ""
                            pasteOpen = true
                        },
                    )
                    if (roots.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("（还没有根目录）", color = colors.ink3) },
                            onClick = { rootMenu = false },
                        )
                    }
                    roots.forEach { root ->
                        DropdownMenuItem(
                            text = { Text("移除 · $root", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                rootMenu = false
                                onRemoveRoot(root)
                            },
                        )
                    }
                }
            }
        }

        if (pasteOpen) {
            AlertDialog(
                onDismissRequest = { pasteOpen = false },
                title = { Text("添加根目录") },
                text = {
                    TextField(
                        value = pastePath,
                        onValueChange = { pastePath = it },
                        singleLine = true,
                        placeholder = { Text("例如 D:\\Movies") },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val path = pastePath.trim()
                            pasteOpen = false
                            if (path.isNotEmpty()) onAddRoot(File(path))
                        },
                    ) { Text("添加") }
                },
                dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("取消") } },
            )
        }

        // ── 扫描条：也只有一行；没扫过就不出现（空库不该先看到一条「未开始」的灰条）
        if (scanning || stats.phase != ScanPhase.IDLE) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    LinearProgressIndicator(
                        progress = { if (stats.phase == ScanPhase.FAST) 0f else stats.probedFraction },
                        modifier = Modifier.width(180.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when (stats.phase) {
                        ScanPhase.IDLE -> "未开始"
                        ScanPhase.FAST -> "快扫中…"
                        ScanPhase.PROBING -> "解析时长 ${stats.probed}/${stats.found}"
                        ScanPhase.DONE -> "扫描完成：${stats.found} 部"
                        ScanPhase.CANCELLED -> "已取消（已列出 ${stats.found} 部）"
                    },
                    color = colors.ink2,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "快扫 ${stats.fastElapsedMs}ms · 解析 ${stats.probeElapsedMs}ms · " +
                        "跳过 ${stats.skippedExtension + stats.skippedHidden + stats.skippedSystemDirs}",
                    color = colors.ink3,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // ── 影片：安卓端的正方形卡片网格 ───────────────────────────────────────
        Text("影片 · ${movies.size} 部", style = MaterialTheme.typography.titleSmall, color = colors.ink2)
        Spacer(Modifier.height(8.dp))
        if (movies.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有影片。添加根目录后点「开始扫描」。", color = colors.ink3)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(movies, key = { it.path }) { movie ->
                    MovieCard(movie = movie, onSelect = { onSelect(movie) })
                }
            }
        }
    }
}

/**
 * 一格影片（安卓端 `MovieSquareCard` 的桌面版）。
 *
 * 正方形卡片、底部两行字（片名 / 时长 · 大小），整块可点 —— 与安卓端「点一格选中这部片」
 * 是同一个动作，所以动作名也用同一句。桌面端的影片库没有海报、也不记观看进度，中间就放
 * 一枚压得很淡的播放符：留纯色块的话，整个网格看起来像一排加载失败的占位。
 */
@Composable
private fun MovieCard(movie: DesktopMovie, onSelect: () -> Unit) {
    val colors = LocalCinemaColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface2)
            .clickable(onClickLabel = "选中这部片", onClick = onSelect),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Text(
                "▶",
                color = colors.ink3.copy(alpha = 0.22f),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                when (movie.bucket) {
                    ScanBucket.MOVIE -> "电影"
                    ScanBucket.SERIES -> "剧集"
                    ScanBucket.SKIP -> "短片"
                    // 快扫阶段时长还没解析出来，`bucket` 就是 null —— 说「待解析」，
                    // 不说「短片」：后者会让用户以为片子被判成了短片。
                    null -> "待解析"
                },
                color = if (movie.bucket == null) colors.ink3 else colors.accent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(
                movie.title,
                color = colors.ink,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (movie.durationMs > 0) {
                    "${formatDuration(movie.durationMs)} · ${formatBytes(movie.sizeBytes)}"
                } else {
                    "时长待解析 · ${formatBytes(movie.sizeBytes)}"
                },
                color = colors.ink3,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── 设置 ────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    volume: Float,
    onVolume: (Float) -> Unit,
    vlcPath: String?,
    initError: String?,
    onExit: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Spacer(Modifier.height(14.dp))

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
    }
}

// ── 诊断 ────────────────────────────────────────────────────────────────────

@Composable
private fun DiagnosticsScreen(
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

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("诊断", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Spacer(Modifier.height(14.dp))
        Panel(title = "运行时") {
            KeyValue("模式", AppPaths.describe())
            KeyValue("libVLC", controller.vlcDir?.absolutePath ?: "未找到")
            KeyValue("可播放", if (controller.canPlay) "是" else "否：${controller.initError}")
            KeyValue("Java", System.getProperty("java.version") + " · " + System.getProperty("java.vendor"))
            KeyValue("OS", System.getProperty("os.name") + " " + System.getProperty("os.version"))
            KeyValue("防休眠", if (KeepAwake.active) "已开启" else "未开启")
        }
        Spacer(Modifier.height(12.dp))
        Panel(title = "影片库") {
            KeyValue("根目录", roots.size.toString())
            KeyValue("影片", movieCount.toString())
            KeyValue("扫描阶段", stats.phase.name)
            KeyValue("耗时", "快扫 ${stats.fastElapsedMs}ms · 解析 ${stats.probeElapsedMs}ms")
            KeyValue("跳过", "扩展名 ${stats.skippedExtension} · 隐藏 ${stats.skippedHidden} · 系统目录 ${stats.skippedSystemDirs}")
        }
        Spacer(Modifier.height(12.dp))
        Panel(title = "日志", subtitle = Log.filePath(), modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lines) { line ->
                    Text(line, color = colors.ink3, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
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

private fun chooseDirectory(): File? {
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
