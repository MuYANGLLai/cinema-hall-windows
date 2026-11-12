package com.cinemahall.desktop.app

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
    // ── 2026-11-12 提亮（UI 审查 F3）──────────────────────────────────────────
    // 原值 `#6E6B65` 对卡片底 `#141416` 只有 **3.46:1**、对侧栏 `#191A1C` **3.28:1**，
    // 而它承载的是真信息（路径、设备名、信令状态、说明文字），字号是 M3 默认的
    // labelSmall/labelMedium（11–12sp，属「小文本」）—— AA 要求 4.5:1。
    // 新值 `#8A8780`：卡片底 5.19:1 / 侧栏 4.90:1 / 页底 5.55:1，三处都过线且仍明显
    // 弱于 `ink2`（7.22:1），层次没被压平。
    ink3 = Color(0xFF8A8780),
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
        // ── 2026-11-12：把剩下几个角色也钉住（UI 审查 F5）────────────────────
        // `base.copy(...)` 只覆盖写出来的字段，没写的仍然是 M3 的深色默认值 —— 那些
        // 默认值是**紫色调**的。实测证据：设置页音量滑条的未激活段取样 `#4A4458`，
        // 正是 M3 默认的 `secondaryContainer`，而本主题里没有任何紫色。
        // 症状不只是「配色不搭」：那段对卡片底只有 **1.98:1**，低于 WCAG 1.4.11 对
        // UI 组件边界要求的 3:1，「音量还能加到多少」看不清。
        // 所以这里把可能被组件读到的角色全部指向本主题的语义色，杜绝漏紫。
        secondaryContainer = colors.surface2,
        onSecondaryContainer = colors.ink2,
        tertiaryContainer = colors.surface2,
        onTertiaryContainer = colors.ink,
        inversePrimary = colors.accent,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.background,
        surfaceTint = colors.accent,
        scrim = Color(0xFF000000),
    )
}

/**
 * 滑条的配色（全应用唯一一处）。
 *
 * ── 为什么必须显式给（UI 审查 F5）─────────────────────────────────────────────
 * `Slider` 不传 `colors` 时读的是 `SliderDefaults.colors()`，其中未激活轨道取
 * `secondaryContainer`。上面 `scheme()` 现在会把它钉到 `surface2`，但那样整条滑条
 * 又会「看不出哪一段还没走」—— 所以轨道单独给一个可辨识的灰：
 * `ink3` 的 72% 叠在卡片底上是 `#66 64 60` 一档，对 `#141416` 约 **3.2:1**
 * （WCAG 1.4.11 对组件边界要求 ≥ 3:1），又明显弱于主色，不抢「已选量」。
 */
@Composable
fun cinemaSliderColors(): SliderColors {
    val colors = LocalCinemaColors.current
    return SliderDefaults.colors(
        thumbColor = colors.accent,
        activeTrackColor = colors.accent,
        inactiveTrackColor = colors.ink3.copy(alpha = 0.72f),
    )
}

/**
 * 滚动条样式：默认是 Material 的浅灰圆角条，在深色底上"像网页"。
 * 这里改成主题灰（`ink3`），静止时半透明、悬浮变实，与其它微交互同一套手感。
 */
private fun scrollbarStyle(colors: CinemaColors) = ScrollbarStyle(
    minimalHeight = 32.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 160,
    unhoverColor = colors.ink3.copy(alpha = 0.30f),
    hoverColor = colors.ink3,
)

@Composable
fun CinemaTheme(day: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (day) DayColors else NightColors
    MaterialTheme(colorScheme = scheme(colors, dark = !day)) {
        CompositionLocalProvider(
            LocalCinemaColors provides colors,
            LocalScrollbarStyle provides scrollbarStyle(colors),
            content = content,
        )
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
        CompositionLocalProvider(
            LocalCinemaColors provides colors,
            LocalScrollbarStyle provides scrollbarStyle(colors),
            content = content,
        )
    }
}
