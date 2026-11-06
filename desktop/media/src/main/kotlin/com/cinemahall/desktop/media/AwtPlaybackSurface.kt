package com.cinemahall.desktop.media

import com.cinemahall.core.media.PlaybackSurface
import java.awt.Canvas
import java.awt.Color
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * 桌面画面句柄（契约 §8.2 的 `AwtPlaybackSurface(Canvas)`）。
 *
 * ── 为什么要自己造一个 `JPanel` 而不是只给 `Canvas` ──────────────────────────
 * Compose Desktop 的 `SwingPanel` 只接受 `Component`，而 libVLC 的视频输出必须挂在
 * 一个**已经显示**的 heavyweight `Canvas` 上。`JPanel(BorderLayout)` + 居中的 `Canvas`
 * 是 Phase 0 实测可用的最小组合（Demo 1）：`Canvas` 由 AWT 直接分配原生 surface，
 * libVLC 在里面做覆盖渲染；换成 `JLabel`/`JComponent` 会因为没走 heavyweight 而黑屏。
 *
 * [canvas] 暴露出来是给 `attachSurface` 用的：挂载要等 `isShowing` 为真，
 * 而 AWT 的显示时机与 Compose 的组合时机不同步（Phase 0 §2.1 的坑）。
 */
class AwtPlaybackSurface : PlaybackSurface {

    val canvas: Canvas = Canvas().apply { background = Color.BLACK }

    val panel: JPanel = JPanel(BorderLayout()).apply {
        background = Color.BLACK
        add(canvas, BorderLayout.CENTER)
    }
}
