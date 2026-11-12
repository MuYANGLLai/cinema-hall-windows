package com.cinemahall.desktop.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.layout.onSizeChanged
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.media.AwtPlaybackSurface
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮控制栏（用户口径：控制条要**浮在画面上**，鼠标一停就收回去）。
 *
 * ── 为什么必须是独立窗口，而不是 Compose 里叠一层 ───────────────────────────
 * 画面挂在 `SwingPanel` 上，而 `SwingPanel` 是 AWT 的 heavyweight `Canvas`
 * （Phase 0 §2.1 的实测结论：Compose 画的控件叠在它上面**完全不可见**）。
 * 所以「浮在画面上」只能是另一个窗口：一个**属于主窗口**的 `JWindow`，
 * 里面装一块 `ComposePanel`（把 Compose 内容嵌回 AWT 的官方通道）。
 *
 * ── 三个必须遵守的细节（都是踩过或会踩的）──────────────────────────────────
 * 1. **属于主窗口**（`JWindow(owner)`）而不是 `alwaysOnTop`：前者的层序是
 *    「永远在主窗口之上、其他程序之下」，后者会在用户切到别的程序时仍然赖在最前面。
 * 2. **不许抢焦点**（`isFocusableWindowState = false`）：抢了焦点，空格/F11/方向键
 *    这一套快捷键就跟着走，而在「浮层」上按空格本来该由主窗口接管。
 * 3. **位置只能用 AWT 算**（`SwingUtilities.convertPoint` + `component.size`）：
 *    Compose 的 `boundsInWindow` 是 Compose 像素，100%/125%/150% 缩放下与 AWT 的
 *    逻辑像素不是一回事，用它会算错；而 `JWindow.bounds` 吃的就是 AWT 逻辑像素。
 *
 * 内容（[setContent]）由 `PlayerPane` 装一次，之后靠 `PlaybackBarState` 里的快照
 * 状态驱动重组 —— 不重复 `setContent`（那会把浮层里的局部状态，比如正在拖的
 * 进度、展开的菜单，一次次重置）。
 */
class PlaybackOverlay(
    private val video: AwtPlaybackSurface,
    /** 正在拖进度条 / 菜单展开时不要自动收回。 */
    private val shouldStayVisible: () -> Boolean = { false },
) {
    /** 浮层起不来（远程桌面、无 DWM 的环境）时为 true —— 调用方据此退回画面下方那排。 */
    var broken by mutableStateOf(false)
        private set

    /**
     * 覆盖模式：这扇窗口正**整块盖住画面**画空态（见 [showCover]）。
     *
     * 覆盖期间「晃鼠标叫出控制条」「1.3 秒自动收回」两套逻辑都必须让位 —— 否则用户
     * 晃一下鼠标，空态就被控制条顶掉、再被自动收回，画面变成一块纯黑：比没有空态更糟。
     */
    var covering by mutableStateOf(false)
        private set

    private var window: JWindow? = null
    private val panel = ComposePanel()
    private var content: (@Composable () -> Unit)? = null
    /** 覆盖模式要画的内容（空态）；null = 这扇浮层只做控制条。 */
    private var coverContent: (@Composable () -> Unit)? = null
    private var disposed = false

    /** 内容自己量出来的高度（Compose 像素）。窗口高度 = 它 ÷ 缩放。 */
    private var contentHeightPx = 0

    private val hideTimer = javax.swing.Timer(AUTO_HIDE_MS) { hide() }.apply { isRepeats = false }

    /** 浮层是最上层窗口，窗口被拖动/改大小时不会自动跟着 —— 显示期间每 200ms 对一次。 */
    private val trackTimer = javax.swing.Timer(TRACK_MS) { syncBounds() }.apply { isRepeats = true }

    /**
     * 鼠标「晃一下」的检测走**轮询指针位置**，不依赖鼠标事件投递。
     *
     * ── 为什么事件靠不住（2026-11-07 用户报「全屏下晃鼠标不出来」）─────────────
     * 画面是 libVLC 的 `direct3d11` 输出，它在 AWT 的 `Canvas` 里另建了自己的原生
     * 子窗口。指针压在视频上时，Windows 把鼠标消息投给那个原生窗口，Java 这一侧
     * （包括全局 `AWTEventListener`）**一个事件都收不到** —— 窗口模式下如果指针
     * 大部分时间在侧栏上，就表现为「窗口模式好使、全屏不好使」。
     *
     * `MouseInfo.getPointerInfo()` 读的是**操作系统光标位置**，谁消费了消息都不影响。
     * 每 120ms 比一次坐标：动了、而且动的时候在画面矩形里，就把控制条叫出来。
     */
    private val hoverPoll = javax.swing.Timer(HOVER_POLL_MS) { pollPointer() }.apply { isRepeats = true }
    private var lastPointer: Point? = null
    private var pollLogged = 0

    private fun pollPointer() {
        if (disposed) return
        val p = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return
        val moved = lastPointer != p
        lastPointer = p
        if (!moved) return
        // 主窗口最小化/隐藏时，指针在桌面上的任何移动都不该把控制条叫出来
        // （见 [ownerUsable]）。收回已经显示的那一块，别让它留在桌面上。
        if (!ownerUsable()) {
            if (isVisible) hideNow()
            return
        }
        val rect = videoScreenRect()
        if (pollLogged < 2) {
            pollLogged++
            Log.info("[overlay] 指针轮询 指针=$p 画面=$rect 可见=$isVisible")
        }
        if (rect == null || !rect.contains(p)) return
        poke("hover")
    }

    /**
     * 主窗口此刻「真的能看见」吗？
     *
     * ⚠️ 这里**不能**只看 `video.panel.isShowing`：窗口被最小化之后它仍然是 true
     * （`isShowing` 只表示「在显示树里且 visible 标记为真」，与窗口是否被系统收进
     * 任务栏无关）。旧版就是靠这一条判断的，于是出现了用户报的现象 ——
     * **影厅页最小化之后，在桌面上挪一下鼠标，悬浮控制条会自己冒出来**：
     * 那时画面矩形还是最小化之前的坐标，指针恰好划过就命中了。
     */
    private fun ownerUsable(): Boolean {
        val w = owner() ?: return false
        if (!w.isVisible) return false
        if (w is java.awt.Frame && (w.extendedState and java.awt.Frame.ICONIFIED) != 0) return false
        if (w is java.awt.Dialog && !w.isShowing) return false
        return runCatching { video.panel.isShowing }.getOrDefault(false)
    }

    /**
     * 画面在**屏幕**上的矩形。
     *
     * ⚠️ 不能直接用 `SwingUtilities.convertPoint(panel, 0, 0, null)`：实测它返回的是
     * 「画面在窗口内的偏移」（本机是 `196,32`），**不含**窗口自己在屏幕上的位置 ——
     * 拿它当屏幕坐标，浮层会整体偏到窗口左上角的方向去（第一次验证时就是这么偏了
     * 48px）。所以这里显式做两步：先算「画面相对窗口」，再加上窗口自己的位置。
     * 窗口是无边框的，两者之间没有标题栏/边框的 inset，不需要额外补偿。
     */
    private fun videoScreenRect(): Rectangle? {
        if (!ownerUsable()) return null
        val w = owner() ?: return null
        val rel = runCatching { SwingUtilities.convertPoint(video.panel, 0, 0, w) }.getOrNull() ?: return null
        val loc = runCatching { w.location }.getOrNull() ?: return null
        val pw = video.panel.width
        val ph = video.panel.height
        if (pw <= 0 || ph <= 0) return null
        return Rectangle(loc.x + rel.x, loc.y + rel.y, pw, ph)
    }

    private fun insideVideo(point: Point?): Boolean {
        if (point == null) return false
        val rect = videoScreenRect() ?: return false
        return rect.contains(point)
    }

    /** 门禁：已经显示且内容量过高度（截图时机用）。 */
    fun isSettled(): Boolean = isVisible && contentHeightPx > 0

    /** 给内容用的 Modifier：内容一量出高度就把窗口调到那个高度。 */
    val sizeModifier: Modifier = Modifier.onSizeChanged { reportContentHeight(it.height) }

    private val ownerWatch = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = if (isVisible) syncBounds() else Unit
        override fun componentResized(e: ComponentEvent) = if (isVisible) syncBounds() else Unit
        override fun componentHidden(e: ComponentEvent) {
            // 覆盖模式跟着主窗口一起藏（空态绝不该留在桌面上），但**不退出**覆盖模式：
            // 跟随定时器还在跑，主窗口一回来 `syncBounds` 就把空态放回去。
            if (covering) hideTemporarily() else hideNow()
        }
    }

    /** 只把窗口藏起来，不动模式、不停跟随（覆盖模式专用，见 [ownerWatch]）。 */
    private fun hideTemporarily() {
        runCatching { window?.isVisible = false }
    }

    /**
     * 最小化要**当场**收回，不能等下一次轮询。
     *
     * 轮询是 120ms 一次，而最小化动画本身有 150~200ms —— 靠轮询的话，用户能看到
     * 控制条先跟着窗口消失、又在桌面上闪一下。这里直接挂窗口状态监听。
     */
    private val stateWatch = object : java.awt.event.WindowStateListener {
        override fun windowStateChanged(e: java.awt.event.WindowEvent) {
            if (e.newState and java.awt.Frame.ICONIFIED == 0) return
            if (covering) hideTemporarily() else hideNow()
        }
    }

    private val isVisible: Boolean get() = window?.isVisible == true

    /**
     * 装内容。只调一次（见类注释第 3 段的说明）。
     *
     * [cover] 是「整块盖住画面」的内容（空态，见 [showCover]），null = 这扇浮层只做控制条。
     * 两种内容**共用一扇窗口**：贴底边与铺满画面只是同一个 `JWindow` 的两种 bounds，
     * 省掉第二扇窗口以及随之而来的第二套定位/跟随代码。
     */
    fun setContent(bar: @Composable () -> Unit, cover: (@Composable () -> Unit)? = null) {
        coverContent = cover
        // 组合时读 `covering`：切模式只要改这一条状态，内容自己换过去。
        val composed: @Composable () -> Unit = {
            val empty = coverContent
            if (covering && empty != null) empty() else bar()
        }
        content = composed
        // 窗口已经建好了（用户先晃过鼠标）就立刻装上；否则等 `ensureWindow` 时装。
        window?.let { runCatching { panel.setContent(composed) } }
    }

    /**
     * 开始「鼠标一动就出来」的检测（见 [hoverPoll] 的说明）。
     */
    fun startWatching() {
        if (disposed) return
        lastPointer = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
        hoverPoll.start()
        Log.info("[overlay] 指针轮询已启动（${HOVER_POLL_MS}ms）")
    }

    /** 鼠标动一下就出来，并重新开始计时。 */
    fun poke(reason: String = "manual") {
        if (disposed || broken) return
        // 空态覆盖期间没有可控制的东西（连片都没有）：既不该把控制条叠到空态上，
        // 也不该让 `hideTimer` 起来把刚盖上的那一块收走（见 [covering]）。
        if (covering) return
        val panelShowing = ownerUsable()
        if (!panelShowing) {
            hideNow()
            return
        }
        if (pokes < 4) {
            pokes++
            Log.info("[overlay] poke($reason) showing=$panelShowing content=${content != null}")
        }
        val w = ensureWindow() ?: return
        if (!w.isVisible) {
            syncBounds()
            w.isVisible = true
            Log.info("[overlay] 显示 bounds=${w.bounds} contentPx=$contentHeightPx")
        }
        hideTimer.restart()
        if (!trackTimer.isRunning) trackTimer.start()
    }

    private var pokes = 0

    fun hide() {
        // 空态覆盖着：这一次不收（只有 [hideCover] 能收，判据在调用方那边）。
        if (covering) return
        // 正在拖动进度条 / 菜单还开着：这一次不收，下一轮再说。
        if (shouldStayVisible()) {
            hideTimer.restart()
            return
        }
        hideNow()
    }

    /**
     * 整块盖住画面（空态）。
     *
     * ── 为什么空态必须借这扇窗口（2026-11-12 实测）────────────────────────────
     * 画面是 AWT 重量级 `Canvas`，Compose 画的任何东西都盖不住它（2026-11-07 定论）。
     * 而「把画布缩成 0×0 让空态露出来」会当场打断视频输出（`surface attach timed out`，
     * 界面左下角甩出红字），「把画布挪出窗口」则让 AWT 子窗口完全无视祖先的位移
     * （实测画布仍停在 `196,32` 盖住别的页）。这扇 `JWindow` 天然在画布之上，
     * 所以这块地方只能由它画。
     *
     * 返回 true = 这会儿真的盖上了。首帧时 `SwingPanel` 还没挂进窗口（`isShowing` 为假），
     * 这里会拒绝 —— 调用方重试即可（见 `PlayerPane` 那条 120ms 的等待）。
     */
    fun showCover(): Boolean {
        if (disposed || broken) return false
        if (coverContent == null) return false
        if (!ownerUsable()) return false
        val w = ensureWindow() ?: return false
        covering = true
        syncBounds()
        hideTimer.stop()
        if (!trackTimer.isRunning) trackTimer.start()
        return w.isVisible
    }

    /** 收起空态的覆盖层（选到片了 / 离开影厅页了）。 */
    fun hideCover() {
        if (!covering) return
        covering = false
        hideNow()
    }

    private fun hideNow() {
        hideTimer.stop()
        trackTimer.stop()
        if (window?.isVisible == true) Log.info("[overlay] ${AUTO_HIDE_MS}ms 无操作，控制条收回")
        runCatching { window?.isVisible = false }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        if (active === this) active = null
        hideTimer.stop()
        trackTimer.stop()
        hoverPoll.stop()
        runCatching { window?.let { owner()?.removeComponentListener(ownerWatch) } }
        runCatching { window?.let { (owner() as? java.awt.Frame)?.removeWindowStateListener(stateWatch) } }
        runCatching { window?.isVisible = false }
        runCatching { window?.dispose() }
        window = null
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private fun owner(): Window? = runCatching { SwingUtilities.getWindowAncestor(video.panel) }.getOrNull()

    private fun ensureWindow(): JWindow? {
        window?.let { return it }
        if (disposed) return null
        val built = runCatching { build() }.getOrElse { error ->
            Log.warn("[overlay] 悬浮控制栏起不来，退回画面下方：${error.message}")
            broken = true
            null
        }
        return built
    }

    private fun build(): JWindow {
        val holder = owner()
        val w = JWindow(holder)
        w.contentPane.layout = BorderLayout()
        w.contentPane.background = Color(0x14, 0x14, 0x16)
        // 见类注释第 2 点：不抢焦点，但照样收鼠标。
        // （Kotlin 属性语法在这里用不了：`isFocusableWindow()` 与 `setFocusableWindowState()`
        //  名字不成对，只能按方法调。）
        @Suppress("DEPRECATION")
        runCatching { w.setFocusableWindowState(false) }
        if (holder == null) {
            // 还没挂进主窗口（极少见：正常路径下这里的 panel 一定已经显示）——
            // 那就只能靠置顶保证看得见，宁可越界也不能让控制条落到画面后面。
            w.isAlwaysOnTop = true
        }
        content?.let { panel.setContent(it) }
        w.contentPane.add(panel, BorderLayout.CENTER)
        holder?.addComponentListener(ownerWatch)
        (holder as? java.awt.Frame)?.addWindowStateListener(stateWatch)
        window = w
        return w
    }

    private fun reportContentHeight(heightPx: Int) {
        if (heightPx <= 0) return
        if (abs(heightPx - contentHeightPx) <= 1) return
        contentHeightPx = heightPx
        if (isVisible) syncBounds()
    }

    /** 贴在画面**底边**，宽度与画面一致；覆盖模式下铺满整个画面矩形。 */
    private fun syncBounds() {
        val w = window ?: return
        if (disposed) return
        val showing = ownerUsable()
        if (!showing) {
            // 覆盖模式：只是没人看得到我们，藏起来即可（定时器继续跑，回来自己显形）。
            // 控制条：按老规矩把跟随一起停下。
            if (covering) hideTemporarily() else hideNow()
            return
        }
        val rect = videoScreenRect() ?: return
        val target = if (covering) {
            rect
        } else {
            val scale = runCatching {
                (panel.graphicsConfiguration ?: video.panel.graphicsConfiguration).defaultTransform.scaleY
            }.getOrNull()?.takeIf { it > 0.1 } ?: 1.0
            // 内容高度是 Compose 像素（= AWT 逻辑像素 × 缩放），窗口要的是 AWT 逻辑像素。
            val contentPx = if (contentHeightPx > 0) contentHeightPx.toDouble() else FALLBACK_HEIGHT_PX * scale
            val heightAwt = (contentPx / scale).roundToInt()
            if (heightAwt <= 0) return
            Rectangle(rect.x, rect.y + rect.height - heightAwt, rect.width, heightAwt)
        }
        // 先定尺寸再显形：反过来的话，恢复时那一帧会以旧位置画出来。
        if (w.bounds != target) w.bounds = target
        if (covering && !w.isVisible) runCatching { w.isVisible = true }
    }

    companion object {
        /** 无操作 1.3 秒自动收回（2026-11-07 用户把 2.5 秒改短）。 */
        const val AUTO_HIDE_MS = 1_300
        const val TRACK_MS = 200

        /** 指针轮询间隔：够快看得出「在动」，又不会白烧 CPU（8 次/秒）。 */
        const val HOVER_POLL_MS = 120
        const val FALLBACK_HEIGHT_PX = 104

        @Volatile
        private var active: PlaybackOverlay? = null

        /**
         * 门禁/截图专用：把当前浮层「叫出来」一次，返回是否有浮层存在。
         *
         * 为什么需要它：`--screenshot` 那条路径按不了鼠标，`Robot().mouseMove` 在无人值守
         * 会话里也不可靠（指针本来就在目标位置时压根不产生移动事件）。而「控制条真的
         * 浮在画面上」必须能被截图验证，所以留一条直接入口 ——
         * 它走的仍然是产品路径上的 [poke]，不另开一套显示逻辑。
         */
        fun pokeActive(): Boolean {
            val instance = active ?: return false
            instance.poke("gate")
            return true
        }

        /**
         * 门禁：把指针**真的**挪到画面中心，让「指针轮询」这条路自己把它叫出来。
         *
         * 这是比 [pokeActive] 更强的验证：`Robot.mouseMove` 改的是操作系统光标，
         * 之后能不能出现，全看轮询有没有看出来 —— 也就是用户按鼠标时走的同一条路。
         */
        fun hoverActiveByRobot(): Boolean {
            val instance = active ?: return false
            val rect = instance.videoScreenRect() ?: return false
            return runCatching {
                java.awt.Robot().mouseMove(rect.x + rect.width / 2, rect.y + rect.height / 2)
                true
            }.getOrDefault(false)
        }

        /** 门禁：已经显示且内容量出了高度（说明 Compose 那一侧真的画出东西了）。 */
        fun activeSettled(): Boolean = active?.isSettled() == true

        /**
         * 立刻收起空态的覆盖层（切页时用）。
         *
         * 为什么需要这条静态入口：空态是**另一扇原生窗口**，`pageVisible` 变 false 之后
         * 要等一次重组 + 一次效果启动才收得回去。门禁逐帧实测：只靠 `pageVisible`，
         * 切到影片库的第一帧与空态截图**逐像素相同**（空态压在目标页上约 50ms）。
         * 切页要它当场消失，就在切页这一个入口里直接收 —— 与幕布
         * `PlayerCurtain.cover()` 同一条思路。
         */
        fun hideCoverNow() {
            active?.hideCover()
        }
    }

    init {
        active = this
    }
}
