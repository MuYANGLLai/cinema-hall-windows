package com.cinemahall.desktop.app.ui

import com.cinemahall.desktop.app.Log
import java.awt.Color
import java.awt.Component
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * 切页时的「幕布」：一块贴在播放器区域上的深色原生小窗。
 *
 * ── 为什么要它（2026-11-12 用户第二次报「切页还是闪一下白」）──────────────────
 * 影厅页的画面是 libVLC 画在一层**原生子窗口**里的，切页时必须把它挪出窗口
 * （见 `AppRoot` 里那段「影厅页常驻」的说明）。它一挪走，那块矩形就被腾空，
 * 需要下层的 Skia 立刻重画一帧 —— 而 Skia 什么时候真的画到，是**运行时的事**：
 * 本机实测那一帧已经是深色（白色像素 0.01%），用户机器上仍然闪白，说明那一帧
 * 没能及时重画。
 *
 * 幕布把这件事变成**确定的**：先把一块深色原生窗口盖在画面区上，再挪画面 ——
 * 腾空的那一帧被盖住，看得到的永远是深色，与 Skia 什么时候重画无关。
 *
 * ── 为什么「创建」要早于「使用」，且一直可见 ─────────────────────────────────
 * 原生窗口**第一次显示**时会有一帧还没画出来的底（和主窗口当年的白边同源）。
 * 所以创建与首帧都在 `attach()`（窗口刚建好时）完成：先在屏幕外显示一次、把首帧
 * 消耗掉；之后只用 `setBounds` 在「屏幕外」与「画面区」之间移动。移动一个已经
 * 画好的窗口不会再触发首帧问题。
 */
internal object PlayerCurtain {

    /** 与窗口底色一致，不和主题打架。 */
    private val BACKDROP = Color(0x0A, 0x0A, 0x0B)

    /**
     * 不在影厅页时，画面被挪走的水平距离（与 `AppRoot.HIDDEN_SCREEN_X` 同源）。
     *
     * 两处必须是同一个数：`AppRoot` 用它把页面挪出窗口，这里用「挪回来」反推
     * 「画面真正显示时在哪」。写死两份就会出现「幕布盖偏」这种只在某台机器上
     * 才看得见的错。
     */
    internal const val HIDDEN_OFFSET_X_DP = -4000.0

    private const val PARK_X = -6000
    private const val PARK_Y = -6000
    private const val PARK_W = 64
    private const val PARK_H = 8

    private var window: JWindow? = null

    /**
     * 「画面真正显示时」在**窗口客户区里的**矩形（`x, y, w, h`）。
     *
     * 由 `PlayerPane` 的 `onGloballyPositioned` 直接上报：它算的是
     * 「Compose 给的窗口内位置 + 被挪走的距离」，所以**页面正在窗口外**时也能拿到
     * 正确位置。不能靠 `Component.locationOnScreen` —— 页面被摘下时它抛异常
     * （2026-11-12 实测：幕布因此一次都没盖上去）。
     */
    private var dest: Rectangle? = null
    private var canvas: Component? = null

    /**
     * 建好幕布并预热（只做一次）。由 `Main` 在窗口刚出现时调用 —— 那里一定拿得到
     * 顶层 `Window`，而 `PlayerPane` 的 effect 时刻 `getWindowAncestor` 可能还是 null。
     */
    fun attach(owner: Window?) {
        val target = owner ?: return
        if (window?.owner === target) return
        window?.dispose()
        window = runCatching {
            JWindow(target).apply {
                focusableWindowState = false
                type = Window.Type.POPUP
                contentPane.background = BACKDROP
                (contentPane as? javax.swing.JComponent)?.isOpaque = true
                setBounds(PARK_X, PARK_Y, PARK_W, PARK_H)
                isVisible = true
            }
        }.onFailure { Log.warn("[ui] 切页幕布建立失败：${it.message}") }.getOrNull()
        if (window != null) Log.info("[ui] 切页幕布就绪（已预热在屏幕外）")
    }

    /** 由 `PlayerPane` 在画面挂上来时调用（幂等）。 */
    fun register(component: Component) {
        canvas = component
        if (window == null) {
            attach(runCatching { SwingUtilities.getWindowAncestor(component) }.getOrNull())
        }
    }

    /** 把幕布盖到画面上。画面没显示、或幕布没建好时是空操作。 */
    fun cover() {
        val c = canvas ?: return
        if (!c.isShowing || c.width <= 0 || c.height <= 0) return
        val w = window ?: run {
            attach(runCatching { SwingUtilities.getWindowAncestor(c) }.getOrNull())
            window
        } ?: return
        val origin = runCatching { c.locationOnScreen }.getOrNull() ?: return
        runCatching { w.bounds = Rectangle(origin.x, origin.y, c.width, c.height) }
    }

    /**
     * **进影厅页**时用：把幕布盖到「画面**即将**出现的那块矩形」上。
     *
     * ── 为什么需要它（2026-11-12 用户报「第一次进影厅页播放器区域闪白」）────────
     * 主窗口是 `WS_EX_NOREDIRECTIONBITMAP`（见 `NativeFrame` 的说明）：它没有重定向
     * 表面，**没被 Skia 交换链覆盖的地方直接露桌面**。进影厅页那一刻，interop 容器
     * 与画面窗口正在新建、Skia 还没画出那一帧 —— 门禁抓到的就是「播放器区域露着
     * 桌面/别的窗口」，用户看到的就是闪一下（背后若是白窗口，就是闪白）。
     *
     * 此刻画面还停在窗口外（`AppRoot` 的 `HIDDEN_SCREEN_X`），所以这里把它加回来
     * 就是真正显示时的那块矩形。调用点必须在「翻页之前」，否则算出来的位置是错的。
     */
    fun coverForEntry(): Boolean {
        val w = window ?: run { Log.warn("[curtain] 入页覆盖失败：幕布窗口不存在"); return false }
        val d = dest ?: run { Log.warn("[curtain] 入页覆盖失败：还没收到画面位置"); return false }
        val owner = runCatching { w.owner?.locationOnScreen }.getOrNull()
            ?: run { Log.warn("[curtain] 入页覆盖失败：取不到主窗口位置"); return false }
        val ok = runCatching { w.bounds = Rectangle(owner.x + d.x, owner.y + d.y, d.width, d.height) }
            .isSuccess
        Log.info("[curtain] 入页覆盖 dest=${d.x},${d.y} ${d.width}x${d.height} ok=$ok")
        return ok
    }

    /** 由 `PlayerPane` 上报「画面真正显示时」在窗口客户区里的矩形。 */
    fun setDestination(x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            if (dest == null) Log.warn("[curtain] 画面位置无效：${width}x$height")
            return
        }
        if (dest == null) Log.info("[curtain] 画面位置=$x,$y ${width}x$height")
        dest = Rectangle(x, y, width, height)
    }

    /** 收幕布：**不是隐藏**，而是挪到屏幕外（见类注释里的首帧说明）。 */
    fun release() {
        val w = window ?: return
        runCatching { w.setBounds(PARK_X, PARK_Y, PARK_W, PARK_H) }
    }
}
