package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.cinemahall.core.media.PlaybackSurface
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.PlayerController
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.media.AwtPlaybackSurface
import kotlinx.coroutines.delay

/**
 * 播放区（W1 + W3）。
 *
 * ── 控制条为什么在画面**下方**而不是叠上去 ──────────────────────────────────
 * Phase 0 Demo 1 的实测：Compose 画的控件叠在 `SwingPanel`（AWT heavyweight Canvas）
 * 上**完全不可见**。要叠层只能换独立窗口或自绘 AWT 控件，两者都超出 W1 的范围，
 * 而任务书给的退路就是「放画面下方」。所以这里固定成上下两段。
 */
@Composable
fun PlayerPane(
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * false = 本机不该动播放（成员受控 / 片源不一致的旁听）。
     *
     * 音量与字幕**不跟着关**：那是这台机器自己的输出，与「谁控制播放」无关。
     * 关掉它们只会让用户以为界面坏了。
     */
    interactive: Boolean = true,
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

    val surface: PlaybackSurface = remember { target.createSurface() }

    DisposableEffect(surface) {
        target.attachSurface(surface)
        onDispose { runCatching { target.detachSurface(surface) } }
    }

    // 选片由外层负责（点影片库/打开文件），这里只负责“有片子就起播”。
    LaunchedEffect(movie?.path, autoplay) {
        if (movie != null && autoplay) {
            controller.open(movie.path, movie.fileName, movie.sizeBytes)
            delay(300)
            target.setVolume(volume)
            target.play()
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

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = target.currentPositionMs
            durationMs = target.durationMs
            playing = target.isPlaying
            buffering = target.isBuffering
            error = target.playbackError
            subtitles = target.subtitleLanguages
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
            if (error != null) {
                Text(
                    error!!,
                    color = colors.danger,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
        }

        // ── 控制条（画面下方，不叠层）────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(if (dragging) (dragValue * durationMs).toLong() else positionMs),
                    color = colors.ink2,
                    style = MaterialTheme.typography.labelMedium,
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
                        if (durationMs > 0) {
                            target.seekTo((dragValue * durationMs).toLong())
                        }
                        dragging = false
                    },
                    enabled = interactive && durationMs > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(formatDuration(durationMs), color = colors.ink2, style = MaterialTheme.typography.labelMedium)
            }

            // ⚠️ 控制条必须**可折行**：影厅页右侧有 332dp 常驻成员栏，播放区比首页窄
            // 一大截。用固定 `Row` 时，一旦固定宽度加起来超过可用宽度，排在最后的那几个
            // 元素会被压到 0 宽 —— 文字变成一列竖排的「1 0 0 %」（真机截图里就是这个样子）。
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = { target.seekTo((positionMs - 10_000).coerceAtLeast(0)) },
                    enabled = interactive,
                ) {
                    Text("−10s")
                }
                Button(
                    onClick = {
                        if (playing) target.pause() else target.play()
                    },
                    enabled = interactive,
                ) {
                    Text(if (playing) "暂停" else "播放")
                }
                OutlinedButton(
                    onClick = { target.seekTo(positionMs + 10_000) },
                    enabled = interactive,
                ) { Text("+10s") }

                Text(
                    if (buffering) "缓冲中…" else if (playing) "播放中" else "已暂停",
                    color = colors.ink3,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )

                // 倍速：固定几档，避免连续滑块带来的“到底是多少”问题。
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { candidate ->
                    val selected = kotlin.math.abs(speed - candidate) < 0.01f
                    TextButton(onClick = {
                        speed = candidate
                        target.setSpeed(candidate)
                    }, enabled = interactive) {
                        Text(
                            if (candidate == 1f) "1×" else "${candidate}×",
                            color = if (selected) colors.accent else colors.ink3,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                SubtitleMenu(target = target, subtitles = subtitles, selected = subtitleIndex) {
                    subtitleIndex = it
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "音量",
                        color = colors.ink3,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Slider(
                        value = volume,
                        onValueChange = {
                            onVolumeChange(it)
                            target.setVolume(it)
                        },
                        modifier = Modifier.width(104.dp).padding(horizontal = 6.dp),
                    )
                    // `softWrap = false` 不是美观问题：宽度不够时默认行为是把
                    // "100%" 拆成四个字符各占一行，控制条会被撑高、底部那行片名被顶出窗口。
                    Text(
                        "${(volume * 100).toInt()}%",
                        color = colors.ink2,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            if (movie != null) {
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

@Composable
private fun SubtitleMenu(
    target: com.cinemahall.desktop.media.VlcPlaybackTarget,
    subtitles: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = subtitles.isNotEmpty()) {
            Text(
                if (subtitles.isEmpty()) "无字幕轨" else "字幕 ${subtitles.size} 条",
                color = LocalCinemaColors.current.ink3,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            subtitles.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(if (label.isBlank()) "字幕 ${index + 1}（未标语言）" else "字幕 ${index + 1}（$label）") },
                    onClick = {
                        expanded = false
                        target.selectSubtitleTrack(index)
                        onSelected(index)
                    },
                )
            }
        }
    }
}
