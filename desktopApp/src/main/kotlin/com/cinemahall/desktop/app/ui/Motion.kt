package com.cinemahall.desktop.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 动效令牌（2026-11-08 用户口径：Windows 端要「苹果那种高级感」）。
 *
 * ── 为什么不能直接用默认的 `tween(300)` / `ease-in-out` ──────────────────────
 * 默认缓动是**对称**的：起步与收尾一样软，看起来像「淡入淡出」。苹果那套的手感是
 * **不对称**的 —— 起步很快、尾巴很长、最后一点点才停住（他们自己的曲线是
 * `cubic-bezier(0.32, 0.72, 0, 1)`）。同一段 220ms，用这条曲线看起来就是
 * 「东西有重量」，换成默认曲线立刻变成「网页在切换」。
 *
 * 所以这里把曲线与时长收成一处：所有交互（悬浮、按下、切页、进场）都从这里取，
 * 不允许各处自己写 `tween(数字)` —— 否则同一个界面上会同时存在三种「弹簧」。
 *
 * ── 只动 transform / opacity ────────────────────────────────────────────────
 * 缩放走 `graphicsLayer`（GPU 合成），不动 width/height/padding：
 * 布局每帧重算会让侧栏文字在动画中途重新折行。
 */
object Motion {

    /** 主曲线：起步陡、收尾长（苹果的 `cubic-bezier(0.32, 0.72, 0, 1)`）。 */
    val emphasized = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 退场曲线：比进场更快收干净，避免「关掉的东西还在飘」。 */
    val exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** 微交互（悬浮色、按下缩放）。短到看不出「动画」，只觉得跟手。 */
    const val MICRO = 120

    /** 标准（选中态、菜单展开、状态色）。 */
    const val STANDARD = 220

    /** 进场（切页、卡片、弹窗）。 */
    const val ENTRANCE = 380

    /** 交错的每一档延迟（卡片依次浮起）。 */
    const val STAGGER = 45

    fun <T> curve(durationMs: Int = STANDARD, easing: Easing = emphasized) =
        tween<T>(durationMillis = durationMs, easing = easing)
}

/**
 * 悬浮进度 0..1（已平滑）。
 *
 * 用 `collectIsHoveredAsState` 而不是自己收 `PointerEventType.Enter/Exit`：
 * 后者在子控件交界处会连续抖动，颜色会闪。
 */
@Composable
fun hoverProgress(source: InteractionSource): Float {
    val hovered by source.collectIsHoveredAsState()
    val value by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = Motion.curve(Motion.MICRO),
        label = "hover",
    )
    return value
}

/** 按下进度 0..1（已平滑）。 */
@Composable
fun pressProgress(source: InteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = Motion.curve(Motion.MICRO),
        label = "press",
    )
    return value
}

/**
 * 「有重量」的可点区域：悬浮微微抬起，按下收一点点。
 *
 * 位移用 dp 换算成 px 后交给 `translationY` —— 只影响绘制，不触发重新布局。
 */
@Composable
fun Modifier.hapticLift(hover: Float, press: Float, lift: Int = 2, shrink: Float = 0.985f): Modifier =
    this.graphicsLayer {
        val scale = 1f - (1f - shrink) * press
        scaleX = scale
        scaleY = scale
        translationY = -lift.dp.toPx() * hover
    }
