package com.cinemahall.desktop.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cinemahall.desktop.app.Log
import kotlinx.coroutines.delay
import java.awt.Window
import javax.swing.JWindow

/**
 * 全屏 / 退出全屏之间的**交叉遮罩**（2026-11-08 用户口径：全屏切换要「丝滑」）。
 *
 * ── 为什么必须是一块独立窗口，而不是 Compose 里画一层 Box ────────────────────
 * 播放页中间那块画面是 libVLC 直接画在 AWT **重量级** `Canvas` 上的：Compose 画的任何
 * 东西都会被它盖住（Phase 0 已定论）。所以遮罩只能是一块**真的在它上面**的窗口 ——
 * 与悬浮控制条（`PlaybackOverlay`）同一个做法。
 *
 * ── 为什么是「遮 → 换 → 揭」而不是直接做尺寸动画 ────────────────────────────
 * 全屏切换本身是一步到位的：窗口矩形从 1440×920 变成 2560×1440，Compose 要重排、
 * 画面 Canvas 要重建后端、libVLC 要重新贴一次 surface。中间那几帧**抓不住也修不掉**
 * （用户看到的就是「闪一下」）。与其去补那几帧，不如用 90ms 把它盖住、换完再 220ms
 * 揭开 —— 观感上是一次平滑的暗场过渡，这也是各家播放器的通用做法。
 *
 * ── 为什么是「瞬间盖住 → 缓揭开」，而不是淡入淡出 ────────────────────────────
 * 淡入那一侧**必然存在半透明的帧**：那几帧里窗口还是旧尺寸，屏幕其余部分是桌面/
 * 别的程序。只要切换恰好落在这一段里，用户就会看到别人家的界面闪一下 —— 实测抓帧
 * 就是这样（亮度从 13 跳到 240）。所以盖上这一侧**不许有过渡**：先置满不透明再显示，
 * 一次到位；只有揭开那一侧做 220ms 缓动，观感上仍然是「平滑的一次暗场」。
 */
internal object TransitionVeil {

    /** 一帧 16ms ≈ 60Hz。 */
    private const val FRAME_MS = 16L

    /**
     * 盖住 [owner] 所在显示器 → 执行 [covered] → 揭开。
     *
     * @param color 遮罩色，取主题背景色（不引入新颜色）。
     * @param holdMs 盖满之后再多等一会儿（让系统把窗口尺寸落定）。
     * @param fadeOutMs 揭开的时长（长：收尾慢，这是「高级感」的来源）。
     */
    suspend fun run(
        owner: Window,
        color: Color,
        holdMs: Int = 30,
        fadeOutMs: Int = 220,
        covered: suspend () -> Unit,
    ) {
        val screen = runCatching { owner.graphicsConfiguration.bounds }.getOrNull()
        if (screen == null) {
            covered()
            return
        }
        val veil = runCatching {
            val created = JWindow(owner)
            // 不吃焦点、不进任务栏：它只是一层 300ms 的过渡。
            // ⚠️ 是 `focusableWindowState`，不是 `isFocusableWindow` —— 后者只有 getter，
            // 在 Kotlin 里映射成只读属性，赋值直接编译不过。
            created.focusableWindowState = false
            created.background = java.awt.Color(color.toArgb(), false)
            created.bounds = screen
            created.isAlwaysOnTop = true
            created
        }.getOrNull()
        if (veil == null) {
            Log.warn("[ui] 全屏遮罩起不来，直接切换")
            covered()
            return
        }

        // ⚠️ 先把不透明度置满**再显示**：顺序反了会有一帧半透明（见类注释）。
        val translucent = runCatching { veil.opacity = 1f }.isSuccess
        veil.isVisible = true
        veil.toFront()
        runCatching { veil.opacity = 1f }

        try {
            if (holdMs > 0) delay(holdMs.toLong())
            covered()
            // 揭开前再让出一帧：Compose 的重排与 Canvas 重建都在这之后才稳。
            delay(FRAME_MS)
        } finally {
            if (translucent) {
                val steps = (fadeOutMs / FRAME_MS).toInt().coerceAtLeast(1)
                for (i in 1..steps) {
                    val t = Motion.emphasized.transform(i.toFloat() / steps)
                    runCatching { veil.opacity = (1f - t).coerceIn(0f, 1f) }
                    delay(FRAME_MS)
                }
            }
            runCatching { veil.isVisible = false }
            runCatching { veil.dispose() }
        }
        Log.info("[ui] 全屏遮罩完成（hold=${holdMs}ms out=${fadeOutMs}ms）")
    }
}
