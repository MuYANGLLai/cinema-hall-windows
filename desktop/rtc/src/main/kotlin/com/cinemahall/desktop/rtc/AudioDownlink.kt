package com.cinemahall.desktop.rtc

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * 远端语音自播（W7 §5 R2）：`javax.sound.sampled` 直接写扬声器，**不走** webrtc-java 的 ADM。
 *
 * ── 为什么不用 ADM 放音 ────────────────────────────────────────────────────
 * Phase 0 实测（`docs/Windows-音频独立进程-设计.md` §6）：Windows 的音频会话绑在进程上，
 * webrtc-java 的 ADM 在 PeerConnection 存活期间不会真正释放设备。下行若也交给 ADM，
 * 「关麦之后扬声器还被占着」就会变成第二个查不清的问题。这里只要「把 PCM 写出去 +
 * 能调音量」，`SourceDataLine` 就够，而且关掉立刻放开。
 *
 * ── 三条线程纪律（违反任何一条都会表现为「对端语音卡顿」）──────────────────
 * 1. [write] 在 **libwebrtc 的音频线程**上被调用，必须**立刻返回**：只入队，不碰设备。
 * 2. 队列满时**丢最旧的一帧**并计数，不阻塞上游：卡住上游会同时拖垮 AEC 的参考对齐。
 * 3. `SourceDataLine.write` 会阻塞（设备缓冲满时），所以它只出现在自己的播放线程上。
 *
 * ── 设备格式 ──────────────────────────────────────────────────────────────
 * 按远端实际给的 `sampleRate` / `channels` 开设备。开不起来（没有扬声器、被独占、
 * 采样率不支持）时 [write] 返回 false —— 上层据此说「本机放不出远端语音」，
 * 而不是让用户对着静音猜。
 */
class AudioDownlink(
    /** 队列容量（帧）。100 帧 × 10 ms ≈ 1 秒的缓冲。 */
    private val capacity: Int = 100,
) : AutoCloseable {

    private val queue = ArrayBlockingQueue<ByteArray>(capacity)

    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var playThread: Thread? = null

    @Volatile
    private var closed = false

    /** 0..1。播放线程每帧读一次，所以改音量立刻生效（不需要重开设备）。 */
    @Volatile
    private var gain = 1f

    /** 当前设备格式（`sampleRate/channels`）。变化时重开设备。 */
    @Volatile
    private var formatKey = ""

    /** 上游送进来的帧里，因为队列满被丢掉的数量。 */
    @Volatile
    var droppedFrames = 0L
        private set

    /** 真正写进设备的帧数。 */
    @Volatile
    var playedFrames = 0L
        private set

    /**
     * 每写出一帧就回调一次，**在播放线程上**。
     *
     * AEC 的参考信号必须按「扬声器实际出声」的时序喂，收到就喂会整体提前一个
     * 设备缓冲的时长（AEC3 会把它当成延迟算错）。
     */
    @Volatile
    var onRender: ((ByteArray) -> Unit)? = null

    val running: Boolean
        get() = playThread?.isAlive == true

    fun setVolume(value: Float) {
        gain = value.coerceIn(0f, 1f)
    }

    /**
     * 送一帧远端 PCM。**任意线程、永不阻塞。**
     *
     * @return false = 本机放不出来（没设备 / 采样率不支持 / 已关闭）。只返回状态，不抛异常。
     */
    fun write(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        if (closed) return false
        // 16-bit 是 WebRTC 的交付格式；真出了别的位深说明上游变了，别猜，直接拒。
        if (bitsPerSample != 16 || sampleRate <= 0 || channels !in 1..2) return false

        val key = "$sampleRate/$channels"
        if (key != formatKey && !openDevice(sampleRate, channels, key)) return false

        val frame = pcm.copyOf()
        if (!queue.offer(frame)) {
            // 丢最旧的：实时语音里「晚到的正确」比「丢掉的旧」更糟。
            queue.poll()
            droppedFrames++
            queue.offer(frame)
        }
        return true
    }

    private fun openDevice(sampleRate: Int, channels: Int, key: String): Boolean {
        closeDevice()
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        // 100 ms 的设备缓冲：太小会在 Windows 上被调度抖动打断，太大则关麦后还会响一下。
        val bufferBytes = sampleRate / 10 * 2 * channels
        val created = runCatching {
            AudioSystem.getSourceDataLine(format).apply {
                open(format, bufferBytes)
                start()
            }
        }.getOrNull() ?: return false

        line = created
        formatKey = key
        if (playThread?.isAlive != true) {
            playThread = Thread({ playLoop() }, "audio-downlink").apply {
                isDaemon = true
                start()
            }
        }
        return true
    }

    private fun playLoop() {
        while (!closed) {
            val frame = runCatching { queue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            val device = line ?: continue
            val out = applyGain(frame)
            // 设备被拔掉 / 被独占时会抛，不能让它打死播放线程（否则重插也回不来）。
            val ok = runCatching { device.write(out, 0, out.size); true }.getOrDefault(false)
            if (!ok) {
                closeDevice()
                formatKey = ""
                continue
            }
            playedFrames++
            onRender?.invoke(out)
        }
    }

    private fun applyGain(pcm: ByteArray): ByteArray {
        val current = gain
        if (current >= 0.999f) return pcm
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            // 16-bit 小端（AudioFormat 的 bigEndian=false）
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val scaled = (sample * current).toInt().coerceIn(-32768, 32767)
            out[i] = (scaled and 0xFF).toByte()
            out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun closeDevice() {
        val current = line ?: return
        line = null
        runCatching { current.stop() }
        runCatching { current.flush() }
        runCatching { current.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        playThread?.interrupt()
        playThread = null
        queue.clear()
        closeDevice()
    }
}
