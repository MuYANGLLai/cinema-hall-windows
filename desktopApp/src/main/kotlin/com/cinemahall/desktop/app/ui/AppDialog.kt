package com.cinemahall.desktop.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.cinemahall.desktop.app.LocalCinemaColors

/**
 * 应用里**所有**独立窗口弹层都走这里（2026-11-07 用户口径：弹窗一律无边框）。
 *
 * ── 外形只有三样东西，内容不要再自己加 ──────────────────────────────────────
 * ① 圆角由 DWM 裁（`DWMWCP_ROUND`，本机 ≈8px @100%，见 `NativeFrame.applyRoundCorners`）；
 * ② 边框由 DWM 的阴影给 —— **不要**再画 1dp 描边（0.1.23 用户口径「去掉这条描边」）；
 * ③ 底色铺**直角矩形**，不要 `clip(圆角)`（见下面 `Column` 上的注释）。
 *
 * ── 为什么不直接用 `DialogWindow` 的默认样式 ────────────────────────────────
 * 默认是系统标题栏 + 系统边框，与主窗口（无边框 + 自绘标题栏）放在一起就是两套外形：
 * 深色自绘窗口里弹出一个浅灰系统框，用户会以为弹错东西了。
 *
 * 所以这里统一成：`undecorated = true` + 自绘的一条 32dp 标题栏（可拖、右侧一颗关闭）
 * + 八向拖边缩放（复用主窗口的 `WindowChrome` / `ResizeHandles`）。
 *
 * ── 用法 ────────────────────────────────────────────────────────────────────
 * ```kotlin
 * AppDialogWindow(title = "改名", onClose = { closing = true }) {
 *     Column(Modifier.fillMaxSize().padding(16.dp)) { … }
 * }
 * ```
 * 内容自己负责内边距与滚动：弹窗只提供「外壳 + 主题」。
 *
 * ── 以后加弹窗的规矩 ────────────────────────────────────────────────────────
 * 1. 要独立窗口（例如要在 `SwingPanel` 画面之上、或者要能拖到主窗口外）→ 用本函数，
 *    **不要**直接写 `DialogWindow`；
 * 2. 只是应用内一层浮层（影片库改名/移动那种）→ 继续用 M3 的 `AlertDialog`，
 *    它本来就没有系统边框，也不会被画面盖住（那些页没有 `SwingPanel`）。
 */
@Composable
fun AppDialogWindow(
    title: String,
    onClose: () -> Unit,
    size: DpSize = DpSize(900.dp, 640.dp),
    resizable: Boolean = true,
    content: @Composable () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = size),
        title = title,
        undecorated = true,
        resizable = resizable,
    ) {
        val colors = LocalCinemaColors.current
        // 无边框之后窗口自己不会动了：拖动/缩放的入口都在 WindowChrome 上。
        val chrome = remember(window) { WindowChrome(window, onClose) }

        // ── 窗口自身的底色也必须跟着主题（2026-11-08 用户报「四角有纯白半圆」）──────
        // 圆角由 DWM 裁，但**裁掉的那四个角之外**仍然是本窗口的界面：窗口自己的
        // AWT 底色还是系统默认的白，于是四个角露出四块白半圆（截图实测）。
        // 把窗口/内容/根面板的底色都设成主题色，白半圆就没了。
        LaunchedEffect(window, colors) {
            val awt = java.awt.Color(colors.background.toArgb())
            runCatching {
                window.background = awt
                (window as? javax.swing.RootPaneContainer)?.let {
                    it.contentPane.background = awt
                    it.rootPane.background = awt
                }
            }
            // 顺带把组件树里所有浅色不透明容器也改深（与主窗口同一套）。
            Backdrops.watch(window) { awt }
        }

        // ── 交互交给 Windows（与主窗口同一套，见 NativeFrame）─────────────────
        // 弹窗只有一颗关闭键、也不该被最大化，所以 captionButtons=1 / maximizable=false。
        // 重试是因为刚 compose 时对话框的 peer 可能还没建好（取不到 HWND）。
        LaunchedEffect(window) {
            var tries = 0
            while (tries < 20) {
                val ok = chrome.installNative(
                    captionButtons = 1,
                    resizable = resizable,
                    maximizable = false,
                    minWidth = 560,
                    minHeight = 400,
                )
                if (ok) break
                tries++
                delay(50)
            }
        }
        // 弹窗关掉时必须把 WndProc 换回去：回调比窗口活得久会让进程直接崩。
        DisposableEffect(window) { onDispose { chrome.disposeNative() } }

        // ── 摆到主窗口正中 ────────────────────────────────────────────────────
        // Compose 的默认位置是「屏幕居中」，而主窗口只占屏幕的一块 —— 结果弹窗
        // 一半压在窗口外面，用户会以为点错了。这里按**主窗口**居中摆一次。
        LaunchedEffect(window) {
            delay(60) // 等窗口按 DpSize 定好尺寸
            val owner = (window as? java.awt.Dialog)?.owner ?: return@LaunchedEffect
            val ob = owner.bounds
            val w = window.width
            val h = window.height
            if (w <= 0 || h <= 0) return@LaunchedEffect
            window.setLocation(ob.x + (ob.width - w) / 2, ob.y + (ob.height - h) / 2)
        }

        Box(Modifier.fillMaxSize().background(colors.background)) {
            // 弹窗进场：0.97 → 1 的缩放 + 淡入（2026-11-08 视觉升级）。
            // 只在**窗口刚出现**时跑一次；窗口被拖动/缩放不会重放（`MutableTransitionState`
            // 只在第一次从 false 变 true）。比 M3 默认的「直接出现」轻，也不喧宾夺主。
            val appear = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = appear,
                enter = fadeIn(Motion.curve(Motion.ENTRANCE)) +
                    scaleIn(initialScale = 0.97f, animationSpec = Motion.curve(Motion.ENTRANCE)),
                exit = fadeOut(Motion.curve(Motion.MICRO, Motion.exit)),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // ⚠️ **不要**在这里 `clip(圆角)` 再 `background`（0.1.22 定论）：
                        // 圆角外那块区域 Compose 就不画了，露出的是窗口自身的 AWT 白底，
                        // 叠上 DWM 裁出来的圆角弧，看起来就是「四角有纯白半圆」。
                        // 背景铺直角矩形（上面那层 Box 已经在铺），圆角只由 DWM 裁。
                        .background(colors.background),
                    // ⚠️ **不要**再补一圈 1dp 描边（0.1.23 用户口径「去掉这条描边」）：
                    // 之前为了「深色弹窗叠在深色主窗口上要有边界」补过 colors.line2，
                    // 实测是一条明显的灰线（(55,56,57)，而底色是 (10,10,11)）。
                    // 边界感现在来自 DWM 自己的圆角 + 阴影，与资源管理器/记事本一致。
                ) {
                    DialogTitleBar(chrome, title)
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
            if (resizable) ResizeHandles(chrome, enabled = true)
        }
    }
}
