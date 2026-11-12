package com.cinemahall.desktop.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cinemahall.core.media.MediaCheck
import com.cinemahall.core.media.MismatchReason
import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RoomSummary
import com.cinemahall.core.network.SignalErrors
import com.cinemahall.core.network.SignalException
import com.cinemahall.core.network.SignalState
import com.cinemahall.core.rtc.CallAudioState
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.session.CinemaMediaCheck
import com.cinemahall.desktop.app.DEFAULT_SIGNAL_ENDPOINT
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.app.PlayerController
import com.cinemahall.desktop.app.media.DesktopFileSource
import com.cinemahall.desktop.app.net.DesktopModule
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.rtc.AudioInputDevice
import com.cinemahall.desktop.rtc.AudioInputs
import com.cinemahall.desktop.rtc.MAX_MIC_GAIN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import kotlin.math.roundToInt
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * 影厅页（W7-C5）。
 *
 * ── 页面三段，按「有没有连上 / 有没有进房」自动切换 ─────────────────────────
 *  1. 没连信令 → 地址 + 连接按钮；
 *  2. 连上但不在房里 → 建房 / 加入 / 房间清单；
 *  3. 在房里 → **左侧播放区 + 右侧常驻成员栏**（这一页的主角）。
 *
 * ── 右侧栏为什么必须常驻而不是抽屉 ──────────────────────────────────────────
 * 影厅里有三件事需要**一直看得见**：还有谁在、谁能控制、我是不是在旁听。
 * 做成抽屉就等于「出问题时先猜一下是不是自己把面板关了」。宽度固定 332dp —— 再窄
 * 放不下「昵称 + 房主徽标 + 在线点」一行，再宽会把画面挤成 4:3。
 *
 * ── 本页**不做**什么（避免读代码的人误判）─────────────────────────────────────
 * 播放同步引擎（`CinemaSyncEngine`）本轮**没有接入**：本页的控制条只动本机播放，
 * 不会广播给房主/成员。所以这一轮能承诺的是三件事 —— 成员看得见、控制权看得见、
 * 片源不一致的人被真的挡住（不许本地自己播）。真正的「一起播」在 C6 之后单独做，
 * 见 `docs/Windows-W7-交接审核.md` 的已知限制。
 */
@Composable
fun CinemaScreen(
    module: DesktopModule?,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    nickname: String,
    connecting: Boolean,
    connectError: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
    /** 影片库里的片（影厅页的「从影片库选片源」用；空列表 = 还没扫过库）。 */
    movies: List<DesktopMovie> = emptyList(),
    /** 在影厅页选片：只换片源，**留在影厅页**、不自动起播。 */
    onSelectMovie: (DesktopMovie) -> Unit = {},
    /** 全屏（窗口级，由 `Main` 承载）。全屏时右侧成员栏也收起来。 */
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /** 「清除影片」（复刻安卓简洁模式影片卡的第二颗按钮）：把当前片卸下来。 */
    onClearMovie: () -> Unit = {},
    /** 当前选定的麦克风输入源（`Mixer.Info.name`，null = 系统默认）。 */
    micInput: String? = null,
    /** 换麦克风输入源。传 null = 用系统默认。 */
    onSelectMicInput: (String?) -> Unit = {},
    /**
     * 本页此刻是不是真的在窗口里看得到。
     *
     * 影厅页是**常驻组合**（摘掉会毁掉视频输出），离开时靠整页位移挪到窗口外；
     * 而空态的覆盖层是另一扇原生窗口、挪不走，所以必须显式告诉它什么时候该收起来。
     */
    pageVisible: Boolean = true,
) {
    val colors = LocalCinemaColors.current

    if (module == null) {
        // 未连接：**画面照样在**（本机看片不需要信令），右边一列是「连信令」。
        Box(Modifier.fillMaxSize().background(colors.background)) {
            StageLayout(
                controller = controller,
                movie = movie,
                volume = volume,
                autoplay = autoplay,
                onVolumeChange = onVolumeChange,
                onOpenFile = onOpenFile,
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                onPickMovie = onOpenFile,
                movies = movies,
                onSelectMovie = onSelectMovie,
                pageVisible = pageVisible,
            ) {
                ConnectSidePanel(
                    controller = controller,
                    endpoint = endpoint,
                    onEndpointChange = onEndpointChange,
                    connecting = connecting,
                    error = connectError,
                    onConnect = onConnect,
                )
            }
        }
        return
    }

    val signalState by module.signaler.state.collectAsState()
    val room by module.signaler.room.collectAsState()

    // 房间视图每次变化都要喂给玩法（判房主、换房复位通话意图）。放在这里而不是
    // 各个按钮的回调里：`peer_joined` / `room_closed` 不会经过任何按钮。
    // 顺带把每次变化记一行：出问题时「房主看不到刚进来的人」这类症状，第一个要看的
    // 就是「本机这份 room 到底有过几个人」。
    LaunchedEffect(room) {
        Log.info(
            "[cinema] room 更新 room=${room?.roomId ?: "-"} members=${room?.members?.size ?: 0} " +
                "host=${room?.hostUserId?.value ?: "-"}",
        )
        module.onRoomChanged()
    }

    val current = room
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // ── 影厅页 = **播放器常驻左侧** + 右侧一块面板（2026-11-07 用户口径）──────
        // 「首页不要播放器页面」之后，本机看片必须在这里能看 —— 未连接、未进房两种
        // 状态都保留画面（与安卓端「影厅页永远有播放器」同一条结构）。
        // 右侧那块面板三态：未连接（连信令）/ 未进房（建房、加入、房间清单）/ 在房（成员栏）。
        when {
            current == null -> StageLayout(
                controller = controller,
                movie = movie,
                volume = volume,
                autoplay = autoplay,
                onVolumeChange = onVolumeChange,
                onOpenFile = onOpenFile,
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                onPickMovie = onOpenFile,
                movies = movies,
                onSelectMovie = onSelectMovie,
                pageVisible = pageVisible,
            ) {
                LobbySidePanel(
                    module = module,
                    controller = controller,
                    signalState = signalState,
                    nickname = nickname,
                    onDisconnect = onDisconnect,
                    connectError = connectError,
                )
            }

            else -> HallPanel(
                module = module,
                room = current,
                controller = controller,
                movie = movie,
                volume = volume,
                autoplay = autoplay,
                onVolumeChange = onVolumeChange,
                onOpenFile = onOpenFile,
                movies = movies,
                onSelectMovie = onSelectMovie,
                onClearMovie = onClearMovie,
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                micInput = micInput,
                onSelectMicInput = onSelectMicInput,
                pageVisible = pageVisible,
            )
        }
    }
}

/**
 * 右栏「大厅 / 房间」面板的收起开关。
 *
 * ── 为什么要有它（2026-11-12 UI 审查 F2）─────────────────────────────────────
 * 默认窗口 1440×920 下，画面区是 `1440 - 196(侧栏) - 380(右栏) = 864` 宽、
 * `920 - 32(标题栏) = 888` 高，接近正方形；16:9 的片子在里面只占 864×486，
 * **上下各约 200px 黑边，占画面区 45%**（窗口拖到 1140 宽时升到 64%）。
 * 这一栏一收起，画面就能用上整幅宽度（1244×888 → 片子 1244×700，面积差不多翻倍）。
 *
 * 做成一条 26dp 的窄轨道，而不是「把面板做成浮层」：这条轨道不在画面矩形之上
 * （AWT 重量级画布会把它整块盖住 —— 控制条当年只能做成独立窗口就是这条限制）。
 */
@Composable
private fun HallPanelToggle(collapsed: Boolean, onToggle: () -> Unit) {
    val colors = LocalCinemaColors.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hover = hoverProgress(interaction)
    Box(
        Modifier
            .fillMaxHeight()
            .width(26.dp)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = if (collapsed) "展开大厅面板" else "收起大厅面板",
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 箭头自绘（不用字形：缺字体会变成方块，与 `AppIcon` 同一条取向）。
        Canvas(Modifier.size(9.dp, 15.dp)) {
            val stroke = 1.6.dp.toPx()
            val c = if (hover > 0.02f) colors.accent else colors.ink3
            if (collapsed) {
                // 面板藏在右侧 → 箭头指右（点它把面板拉回来）
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height / 2f), stroke, StrokeCap.Round)
                drawLine(c, Offset(size.width, size.height / 2f), Offset(0f, size.height), stroke, StrokeCap.Round)
            } else {
                // 面板展开 → 箭头指左（点它把面板推走，画面吃满）
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height / 2f), stroke, StrokeCap.Round)
                drawLine(c, Offset(0f, size.height / 2f), Offset(size.width, size.height), stroke, StrokeCap.Round)
            }
        }
    }
}

/**
 * 影厅页的「舞台」：左边画面（常驻），右边一列面板。
 *
 * 画面这一块**必须**由本函数持有、并且每次进入影厅页都走同一个调用点 ——
 * `SwingPanel` 里的 AWT Canvas 换了实例就等于换了视频输出，画面会黑、进度会被打回 0。
 * 全屏时右栏收起，画面占满（与 `HallPanel` 同一条口径）。
 */
@Composable
private fun StageLayout(
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPickMovie: () -> Unit,
    movies: List<DesktopMovie>,
    onSelectMovie: (DesktopMovie) -> Unit,
    /** 见 `CinemaScreen.pageVisible`。 */
    pageVisible: Boolean = true,
    panel: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCinemaColors.current
    var picking by remember { mutableStateOf(false) }
    // 右栏可收起（UI 审查 F2）：收起后画面吃满整幅宽度，16:9 的黑边从 45% 降下来。
    var panelCollapsed by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 画面顶部**不再有片源条**（2026-11-07 用户口径：「影厅顶部的选片源那一栏删掉」）。
            // 换片入口挪到右栏：这里一进来就是画面本身，与安卓端「影厅页顶上只有播放器」一致。
            PlayerPane(
                controller = controller,
                movie = movie,
                volume = volume,
                autoplay = autoplay,
                onVolumeChange = onVolumeChange,
                onOpenFile = onOpenFile,
                onPickFromLibrary = { picking = true },
                modifier = Modifier.weight(1f),
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                pageVisible = pageVisible,
            )
        }

        if (!fullscreen) {
            // 收起开关在面板**左侧**的窄轨道上：面板收起后它仍然在，不然就没入口再展开。
            HallPanelToggle(collapsed = panelCollapsed, onToggle = { panelCollapsed = !panelCollapsed })
            if (!panelCollapsed) {
            Column(
                Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .background(colors.surface2)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // 选片入口：顶部一行，不占画面高度。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        movie?.title ?: "还没有选片",
                        Modifier.weight(1f),
                        color = if (movie == null) colors.ink3 else colors.ink,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(onClick = { picking = true }) { Text("从影片库选片") }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = colors.line)
                Spacer(Modifier.height(10.dp))
                panel()
            }
            }
        }
    }

    if (picking) {
        LibraryPickerDialog(
            movies = movies,
            currentPath = movie?.path,
            onPick = {
                picking = false
                onSelectMovie(it)
            },
            onOpenFile = {
                picking = false
                onOpenFile()
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * 大厅里的一张房间卡。
 *
 * 按钮文案是「加入」而不是「选中」（2026-11-07 用户口径）：无密码的房间点一下**直接进房**，
 * 有密码的才在调用方弹出密码框。原来那颗「选中」只把房号填进输入框，用户还得再按一次
 * 「加入房间」——同一件事要按两次，而且第一次按完界面没有任何「我进房了」的反馈。
 */
@Composable
private fun RoomRow(summary: RoomSummary, onJoin: () -> Unit, enabled: Boolean = true) {
    val colors = LocalCinemaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                summary.roomId,
                fontFamily = FontFamily.Monospace,
                color = colors.ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "房主 ${summary.hostNickname} · ${summary.memberCount}/${summary.maxSeats} 人" +
                    if (summary.hasPassword) " · 有密码" else "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
            )
        }
        OutlinedButton(onClick = onJoin, enabled = enabled) { Text("加入") }
    }
}

// ── ③ 影厅：播放区 + 右侧常驻成员栏 ────────────────────────────────────────

@Composable
private fun HallPanel(
    module: DesktopModule,
    room: Room,
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
    movies: List<DesktopMovie> = emptyList(),
    onSelectMovie: (DesktopMovie) -> Unit = {},
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /** 「清除影片」（复刻安卓简洁模式影片卡的第二颗按钮）：把当前片卸下来。 */
    onClearMovie: () -> Unit = {},
    /** 当前选定的麦克风输入源（`Mixer.Info.name`，null = 系统默认）。 */
    micInput: String? = null,
    /** 换麦克风输入源。传 null = 用系统默认。 */
    onSelectMicInput: (String?) -> Unit = {},
    /** 见 `CinemaScreen.pageVisible`（空态覆盖层要知道本页是不是看得见）。 */
    pageVisible: Boolean = true,
) {
    val colors = LocalCinemaColors.current
    val callAudio by module.handler.callAudio.collectAsState()
    val mediaCheck by module.handler.mediaCheck.collectAsState()
    // 「房间成员」卡的麦克风列：别人那行读 `cinema.call_state`（`peerCall`），
    // 自己那行读本机通话状态 —— 与安卓端 `CinemaHomeScreen` 完全同一条口径。
    val peerCall by module.handler.peerCall.collectAsState()
    val controlPolicy by module.handler.controlPolicy.collectAsState()
    val linkState by module.link.state.collectAsState()
    val signalState by module.signaler.state.collectAsState()

    val isHost = room.hostUserId == module.myUserId
    val listening = !isHost && mediaCheck.verdict is MediaCheck.Mismatch

    // 字幕轨跟着「当前这部片」：轮询一次给「影片」卡用（2026-11-08 用户口径：
    // 字幕选择从悬浮控制条挪到这里，与安卓简洁模式一致）。没有内封文字轨时整行不画。
    val subtitleState = rememberSubtitleState(controller.target)

    // ── 同步引擎必须拿到播放器 ────────────────────────────────────────────────
    // `attachPlayback` 是幂等的（同一个播放器只建一个引擎），它同时补设角色与已知的片源摘要。
    // ⚠️ 少了这一步，桌面端**根本没有同步引擎的播放器**：房主按播放不会广播，成员收不到
    // 位置/播放态，`片源 未上报` 旁边那些同步面板全是空的。2026-11-06 真机报
    // 「只校验了哈希、播放完全不同步」就是这条线没接（全仓只有定义、零调用点）。
    var syncEngine by remember { mutableStateOf<com.cinemahall.core.session.CinemaSyncEngine?>(null) }
    LaunchedEffect(module, controller.target) {
        val player = controller.target ?: return@LaunchedEffect
        syncEngine = withContext(Dispatchers.Swing) { module.attachPlayback(player) }
        Log.info("[cinema] 同步引擎已挂上播放器（角色随 room 更新补设）")
    }

    // 界面动作 → 引擎。约定是「先动播放器、再调引擎」，所以这几个回调只负责通报。
    val hooks = remember(syncEngine) {
        val engine = syncEngine
        LocalPlaybackHooks(
            onPlay = { engine?.onLocalPlay(it) },
            onPause = { engine?.onLocalPause(it) },
            onSeek = { engine?.onLocalSeek(it) },
            onSpeed = { engine?.onLocalSpeed(it) },
        )
    }

    // 影厅页选片：不离开本页、不自动起播（起播由用户按播放键决定）。
    var picking by remember { mutableStateOf(false) }
    // 右栏可收起（UI 审查 F2）：与大厅页同一条口径，收起后画面吃满整幅宽度。
    var panelCollapsed by remember { mutableStateOf(false) }
    // 同步读数：引擎还没建好时给一份默认快照（`canControl=true`，等价于「单机」）。
    val syncStatus by (syncEngine?.status ?: EMPTY_SYNC_STATUS).collectAsState()
    // 只在**有意义的变化**时留痕（角色 / 链路 / 是否锚定），不跟着 500ms 的漂移刷日志。
    // 「是不是根本没同步」这类问题，一行 anchored=false 比让用户描述现象可靠得多。
    LaunchedEffect(syncStatus.linkUp, syncStatus.isAnchored, syncStatus.roleText) {
        Log.info(
            "[sync] role=${syncStatus.roleText} linkUp=${syncStatus.linkUp} " +
                "anchored=${syncStatus.isAnchored} drift=${syncStatus.driftMs}ms tier=${syncStatus.tier}",
        )
    }
    // 引擎的每条 `lastEvent` 都要进日志。
    //
    // 它原先只出现在界面上（诊断页那一行），桌面上没有 logcat —— 于是「对端发来的控制
    // 被忽略了」「心跳到了但没锚定」这类判断只有人坐在窗口前才看得见。2026-11-06 排查
    // 「从端收到了心跳却不锚定」时就卡在这里：日志里有「收到 heartbeat」，引擎里发生过
    // 什么却一个字都没有。
    LaunchedEffect(syncStatus.lastEvent) {
        val line = syncStatus.lastEvent
        if (line.isNotBlank()) Log.info("[sync] evt=$line")
    }
    // 每 5 秒一行位置读数：`local` 与 `target` 是不是一起走，一眼就能看出来。
    // 500ms 的 tick 不刷日志（那会把 app.log 冲掉），5 秒足够定位「到底动没动」。
    LaunchedEffect(syncEngine) {
        val engine = syncEngine ?: return@LaunchedEffect
        while (true) {
            delay(5_000)
            val s = engine.status.value
            Log.info(
                "[sync] t=5s role=${s.roleText} anchored=${s.isAnchored} " +
                    "local=${s.localPositionMs} target=${s.targetPositionMs} drift=${s.driftMs}ms " +
                    "tier=${s.tier} rtt=${s.rttMs}",
            )
        }
    }

    // 本机片源摘要：进房、换片、模块就绪都会重跑一次。
    // 判据放在 `movie.path` 上而不是「点了选片按钮」上 —— 从影片库挑片进来的路径
    // 根本不会经过影厅页的按钮。
    LaunchedEffect(movie?.path, module) {
        val picked = movie ?: return@LaunchedEffect
        val signature = withContext(Dispatchers.IO) { DesktopFileSource.fingerprint(File(picked.path)) }
        if (signature == null) {
            Log.warn("[cinema] 算不出片源摘要：${picked.path}")
            return@LaunchedEffect
        }
        // `onLocalSignatureReady` 会顺手向对端宣告一次；链路还没通时它返回 false，
        // 由协调者在 DataChannel OPEN 时补发，这里不用重试。
        withContext(Dispatchers.Swing) { module.handler.onLocalSignatureReady(signature) }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 画面顶部不再有片源条（2026-11-07 用户口径）：片源信息与选片入口都在右栏
            // 的「影片」卡里。这里只留成员端那句「为什么按不动」的说明。
            if (!fullscreen) {
                if (!isHost) {
                    Text(
                        "播放由房主控制：本机的播放键、±10s、进度条与倍速已锁；换片、音量、字幕不受限。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.ink3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            PlayerPane(
                controller = controller,
                movie = movie,
                volume = volume,
                autoplay = autoplay,
                onVolumeChange = onVolumeChange,
                onOpenFile = onOpenFile,
                onPickFromLibrary = { picking = true },
                hooks = hooks,
                modifier = Modifier.weight(1f),
                // 控制权取自同步引擎的 `canControl`（契约 §7 的唯一真值：房主恒真、
                // 成员按策略表且「在看」）。它同时管播放键、±10s、进度条与倍速；
                // 音量/字幕不在此列。2026-11-06 用户报「房员能自己拖进度条」就是这里
                // 原先只判了「片源不一致」，没判控制权。
                interactive = syncStatus.canControl && !listening,
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                // 手动同步：房主按 = 立刻广播自己的位置；成员按 = 请房主回一条心跳。
                // 它不参与控制权划分（两端都安全），与安卓端同一口径。
                onManualSync = { module.handler.requestManualSync() },
                // 成员端的说明改到画面左上角（与安卓端那条灰条同位置），
                // 不再占掉画面高度 —— 全屏时也能看到「为什么按不动」。
                controlHint = if (!isHost && !listening) {
                    "播放由房主控制（换片 / 音量 / 字幕不受限）"
                } else {
                    null
                },
                pageVisible = pageVisible,
            )
        }

        // ── 右侧栏（2026-11-07 重做）───────────────────────────────────────────
        //
        // 结构对齐安卓端影厅页：**房间成员 → 通话 → 影片**；从下往上就是
        // 影片（贴底）→ 通话 → 房间成员（贴顶）。全屏时整块收起。
        //
        // 「复制房间号」从一颗宽按钮变成房间号右边的一个小图标（用户口径）；
        // 「重发片源」整颗删掉 —— 它是「片源摘要一直没到对端」时手动补一次的兜底，
        // 现在协调者在链路建起来、换片时都会自动重发，正常使用碰不到它。
        if (!fullscreen) {
            HallPanelToggle(collapsed = panelCollapsed, onToggle = { panelCollapsed = !panelCollapsed })
            if (!panelCollapsed) {
            Column(
                Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .background(colors.surface2)
                    // ⚠️ 必须能竖向滚：字幕轨一多，「影片」卡里的胶囊会折成好几行
                    // （见 `SubtitleTrackRow`），这栏本来是固定高度，会把「离开房间」顶出屏幕。
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                // 房间号：位置不变，复制做成右侧小图标。
                //
                // 复制反馈（2026-11-12 用户口径）：按下时**图标高亮**，右侧浮出一颗
                // 「复制成功！」小气泡，1.4 秒后自己消失。气泡不占布局（叠在那一行上，
                // 带 `zIndex` 免得被后面的信号状态字压住），也不改这一行的高度。
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (!copied) return@LaunchedEffect
                    delay(1_400)
                    copied = false
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("房间号", style = MaterialTheme.typography.labelMedium, color = colors.ink3)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        room.roomId,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                    Spacer(Modifier.width(6.dp))
                    // 「复制房间号」是一颗自绘的小图标（原来是 ⧉ 字形，缺字体会变方块）。
                    Box {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (copied) colors.accent.copy(alpha = 0.18f) else Color.Transparent,
                                )
                                .clickable(onClickLabel = "复制房间号") {
                                    copyToClipboard(room.roomId)
                                    copied = true
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            AppIconView(
                                AppIcon.Copy,
                                15.dp,
                                if (copied) colors.accent else colors.ink3,
                            )
                        }
                        // 悬浮小提示：挂在图标右边，不占位、不参与布局。
                        CopyToast(
                            visible = copied,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = 30.dp)
                                .zIndex(1f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        signalStateText(signalState),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.ink3,
                    )
                }
                Text(
                    "玩法 ${room.mode} · 链路 ${peerStateText(linkState)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink3,
                )

                Spacer(Modifier.height(10.dp))
                HallCard(title = "房间成员", subtitle = "${room.members.size} 人") {
                    MemberHeader()
                    room.members.forEach { member ->
                        MemberTableRow(
                            member = member,
                            isHostOfRoom = member.userId == room.hostUserId,
                            isMe = member.userId == module.myUserId,
                            myNickname = module.handler.localNickname,
                            allow = controlPolicy.allowedFor(member.userId),
                            // 只有房主能拨别人；自己那行只显示结论（与安卓端一致）。
                            canSwitch = isHost && member.userId != module.myUserId,
                            micOn = if (member.userId == module.myUserId) {
                                callAudio.sending
                            } else {
                                peerCall[member.userId]?.let { it.callOn && it.micOn } ?: false
                            },
                            onSetAllowed = { module.handler.setMemberAllowed(member.userId, it) },
                        )
                    }
                    // 2026-11-12 用户口径：「还差一个人。把房间号念给对方……」这行灰字删掉
                    // —— 房间号就在卡片上方，多一句话只是噪音。
                }

                Spacer(Modifier.height(10.dp))
                // 麦克风输入源：**在卡片内展开**，不做弹出菜单。
                // 侧栏只有 300dp 出头，`DropdownMenu` 是个独立窗口、会按内容宽度往右
                // 铺开，于是菜单一半落在侧栏外面（2026-11-12 用户报「宽度不足以显示」）。
                // 改成卡片内的一列之后，宽度天然被侧栏约束，长设备名也不会溢出。
                var micOpen by remember { mutableStateOf(false) }
                var micDevices by remember { mutableStateOf<List<AudioInputDevice>>(emptyList()) }
                HallCard(title = "通话") {
                    // 2026-11-12 用户口径：两颗按钮从标题右侧挪到标题**下方**，靠左，
                    // 顺序是「开启通话」在前、「麦克风」在后。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { module.handler.setCallEnabled(!callAudio.requested, "影厅面板") },
                        ) {
                            Text(if (callAudio.requested) "关闭通话" else "开启通话")
                        }
                        MicButton(
                            open = micOpen,
                            onClick = {
                                // 每次展开都重新枚举：插拔 USB 麦之后不用重启程序。
                                if (!micOpen) {
                                    micDevices = runCatching { AudioInputs.list() }
                                        .getOrDefault(emptyList())
                                }
                                micOpen = !micOpen
                            },
                        )
                    }
                    // 通话状态那一行：**只在通话开着或出错时**出现（用户口径：删掉
                    // 「未开启（对端听不到你）」那句话 —— 按钮本身已经写明状态）。
                    if (callAudio.requested || callAudio.error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            callStateText(callAudio),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                callAudio.error != null -> colors.danger
                                callAudio.sending && callAudio.receiving -> colors.success
                                else -> colors.ink3
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 两路音量：本机麦克风增益 + 对端声音。与安卓端同一条口径 ——
                    // 它们都是本机自己的输出/输入，与「谁控制播放」无关，所以始终可调。
                    //
                    // 2026-11-12 用户口径（三处一起改）：
                    // ① 名字不再常驻，悬浮图标才浮出来（见 `VolumeRow`）；
                    // ② 百分比挪到滑条**后面**；
                    // ③ 麦克风那一路可推到 200%（`MAX_MIC_GAIN`），接收音量仍是 0..100%。
                    VolumeRow(
                        label = "麦克风输入音量",
                        value = callAudio.micVolume,
                        icon = AppIcon.Mic,
                        range = 0f..MAX_MIC_GAIN,
                    ) { module.handler.setMicVolume(it) }
                    if (micOpen) {
                        Spacer(Modifier.height(8.dp))
                        MicInputList(
                            selected = micInput,
                            devices = micDevices,
                            onSelect = { name ->
                                micOpen = false
                                onSelectMicInput(name)
                            },
                        )
                    }
                    VolumeRow(
                        label = "接收音量",
                        value = callAudio.volume,
                        icon = AppIcon.Headphones,
                    ) { module.handler.setCallVolume(it) }
                }

                Spacer(Modifier.height(10.dp))
                HallCard(
                    title = "影片",
                    subtitle = sourceStatusText(mediaCheck, isHost),
                ) {
                    Text(
                        movie?.title ?: "还没有选片",
                        color = if (movie == null) colors.ink3 else colors.ink,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 两颗按钮与安卓简洁模式一致：「片库」弹浮窗选片，「清除影片」把当前片卸掉。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picking = true }) { Text("片库") }
                        OutlinedButton(onClick = onClearMovie, enabled = movie != null) { Text("清除影片") }
                    }
                    // 字幕：跟着选中的片走，没有内封文字轨时整行不出现（与安卓端一致）。
                    if (subtitleState.hasTracks) {
                        Spacer(Modifier.height(10.dp))
                        SubtitleTrackRow(target = controller.target, state = subtitleState)
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { module.leaveRoom() }) { Text("离开房间") }
            }
            }
        }

    }

    // 选片浮窗：独立窗口（见 `LibraryPickerDialog` 的注释），放在 Row 之外 ——
    // 它不参与本页布局，也不能因为 `fullscreen` 把画面挤走。
    if (picking) {
        LibraryPickerDialog(
            movies = movies,
            currentPath = movie?.path,
            onPick = {
                picking = false
                onSelectMovie(it)
            },
            onOpenFile = {
                picking = false
                onOpenFile()
            },
            onDismiss = { picking = false },
        )
    }
}

// ── 右侧栏的三张卡（房间成员 / 通话 / 影片）──────────────────────────────────

/** 卡片外壳：与设置页的 `Panel` 同一套视觉（描边 + 圆角 + 标题行）。 */
@Composable
private fun HallCard(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCinemaColors.current
    // 悬浮时描边从分隔线过渡到主色 28%（2026-11-08 视觉升级）：卡片本身不可点，
    // 所以只做「靠近时有回应」，不做抬起 —— 抬起会让人以为能点。
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hover = hoverProgress(interaction)
    val border = androidx.compose.ui.graphics.lerp(
        colors.line2,
        colors.accent.copy(alpha = 0.28f),
        hover,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.ink)
            if (subtitle != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * 一路音量（2026-11-12 改版）。
 *
 * 用户口径三条：
 *  ① 名字**不再常驻**（原来是一行「麦克风输入音量 100%」），改成**悬浮在图标上**才浮出来；
 *  ② 百分比挪到滑条**后面**（右边）；
 *  ③ 麦克风那一路可推到 200%（[range]），接收音量仍是 0..100%。
 *
 * 名字用 `TooltipArea` 而不是自绘浮层：它会跟着指针定位、跨平台、也不会被同一行里的
 * `Slider` 抢走焦点/手势 —— 自己写一套 hover 浮层要处理这两种干扰。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VolumeRow(
    label: String,
    value: Float,
    icon: AppIcon,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val colors = LocalCinemaColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.TooltipArea(
            tooltip = {
                Surface(
                    color = colors.surface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, colors.line2),
                ) {
                    Text(
                        label,
                        color = colors.ink2,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            },
            delayMillis = 350,
            tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(
                offset = DpOffset(0.dp, 18.dp),
            ),
        ) {
            // 图标本身就是「说明」的入口：悬浮它才显示名字。
            Box(Modifier.padding(end = 4.dp)) {
                AppIconView(icon, 15.dp, colors.ink2)
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            enabled = enabled,
            valueRange = range,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        // 百分比在滑条**后面**（用户口径 ②）。等宽，值从 100% 跳到 200% 时滑条不会抖。
        Text(
            "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = colors.ink2,
            textAlign = TextAlign.End,
            modifier = Modifier.width(38.dp),
        )
    }
}

/**
 * 「复制成功！」小气泡（2026-11-12 用户口径：点复制后右边浮出来一下）。
 *
 * 为什么单独抽成一个函数：调用点在 `Row` 里面，`RowScope.AnimatedVisibility` 会盖住
 * 同名的顶层重载，编译器报「cannot be called in this context with an implicit receiver」。
 * 放到文件级之后没有 RowScope 了，解析到的就是那个普通重载。
 */
@Composable
private fun CopyToast(visible: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCinemaColors.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.curve(Motion.MICRO)) +
            slideInHorizontally(Motion.curve(Motion.MICRO)) { -6 },
        exit = fadeOut(Motion.curve(Motion.MICRO, Motion.exit)),
        modifier = modifier,
    ) {
        Text(
            "复制成功！",
            style = MaterialTheme.typography.labelSmall,
            color = colors.accent,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 「麦克风」那颗按钮（2026-11-12 用户口径：与「开启通话」并排，后者在前）。
 *
 * 只负责**开关**列表；列表本身画在卡片里（见 [MicInputList]）—— 用 `DropdownMenu`
 * 会是一个独立弹出窗口、宽度按内容走，侧栏装不下（用户报「宽度不足以显示」）。
 *
 * 文字颜色固定用 `accent`：它和旁边那颗 `OutlinedButton` 的内容色**一样**
 * （`scheme()` 里 `primary = colors.accent`），两颗并排才不会一大一小两种颜色。
 * 「展开中」只由描边表示。
 */
@Composable
private fun MicButton(open: Boolean, onClick: () -> Unit) {
    val colors = LocalCinemaColors.current
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, if (open) colors.accent else colors.line2),
    ) {
        Text("麦克风", color = colors.accent)
    }
}

/**
 * 麦克风输入源列表：**卡片内的一列**，宽度天然被侧栏约束。
 *
 * 设备名可能很长（"麦克风阵列 (Realtek(R) Audio)" 这类），所以：
 * ① 每行 `maxLines = 1` + 省略号，绝不撑宽侧栏；
 * ② 整个列表最多 168dp 高、内部滚动 —— 虚拟设备多的机器也不会把卡片顶穿。
 */
@Composable
private fun MicInputList(
    selected: String?,
    devices: List<AudioInputDevice>,
    onSelect: (String?) -> Unit,
) {
    val colors = LocalCinemaColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 168.dp)
            .verticalScroll(rememberScrollState())
            .background(colors.surface2, RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        MicInputRow("系统默认", selected.isNullOrBlank()) { onSelect(null) }
        devices.forEach { device ->
            MicInputRow(device.label, device.name == selected) { onSelect(device.name) }
        }
        if (devices.isEmpty()) {
            Text(
                "没找到可用的麦克风",
                color = colors.ink3,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MicInputRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCinemaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) colors.accent else colors.ink2,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            CheckMarkDot()
        }
    }
}

/** 列表里表示「当前选中的就是这条」的小圆点（不用 ✓ 字形，与项目图标口径一致）。 */
@Composable
private fun CheckMarkDot() {
    val colors = LocalCinemaColors.current
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(6.dp).background(colors.accent, CircleShape))
    }
}

// 四列的固定宽度。名字用 `weight(1f)` 吃掉剩下的空间 —— 名字长短不同也不会把
// 后面三列挤偏，这正是「一行一个人」的表格最要紧的一点。
private val MEMBER_CTRL_COLUMN = 62.dp
private val MEMBER_MIC_COLUMN = 44.dp
private val MEMBER_STATE_COLUMN = 46.dp

@Composable
private fun MemberHeader() {
    val colors = LocalCinemaColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("成员", Modifier.weight(1f), color = colors.ink3, style = MaterialTheme.typography.labelSmall)
        Text("控制", Modifier.width(MEMBER_CTRL_COLUMN), color = colors.ink3, style = MaterialTheme.typography.labelSmall)
        Text("状态", Modifier.width(MEMBER_STATE_COLUMN), color = colors.ink3, style = MaterialTheme.typography.labelSmall)
        Text("麦", Modifier.width(MEMBER_MIC_COLUMN), color = colors.ink3, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 成员表的一行（复刻安卓端 `RoomMembersSection` 的四列）。
 *
 * 四列：名字（自己高亮）/ 控制权（房主看到开关）/ 麦克风 / 状态。
 * 自己那一行**不给开关**：房主拨自己的控制权没有意义，而「自己拨自己」在两人房里
 * 会让人误以为对面也被关了。
 */
@Composable
private fun MemberTableRow(
    member: Member,
    isHostOfRoom: Boolean,
    isMe: Boolean,
    myNickname: String,
    allow: Boolean,
    canSwitch: Boolean,
    micOn: Boolean,
    onSetAllowed: (Boolean) -> Unit,
) {
    val colors = LocalCinemaColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (member.online) colors.success else colors.ink3, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                member.nickname.ifBlank {
                    if (isMe && myNickname.isNotBlank()) myNickname else member.userId.value.take(8)
                },
                style = MaterialTheme.typography.bodySmall,
                // 自己那一行靠**名字高亮**认出来（2026-11-07 用户口径），不再挂「我」徽标。
                color = when {
                    isMe -> colors.accent
                    member.online -> colors.ink
                    else -> colors.ink3
                },
                fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isHostOfRoom) {
                Spacer(Modifier.width(4.dp))
                Badge("房主", colors.accent)
            }
        }
        Box(Modifier.width(MEMBER_CTRL_COLUMN), contentAlignment = Alignment.CenterStart) {
            if (canSwitch) {
                Box(Modifier.size(width = 40.dp, height = 24.dp), contentAlignment = Alignment.Center) {
                    Switch(
                        checked = allow,
                        onCheckedChange = onSetAllowed,
                        modifier = Modifier.scale(0.66f),
                    )
                }
            } else {
                Text(
                    when {
                        // 房主自己那格不显示「受控」：它恒为可控制，写两个字反而像被限制了。
                        isMe && isHostOfRoom -> "—"
                        allow -> "可"
                        else -> "受控"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (allow || (isMe && isHostOfRoom)) colors.accent else colors.ink3,
                )
            }
        }
        // 列顺序对齐安卓简洁模式：成员 / 控制 / 状态 / 麦。
        Box(Modifier.width(MEMBER_STATE_COLUMN)) {
            Text(
                if (member.online) "在线" else "离线",
                style = MaterialTheme.typography.labelSmall,
                color = if (member.online) colors.ink2 else colors.ink3,
            )
        }
        Box(Modifier.width(MEMBER_MIC_COLUMN), contentAlignment = Alignment.CenterStart) {
            // 开麦用主色、关麦用灰色：同一枚图标，靠颜色区分状态（安卓端也是这么画的）。
            AppIconView(
                AppIcon.Mic,
                13.dp,
                if (micOn) colors.accent else colors.ink3.copy(alpha = 0.45f),
                strokeScale = 1.15f,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * 片源一致性横幅 —— 影厅页唯一会**改变玩法**的提示。
 *
 * 三种「不匹配」说三句不同的话，因为用户要做的事不同：房主要知道「他只能旁听」，
 * 成员要知道「不是你的播放器坏了，是片源不同」，还没上报的人要知道「再等等」。
 */
@Composable
private fun SourceBanner(
    isHost: Boolean,
    check: CinemaMediaCheck,
    onOpenFile: () -> Unit,
    onPickFromLibrary: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    val verdict = check.verdict

    val tone: Color
    val title: String
    val detail: String
    when {
        verdict is MediaCheck.Mismatch -> {
            tone = colors.danger
            val reason = mismatchText(verdict.reason)
            if (isHost) {
                title = "对方片源与你不一致，他将只能旁听"
                detail = "$reason。语音不受影响；他换一部一样的片之后会自动回到同步组。"
            } else {
                title = "你的片源与房主不一致，无法同步观看"
                detail = "$reason。你仍可语音旁听；换一部和房主一样的片就能加入。"
            }
        }

        check.bothKnown -> {
            tone = colors.success
            title = "片源一致：${check.local?.fileName ?: "-"}"
            detail = "两端文件名、大小、首/中/尾三段抽样哈希全部相同。"
        }

        check.local == null -> {
            tone = colors.ink3
            title = "还没有选片"
            detail = "先在影片库选一部片，本机会把摘要发给${if (isHost) "对方" else "房主"}核对。"
        }

        else -> {
            tone = colors.ink3
            title = "已上报本机片源，等待对方"
            detail = "对端还没把摘要发过来（链路没通、或他还没选片）。"
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = tone, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.ink2)
        }
        if (verdict is MediaCheck.Mismatch && !isHost) {
            OutlinedButton(onClick = onOpenFile) { Text("换一部片") }
        }
        Spacer(Modifier.width(8.dp))
        // 影厅页选片：不必先跑去影片库再回来（那条路还会把页面踢回播放页）。
        OutlinedButton(onClick = onPickFromLibrary) { Text("从影片库选片源") }
    }
}

/**
 * 影厅页的选片面板（替代「跳去影片库再回来」那条路）。
 *
 * 为什么是整块替换而不是弹窗：`SwingPanel`（AWT heavyweight 画布）之上叠 Compose 控件
 * **完全不可见**，而 Compose Desktop 的独立 DialogWindow 又拿不到本应用的主题
 * （`LocalCinemaColors` 不会跨窗口传递）。整块替换是唯一既能看到、又不用重画主题的做法。
 */
@Composable
private fun SourcePicker(
    movies: List<DesktopMovie>,
    currentPath: String?,
    onPick: (DesktopMovie) -> Unit,
    onCancel: () -> Unit,
    onOpenFile: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选择片源", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Spacer(Modifier.width(10.dp))
            Text("${movies.size} 部 · 选中后不会自动播放", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenFile) { Text("直接打开文件…") }
            TextButton(onClick = onCancel) { Text("返回影厅") }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = colors.line)
        if (movies.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("影片库还是空的：去「影片库」加一个根目录并扫描，或用上面的「直接打开文件…」。", color = colors.ink3)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(movies, key = { it.path }) { movie ->
                val selected = movie.path == currentPath
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (selected) "${movie.title}（当前）" else movie.title,
                            color = if (selected) colors.accent else colors.ink,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${formatDuration(movie.durationMs)} · ${formatBytes(movie.sizeBytes)} · ${movie.path}",
                            color = colors.ink3,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { onPick(movie) }) { Text(if (selected) "重新载入" else "选这部") }
                }
            }
        }
    }
}

@Composable
private fun CallPanel(
    audio: CallAudioState,
    onToggle: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
) {
    val colors = LocalCinemaColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("语音", style = MaterialTheme.typography.titleSmall, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Button(onClick = { onToggle(!audio.requested) }) {
            Text(if (audio.requested) "关闭麦克风" else "开启通话")
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        callStateText(audio),
        style = MaterialTheme.typography.labelSmall,
        color = when {
            audio.error != null -> colors.danger
            audio.sending && audio.receiving -> colors.success
            else -> colors.ink3
        },
    )
    // 对端的音频 m 行是 recvonly（协商成了「只收不发」）时，再怎么等也不会有声音：
    // 这一格就是把「听不见」从「再试试」变成「去对面那台机器上看」。
    if (audio.remoteSending == false) {
        Text(
            "对端的音频协商成「只收不发」，等再久也不会有声音 —— 问题在他那台机器上。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.danger,
        )
    }
    Text(
        "本机发送 ${audio.audioPacketsSent ?: "-"} · 收到 ${audio.audioPacketsReceived ?: "-"} 包",
        style = MaterialTheme.typography.labelSmall,
        color = colors.ink3,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("对端音量", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
        Slider(
            value = audio.volume,
            onValueChange = onVolume,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("${(audio.volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.ink2)
    }
}

@Composable
private fun StatusLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalCinemaColors.current.ink3)
        Spacer(Modifier.width(10.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ── 文案 ────────────────────────────────────────────────────────────────────

private fun isHostOf(member: Member, room: Room): Boolean = member.userId == room.hostUserId

/** 引擎还没建好时的占位快照（`canControl=true`，与「单机看片」一致）。 */
private val EMPTY_SYNC_STATUS = kotlinx.coroutines.flow.MutableStateFlow(
    com.cinemahall.core.session.CinemaSyncStatus(),
)

/**
 * 同步状态的一句话。
 *
 * 刻意不写成「同步中 / 未同步」这种二值：`isAnchored` 才是「房主已经下发过一次权威时间轴」，
 * 而 `linkUp` 只管数据面通没通。两者混在一起时，用户看到「已连接」却怎么都不同步，
 * 会去查网络 —— 实际情况往往是房主还没按播放（没有锚点）。
 */
private fun syncStatusText(status: com.cinemahall.core.session.CinemaSyncStatus): String = when {
    !status.linkUp -> "链路未通 · ${status.roleText}"
    status.isAnchored -> "已锚定 · ${status.roleText}"
    else -> "已连 · 等房主起播（还没锚点）"
}

private fun isRoomCode(value: String): Boolean =
    value.length == 6 && value.all { it in '2'..'9' || (it in 'A'..'Z' && it != 'O' && it != 'I' && it != 'L') }

private fun signalStateText(state: SignalState): String = when (state) {
    is SignalState.Connecting -> "信令：连接中"
    is SignalState.Disconnected -> "信令：未连接"
    is SignalState.Reconnecting -> "信令：重连中"
    is SignalState.Online -> "信令：在线"
}

private fun peerStateText(state: PeerState): String = when (state) {
    is PeerState.Idle -> "未建立"
    is PeerState.Negotiating -> "协商中"
    is PeerState.Connected -> "已连接（P2P）"
    is PeerState.Failed -> "失败：${state.reason}"
    is PeerState.Closed -> "已断开"
}

private fun callStateText(audio: CallAudioState): String {
    audio.error?.let { return it }
    return when {
        !audio.requested -> "未开启（对端听不到你）"
        audio.sending && audio.receiving -> "双向通话中"
        audio.sending -> "已开麦 · 还没收到对端语音"
        audio.receiving -> "只能听（本机麦克风没接上）"
        else -> "已请求开启 · 等链路"
    }
}

private fun sourceStatusText(check: CinemaMediaCheck, isHost: Boolean): String = when (val verdict = check.verdict) {
    is MediaCheck.Match -> "一致 · ${check.local?.fileName ?: "-"}"
    is MediaCheck.Mismatch -> if (isHost) "对端不一致（他旁听）" else "不一致（你旁听）"
    null -> if (check.local == null) "未上报" else "等对端"
}

private fun mismatchText(reason: MismatchReason): String = when (reason) {
    is MismatchReason.FileName -> "文件名不同：本机「${reason.local}」/ 对端「${reason.remote}」"
    is MismatchReason.Size -> "大小不同：本机 ${formatBytes(reason.localBytes)} / 对端 ${formatBytes(reason.remoteBytes)}"
    is MismatchReason.Block -> "${reason.label}的抽样哈希不同"
    is MismatchReason.BlockCount -> "分块数不同：本机 ${reason.localCount} / 对端 ${reason.remoteCount}"
}

private fun friendlySignalError(error: Throwable): String = when (error) {
    is SignalException -> when (error.code) {
        SignalErrors.CLIENT_NOT_CONNECTED -> "还没连上信令服务器"
        SignalErrors.CLIENT_TIMEOUT -> "服务端没有回应（超时）"
        SignalErrors.CLIENT_BAD_ENDPOINT -> "信令地址格式不对，形如 ws://host:port"
        SignalErrors.ROOM_CODE_TAKEN -> "这个房号已经被占用了，换一个"
        SignalErrors.ROOM_NOT_FOUND -> "房间不存在（可能已经解散）"
        SignalErrors.ROOM_FULL -> "房间人数已满"
        SignalErrors.BAD_PASSWORD -> "密码不对"
        SignalErrors.RATE_LIMITED, SignalErrors.CREATE_RATE_LIMITED -> "操作太频繁，等几秒再试"
        else -> "${error.code}：${error.message ?: "未知错误"}"
    }

    else -> error.message ?: error::class.java.simpleName
}

private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        Log.info("[cinema] 已复制：$text")
    }
}

// ── 舞台右侧栏的两个「非在房」态 ────────────────────────────────────────────
//
// 这两个面板是 `ConnectPanel` / `LobbyPanel` 的**窄版**（380dp 一列）。
// 宽版仍然留在文件里：`--screen=cinema` 的门禁截图与「全屏铺满」的历史布局用的是它，
// 换成窄版会把那一整页的排版判据推翻。产品路径（本机看片）走的是这两个。

@Composable
private fun ConnectSidePanel(
    controller: PlayerController,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    connecting: Boolean,
    error: String?,
    onConnect: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    Text("影厅", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
    Spacer(Modifier.height(6.dp))
    Text(
        "还没连信令服务器。连上之后才能建房或加入房间；只在本机看电影不用连。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.ink2,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = endpoint,
        onValueChange = onEndpointChange,
        label = { Text("信令服务地址") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "默认 $DEFAULT_SIGNAL_ENDPOINT。自建服务器填它的地址，例如 ws://192.168.1.10:8080。",
        style = MaterialTheme.typography.labelSmall,
        color = colors.ink3,
    )
    Spacer(Modifier.height(14.dp))
    Button(onClick = onConnect, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
        Text(if (connecting) "连接中…" else "连接信令服务器")
    }
    if (error != null) {
        Spacer(Modifier.height(10.dp))
        Text(error, color = colors.danger, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = colors.line)
    Spacer(Modifier.height(12.dp))
    Text(
        "不连也能看片：左侧「打开文件」或「从影片库选片」可以直接放。",
        style = MaterialTheme.typography.labelSmall,
        color = colors.ink3,
    )

    // 字幕：本机看片也要能选字幕轨；没有内封文字轨时整张卡不画。
    val subs = rememberSubtitleState(controller.target)
    if (subs.hasTracks) {
        Spacer(Modifier.height(14.dp))
        HallCard(title = "字幕") { SubtitleTrackRow(controller.target, subs, showLabel = false) }
    }
}

@Composable
private fun LobbySidePanel(
    module: DesktopModule,
    controller: PlayerController,
    signalState: SignalState,
    nickname: String,
    onDisconnect: () -> Unit,
    connectError: String?,
) {
    val colors = LocalCinemaColors.current
    val scope = rememberCoroutineScope()

    var roomCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    var rooms by remember { mutableStateOf<List<RoomSummary>?>(null) }
    var roomsLoading by remember { mutableStateOf(false) }
    var roomsError by remember { mutableStateOf<String?>(null) }

    // 有密码的房间：点「加入」先问密码，再走同一条 join。
    var askingFor by remember { mutableStateOf<RoomSummary?>(null) }
    var askingPassword by remember { mutableStateOf("") }

    suspend fun loadRooms() {
        roomsLoading = true
        roomsError = null
        runCatching { module.signaler.listRooms() }
            .onSuccess { rooms = it }
            .onFailure { roomsError = friendlySignalError(it) }
        roomsLoading = false
    }

    LaunchedEffect(signalState) {
        if (signalState is SignalState.Online && module.signaler.room.value == null) loadRooms()
    }

    fun act(success: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            lastError = null
            notice = null
            runCatching { block() }
                .onSuccess { notice = success }
                .onFailure { lastError = friendlySignalError(it) }
            busy = false
        }
    }

    /**
     * 点房间卡的「加入」。
     *
     * 用户口径（2026-11-07）：**无密码直接进房**，有密码才弹输入框。
     * 所以原来那颗「选中」（先填到输入框、再让用户点一次「加入房间」）被拆成了
     * 两条路径 —— 房间卡自己就是入口，不再需要两步。
     */
    fun join(summary: RoomSummary, secret: String?) {
        module.handler.localNickname = nickname
        act("已加入房间 ${summary.roomId}") {
            module.signaler.joinRoom(summary.roomId, nickname, secret?.takeIf { it.isNotBlank() })
        }
    }

    Text("大厅", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(signalStateText(signalState), style = MaterialTheme.typography.labelMedium, color = colors.ink3)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDisconnect) { Text("断开") }
    }
    if (connectError != null) {
        Text(connectError, style = MaterialTheme.typography.bodySmall, color = colors.danger)
    }
    // 昵称那一格删掉（2026-11-08 用户口径）：大厅里再列一遍账号昵称没有用，
    // 要改名字去「设置 → 账号」。建房/加入房间仍然带着昵称（见上面的 join()），
    // 只是不再在这一页显示与编辑。
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = colors.line)
    Spacer(Modifier.height(12.dp))

    Text("建房", style = MaterialTheme.typography.titleSmall, color = colors.ink)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = roomCode,
        onValueChange = { roomCode = it.uppercase().filter { c -> !c.isWhitespace() } },
        label = { Text("房间号（6 位，留空 = 随机）") },
        singleLine = true,
        isError = roomCode.isNotEmpty() && !isRoomCode(roomCode),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("密码（可留空）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            enabled = !busy && (roomCode.isEmpty() || isRoomCode(roomCode)),
            onClick = {
                module.handler.localNickname = nickname
                act("已创建房间") {
                    val created = module.signaler.createRoom(
                        nickname = nickname,
                        password = password.takeIf { it.isNotBlank() },
                        roomId = roomCode.takeIf { it.isNotBlank() },
                    )
                    copyToClipboard(created.roomId)
                }
            },
        ) { Text("创建房间") }
        OutlinedButton(
            enabled = !busy && isRoomCode(roomCode),
            onClick = {
                module.handler.localNickname = nickname
                act("已加入房间") {
                    module.signaler.joinRoom(roomCode, nickname, password.takeIf { it.isNotBlank() })
                }
            },
        ) { Text("按房号加入") }
    }
    if (roomCode.isNotEmpty() && !isRoomCode(roomCode)) {
        Spacer(Modifier.height(6.dp))
        Text(
            "房号必须 6 位，且不含 0 / O / 1 / I / L。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.danger,
        )
    }
    if (busy) {
        Spacer(Modifier.height(6.dp))
        Text("处理中…", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
    }
    if (notice != null) {
        Spacer(Modifier.height(8.dp))
        Text(notice!!, style = MaterialTheme.typography.bodySmall, color = colors.success)
    }
    if (lastError != null) {
        Spacer(Modifier.height(8.dp))
        Text(lastError!!, style = MaterialTheme.typography.bodySmall, color = colors.danger)
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = colors.line)
    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("当前房间", style = MaterialTheme.typography.titleSmall, color = colors.ink)
        Spacer(Modifier.width(8.dp))
        Text(
            if (roomsLoading) "拉取中…" else "${rooms?.size ?: 0} 个",
            style = MaterialTheme.typography.labelSmall,
            color = colors.ink3,
        )
        Spacer(Modifier.weight(1f))
        TextButton(enabled = !roomsLoading, onClick = { scope.launch { loadRooms() } }) { Text("刷新") }
    }
    if (roomsError != null) {
        Text(roomsError!!, style = MaterialTheme.typography.bodySmall, color = colors.danger)
    } else if (rooms.isNullOrEmpty()) {
        Text(
            if (signalState is SignalState.Online) {
                "现在没有开着的房间。建房之后把房号念给对方。"
            } else {
                "连上信令服务器之后才会列出房间。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink3,
        )
    } else {
        rooms!!.forEach { summary ->
            RoomRow(
                summary = summary,
                onJoin = {
                    if (summary.hasPassword) {
                        askingPassword = ""
                        askingFor = summary
                    } else {
                        join(summary, null)
                    }
                },
                enabled = !busy,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    askingFor?.let { target ->
        AlertDialog(
            onDismissRequest = { askingFor = null },
            title = { Text("房间 ${target.roomId} 需要密码") },
            text = {
                OutlinedTextField(
                    value = askingPassword,
                    onValueChange = { askingPassword = it },
                    label = { Text("房间密码") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val secret = askingPassword
                        askingFor = null
                        join(target, secret)
                    },
                ) { Text("加入") }
            },
            dismissButton = { TextButton(onClick = { askingFor = null }) { Text("取消") } },
        )
    }

    // 字幕：没进房也能挑（本机看片同样要选字幕轨）。没有内封文字轨时整张卡不画。
    val subs = rememberSubtitleState(controller.target)
    if (subs.hasTracks) {
        Spacer(Modifier.height(14.dp))
        HallCard(title = "字幕") { SubtitleTrackRow(controller.target, subs, showLabel = false) }
    }
}

/**
 * 从影片库选片（浮窗）。
 *
 * 用 [DialogWindow] 而不是 material3 的 `AlertDialog`：影厅页的画面是 `SwingPanel`
 * 里的 AWT heavyweight `Canvas`，Compose 画的应用内浮层会被它整个盖住（同一条
 * 已实测的限制，见 `PlayerPane` 类注释）。独立窗口不受这条限制。
 */
@Composable
private fun LibraryPickerDialog(
    movies: List<DesktopMovie>,
    currentPath: String?,
    onPick: (DesktopMovie) -> Unit,
    onOpenFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(movies, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            movies
        } else {
            movies.filter { it.title.contains(q, ignoreCase = true) || it.fileName.contains(q, ignoreCase = true) }
        }
    }

    // 无边框弹窗外壳（2026-11-07 用户口径：弹窗与主窗口同一套外形）。
    AppDialogWindow(
        title = "从影片库选片",
        onClose = onDismiss,
        size = DpSize(900.dp, 640.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索片名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onOpenFile) { Text("打开文件…") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (movies.isEmpty()) "影片库还是空的 —— 先去「影片库」页添加根目录并扫描。" else "共 ${filtered.size} 部",
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
            )
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (movies.isEmpty()) "影片库是空的" else "没有匹配的影片", color = colors.ink3)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(filtered, key = { it.path }) { movie ->
                        PickerCard(
                            movie = movie,
                            current = movie.path == currentPath,
                            onPick = { onPick(movie) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerCard(movie: DesktopMovie, current: Boolean, onPick: () -> Unit) {
    val colors = LocalCinemaColors.current
    val cover = rememberCover(movie)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) colors.accent.copy(alpha = 0.14f) else colors.surface2)
            .border(
                width = if (current) 2.dp else 0.dp,
                color = if (current) colors.accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClickLabel = "选择这部片", onClick = onPick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(colors.accent.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        movie.title.take(1).uppercase(),
                        color = colors.accent.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                movie.title,
                color = colors.ink,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (movie.durationMs > 0) formatDuration(movie.durationMs) else "时长待解析",
                color = colors.ink3,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
