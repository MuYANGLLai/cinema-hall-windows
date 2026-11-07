package com.cinemahall.desktop.media

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.awt.Canvas
import java.awt.Color
import java.io.File
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * 从视频里抽一帧当封面（影片库的「封面」）。
 *
 * ── 为什么必须有一个真实窗口 ────────────────────────────────────────────────
 * 抽帧要解码，而 `snapshot` 取的是 **vout 的 framebuffer**：没有视频输出就没有帧。
 * 所以这里开一个 640×360 的 `JWindow` 托着 `Canvas`：
 *  - **必须在屏幕内**：放到 (-32000,-32000) 时 Windows 认为它不在任何显示器上，
 *    vout 不出帧（实测 `snapshot` 返回 true 但图是纯黑 640×360）。
 *  - `opacity = 0.01` 把它藏起来：窗口仍是「可见」的（vout 正常工作），人眼几乎看不出。
 *
 * ── 为什么不用 `--video-filter=scene` ───────────────────────────────────────
 * 试过：在那个独立 factory 上 scene 过滤器一个文件都没写出来（残留=0），
 * 而同一个窗口 + 同一个 vout 用 `snapshot` 能稳定出图。所以回到 snapshot，
 * 靠「等够时间 + 回读时间轴」保证截的是 seek 之后的画面。
 *
 * ── 线程 ────────────────────────────────────────────────────────────────────
 * [capture] **阻塞**（约 4–6 秒/部），只能在后台线程调用；内部串行（`synchronized`）。
 */
class VlcThumbnailer(
    private val factory: MediaPlayerFactory,
    private val widthPx: Int = 640,
    private val heightPx: Int = 360,
) {

    fun capture(path: String, out: File): Boolean = synchronized(this) {
        var holder: JWindow? = null
        var player: uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer? = null
        try {
            val canvas = Canvas().apply { background = Color.BLACK }
            holder = JWindow().apply {
                isAlwaysOnTop = false
                focusableWindowState = false
                setBounds(0, 0, widthPx, heightPx)
                runCatching { opacity = 0.01f }
                contentPane.add(canvas)
            }
            SwingUtilities.invokeAndWait { holder.isVisible = true }

            player = factory.mediaPlayers().newEmbeddedMediaPlayer()
            player.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
            if (!player.media().prepare(path)) return false
            player.controls().play()

            // 1) 等 vout 建起来（`setTime` 在 OPENING 阶段会被静默忽略）
            if (!await(player, 5_000) { it.video().videoDimension() != null }) return false

            // 2) 用播放器自己的时长决定截哪一帧（比 `media.info().duration()` 可靠：
            //    后者在未 parse 时返回 0，实测所有片都退化成「截片头」）。
            val length = player.status().length()
            val atMs = if (length > 0) {
                minOf((length * 0.15).toLong(), 480_000L).coerceAtLeast(5_000L)
            } else {
                0L
            }

            // 3) seek + **等画面真的换过去**：只看 `time()` 会误判（它瞬间就到目标值），
            //    所以「时间轴到位」之后再固定等 1.2 秒给解码器与 vout 时间。
            if (atMs > 0) {
                player.controls().setTime(atMs)
                if (await(player, 6_000) { it.status().time() >= atMs * 0.9 }) {
                    Thread.sleep(1_200)
                } else {
                    // seek 没成：退到片头 5 秒（那里通常有 logo，至少不是纯黑）
                    player.controls().setTime(5_000)
                    Thread.sleep(900)
                }
            } else {
                Thread.sleep(1_000)
            }

            out.parentFile?.mkdirs()
            if (out.isFile) runCatching { out.delete() }
            return player.snapshots().save(out) && out.isFile && out.length() > 0
        } catch (e: Throwable) {
            return false
        } finally {
            runCatching { player?.controls()?.stop() }
            runCatching { player?.release() }
            val window = holder
            if (window != null) runCatching { SwingUtilities.invokeAndWait { window.dispose() } }
        }
    }

    private fun await(
        player: uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer,
        timeoutMs: Long,
        condition: (uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer) -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { condition(player) }.getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }

    fun release() {
        runCatching { factory.release() }
    }
}
