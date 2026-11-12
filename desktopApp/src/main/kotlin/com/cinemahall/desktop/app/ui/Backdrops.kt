package com.cinemahall.desktop.app.ui

import com.cinemahall.desktop.app.Log
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Collections
import javax.swing.Timer

/**
 * 把窗口里**浅色且不透明**的 AWT 底色统一成主题背景（2026-11-08 拖边白边第三处来源）。
 *
 * ── 为什么需要这一层 ─────────────────────────────────────────────────────────
 * 组件树实测（`--dump-tree`）：
 * ```
 * ComposeWindow            bg=#0A0A0B
 *   SwingInteropViewGroup  bg=[240,240,240] opaque=true  ← Compose 自己造的，没有 API 能设
 *     JPanel               bg=[255,255,255] opaque=true  ← SwingPanel 的默认白，已由 background 参数修掉
 *       Canvas             bg=[0,0,0]
 * ```
 * 那两层白/灰盖着的正是**视频画面那一块**。拖边时 Compose 的重排与 libVLC 的 surface
 * 重建不同步，中间那一两帧露出的就是它们 —— 用户看到的是「播放器与影厅的接缝处发白，
 * 甚至接缝断开、显示后面的内容」。（「显示窗口后面的内容」不是桌面：垫了纯洋红窗体
 * 实测洋红 0 像素，露的就是这块白底。）
 *
 * `SwingInteropViewGroup` 是 Compose 内部类，拿不到实例、也没有 setter，只能顺着
 * 组件树找出来改底色。它平时被 Compose 画的界面完全盖住，只有「什么都没画」的那一帧
 * 才会露出来 —— 把它改成主题色，那一帧就从「刺眼的白」变成「跟主题一样深」。
 *
 * ── 为什么是定时扫一阵，而不是只扫一次 ───────────────────────────────────────
 * 这个容器是**进影厅页时才创建**的（首页没有 `SwingPanel`）。启动那一刻扫不到它，
 * 只能等它出现；又因为没有「它被加进来」的通知（要监听每一层的 componentAdded，
 * 比定时扫还贵），就用一个 800ms 的 Swing 定时器盯前 8 秒，之后停掉。
 * 另外窗口每次尺寸变化都补扫一次 —— 那是白边唯一会出现的时机。
 */
internal object Backdrops {

    /** 已经改过的组件（同一个组件不重复打日志，否则拖边时日志会刷屏）。 */
    private val touched = Collections.synchronizedSet(mutableSetOf<Component>())

    /** 亮度阈值：比中灰亮的都算「白底」，一律换掉；纯黑/深灰不动（那是画面底色）。 */
    private const val LIGHT = 0.5

    /**
     * 开始盯这个窗口。
     *
     * @param color 主题背景色（每次扫描现取，跟着日间/夜间切换）。
     */
    fun watch(window: Window, color: () -> java.awt.Color) {
        apply(window, color())
        // ── 为什么不能只盯前 8 秒（2026-11-12 实测）──────────────────────────────
        // 原来是「800ms × 8 次就停」。问题是 `SwingPanel` 那几层容器**不是只在启动时
        // 出现**：切页时 Compose 会重建 interop 容器，而那一刻它还是出厂白，`Backdrops`
        // 早就停了，没人再改它 —— 用户看到的就是「切走时播放器区域闪一下白」。
        // 5Hz 的组件树遍历对这个规模的界面可以忽略（几十个组件、只读背景色），
        // 所以改成一直盯；而且每次改色都写日志，下一次再出现白底时日志里能直接看到是谁。
        // 5Hz、永远不停。每次改色都有日志（见 `apply`），所以将来再冒白底能直接看到是谁。
        val timer = Timer(200) { apply(window, color()) }
        timer.isRepeats = true
        timer.start()
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                apply(window, color())
                // ── 尺寸一变就让 Compose 重画一帧（2026-11-08）────────────────────
                // 拖边时窗口先变大、Compose 的那一帧还没画，新露出的那条边是**空白的**
                // （实测 Skia 交换链在新尺寸上还没内容，系统/DWM 给的是白）。
                // 这里在 AWT 处理完 reshape 之后主动要一帧，把那条边填掉。
                // `invokeLater`：`componentResized` 常常在子组件重排之前到，
                // 当场 repaint 会拍到半成品。
                javax.swing.SwingUtilities.invokeLater {
                    runCatching {
                        (window as? javax.swing.RootPaneContainer)?.contentPane?.repaint()
                        window.repaint()
                    }
                }
            }

            override fun componentShown(e: ComponentEvent) {
                apply(window, color())
            }
        })
        Log.info("[ui] 改底色监视已启动（浅色不透明容器 → 主题背景）")
    }

    /** 扫一遍，返回改了几个。 */
    fun apply(window: Window, target: java.awt.Color): Int {
        var changed = 0
        fun walk(c: Component) {
            runCatching {
                val bg = c.background
                if (c.isOpaque && bg != null && bg.alpha > 0 && bg != target && luminance(bg) >= LIGHT) {
                    c.background = target
                    changed++
                    if (touched.add(c)) {
                        Log.info(
                            "[ui] 底色已改深 ${c.javaClass.simpleName} " +
                                "#${hex(bg)} → #${hex(target)}",
                        )
                    }
                }
            }
            if (c is Container) {
                // 复制一份再遍历：改背景会触发重绘，个别容器可能在回调里增删子组件。
                runCatching { c.components.toList() }.getOrDefault(emptyList()).forEach { walk(it) }
            }
        }
        walk(window)
        return changed
    }

    private fun luminance(c: java.awt.Color): Double =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0

    private fun hex(c: java.awt.Color): String =
        "%02X%02X%02X".format(c.red, c.green, c.blue)
}
