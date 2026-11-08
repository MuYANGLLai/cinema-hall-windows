package com.cinemahall.desktop.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 桌面端色板 —— **逐字取自 Android 端** `feature/cinema/.../CinemaTheme.kt`。
 *
 * ── 为什么要把颜色搬一份，而不是“桌面随便挑个深色主题” ──────────────────────
 * 同一部片在两端播放时，用户对“这是同一个产品”的判断几乎只来自色板与控件形状。
 * Phase 0 的 Demo 1 用的是 M3 默认紫，截图交出去的第一反应是“这不是影厅”。
 *
 * ── 为什么只搬语义色，不搬整套 `CinemaPalette` ──────────────────────────────
 * Android 那份 22 个槽里有一半是给“视频上叠加控件”准备的，而桌面端的控制条在
 * **画面下方**（Phase 0 §2.1 的结论：叠层不可见），用不到 `onVideo*` 那一族。
 * 这里只留页面真的会用的：背景/容器/两级文字/分隔线/强调/成功/失败。
 */
@Immutable
data class CinemaColors(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val line2: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val danger: Color,
    /** 画面区底色：`#070708`（设计基线禁纯黑）。 */
    val videoBackdrop: Color,
)

private val NightColors = CinemaColors(
    background = Color(0xFF0A0A0B),
    surface = Color(0xFF141416),
    surface2 = Color(0xFF191A1C),
    ink = Color(0xFFECEBE7),
    ink2 = Color(0xFFA5A29B),
    ink3 = Color(0xFF6E6B65),
    line = Color(0x11FFFFFF),
    line2 = Color(0x21FFFFFF),
    accent = Color(0xFFD3A24A),
    onAccent = Color(0xFF1B1409),
    success = Color(0xFF7FAE90),
    danger = Color(0xFFCD7C6B),
    videoBackdrop = Color(0xFF070708),
)

private val DayColors = CinemaColors(
    background = Color(0xFFF2EFE9),
    surface = Color(0xFFFFFEFB),
    surface2 = Color(0xFFF5F2EC),
    ink = Color(0xFF1D1A16),
    ink2 = Color(0xFF55504A),
    ink3 = Color(0xFF6E685D),
    line = Color(0x171E1A14),
    line2 = Color(0x2B1E1A14),
    accent = Color(0xFF855A11),
    onAccent = Color(0xFFFFFAF0),
    success = Color(0xFF3F7355),
    danger = Color(0xFFA2442F),
    videoBackdrop = Color(0xFF070708),
)

val LocalCinemaColors = staticCompositionLocalOf { NightColors }

/**
 * 给**窗口层**（AWT）取一套配色。
 *
 * 两个地方要用，而它们都读不到 `LocalCinemaColors`：
 *  ① 窗口自己的底色 —— 缩放时 Compose 还没画出这一帧的那条边，露出的是 AWT 窗口底色。
 *     不设的话那是 **AWT 的默认白**，快速拖边就是一条白边；
 *  ② 全屏过渡遮罩（`TransitionVeil`）要跟当前主题同色。
 */
fun cinemaColorsFor(day: Boolean): CinemaColors = if (day) DayColors else NightColors

private fun scheme(colors: CinemaColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.surface2,
        onPrimaryContainer = colors.ink,
        secondary = colors.ink2,
        onSecondary = colors.background,
        tertiary = colors.success,
        onTertiary = colors.background,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.surface,
        onSurface = colors.ink,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.ink2,
        outline = colors.line2,
        outlineVariant = colors.line,
        error = colors.danger,
        onError = colors.background,
        errorContainer = colors.surface2,
        onErrorContainer = colors.danger,
        surfaceContainer = colors.surface2,
        surfaceContainerHigh = colors.surface2,
        surfaceContainerHighest = colors.surface2,
        surfaceContainerLow = colors.surface,
        surfaceContainerLowest = colors.surface,
    )
}

@Composable
fun CinemaTheme(day: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (day) DayColors else NightColors
    MaterialTheme(colorScheme = scheme(colors, dark = !day)) {
        CompositionLocalProvider(LocalCinemaColors provides colors, content = content)
    }
}

/**
 * 把**已经算好的**一套主题装进另一个组合。
 *
 * 悬浮控制栏跑在独立窗口里（`PlaybackOverlay`），那是**另一个组合**：
 * [CinemaTheme] 只在主窗口里跑过一次，浮层读不到那次的 `LocalCinemaColors`，
 * 也读不到 `MaterialTheme.colorScheme`（会掉回 `staticCompositionLocalOf` 的默认值
 * 与 M3 的默认浅色板 —— 表现为「浮层是白底黑字」）。而按 `day` 重算一遍又得知道
 * 当前主题是哪一个，所以这里直接收下主组合里现成的这两个值。
 */
@Composable
fun CinemaThemeOn(
    scheme: ColorScheme,
    colors: CinemaColors,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalCinemaColors provides colors, content = content)
    }
}
