package com.cinemahall.desktop.media

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * W2 的「后台时长解析」。
 *
 * ── 为什么不用 `Media.parsing().parse()` ────────────────────────────────────
 * 实测（本机 libVLC 3.0.21 + vlcj 4.12.1）它对 Windows 路径**恒定失败**：500 个
 * 真实 mkv，500 次 `parse` 在 52 ms 内全部返回、`info().duration()` 全是 0 ——
 * 即“立刻失败”而不是“解析超时”。换成 `file:///` MRL 也一样。
 * 这条耗时短到不可能是解码，说明失败发生在 `newMedia`/`parse` 的入口，
 * 而播放侧 `media().prepare(path)` 同一个文件却正常（Demo 与 W1 都验过）。
 *
 * 所以这里改用**已经验证可用**的路径：起一次播放，等 `status().length()` 出来。
 * 代价是每片要真的跑一遍 demux（实测约 0.4 s/片/槽位），换来的是读数可靠 ——
 * 宁可慢一点，也不要一个“全是 0 的假成功”。
 *
 * ── 两条试错记录（别再踩） ──────────────────────────────────────────────────
 * - `prepare()` 不行：它只登记媒体、**不启动 demux**，`length` 恒为 0。
 * - `:no-video` 不行（把视频轨整个禁掉，纯视频文件立刻 EOF，实测 500 部片
 *   `failed=491`）；`:vout=dummy` 无效（读数与不加选项完全一致）。
 *
 * ── 为什么要一个池子 ────────────────────────────────────────────────────────
 * 探测是阻塞的，单播放器会把 500 部片串成分钟级。池子大小 = 并发数，
 * 每部片借一个、探完归还；超时（损坏文件、网络盘）就 `stop()` 后归还，不重试。
 */
class VlcMediaProbe(
    private val factory: MediaPlayerFactory,
    poolSize: Int = 4,
    /**
     * 探测失败时回调 `(路径, 原因)`。默认丢弃。
     *
     * 为什么要这个：500 部片的门禁里出现 `failed=1` 时，如果只留一个计数，
     * 现场根本没法判断是「文件本身没有时长元数据」还是「播放器池抢不到」还是
     * 「真的解码不了」。这三类的处置完全不同（前者接受、后者要修）。
     */
    private val onFailure: (String, String) -> Unit = { _, _ -> },
) : AutoCloseable {

    private val pool = ArrayBlockingQueue<MediaPlayer>(poolSize.coerceAtLeast(1))

    init {
        repeat(poolSize.coerceAtLeast(1)) {
            val player: MediaPlayer? = runCatching {
                // `newMediaPlayer()` 是不带视频窗口的播放器（vlcj 4.12 里没有
                // `newHeadlessMediaPlayer` 这个方法名 —— 那是老版本的叫法）。
                factory.mediaPlayers().newMediaPlayer()
            }.getOrNull()
            if (player != null) pool.offer(player)
        }
    }

    /**
     * @return 毫秒；0 = 解析不出来 / 超时 / 不是可识别的媒体
     */
    fun durationMs(path: String, timeoutMs: Int = 4_000): Long {
        val player = pool.poll(2, TimeUnit.SECONDS) ?: run {
            onFailure(path, "播放器池 2 秒内没借到")
            return 0L
        }
        return try {
            // ⚠️ 必须是 play 而不是 prepare：实测 `prepare()` 只登记媒体、**不启动
            // demux**，`status().length()` 一直为 0（501 部片各等 4 秒超时，等于把扫描
            // 变成分钟级）。`play()` 才会真的打开输入，长度在几十毫秒内出来。
            // 探测完立刻 `stop()`，不会有声音/画面（这个播放器本来就没有输出窗口）。
            val started = runCatching { player.media().play(path) }.getOrDefault(false)
            if (!started) {
                onFailure(path, "media().play() 返回 false")
                return 0L
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            val began = System.nanoTime()
            var lastState = State.NOTHING_SPECIAL
            while (System.nanoTime() < deadline) {
                val length = runCatching { player.status().length() }.getOrDefault(0L)
                if (length > 0) return length
                lastState = runCatching { player.status().state() }.getOrDefault(State.NOTHING_SPECIAL)
                if (lastState == State.ERROR || lastState == State.ENDED) {
                    onFailure(path, "播放器进入 $lastState")
                    return 0L
                }
                // `STOPPED` 要早点认输：打不开的文件（实测缺 moov 原子的 mp4）会走
                // 「play() 返回 true → 立刻 STOPPED」这条路，如果只认 ERROR，
                // 每个坏文件都要白等满超时。留 400ms 宽限是因为 play() 之后状态是
                // 异步推进的，刚发出去的那一瞬间可能还是上一次遗留的 STOPPED。
                val elapsed = (System.nanoTime() - began) / 1_000_000
                if (lastState == State.STOPPED && elapsed > 400) {
                    onFailure(path, "播放器 ${elapsed}ms 后停在 STOPPED（文件打不开）")
                    return 0L
                }
                Thread.sleep(15)
            }
            onFailure(path, "${timeoutMs}ms 内 length 仍为 0（最后状态 $lastState）")
            0L
        } finally {
            runCatching { player.controls().stop() }
            pool.offer(player)
        }
    }

    override fun close() {
        while (true) {
            val player = pool.poll() ?: break
            runCatching { player.release() }
        }
    }

    private companion object {
        /**
         * 探测时**不传任何媒体选项** —— 两条试错记录都写在类头注释里了：
         * `:no-video` 会把视频轨禁掉（`failed=491`），`:vout=dummy` 完全无效。
         *
         * 顺带记一条噪声：每成功探测一片，控制台多一条
         * `direct3d11 vout display error: SetThumbNailClip failed`。那是探测播放器
         * 建 vout 时的无害报错（不受媒体选项控制），**不是**扫描失败的信号，
         * 别把它当成 `failed` 的来源去追。
         */
    }
}
