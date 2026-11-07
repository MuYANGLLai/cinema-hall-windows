package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.media.VlcPlaybackTarget
import kotlinx.coroutines.delay

/**
 * 字幕轨的当前快照（`target` 是 VLC 播放器，本身不是 Compose 状态，所以轮询）。
 *
 * 400ms 一次：字幕轨只在换片/换轨时变，250ms 是给进度条用的，这里不必那么勤。
 */
class SubtitleUiState(val tracks: List<String>, val index: Int) {
    val hasTracks: Boolean get() = tracks.isNotEmpty()
}

@Composable
fun rememberSubtitleState(target: VlcPlaybackTarget?): SubtitleUiState {
    var tracks by remember(target) { mutableStateOf(target?.subtitleLanguages ?: emptyList()) }
    var index by remember(target) { mutableStateOf(target?.currentSubtitleIndex ?: -1) }
    LaunchedEffect(target) {
        val player = target ?: return@LaunchedEffect
        while (true) {
            tracks = player.subtitleLanguages
            index = player.currentSubtitleIndex
            delay(400)
        }
    }
    return SubtitleUiState(tracks, index)
}

/**
 * 字幕选择栏（2026-11-08 用户口径：放进「影片」卡，跟安卓简洁模式一样）。
 *
 * ── 为什么是「一行横滑的胶囊」而不是下拉菜单 ────────────────────────────────
 * 安卓端简洁模式就是这么做的（选哪条字幕是**当前这部片**的属性，跟着选片走）。
 * 下拉要么多一次点击，要么把当前选择藏起来；一行摆开则「有哪几条、现在用的是哪条」
 * 一眼看见 —— 这正是之前「选完不知道选没选中」那个 bug 的成因。
 *
 * 没有内封文字轨时**整行不画**（调用方用 [SubtitleUiState.hasTracks] 判断），
 * 与安卓端一致：片子里没有字幕，界面上就不该出现一个点不动的控件。
 */
@Composable
fun SubtitleTrackRow(
    target: VlcPlaybackTarget?,
    state: SubtitleUiState,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    if (target == null || !state.hasTracks) return
    val colors = LocalCinemaColors.current

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLabel) {
            Text(
                "字幕",
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
            )
            Spacer(Modifier.width(8.dp))
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubtitleChip(
                label = "关闭",
                selected = state.index < 0,
            ) { target.disableSubtitles() }
            state.tracks.forEachIndexed { index, label ->
                SubtitleChip(
                    label = chipLabel(index, label),
                    selected = index == state.index,
                ) { target.selectSubtitleTrack(index) }
            }
        }
    }
}

private fun chipLabel(index: Int, raw: String): String {
    val name = raw.trim()
    return if (name.isEmpty()) "字幕 ${index + 1}" else "字幕 ${index + 1} · $name"
}

@Composable
private fun SubtitleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCinemaColors.current
    val interaction = remember { MutableInteractionSource() }
    val hover = hoverProgress(interaction)
    val press = pressProgress(interaction)
    // 选中项用主色描边 + 主色文字；未选中只有一层很淡的描边。
    // 颜色都取自现有主题（用户 2026-11-06 口径：主题色不要动，只改控件样式）。
    val border = if (selected) {
        colors.accent.copy(alpha = 0.75f)
    } else {
        colors.line2.copy(alpha = 0.55f + 0.35f * hover)
    }
    val text = when {
        selected -> colors.accent
        else -> colors.ink2
    }
    val fill = if (selected) {
        colors.accent.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }

    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .hapticLift(hover = hover * if (selected) 0f else 1f, press = press, lift = 1)
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
