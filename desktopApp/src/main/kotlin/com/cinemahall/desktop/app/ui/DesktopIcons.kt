package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.CinemaColors
import com.cinemahall.desktop.app.LocalCinemaColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * 播放控制条上的图标。
 *
 * ── 为什么自己画，而不是引 `material-icons-extended` ──────────────────────────
 * 那个包会给 app-image 再加十几兆（jpackage 的 runtime 已经 170 MB），而这里一共
 * 只用到 8 个形状。`Canvas` 画出来的路径是矢量的，缩放、主题色都跟着走，代价是
 * 每个形状十来行 —— 比多带一个依赖划算。
 *
 * 形状与安卓端 `PlaybackIcons.kt` 对齐（播放三角 / 暂停双竖条 / 双三角快退快进 /
 * 喇叭与斜线 / 全屏四角括号），这样两端截图放在一起看是同一套语言。
 */
enum class PlayerIcon {
    Rewind,
    Play,
    Pause,
    Forward,
    VolumeOn,
    VolumeOff,
    Fullscreen,
    FullscreenExit,
}

@Composable
fun PlayerIconButton(
    icon: PlayerIcon,
    contentDescription: String,
    colors: CinemaColors,
    enabled: Boolean = true,
    /** 主按钮（播放/暂停）给一圈底色，视觉上压在其它按钮之上 —— 与安卓端同一条取向。 */
    primary: Boolean = false,
    // `onClick` 必须是最后一个参数：调用点用的是尾随 lambda 写法，
    // Kotlin 只把它绑给**最后一个**参数 —— 放在中间会被绑到 `primary` 上。
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> colors.ink3.copy(alpha = 0.45f)
        primary -> colors.onAccent
        else -> colors.ink2
    }
    Box(
        Modifier
            .size(if (primary) 40.dp else 34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (primary && enabled) colors.accent else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = contentDescription) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(if (primary) 18.dp else 17.dp)) { drawPlayerIcon(icon, tint) }
    }
}

private fun DrawScope.drawPlayerIcon(icon: PlayerIcon, tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = (w * 0.09f).coerceAtLeast(1.2f))
    when (icon) {
        PlayerIcon.Play -> {
            val path = Path().apply {
                moveTo(w * 0.26f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.5f)
                lineTo(w * 0.26f, h * 0.86f)
                close()
            }
            drawPath(path, tint)
        }

        PlayerIcon.Pause -> {
            drawRect(tint, topLeft = Offset(w * 0.26f, h * 0.16f), size = Size(w * 0.16f, h * 0.68f))
            drawRect(tint, topLeft = Offset(w * 0.58f, h * 0.16f), size = Size(w * 0.16f, h * 0.68f))
        }

        PlayerIcon.Rewind, PlayerIcon.Forward -> {
            // 双三角（与安卓端的「后退/前进 10 秒」同一形状）。Rewind 朝左、Forward 朝右 ——
            // 上面那条 `if` 里画的基准是「朝右」，所以**朝左的那一侧才要镜像**。
            val mirror = icon == PlayerIcon.Rewind
            listOf(0.06f, 0.46f).forEach { start ->
                val path = Path().apply {
                    if (mirror) {
                        moveTo(w * (start + 0.4f), h * 0.18f)
                        lineTo(w * (start + 0.02f), h * 0.5f)
                        lineTo(w * (start + 0.4f), h * 0.82f)
                    } else {
                        moveTo(w * (start + 0.42f), h * 0.18f)
                        lineTo(w * (start + 0.84f), h * 0.5f)
                        lineTo(w * (start + 0.42f), h * 0.82f)
                    }
                    close()
                }
                drawPath(path, tint)
            }
        }

        PlayerIcon.VolumeOn -> {
            val body = Path().apply {
                moveTo(w * 0.1f, h * 0.36f)
                lineTo(w * 0.3f, h * 0.36f)
                lineTo(w * 0.5f, h * 0.18f)
                lineTo(w * 0.5f, h * 0.82f)
                lineTo(w * 0.3f, h * 0.64f)
                lineTo(w * 0.1f, h * 0.64f)
                close()
            }
            drawPath(body, tint)
            // 两道声波弧线
            drawArc(
                tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(w * 0.42f, h * 0.22f),
                size = Size(w * 0.34f, h * 0.56f),
                style = stroke,
            )
            drawArc(
                tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(w * 0.36f, h * 0.06f),
                size = Size(w * 0.56f, h * 0.88f),
                style = stroke,
            )
        }

        PlayerIcon.VolumeOff -> {
            val body = Path().apply {
                moveTo(w * 0.1f, h * 0.36f)
                lineTo(w * 0.3f, h * 0.36f)
                lineTo(w * 0.5f, h * 0.18f)
                lineTo(w * 0.5f, h * 0.82f)
                lineTo(w * 0.3f, h * 0.64f)
                lineTo(w * 0.1f, h * 0.64f)
                close()
            }
            drawPath(body, tint)
            drawLine(tint, Offset(w * 0.6f, h * 0.3f), Offset(w * 0.92f, h * 0.7f), stroke.width)
            drawLine(tint, Offset(w * 0.92f, h * 0.3f), Offset(w * 0.6f, h * 0.7f), stroke.width)
        }

        PlayerIcon.Fullscreen, PlayerIcon.FullscreenExit -> {
            // 四角括号：进入时朝外，退出时朝内。
            val outward = icon == PlayerIcon.Fullscreen
            val inset = if (outward) 0.1f else 0.3f
            val corner = if (outward) 0.32f else 0.14f
            fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(tint, Offset(w * x, h * y), Offset(w * (x + dx), h * y), stroke.width)
                drawLine(tint, Offset(w * x, h * y), Offset(w * x, h * (y + dy)), stroke.width)
            }
            bracket(inset, inset, corner, corner)
            bracket(1f - inset, inset, -corner, corner)
            bracket(inset, 1f - inset, corner, -corner)
            bracket(1f - inset, 1f - inset, -corner, -corner)
        }
    }
}

// ── 应用内通用图标（2026-11-07 用户口径：**全部**改成简约、自绘、纯色）──────────────
//
// 为什么不用 emoji / 字体字形：
//  * emoji（🎤 🎧 🗑 …）在不同机器上是不同的彩色位图，大小、基线、留白都不受控；
//    混在深色界面里既不是同一个色系，也没法和文字对齐。
//  * 字体字形（⚙ ▤ ⋯ ＋ ✎）在缺字体的机器上会掉成方块，而且笔粗细跟着字体走，
//    同一个界面里会出现两三种「线宽」。
//
// 所以统一成这里的一套：**24×24 的虚拟画布**上按比例描线，颜色只有一个 `tint`，
// 线宽固定为边长的 0.085（≈ 18dp 时 1.5px），圆头圆角。缩放、主题色都跟着走。
enum class AppIcon {
    /** 首页：房子。 */
    Home,

    /** 影厅：屏幕 + 播放三角。 */
    Cinema,

    /** 影片库：2×2 的格子。 */
    Library,

    /** 设置：齿轮。 */
    Settings,

    /** 诊断：心跳线。 */
    Diagnostics,

    /** 复制。 */
    Copy,

    /** 麦克风。 */
    Mic,

    /** 耳机（接收音量）。 */
    Headphones,

    /** 加号。 */
    Plus,

    /** 铅笔（改名）。 */
    Pencil,

    /** 垃圾桶（删除）。 */
    Trash,

    /** 三个点（更多）。 */
    More,

    /** 对勾。 */
    Check,

    /** 叉（关闭）。 */
    Close,
}

/**
 * 画一颗图标。[strokeScale] 只调线宽：很小的尺寸（12–13dp）下调粗一点才不会糊。
 */
fun DrawScope.drawAppIcon(icon: AppIcon, tint: Color, strokeScale: Float = 1f) {
    // 统一到 24 格的坐标系：s = 一格有多少像素，中心点 (12,12)。
    val s = size.minDimension / 24f
    val stroke = (s * 2f * strokeScale).coerceAtLeast(1.1f)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, p(x1, y1), p(x2, y2), stroke, StrokeCap.Round)

    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1.5f) =
        drawRoundRect(
            color = tint,
            topLeft = p(x, y),
            size = Size(w * s, h * s),
            cornerRadius = CornerRadius(r * s),
            style = Stroke(width = stroke, join = StrokeJoin.Round),
        )

    fun circle(cx: Float, cy: Float, radius: Float, filled: Boolean = false) =
        drawCircle(
            color = tint,
            radius = radius * s,
            center = p(cx, cy),
            style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = stroke),
        )

    when (icon) {
        AppIcon.Home -> {
            line(3.6f, 11.2f, 12f, 4.2f)
            line(12f, 4.2f, 20.4f, 11.2f)
            line(6.4f, 9.4f, 6.4f, 19.6f)
            line(17.6f, 9.4f, 17.6f, 19.6f)
            line(6.4f, 19.6f, 17.6f, 19.6f)
        }

        AppIcon.Cinema -> {
            rect(2.8f, 4.6f, 18.4f, 14.8f, r = 2.4f)
            // 播放三角（实心）：影厅 = 看片子的地方。
            val tri = Path().apply {
                moveTo(10.2f * s, 8.6f * s)
                lineTo(15.6f * s, 12f * s)
                lineTo(10.2f * s, 15.4f * s)
                close()
            }
            drawPath(tri, tint)
        }

        AppIcon.Library -> {
            // 一框三格（电影格）：16dp 上四宫格会被压成「88」，而「外框 + 两条竖线」
            // 缩到 13dp 还是三格，与「影厅」那块「框 + 播放三角」也分得开。
            rect(3f, 4.4f, 18f, 15.2f, r = 2.2f)
            line(8.6f, 4.4f, 8.6f, 19.6f)
            line(15.4f, 4.4f, 15.4f, 19.6f)
        }

        AppIcon.Settings -> {
            // 两条横线 + 两个旋钮（「滑杆」而不是齿轮）。
            //
            // 为什么不是齿轮：16–18dp 上「圆 + 一圈短齿」会糊成一颗雪花/星号，
            // 而滑杆只要两条线和两个点，缩到 13dp 也还认得出是「设置」。
            line(3.4f, 8.2f, 20.6f, 8.2f)
            line(3.4f, 15.8f, 20.6f, 15.8f)
            circle(9.2f, 8.2f, 2.5f, filled = true)
            circle(15.2f, 15.8f, 2.5f, filled = true)
        }

        AppIcon.Diagnostics -> {
            line(2.8f, 13f, 7.2f, 13f)
            line(7.2f, 13f, 9.6f, 7.4f)
            line(9.6f, 7.4f, 12.6f, 17.2f)
            line(12.6f, 17.2f, 15f, 10.6f)
            line(15f, 10.6f, 16.8f, 13f)
            line(16.8f, 13f, 21.2f, 13f)
        }

        AppIcon.Copy -> {
            rect(3.4f, 3.4f, 11.6f, 11.6f, r = 2.4f)
            rect(9f, 9f, 11.6f, 11.6f, r = 2.4f)
        }

        AppIcon.Mic -> {
            rect(8.8f, 2.6f, 6.4f, 11.2f, r = 3.2f)
            drawArc(
                color = tint,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(6.6f, 9.4f),
                size = Size(10.8f * s, 10.8f * s),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            line(12f, 20.2f, 12f, 21.8f)
            line(8.8f, 21.8f, 15.2f, 21.8f)
        }

        AppIcon.Headphones -> {
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(3.4f, 3.4f),
                size = Size(17.2f * s, 17.2f * s),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            rect(3.4f, 12.4f, 4.4f, 8f, r = 2.2f)
            rect(16.2f, 12.4f, 4.4f, 8f, r = 2.2f)
        }

        AppIcon.Plus -> {
            line(12f, 4.6f, 12f, 19.4f)
            line(4.6f, 12f, 19.4f, 12f)
        }

        AppIcon.Pencil -> {
            val body = Path().apply {
                moveTo(4.2f * s, 19.8f * s)
                lineTo(5.2f * s, 15.4f * s)
                lineTo(15.6f * s, 5f * s)
                lineTo(19f * s, 8.4f * s)
                lineTo(8.6f * s, 18.8f * s)
                close()
            }
            drawPath(body, tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            line(5.2f, 15.4f, 8.6f, 18.8f)
            line(14.2f, 6.4f, 17.6f, 9.8f)
        }

        AppIcon.Trash -> {
            line(3.4f, 6.4f, 20.6f, 6.4f)
            line(9.2f, 6.4f, 9.8f, 3.8f)
            line(9.8f, 3.8f, 14.2f, 3.8f)
            line(14.2f, 3.8f, 14.8f, 6.4f)
            val body = Path().apply {
                moveTo(5.8f * s, 6.4f * s)
                lineTo(7.2f * s, 20.2f * s)
                lineTo(16.8f * s, 20.2f * s)
                lineTo(18.2f * s, 6.4f * s)
            }
            drawPath(body, tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            line(10.2f, 9.8f, 10.2f, 16.8f)
            line(13.8f, 9.8f, 13.8f, 16.8f)
        }

        AppIcon.More -> {
            circle(5.6f, 12f, 1.7f, filled = true)
            circle(12f, 12f, 1.7f, filled = true)
            circle(18.4f, 12f, 1.7f, filled = true)
        }

        AppIcon.Check -> {
            line(4.8f, 12.6f, 9.8f, 17.4f)
            line(9.8f, 17.4f, 19.2f, 6.8f)
        }

        AppIcon.Close -> {
            line(6.4f, 6.4f, 17.6f, 17.6f)
            line(17.6f, 6.4f, 6.4f, 17.6f)
        }
    }
}

/** 一颗图标。 */
@Composable
fun AppIconView(
    icon: AppIcon,
    size: Dp = 18.dp,
    tint: Color = LocalCinemaColors.current.ink2,
    strokeScale: Float = 1f,
) {
    Canvas(Modifier.size(size)) { drawAppIcon(icon, tint, strokeScale) }
}

/** 可点击的图标（影片库卡头那三颗、`⋯` 之类）。 */
@Composable
fun AppIconButton(
    icon: AppIcon,
    label: String,
    tint: Color = LocalCinemaColors.current.accent,
    size: Dp = 16.dp,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 3.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        AppIconView(icon, size, if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}
