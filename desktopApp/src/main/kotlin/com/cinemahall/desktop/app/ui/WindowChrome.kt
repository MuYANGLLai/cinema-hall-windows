package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.LocalCinemaColors
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlin.math.roundToInt

/**
 * 无边框窗口的外壳动作（2026-11-07）。
 *
 * ── 为什么不再用 `WindowPlacement.Fullscreen` / `GraphicsDevice.fullScreenWindow` ──
 * 两者都要在进入/退出时**切换窗口的 native 样式**（`WS_POPUP` ↔ `WS_OVERLAPPEDWINDOW`），
 * Windows 的 DWM 会因此重绘整块窗口 —— 用户看到的就是「退出全屏时屏幕黑一下」。
 * 进入时恰好不明显，退出时几乎每次都能看到。
 *
 * 现在的方式：窗口**从创建起就是无边框**（`undecorated = true`），全屏只是把窗口
 * bounds 设成屏幕尺寸、还原只是设回上次的 bounds。样式一次都不改，所以两头都不黑。
 *
 * ── 代价 ────────────────────────────────────────────────────────────────────
 * 无边框窗口没有系统标题栏，也没有系统的「拖边改大小」。所以这两件事都得自己做：
 * 标题栏见 [DesktopTitleBar]，八向缩放见 [ResizeHandles]。
 */
class WindowChrome(
    private val window: Window,
    private val onClose: () -> Unit,
) {
    private var normalBounds: Rectangle? = null

    /** 是否处于「最大化」状态（双击标题栏切换）。 */
    var maximized by mutableStateOf(false)
        private set

    fun minimize() {
        (window as? java.awt.Frame)?.extendedState = java.awt.Frame.ICONIFIED
    }

    /**
     * 最大化 / 还原。
     *
     * 用 `bounds` 而不是 `extendedState = MAXIMIZED_BOTH`：无边框窗口的「最大化」
     * 会把任务栏一起盖住，而用户对「放大窗口」的预期是留出任务栏。
     * 边界取 `maximumWindowBounds`（系统已经扣掉任务栏与多显示器留白）。
     */
    fun toggleMaximize() {
        if (maximized) {
            normalBounds?.let { window.bounds = it }
            maximized = false
        } else {
            normalBounds = window.bounds
            maximized = true
            window.bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        }
    }

    fun closeFromTitleBar() = onClose()

    /**
     * 把窗口挪到「按下时窗口位置 + 指针屏幕位移」。
     *
     * ── 为什么不能用手势的 drag 增量（2026-11-07 用户报「拖动一抽一抽」）───────
     * Compose 的 `drag` 是「本次指针相对**组件**的位移」。而我们每挪一次窗口，
     * 组件本身也跟着挪了 —— 下一帧算出来的位移里又混进了上一帧的窗口移动，
     * 于是窗口开始追着自己的尾巴跑：表现为一顿一顿、不跟手。
     *
     * 正解是只看**屏幕坐标**：按下那一刻记下指针位置与窗口位置，之后每个事件都
     * 用「指针现在在屏幕哪儿」直接算出窗口该在哪儿。窗口怎么动都不影响这个等式。
     */
    fun dragTo(startPointer: Point?, startWindow: Point?, currentPointer: Point?) {
        if (maximized) return
        if (startPointer == null || startWindow == null || currentPointer == null) return
        window.location = Point(
            startWindow.x + (currentPointer.x - startPointer.x),
            startWindow.y + (currentPointer.y - startPointer.y),
        )
    }

    fun pointerOnScreen(): Point? = java.awt.MouseInfo.getPointerInfo()?.location

    fun windowLocation(): Point = window.location

    /** 拖动结束：退出「跟手」状态。 */
    fun endDrag() {
        // 没有需要清理的状态（起点是每次按下时现记的），保留这个口子是为了
        // 以后加「贴边吸附」时有地方挂。
    }

    /** 窗口在屏幕上的位置（浮层要跟着它走）。 */
    fun screenBounds(): Rectangle = window.bounds

    /**
     * 拖边/拖角改大小。
     *
     * `left/right` 是 x 方向的边，`top/bottom` 是 y 方向的边；角位两边都给 true。
     * 下限 [MIN_WIDTH]×[MIN_HEIGHT]：再小就会把侧栏压没，用户会以为界面坏了。
     *
     * ── 和拖动同一个坑 ────────────────────────────────────────────────────────
     * 这里**必须**按「按下时的 bounds + 指针屏幕位移」算，不能用 `drag.x/drag.y`
     * 这类相对热区的增量：热区钉在窗口边上，窗口一变大热区自己就挪了，下一帧的
     * 增量里混进了这次挪动 —— 表现为缩放来回抖、拉不到想要的大小。
     */
    fun beginResize() {
        if (maximized) return
        resizeStartPointer = pointerOnScreen()
        resizeStartBounds = window.bounds
    }

    fun resizeTo(
        currentPointer: Point?,
        left: Boolean = false,
        right: Boolean = false,
        top: Boolean = false,
        bottom: Boolean = false,
    ) {
        if (maximized) return
        val start = resizeStartPointer ?: return
        val base = resizeStartBounds ?: return
        val now = currentPointer ?: return
        val dx = now.x - start.x
        val dy = now.y - start.y
        var x = base.x
        var y = base.y
        var w = base.width
        var h = base.height
        if (left) {
            x = base.x + dx
            w = base.width - dx
        }
        if (right) w = base.width + dx
        if (top) {
            y = base.y + dy
            h = base.height - dy
        }
        if (bottom) h = base.height + dy
        if (w < MIN_WIDTH) {
            if (left) x = base.x + base.width - MIN_WIDTH
            w = MIN_WIDTH
        }
        if (h < MIN_HEIGHT) {
            if (top) y = base.y + base.height - MIN_HEIGHT
            h = MIN_HEIGHT
        }
        window.bounds = Rectangle(x, y, w, h)
    }

    fun endResize() {
        resizeStartPointer = null
        resizeStartBounds = null
    }

    private var resizeStartPointer: Point? = null
    private var resizeStartBounds: Rectangle? = null

    companion object {
        const val MIN_WIDTH = 980
        const val MIN_HEIGHT = 620
    }
}

/** 自绘标题栏：整条是拖动区，右侧三个按钮（Windows 系统同款样式）。全屏时不画。 */
@Composable
fun DesktopTitleBar(chrome: WindowChrome, title: String) {
    val colors = LocalCinemaColors.current
    // 拖动用**屏幕坐标**绝对定位（见 `WindowChrome.dragTo`）：用拖拽增量会让窗口
    // 追着自己跑，一抽一抽。
    var startPointer by remember { mutableStateOf<Point?>(null) }
    var startWindow by remember { mutableStateOf<Point?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(TITLE_BAR_HEIGHT)
            .background(colors.surface2)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))
            .pointerInput(chrome) {
                detectDragGestures(
                    onDragStart = {
                        startPointer = chrome.pointerOnScreen()
                        startWindow = chrome.windowLocation()
                    },
                    onDragEnd = { chrome.endDrag() },
                    onDragCancel = { chrome.endDrag() },
                ) { change, _ ->
                    change.consume()
                    chrome.dragTo(startPointer, startWindow, chrome.pointerOnScreen())
                }
            }
            .pointerInput(chrome) {
                detectTapGestures(onDoubleTap = { chrome.toggleMaximize() })
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = colors.ink3)
        Spacer(Modifier.weight(1f))
        // 图标与尺寸对齐 Windows 11：46×32 的方块，关闭键 hover 是 #C42B1C。
        WindowButton(WindowGlyph.Minimize, "最小化") { chrome.minimize() }
        WindowButton(
            if (chrome.maximized) WindowGlyph.Restore else WindowGlyph.Maximize,
            "最大化 / 还原",
        ) { chrome.toggleMaximize() }
        WindowButton(WindowGlyph.Close, "关闭", danger = true) { chrome.closeFromTitleBar() }
    }
}

private enum class WindowGlyph { Minimize, Maximize, Restore, Close }

/**
 * Windows 系统同款按钮。
 *
 * 图标**自绘**而不是用字体字形：Segoe Fluent Icons 在部分机器上没装，
 * 那时会显示成方块；而这几条线用 `Canvas` 画出来在任何机器上都一样。
 */
@Composable
private fun WindowButton(glyph: WindowGlyph, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalCinemaColors.current
    var hovered by remember { mutableStateOf(false) }
    val hoverTint = when {
        !hovered -> Color.Transparent
        danger -> Color(0xFFC42B1C)
        else -> colors.ink.copy(alpha = 0.10f)
    }
    val glyphTint = if (hovered && danger) Color.White else colors.ink2

    Box(
        Modifier
            .fillMaxHeight()
            .width(46.dp)
            .background(hoverTint)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .pointerInput(label) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        hovered = event.type == PointerEventType.Enter
                        if (event.type == PointerEventType.Press) {
                            onClick()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1f
            when (glyph) {
                WindowGlyph.Minimize -> drawLine(
                    color = glyphTint,
                    start = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.5f),
                    end = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.5f),
                    strokeWidth = stroke,
                )

                WindowGlyph.Maximize -> drawRect(
                    color = glyphTint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.1f),
                    size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.8f),
                    style = Stroke(width = stroke),
                )

                WindowGlyph.Restore -> {
                    drawRect(
                        color = glyphTint,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.1f),
                        size = androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.62f),
                        style = Stroke(width = stroke),
                    )
                    drawRect(
                        color = glyphTint,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.28f),
                        size = androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.62f),
                        style = Stroke(width = stroke),
                    )
                }

                WindowGlyph.Close -> {
                    drawLine(
                        color = glyphTint,
                        start = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.1f),
                        end = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.9f),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = glyphTint,
                        start = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.1f),
                        end = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.9f),
                        strokeWidth = stroke,
                    )
                }
            }
        }
    }
}

private val TITLE_BAR_HEIGHT = 32.dp

/**
 * 八向缩放热区：四边 + 四角。
 *
 * 叠在内容之上（不是给内容加 padding）：窗口边缘本来就该由内容占满，
 * 热区只负责「接收拖动」，不占位。先放边、后放角 —— 后画的在上，角优先命中。
 */
@Composable
fun BoxScope.ResizeHandles(chrome: WindowChrome, enabled: Boolean) {
    if (!enabled) return
    val edge = 5.dp
    val corner = 14.dp

    ResizeHandle(chrome, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge), Cursor.N_RESIZE_CURSOR, top = true)
    ResizeHandle(chrome, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge), Cursor.S_RESIZE_CURSOR, bottom = true)
    ResizeHandle(chrome, Modifier.align(Alignment.CenterStart).width(edge).fillMaxHeight(), Cursor.W_RESIZE_CURSOR, left = true)
    ResizeHandle(chrome, Modifier.align(Alignment.CenterEnd).width(edge).fillMaxHeight(), Cursor.E_RESIZE_CURSOR, right = true)

    ResizeHandle(chrome, Modifier.align(Alignment.TopStart).size(corner), Cursor.NW_RESIZE_CURSOR, left = true, top = true)
    ResizeHandle(chrome, Modifier.align(Alignment.TopEnd).size(corner), Cursor.NE_RESIZE_CURSOR, right = true, top = true)
    ResizeHandle(chrome, Modifier.align(Alignment.BottomStart).size(corner), Cursor.SW_RESIZE_CURSOR, left = true, bottom = true)
    ResizeHandle(chrome, Modifier.align(Alignment.BottomEnd).size(corner), Cursor.SE_RESIZE_CURSOR, right = true, bottom = true)
}

@Composable
private fun ResizeHandle(
    chrome: WindowChrome,
    modifier: Modifier,
    cursor: Int,
    left: Boolean = false,
    right: Boolean = false,
    top: Boolean = false,
    bottom: Boolean = false,
) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(chrome, left, right, top, bottom) {
                detectDragGestures(
                    onDragStart = { chrome.beginResize() },
                    onDragEnd = { chrome.endResize() },
                    onDragCancel = { chrome.endResize() },
                ) { change, _ ->
                    change.consume()
                    chrome.resizeTo(chrome.pointerOnScreen(), left, right, top, bottom)
                }
            },
    )
}
