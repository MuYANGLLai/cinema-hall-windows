package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.CinemaColors

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
