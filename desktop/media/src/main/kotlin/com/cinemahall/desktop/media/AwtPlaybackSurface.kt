package com.cinemahall.desktop.media

import com.cinemahall.core.media.PlaybackSurface
import java.awt.Canvas
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 *
 * ── 为什么还要一个 [awaitReady]（2026-11-06 真机实测）────────────────────────
 * `isShowing` 为真时尺寸仍可能是 `0x0`：`SwingPanel` 刚把 panel 挂进容器的那几十毫秒
 * （现场日志：`surface attach ... ok size=0x0`）。以 0x0 建立视频输出，libVLC 的 vout
 * 就按零尺寸初始化，之后窗口再变大也不会补画 —— 用户看到的是「按了播放没反应 / 黑屏」。
 * 所以装片之前先等到「显示 **且** 有尺寸」，这里用 AWT 的组件事件做一个闩。
 */
class AwtPlaybackSurface : PlaybackSurface {

    val canvas: Canvas = Canvas().apply { background = Color.BLACK }

    val panel: JPanel = JPanel(BorderLayout()).apply {
        background = Color.BLACK
        add(canvas, BorderLayout.CENTER)
    }

    /** 1 = 已经「显示且有尺寸」。只数一次，之后不再变化。 */
    private val readyLatch = CountDownLatch(1)

    init {
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = markIfReady()

            override fun componentShown(e: ComponentEvent) = markIfReady()
        }
        canvas.addComponentListener(listener)
        panel.addComponentListener(listener)
        markIfReady()
    }

    private fun markIfReady() {
        if (canvas.isShowing && canvas.width > 0 && canvas.height > 0) readyLatch.countDown()
    }

    /**
     * 等到画面可以挂视频输出为止。**必须离开 EDT 调用**（它会阻塞），超时返回 false。
     *
     * 超时不算错误：调用方继续装片即可（`attachSurface` 自己也在轮询），只是那种情况下
     * 大概率会撞上 libVLC 的 vout 错误 —— 所以调用点仍应在装片前等一次。
     */
    fun awaitReady(timeoutMs: Long): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
