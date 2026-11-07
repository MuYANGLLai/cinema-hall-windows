package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.CinemaColors
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.media.VlcPlaybackTarget

/**
 * 播放控制条的状态（2026-11-07）。
 *
 * ── 为什么把状态从 `PlayerPane` 里拎出来 ──────────────────────────────────────
 * 控制条现在有**两个宿主**：浮在画面上的独立窗口（[PlaybackOverlay]，常态）与
 * 画面下方的兜底版本（浮层窗口起不来时）。两个宿主如果各自 `remember` 一套
 * 位置/时长/播放态，就会出现「浮层显示 12:31、兜底那排显示 00:00」这类互相打架的
 * 画面。所以状态只有这一份，两边都读它。
 *
 * 轮询（每 250ms）由 `PlayerPane` 负责推进来 —— 状态类自己不持有协程，
 * 与 `CinemaSyncEngine` 的做法一致：谁装配谁负责驱动。
 */
class PlaybackBarState {
    // ── 轮询得到的播放状态 ────────────────────────────────────────────────────
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)

    /** 倍速**不轮询**：它只由用户在这一条上选，播放器不会自己改。 */
    var speed by mutableStateOf(1f)

    // 字幕选择**不在控制条上**（2026-11-08 用户口径）：它挪到影厅右栏的「影片」卡片里，
    // 与控制条同一套「跟着片子走」的语义。控制条只留播放相关的动作。

    // ── 拖动进度条时的临时态（拖的过程中不要被轮询顶回去）────────────────────
    var dragging by mutableStateOf(false)
    var dragValue by mutableStateOf(0f)
    var speedMenu by mutableStateOf(false)

    // ── 由 `PlayerPane` 每次重组推进来的「当前参数」──────────────────────────
    var volume by mutableStateOf(1f)
    var interactive by mutableStateOf(true)
    var fullscreen by mutableStateOf(false)

    // ── 主题：浮层是**另一个组合**，得把这些现成值带过去 ──────────────────────
    var themeScheme by mutableStateOf<ColorScheme?>(null)
    var themeColors by mutableStateOf<CinemaColors?>(null)

    // ── 回调：不进快照，只在点击那一刻读 ─────────────────────────────────────
    var onVolumeChange: (Float) -> Unit = {}
    var onToggleFullscreen: () -> Unit = {}
    var onManualSync: (() -> Unit)? = null
    var hooks: LocalPlaybackHooks = LocalPlaybackHooks()
}

/**
 * 播放控制条：上排「时间 + 进度 + 状态」，下排「图标按钮」。
 *
 * 结构对齐安卓端 `CinemaPlaybackBar`；调用方决定它落在画面下方（兜底）还是
 * 浮在画面上（[PlaybackOverlay]）。**高度由内容自己决定**，不 `fillMaxSize` ——
 * 浮层窗口的高度就是按这里量出来的像素反算的（见 [PlaybackOverlay.reportContentHeight]）。
 */
@Composable
fun PlaybackBar(
    target: VlcPlaybackTarget,
    state: PlaybackBarState,
    modifier: Modifier = Modifier,
    /** 兜底版会带一行「片名 · 路径」；浮在画面上时那一行是噪音，关掉。 */
    showTitle: Boolean = true,
    title: String? = null,
) {
    val colors = LocalCinemaColors.current

    Column(modifier.fillMaxWidth().background(colors.surface)) {
        // 浮在画面上时与影片之间需要一条分界，否则暗色画面与暗色控制条糊成一片。
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line2))
        Column(Modifier.fillMaxWidth().padding(horizontal = if (state.fullscreen) 24.dp else 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 与安卓端同一种读法：`当前位置 / 总长`，等宽字体，不抖动。
                    "${formatDuration(if (state.dragging) (state.dragValue * state.durationMs).toLong() else state.positionMs)} / " +
                        if (state.durationMs > 0) formatDuration(state.durationMs) else "--:--",
                    color = colors.ink2,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
                Slider(
                    value = if (state.durationMs > 0) {
                        if (state.dragging) {
                            state.dragValue
                        } else {
                            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        }
                    } else {
                        0f
                    },
                    onValueChange = {
                        state.dragging = true
                        state.dragValue = it
                    },
                    onValueChangeFinished = {
                        if (state.durationMs > 0 && state.interactive) {
                            val to = (state.dragValue * state.durationMs).toLong()
                            target.seekTo(to)
                            state.hooks.onSeek(to)
                        }
                        state.dragging = false
                    },
                    enabled = state.interactive && state.durationMs > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(
                    when {
                        state.buffering -> "缓冲中…"
                        state.playing -> "播放中"
                        else -> "已暂停"
                    },
                    color = colors.ink3,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Row(
                Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlayerIconButton(
                    icon = PlayerIcon.Rewind,
                    contentDescription = "后退 10 秒",
                    colors = colors,
                    enabled = state.interactive,
                ) {
                    val to = (state.positionMs - 10_000).coerceAtLeast(0)
                    target.seekTo(to)
                    state.hooks.onSeek(to)
                }
                PlayerIconButton(
                    icon = if (state.playing) PlayerIcon.Pause else PlayerIcon.Play,
                    contentDescription = if (state.playing) "暂停" else "播放",
                    colors = colors,
                    enabled = state.interactive,
                    primary = true,
                ) {
                    // 暂停/播放都按 `isPlayRequested` 判：缓冲期间 `isPlaying` 可能是 false，
                    // 而按钮文案与图标必须说的是「下一按会发生什么」——
                    // 缓冲时按下去也该是暂停（引擎那边读的也是 isPlayRequested）。
                    if (target.isPlayRequested) {
                        target.pause()
                        state.hooks.onPause(target.currentPositionMs)
                    } else {
                        target.play()
                        state.hooks.onPlay(target.currentPositionMs)
                    }
                }
                PlayerIconButton(
                    icon = PlayerIcon.Forward,
                    contentDescription = "前进 10 秒",
                    colors = colors,
                    enabled = state.interactive,
                ) {
                    val to = state.positionMs + 10_000
                    target.seekTo(to)
                    state.hooks.onSeek(to)
                }

                PlayerIconButton(
                    icon = if (state.volume > 0.001f) PlayerIcon.VolumeOn else PlayerIcon.VolumeOff,
                    contentDescription = if (state.volume > 0.001f) "静音" else "取消静音",
                    colors = colors,
                ) {
                    // 一键静音：**影片**通道；通话音量在右栏「通话」那张卡里单独管。
                    val next = if (state.volume > 0.001f) 0f else 1f
                    state.onVolumeChange(next)
                    target.setVolume(next)
                }
                Slider(
                    value = state.volume,
                    onValueChange = {
                        state.onVolumeChange(it)
                        target.setVolume(it)
                    },
                    modifier = Modifier.width(96.dp).padding(horizontal = 4.dp),
                )
                Text(
                    "${(state.volume * 100).toInt()}%",
                    color = colors.ink2,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.widthIn(min = 34.dp),
                )

                Spacer(Modifier.weight(1f))

                // 倍速：文字按钮显示当前档位，点开才出一排（与安卓端一致）。
                Box {
                    OutlinedButton(onClick = { state.speedMenu = true }, enabled = state.interactive) {
                        Text(formatSpeedLabel(state.speed))
                    }
                    DropdownMenu(expanded = state.speedMenu, onDismissRequest = { state.speedMenu = false }) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { candidate ->
                            val selected = kotlin.math.abs(state.speed - candidate) < 0.01f
                            DropdownMenuItem(
                                // 选中的那一档用**自绘对勾**（不是「✓ 」字符串前缀）：前缀只在
                                // 选中项出现，会把那一行整体右移，一列菜单看着是歪的。
                                leadingIcon = { CheckSlot(selected) },
                                text = { Text(formatSpeedLabel(candidate)) },
                                onClick = {
                                    state.speedMenu = false
                                    state.speed = candidate
                                    target.setSpeed(candidate)
                                    state.hooks.onSpeed(candidate)
                                },
                            )
                        }
                    }
                }

                state.onManualSync?.let { sync ->
                    TextButton(onClick = sync) {
                        Text("同步", color = colors.ink2, style = MaterialTheme.typography.labelMedium)
                    }
                }

                PlayerIconButton(
                    icon = if (state.fullscreen) PlayerIcon.FullscreenExit else PlayerIcon.Fullscreen,
                    contentDescription = if (state.fullscreen) "退出全屏" else "全屏",
                    colors = colors,
                    onClick = state.onToggleFullscreen,
                )
            }

            if (showTitle && title != null) {
                Text(
                    title,
                    color = colors.ink3,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 菜单里的「对勾位」：选中画自绘对勾，未选中留同样大的空位。
 *
 * 用 `leadingIcon` 而不是给文字加「✓ 」前缀 —— 前缀只在选中项出现，会把那一行
 * 整体右移，一列菜单看着是歪的。
 */
@Composable
private fun CheckSlot(checked: Boolean) {
    if (checked) {
        AppIconView(AppIcon.Check, 14.dp, LocalCinemaColors.current.accent)
    } else {
        Spacer(Modifier.size(14.dp))
    }
}

private fun formatSpeedLabel(speed: Float): String =
    if (kotlin.math.abs(speed - speed.toInt()) < 0.01f) "${speed.toInt()}×" else "${speed}×"
