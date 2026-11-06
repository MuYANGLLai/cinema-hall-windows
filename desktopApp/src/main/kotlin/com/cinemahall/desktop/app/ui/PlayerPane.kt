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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.PlayerController
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.media.AwtPlaybackSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 播放区（W1 + W3）。
 *
 * ── 控制条为什么在画面**下方**而不是叠上去 ──────────────────────────────────
 * Phase 0 Demo 1 的实测：Compose 画的控件叠在 `SwingPanel`（AWT heavyweight Canvas）
 * 上**完全不可见**。要叠层只能换独立窗口或自绘 AWT 控件，两者都超出 W1 的范围，
 * 而任务书给的退路就是「放画面下方」。所以这里固定成上下两段。
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
) {
    val colors = LocalCinemaColors.current
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
    LaunchedEffect(movie?.path, autoplay) {
        if (movie != null) {
            // 先等画面真的有尺寸再装片。`SwingPanel` 刚挂上来的那几十毫秒里 Canvas 是
            // `0x0`，以这个尺寸建立视频输出，libVLC 的 vout 会按零尺寸初始化、之后不再
            // 补画（症状是「按了播放没反应」）。等待放在 IO 上，EDT 不阻塞。
            withContext(Dispatchers.IO) { (surface as? AwtPlaybackSurface)?.awaitReady(4_000) }
            controller.open(movie.path, movie.fileName, movie.sizeBytes)
            delay(300)
            target.setVolume(volume)
            if (autoplay) target.play()
        }
    }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var subtitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var subtitleIndex by remember { mutableStateOf(-1) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = target.currentPositionMs
            durationMs = target.durationMs
            playing = target.isPlaying
            buffering = target.isBuffering
            error = target.playbackError
            subtitles = target.subtitleLanguages
            subtitleIndex = target.currentSubtitleIndex
            delay(250)
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(colors.videoBackdrop),
        ) {
            SwingPanel(
                factory = { (surface as AwtPlaybackSurface).panel },
                modifier = Modifier.fillMaxSize(),
            )
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

        // ── 控制条（画面下方，不叠层）────────────────────────────────────────
        // 结构对齐安卓端 `CinemaPlaybackBar`：上排「时间 + 进度条」，下排「图标按钮」。
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = if (fullscreen) 24.dp else 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 与安卓端同一种读法：`当前位置 / 总长`，等宽字体，不抖动。
                    "${formatDuration(if (dragging) (dragValue * durationMs).toLong() else positionMs)} / " +
                        if (durationMs > 0) formatDuration(durationMs) else "--:--",
                    color = colors.ink2,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Slider(
                    value = if (durationMs > 0) {
                        if (dragging) dragValue else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        if (durationMs > 0 && interactive) {
                            val to = (dragValue * durationMs).toLong()
                            target.seekTo(to)
                            hooks.onSeek(to)
                        }
                        dragging = false
                    },
                    enabled = interactive && durationMs > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(
                    when {
                        buffering -> "缓冲中…"
                        playing -> "播放中"
                        else -> "已暂停"
                    },
                    color = colors.ink3,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlayerIconButton(
                    icon = PlayerIcon.Rewind,
                    contentDescription = "后退 10 秒",
                    colors = colors,
                    enabled = interactive,
                ) {
                    val to = (positionMs - 10_000).coerceAtLeast(0)
                    target.seekTo(to)
                    hooks.onSeek(to)
                }
                PlayerIconButton(
                    icon = if (playing) PlayerIcon.Pause else PlayerIcon.Play,
                    contentDescription = if (playing) "暂停" else "播放",
                    colors = colors,
                    enabled = interactive,
                    primary = true,
                ) {
                    // 暂停/播放都按 `isPlaying` 判：缓冲期间它可能是 false，
                    // 而按钮文案与图标必须说的是「下一按会发生什么」——
                    // 缓冲时按下去也该是暂停（引擎那边读的是 isPlayRequested）。
                    if (target.isPlayRequested) {
                        target.pause()
                        hooks.onPause(target.currentPositionMs)
                    } else {
                        target.play()
                        hooks.onPlay(target.currentPositionMs)
                    }
                }
                PlayerIconButton(
                    icon = PlayerIcon.Forward,
                    contentDescription = "前进 10 秒",
                    colors = colors,
                    enabled = interactive,
                ) {
                    val to = positionMs + 10_000
                    target.seekTo(to)
                    hooks.onSeek(to)
                }

                PlayerIconButton(
                    icon = if (volume > 0.001f) PlayerIcon.VolumeOn else PlayerIcon.VolumeOff,
                    contentDescription = if (volume > 0.001f) "静音" else "取消静音",
                    colors = colors,
                ) {
                    // 一键静音：**影片**通道；通话音量在右侧「语音」那块单独管
                    // （安卓端那颗麦克风键在桌面等价于「开启通话」，已在侧栏）。
                    val next = if (volume > 0.001f) 0f else 1f
                    onVolumeChange(next)
                    target.setVolume(next)
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        onVolumeChange(it)
                        target.setVolume(it)
                    },
                    modifier = Modifier.width(96.dp).padding(horizontal = 4.dp),
                )
                Text(
                    "${(volume * 100).toInt()}%",
                    color = colors.ink2,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.widthIn(min = 34.dp),
                )

                Spacer(Modifier.weight(1f))

                // 倍速：文字按钮显示当前档位，点开才出一排（与安卓端一致）。
                Box {
                    OutlinedButton(onClick = { speedMenu = true }, enabled = interactive) {
                        Text(formatSpeedLabel(speed))
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { candidate ->
                            val selected = kotlin.math.abs(speed - candidate) < 0.01f
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (selected) "✓ ${formatSpeedLabel(candidate)}" else formatSpeedLabel(candidate),
                                    )
                                },
                                onClick = {
                                    speedMenu = false
                                    speed = candidate
                                    target.setSpeed(candidate)
                                    hooks.onSpeed(candidate)
                                },
                            )
                        }
                    }
                }

                SubtitleMenu(
                    target = target,
                    subtitles = subtitles,
                    selected = subtitleIndex,
                )

                onManualSync?.let { sync ->
                    TextButton(onClick = sync) {
                        Text("同步", color = colors.ink2, style = MaterialTheme.typography.labelMedium)
                    }
                }

                PlayerIconButton(
                    icon = if (fullscreen) PlayerIcon.FullscreenExit else PlayerIcon.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏",
                    colors = colors,
                    onClick = onToggleFullscreen,
                )
            }

            if (movie != null && !fullscreen) {
                Text(
                    movie.title + " · " + movie.path,
                    color = colors.ink3,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun formatSpeedLabel(speed: Float): String =
    if (kotlin.math.abs(speed - speed.toInt()) < 0.01f) "${speed.toInt()}×" else "${speed}×"

/**
 * 字幕菜单。
 *
 * ── 「关闭字幕」为什么必须显式有一项（2026-11-06）──────────────────────────────
 * 之前只有「字幕 1/2/3…」，选中之后**没有任何视觉反馈**（菜单不标当前项），
 * 而关掉内封字幕的唯一办法是把片子从头拖一遍 —— 用户报的「不能选使用哪条字幕」
 * 有一半是这件事：他看不到自己选中了没有。
 *
 * 现在：当前项打勾、第一项是「关闭字幕」，并且选择动作会写日志（见
 * `VlcPlaybackTarget.applySubtitle`），点不动时 app.log 里有据可查。
 */
@Composable
private fun SubtitleMenu(
    target: com.cinemahall.desktop.media.VlcPlaybackTarget,
    subtitles: List<String>,
    selected: Int,
) {
    val colors = LocalCinemaColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = subtitles.isNotEmpty()) {
            Text(
                if (subtitles.isEmpty()) "无字幕" else "字幕 ${selected + 1}/${subtitles.size}",
                color = colors.ink2,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (selected < 0) "✓ 关闭字幕" else "关闭字幕") },
                onClick = {
                    expanded = false
                    target.disableSubtitles()
                },
            )
            subtitles.forEachIndexed { index, label ->
                val text = if (label.isBlank()) "字幕 ${index + 1}（未标语言）" else "字幕 ${index + 1}（$label）"
                DropdownMenuItem(
                    text = { Text(if (index == selected) "✓ $text" else text) },
                    onClick = {
                        expanded = false
                        target.selectSubtitleTrack(index)
                    },
                )
            }
        }
    }
}
