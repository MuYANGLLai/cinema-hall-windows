package com.cinemahall.desktop.app

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.Timer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记住主窗口的位置与大小（2026-11-12 用户口径：「重新打开软件的时候，窗口位置和大小
 * 跟上次关闭的时候一样」）。
 *
 * ── 四个必须守住的点 ──────────────────────────────────────────────────────────
 * 1. **存像素，不存 dp**。dp 会随显示器缩放变样（125% 的机器上同一个 dp 对应更大的
 *    物理窗口），而用户要的是「一模一样」。Compose 的 `WindowState` 吃 dp，所以启动时
 *    换算一次（见 [primaryScreenScale]）。
 * 2. **单独一个文件**（`data/window.json`）。放进 `settings.json` 试过，会丢：
 *    那份文件有好几条 `settings.copy(...)` 写入路径，其中任何一条拿着启动时的快照
 *    写一次，就把窗口位置盖回旧值（实测：启动那年写的 560,236 被信令地址那条覆盖掉）。
 * 3. **全屏 / 最大化期间的矩形不算数**。那两种状态的 `bounds` 是「铺满屏幕」，存下去
 *    下次启动就变成满屏窗口 —— 见 [WindowBoundsRecorder.normalBounds]。
 * 4. **老坐标可能已经在屏幕外**（拔了外接屏、改了分辨率）。恢复前先过一遍
 *    [sanitizeWindowBounds]，不合理就退回系统默认摆放。
 */
internal fun primaryScreenScale(): Float = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
}.getOrDefault(1.0).toFloat().takeIf { it > 0.1f } ?: 1.0f

/** 从没存过窗口矩形时的默认尺寸（像素）。 */
internal const val DEFAULT_WINDOW_WIDTH = 1440
internal const val DEFAULT_WINDOW_HEIGHT = 920

/** 所有屏幕的**像素**边界。多显示器时窗口可能在任意一块上。 */
private fun screenBounds(): List<Rectangle> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds }
}.getOrDefault(emptyList())

/**
 * 窗口矩形的落盘（`data/window.json`）。
 *
 * 4 个整数、一个文件，读坏了就当没存过 —— 不让「位置记忆」把启动卡住。
 */
internal class WindowBoundsFile(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): Rectangle? = runCatching {
        if (!file.isFile) return null
        val saved = json.decodeFromString<SavedBounds>(file.readText())
        if (saved.width <= 0 || saved.height <= 0) return null
        Rectangle(saved.x, saved.y, saved.width, saved.height)
    }.getOrNull()

    fun save(rect: Rectangle) {
        runCatching {
            file.parentFile?.mkdirs()
            val text = json.encodeToString(SavedBounds(rect.x, rect.y, rect.width, rect.height))
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }.onFailure { Log.warn("[ui] 记窗口矩形失败：${it.message}") }
    }

    @Serializable
    private data class SavedBounds(val x: Int, val y: Int, val width: Int, val height: Int)
}

/**
 * 把存下来的矩形校正成「真的能看见」的一份；不合理就返回 null（= 当成没存过）。
 *
 * 判据是「至少 120×60 落在某块屏幕里」—— 那一块正好是标题栏能抓住的最小面积。
 * 尺寸超过目标屏幕时按屏幕裁一次，否则「4K 上存的 2560 宽」搬到笔记本上会顶出屏幕。
 */
internal fun sanitizeWindowBounds(saved: Rectangle?): Rectangle? =
    saved?.let { sanitizeWindowBounds(it.x, it.y, it.width, it.height) }

internal fun sanitizeWindowBounds(x: Int?, y: Int?, w: Int, h: Int): Rectangle? {
    if (x == null || y == null) return null
    if (w < 640 || h < 420) return null
    val candidate = Rectangle(x, y, w, h)
    val screens = screenBounds()
    if (screens.isEmpty()) return candidate // 拿不到屏幕信息就别拦着
    val home = screens.maxByOrNull { s ->
        val i = s.intersection(candidate)
        if (i.isEmpty) 0 else i.width * i.height
    } ?: return null
    val visible = home.intersection(candidate)
    if (visible.width < 120 || visible.height < 60) return null
    // 已经整块在屏幕里：原样还原。
    if (home.contains(candidate)) return candidate
    // 否则收进这块屏幕（尺寸超了就缩）。
    val out = Rectangle(candidate)
    out.width = out.width.coerceAtMost(home.width)
    out.height = out.height.coerceAtMost(home.height)
    out.x = out.x.coerceIn(home.x, home.x + home.width - out.width)
    out.y = out.y.coerceIn(home.y, home.y + home.height - out.height)
    return out
}

/**
 * 进程级的「退出前把窗口矩形落盘」钩子。
 *
 * 为什么要有：退出路径有六条（标题栏关闭、托盘退出、程序内退出按钮、`--exit-after`、
 * JVM shutdown hook、看门狗），它们最终都走 `cleanup()` —— 而 `cleanup()` 在组合之外，
 * 拿不到记录器。这里存一个函数引用，`cleanup()` 直接叫它。
 */
internal object WindowBoundsMemory {
    @Volatile
    private var flush: (() -> Unit)? = null

    fun install(f: () -> Unit) {
        flush = f
    }

    fun flushNow() {
        runCatching { flush?.invoke() }
    }

    fun clear() {
        flush = null
    }
}

/**
 * 听窗口的移动 / 缩放，把「普通状态」的矩形写进设置。
 *
 * 拖动一次窗口会来几十个事件，所以写盘走 600ms 防抖；退出时由 [flush] 无条件补一次
 * （用户拖完立刻关窗口也不会丢）。
 */
internal class WindowBoundsRecorder(
    private val window: Window,
    private val isFullscreen: () -> Boolean,
    private val isZoomed: () -> Boolean,
    private val persist: (Rectangle) -> Unit,
) {
    private var pending: Timer? = null
    private var last: Rectangle? = null

    private val listener = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = note()
        override fun componentResized(e: ComponentEvent) = note()
    }

    fun start() {
        // 故意不拿「当前矩形」当基准：那样「启动后没动过窗口」就一次都不会落盘，
        // 而第一次运行本来就是从没有文件开始的。一个会话最多写两三次，不值得省。
        last = null
        window.addComponentListener(listener)
    }

    fun dispose() {
        window.removeComponentListener(listener)
        pending?.stop()
        pending = null
    }

    /** 退出前直接落盘，不等防抖。 */
    fun flush() = commit()

    private fun note() {
        val rect = normalBounds() ?: return
        if (rect == last) return
        val timer = pending ?: Timer(DEBOUNCE_MS) { commit() }.apply {
            isRepeats = false
            pending = this
        }
        timer.restart()
    }

    private fun commit() {
        pending?.stop()
        val rect = normalBounds() ?: return
        if (rect == last) return
        last = rect
        persist(rect)
    }

    /**
     * 「普通」窗口矩形。全屏 / 系统最大化时返回 null（那不是用户要的窗口大小），
     * 于是那两种状态**不会覆盖**上一次存下来的正常矩形。
     */
    private fun normalBounds(): Rectangle? {
        if (isFullscreen() || isZoomed()) return null
        val b = runCatching { window.bounds }.getOrNull() ?: return null
        if (b.width <= 0 || b.height <= 0) return null
        return b
    }

    companion object {
        const val DEBOUNCE_MS = 600
    }
}
