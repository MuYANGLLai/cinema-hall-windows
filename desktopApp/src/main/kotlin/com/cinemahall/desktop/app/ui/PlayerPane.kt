package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.CinemaThemeOn
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.PlayerController
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.media.AwtPlaybackSurface
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 播放区（W1 + W3）。
 *
 * ── 控制条：2026-11-07 起**浮在画面上**（[PlaybackOverlay]）───────────────────
 * Phase 0 Demo 1 的实测：Compose 画的控件叠在 `SwingPanel`（AWT heavyweight Canvas）
 * 上**完全不可见**。所以「浮」只能靠另一个窗口：一个属于主窗口的 `JWindow` +
 * `ComposePanel`，跟着画面底边定位、2.5 秒无操作自动收回（用户口径）。
 *
 * 画面下方那一排**保留**成兜底：浮层窗口在个别环境（远程桌面、无 DWM）里可能
 * 起不来，那时 `overlay.broken` 为真，这一排接回来，功能一个不少 —— 只是位置
 * 回到画面下方。判断依据由 `PlaybackOverlay` 自己给，这里不猜。
 *
 * ── 2026-11-06：画面改成**进程级单例**（[PlayerController.surface]）────────────
 * 切一次页面再回来，原来会重新 `remember` 一块新 Canvas 并把旧的 `detachSurface()` ——
 * 视频输出当场没了，画面黑掉而声音还在走；成员端播放键又是灰的，于是「一直无法播放」。
 * 现在画面跟着播放器走，切页只是把同一块 Canvas 重新挂回组合（[attachSurface] 幂等），
 * 并且在回到页面时兜底修一次「输出丢了但播放器还认为在播」的场面。
 */
class LocalPlaybackHooks(
    val onPlay: (Long) -> Unit = {},
    val onPause: (Long) -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
)

@Composable
fun PlayerPane(
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
    /** 播放/暂停/拖动/倍速之后通知同步引擎；默认空实现（单机看片）。 */
    hooks: LocalPlaybackHooks = LocalPlaybackHooks(),
    /**
     * false = 本机不该动播放（成员受控 / 片源不一致的旁听）。
     *
     * 音量与字幕**不跟着关**：那是这台机器自己的输出，与「谁控制播放」无关。
     * 关掉它们只会让用户以为界面坏了。
     */
    interactive: Boolean = true,
    /** 当前是否全屏（只影响按钮图标与布局留白）。 */
    fullscreen: Boolean = false,
    /** 切全屏。由 `AppRoot` 接到窗口上。 */
    onToggleFullscreen: () -> Unit = {},
    /** 「手动同步」：房主立刻广播一次自己的位置；从端请房主回一条心跳。 */
    onManualSync: (() -> Unit)? = null,
    /** 控制权说明（成员端画在画面左上角，等价于安卓端那条灰条）。 */
    controlHint: String? = null,
    /**
     * 空态里那颗「从影片库选片」。null = 不画（例如本页没有影片库入口）。
     *
     * 为什么空态必须由这里出（2026-11-12 UI 审查 F1）：影厅页没有选片时，画面区是
     * 一整块纯黑（864×888，占窗口 55%），用户第一眼会当成播放器坏了。而空态只能用
     * Compose 画，**Compose 内容又盖不住 AWT 重量级画布** —— 所以「没片」时把画布
     * 整块挪出窗口（不是摘掉：peer 与 vout 都不能动，见类注释），空态才看得见。
     */
    onPickFromLibrary: (() -> Unit)? = null,
) {
    val colors = LocalCinemaColors.current
    // 位置换算要用（把 dp 偏移换成像素），见下面 onGloballyPositioned。
    val density = LocalDensity.current
    val target = controller.target

    if (target == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("播放不可用", style = MaterialTheme.typography.titleLarge, color = colors.danger)
                Spacer(Modifier.height(8.dp))
                Text(controller.initError ?: "未知原因", color = colors.ink2)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenFile) { Text("用系统播放器打开一个文件试机") }
            }
        }
        return
    }

    // 画面与播放器同寿命：**不在这里创建**，也不在离开页面时摘掉（见类注释）。
    val surface = controller.surface
    if (surface == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("画面初始化失败", color = colors.danger)
        }
        return
    }

    DisposableEffect(surface) {
        target.attachSurface(surface)
        // 切页幕布要知道画面在哪：注册之后它就能在离开影厅页的那一瞬间盖住这块矩形
        // （见 `PlayerCurtain`）。幂等，重复注册没有副作用。
        (surface as? AwtPlaybackSurface)?.let { PlayerCurtain.register(it.canvas) }
        // 回到页面时兜底：如果视频输出在切页过程中丢了，libVLC 会停在 STOPPED/ENDED
        // 而 `requestedPlaying` 仍是 true（引擎那边也认为在播）。此时把播放器重新
        // 叫起来 —— 只对「意图在播」的机器做，暂停中的机器不受影响。
        if (target.isPlayRequested && !target.isPlaying && !target.isBuffering) {
            target.resumeIfOutputLost()
        }
        onDispose { /* 故意不 detachSurface：画面要留给下一次挂载 */ }
    }

    // 选片由外层负责（点影片库/打开文件），这里只负责「把片子装进播放器」。
    // 2026-11-06 起**不再自动起播**：装好就停在首帧，等用户按「播放」（房主按下去才会广播）。
    //
    // ── 2026-11-07：已经装好的片不要重开 ──────────────────────────────────────
    // 切到导航栏别的页再切回来，这个 effect 会**重新跑一遍**（组合被拆掉又建起来）。
    // 原先无条件 `open(...)`，那是一次 `setMediaItem`：位置打回 0、索引重建、画面黑掉。
    // 用户看到的「切页进度归 0」不是同步引擎干的，就是这里。判据用播放器自己记着的
    // `openedPath`，不是界面上的 `movie.path` —— 后者在切页时一直是同一个值。
    LaunchedEffect(movie?.path, autoplay) {
        if (movie != null) {
            val alreadyLoaded = controller.openedPath == movie.path
            if (!alreadyLoaded) {
                // 先等画面真的有尺寸再装片：`SwingPanel` 刚挂上来的那几十毫秒里 Canvas 是
                // `0x0`，以这个尺寸建立视频输出，libVLC 的 vout 会按零尺寸初始化、之后不再
                // 补画（症状是「按了播放没反应」）。等待放在 IO 上，EDT 不阻塞。
                //
                // ⚠️ 这里的等待**必须短**（2026-11-12 实测）：`awaitReady` 现在能挂就立刻
                // 返回（20ms 轮询），上限只是防呆。原来写的是 4 秒，而等待靠的 AWT 事件
                // 在这条路径上根本不触发 —— 于是每次选片都白等 4 秒，用户报的「片库选完
                // 好几秒才播放」就是它（日志：进影厅 02.299 → 选片 06.301，整整 4002ms）。
                val waited = withContext(Dispatchers.IO) {
                    val started = System.currentTimeMillis()
                    (surface as? AwtPlaybackSurface)?.awaitReady(2_000)
                    System.currentTimeMillis() - started
                }
                com.cinemahall.desktop.app.Log.info("[player] 画面就绪等待 ${waited}ms，开始装片")
                controller.open(movie.path, movie.fileName, movie.sizeBytes)
                // 音量不依赖画面，装好片就落下去；起播紧跟其后，中间不再夹 delay ——
                // 原来那个 300ms 等于让用户白白多等 0.3 秒才看见第一帧。
                target.setVolume(volume)
                // 自动起播只在「新装进来」的这一次发生：切页回来时 `autoplay` 还是启动时的
                // true，照原样再调一次 `play()` 会把用户按下的暂停顶掉。
                if (autoplay) target.play()
            }
        }
    }

    var error by remember { mutableStateOf<String?>(null) }

    // 控制条状态只有这一份：浮层与兜底那排读的是同一个对象（见 `PlaybackBarState`）。
    val bar = remember(target) { PlaybackBarState() }
    LaunchedEffect(target) {
        while (true) {
            bar.positionMs = target.currentPositionMs
            bar.durationMs = target.durationMs
            bar.playing = target.isPlaying
            bar.buffering = target.isBuffering
            error = target.playbackError
            delay(250)
        }
    }

    // ── 悬浮控制栏 ────────────────────────────────────────────────────────────
    // 只有真 AWT 画面才能定位浮层（`convertPoint` 要有活的组件），别的实现直接退回兜底。
    val overlay = remember(surface) {
        (surface as? AwtPlaybackSurface)?.let { awt ->
            PlaybackOverlay(awt, shouldStayVisible = { bar.dragging || bar.speedMenu })
        }
    }
    val overlayReady = overlay != null && !overlay.broken

    val scheme = MaterialTheme.colorScheme
    SideEffect {
        bar.volume = volume
        bar.interactive = interactive
        bar.fullscreen = fullscreen
        bar.onVolumeChange = onVolumeChange
        bar.onToggleFullscreen = onToggleFullscreen
        bar.onManualSync = onManualSync
        bar.hooks = hooks
        bar.themeScheme = scheme
        bar.themeColors = colors
    }

    if (overlay != null && overlayReady) {
        DisposableEffect(overlay) {
            // 内容只装这一次：之后靠 `bar` 里的快照状态驱动浮层重组。每帧重装会把
            // 浮层里的局部状态（正在拖的进度、展开的菜单）一次次重置。
            com.cinemahall.desktop.app.Log.info("[overlay] 装配成功，装上内容")
            overlay.setContent {
                val themeColors = bar.themeColors
                val themeScheme = bar.themeScheme
                if (themeColors != null && themeScheme != null) {
                    CinemaThemeOn(themeScheme, themeColors) {
                        PlaybackBar(
                            target = target,
                            state = bar,
                            modifier = overlay.sizeModifier,
                            showTitle = false,
                        )
                    }
                }
            }
            overlay.startWatching()
            onDispose { overlay.dispose() }
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.videoBackdrop)
                // 把「画面真正显示时在哪」告诉切页幕布：进影厅页那一瞬间画面窗口
                // 还在新建，那块矩形是没画过的（窗口是 NOREDIRECTIONBITMAP → 露桌面）。
                // 这里用 Compose 的位置 + 被挪走的距离反推，页面在窗口外也拿得到。
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    val shift = with(density) {
                        (-PlayerCurtain.HIDDEN_OFFSET_X_DP).dp.roundToPx()
                    }
                    PlayerCurtain.setDestination(
                        x = pos.x.roundToInt() + shift,
                        y = pos.y.roundToInt(),
                        width = coords.size.width,
                        height = coords.size.height,
                    )
                },
        ) {
            // ── 没选片时把画布缩成 0×0，让空态（Compose 画的）能被看见 ────────────
            // AWT 重量级组件永远盖在 Compose 之上（同「控制条只能用独立窗口」那条），
            // 所以空态要显示，画布就必须让开。缩尺寸而不是挪位置，理由见下面那段警告。
            // 画布本身不摘（peer 与 vout 都不动），选片之后 `awaitReady` 会等到它有
            // 真实尺寸再装片 —— 这正是「零尺寸初始化导致按播放没反应」的既有防线。
            val idle = movie == null
            SwingPanel(
                // ⚠️ `background` 必须显式给，默认是**白**（`Color.White`）。
                // Compose 会在 `SwingPanel` 里再包一层 JPanel，它的底色就是这个值；
                // 拖动窗口边框时，那一层会在 Canvas 重画之前先被刷一遍 ——
                // 于是观众看到的就是「播放器与影厅之间一条白边」，甚至整条接缝发白
                // （2026-11-08 用户报的就是这个）。给它画面底色，等于把这条缝填成黑的。
                background = colors.videoBackdrop,
                factory = { (surface as AwtPlaybackSurface).panel },
                // ⚠️ 这里**不能**用 `offset` 把画布挪出窗口（2026-11-12 两轮实测）：
                // `offset` 只要出现在本函数里（加在 `SwingPanel` 上、或加在它外面套的
                // `Box` 上，都一样），AWT 子窗口就会**无视祖先的位移** —— 常驻层把整页
                // 挪到 -4000dp，画布却仍停在 196,32 挡住别的页（AWT 树实测：
                // 去掉了 offset 才回到 `bounds=-3804,32`）。缩尺寸没有这个副作用。
                modifier = if (idle) Modifier.size(0.dp) else Modifier.fillMaxSize(),
            )
            if (idle) {
                EmptyPlayerHint(
                    onPickFromLibrary = onPickFromLibrary,
                    onOpenFile = onOpenFile,
                )
            }
            if (controller.initError != null) {
                Text(
                    controller.initError,
                    color = colors.danger,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
            // 成员端那条「播放由房主控制」的说明：安卓端也有一条同样的灰条，
            // 位置在画面左上角，不挡操作（这里的按钮本来就灰了）。
            controlHint?.let { hint ->
                Text(
                    hint,
                    color = colors.ink3,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
            if (error != null) {
                Text(
                    error!!,
                    color = colors.danger,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
        }

        // 兜底：浮层窗口起不来时控制条回到画面下方（功能不减，位置不同）。
        if (!overlayReady) {
            PlaybackBar(
                target = target,
                state = bar,
                showTitle = movie != null && !fullscreen,
                title = movie?.let { "${it.title} · ${it.path}" },
            )
        }
    }
}

/**
 * 影厅页的「还没有选片」空态。
 *
 * 用户在 UI 审查里看到的是：一整块 864×888 的纯黑，占窗口 55%，没有任何字或按钮；
 * 唯一的提示在右侧栏，还是 12sp 的灰字「还没有选片」。首次进入会以为播放器坏了。
 *
 * 这里给的是「图标 + 一句话 + 两条出路」：从影片库挑（主），或直接开本地文件（次）。
 * 它只在 `movie == null` 时画，而且必须配合上面把画布挪出窗口 —— Compose 盖不住
 * AWT 重量级画布（这是 2026-11-07 就定下的既有结论）。
 */
@Composable
private fun EmptyPlayerHint(
    onPickFromLibrary: (() -> Unit)?,
    onOpenFile: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                AppIconView(AppIcon.Cinema, 26.dp, colors.accent)
            }
            Spacer(Modifier.height(16.dp))
            Text("还没有选片", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "从影片库挑一部，或直接打开本地文件",
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onPickFromLibrary != null) {
                    Button(onClick = onPickFromLibrary) { Text("从影片库选片") }
                }
                OutlinedButton(onClick = onOpenFile) { Text("打开文件") }
            }
        }
    }
}
