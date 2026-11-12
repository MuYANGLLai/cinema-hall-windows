package com.cinemahall.desktop.app.ui

import com.cinemahall.desktop.app.Log
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.util.Collections
import javax.swing.Timer

/**
 * 把窗口里**浅色且不透明**的 AWT 底色统一成主题背景。
 *
 * ── 为什么需要这一层 ─────────────────────────────────────────────────────────
 * 组件树实测（`--dump-tree`）：
 * ```
 * ComposeWindow            bg=#0A0A0B
 *   SwingInteropViewGroup  bg=[240,240,240] opaque=true  ← Compose 自己造的，没有 API 能设
 *     JPanel               bg=[255,255,255] opaque=true  ← SwingPanel 的默认白，已由 background 参数修掉
 *       Canvas             bg=[0,0,0]
 * ```
 * 那两层白/灰盖着的正是**视频画面那一块**。它平时被画面完全挡住，只有在
 * 「Compose 什么都没画」的那一帧才会露出来 —— 拖边、切页都是那个时刻。
 *
 * ── 为什么要监听「加进来」而不是定时扫（2026-11-12 用户第二次报闪白）──────────
 * 那个容器**不是启动时建一次就完事**：它是 Compose 的 interop 容器，离开影厅页会
 * 连人带窗口被摘掉，**第一次进影厅页 / 每次切回来都新建一个**，出厂色就是
 * `#F0F0F0`。原来的实现是「800ms×8 次」后来改成「200ms 一直扫」，但**扫描永远比
 * 绘制晚**：容器加进来 → 立刻绘制那一帧（白）→ 200ms 后才被扫到改深。用户看到的
 * 就是「第一次进影厅页，播放器区域闪一下白」。
 *
 * 现在改成 `ContainerListener`：新组件**在第一次绘制之前**就被改深（这条回调发生在
 * `Container.add()` 内部，早于任何 paint）。定时器只作为兜底保留。
 */
internal object Backdrops {

    /** 已经改过的组件（同一个组件不重复打日志，否则拖边时日志会刷屏）。 */
    private val touched = Collections.synchronizedSet(mutableSetOf<Component>())

    /** 已经挂过 `ContainerListener` 的容器（避免重复挂）。 */
    private val hooked = Collections.synchronizedSet(mutableSetOf<Container>())

    /** 亮度阈值：比中灰亮的都算「白底」，一律换掉；纯黑/深灰不动（那是画面底色）。 */
    private const val LIGHT = 0.5

    private var watched: Window? = null
    private var colorOf: (() -> java.awt.Color)? = null

    /**
     * 开始盯这个窗口。
     *
     * @param color 主题背景色（每次扫描现取，跟着日间/夜间切换）。
     */
    fun watch(window: Window, color: () -> java.awt.Color) {
        watched = window
        colorOf = color
        apply(window, color())
        // 兜底：有些容器不是通过 `add()` 出现的（或者监听挂晚了一步），200ms 扫一次。
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
            if (fixOne(c, target)) changed++
            if (c is Container) {
                ensureHooked(c)
                // 复制一份再遍历：改背景会触发重绘，个别容器可能在回调里增删子组件。
                runCatching { c.components.toList() }.getOrDefault(emptyList()).forEach { walk(it) }
            }
        }
        walk(window)
        return changed
    }

    /**
     * 给容器挂「加进来就改深」的监听。**这是白闪真正的拦断面** —— 见类注释。
     */
    private fun ensureHooked(container: Container) {
        if (!hooked.add(container)) return
        runCatching {
            container.addContainerListener(object : ContainerAdapter() {
                override fun componentAdded(e: ContainerEvent) {
                    val target = colorOf?.invoke() ?: return
                    val child = e.child ?: return
                    // 就地改深，不排队：这条回调早于子组件的第一次绘制。
                    if (fixOne(child, target)) {
                        Log.info("[ui] 新容器即刻改深 ${child.javaClass.simpleName} #${hex(target)}")
                    }
                    if (child is Container) {
                        ensureHooked(child)
                        runCatching { child.components.toList() }
                            .getOrDefault(emptyList())
                            .forEach { grand -> if (fixOne(grand, target) && grand is Container) ensureHooked(grand) }
                    }
                }
            })
        }
    }

    /** 改一个组件的底色，返回是否真的改了。 */
    private fun fixOne(c: Component, target: java.awt.Color): Boolean {
        return runCatching {
            val bg = c.background
            if (c.isOpaque && bg != null && bg.alpha > 0 && bg != target && luminance(bg) >= LIGHT) {
                c.background = target
                if (touched.add(c)) {
                    Log.info("[ui] 底色已改深 ${c.javaClass.simpleName} #${hex(bg)} → #${hex(target)}")
                }
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun luminance(c: java.awt.Color): Double =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0

    private fun hex(c: java.awt.Color): String =
        "%02X%02X%02X".format(c.red, c.green, c.blue)
}
