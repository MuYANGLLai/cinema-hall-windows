package com.cinemahall.desktop.rtc

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
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
    /**
     * 开扬声器的方式。生产环境用 [AudioDeviceFactory.System]，测试里换成假设备。
     *
     * 之所以要有这条接缝：本类最容易出错的不是「写」而是「什么时候开、开几次」——
     * 而那条路径只有靠**数开设备次数**才能钉住（见 [openFailedAt]）。
     */
    private val devices: AudioDeviceFactory = AudioDeviceFactory.System,
) : AutoCloseable {

    private val queue = ArrayBlockingQueue<ByteArray>(capacity)

    @Volatile
    private var line: AudioDevice? = null

    @Volatile
    private var playThread: Thread? = null

    @Volatile
    private var closed = false

    /** 0..1。播放线程每帧读一次，所以改音量立刻生效（不需要重开设备）。 */
    @Volatile
    private var gain = 1f

    /**
     * 上一次**协商成功**的**上游**格式（`sampleRate/channels`）。
     *
     * ⚠️ 这里记的必须是「对端发来的那个格式」，**不是**设备实际开出来的声道数。
     * 0.1.17 之前记的是后者：对端发单声道、设备退让成 2 声道打开之后，`formatKey`
     * 变成 `48000/2`，而上游下一帧算出来的还是 `48000/1` —— 两边**每一帧都不等**，
     * 于是每 10 ms 就 `close()` 再 `open()` 一次，一秒一百次。
     * 那是「对端一说话，影片声音就一卡一卡」的直接成因（同一端点上的 VLC 流跟着顿）。
     */
    @Volatile
    private var negotiatedKey = ""

    /** 空闲时写的静音帧（长度 = 一帧）。见 [playLoop] 的说明。 */
    @Volatile
    private var silence: ByteArray = ByteArray(0)

    /**
     * 开设备失败的时刻（ms）。失败后 1 秒内不再重试。
     *
     * ⚠️ 没有这个冷却，`write()` 会在**每一帧**重新 `open()`：`formatKey` 一直是空串，
     * 于是一秒钟开/关设备 100 次。那不只是白费 CPU —— 在部分 Windows 驱动上，
     * 反复抢占同一块声卡会把**别的**流（比如影片的声音）顶掉，听感是「对方说话时
     * 影片声音一卡一卡」。用户 2026-11-08 报的就是这个形态。
     */
    @Volatile
    private var openFailedAt = 0L

    /** 诊断用：真正选中的 mixer / 格式 / 缓冲。开不起来时是失败原因。 */
    @Volatile
    var deviceNote: String = "-"
        private set

    /** 上游送进来的帧里，因为队列满被丢掉的数量。 */
    @Volatile
    var droppedFrames = 0L
        private set

    /** 真正写进设备的帧数。 */
    @Volatile
    var playedFrames = 0L
        private set

    /** 空闲时补写的静音帧数（正常流不应该靠它，多了说明上游在断续）。 */
    @Volatile
    var silenceFrames = 0L
        private set

    /**
     * 真正去「开扬声器」的次数。
     *
     * 这是判断「有没有反反复复抢占声卡」的唯一可靠计数：正常一整通电话只应该是
     * **个位数**。它要是跟着秒数往上涨，同端点上的影片声音就会一卡一卡。
     */
    @Volatile
    var openAttempts = 0L
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
        if (key != negotiatedKey && !openDevice(sampleRate, channels, key)) return false

        val frame = pcm.copyOf()
        if (!queue.offer(frame)) {
            // 丢最旧的：实时语音里「晚到的正确」比「丢掉的旧」更糟。
            queue.poll()
            droppedFrames++
            queue.offer(frame)
        }
        return true
    }

    /**
     * 开一块扬声器。
     *
     * ── 为什么按「主的 → 立体声 → 单声道」依次试 ──────────────────────────────
     * 设备的共享混音格式通常是 48 kHz **立体声**。若我们坚持按对端给的
     * **单声道**开流，Windows 每次都要为这条流插一层重采样；而某些驱动在被要求
     * 换格式时会短暂重置整块端点 —— 同一块声卡上别的流（影片）就断一下。
     * 先试上游给的声道数，再试 2，最后才退 1，命中率最高的那种组合先上。
     */
    private fun openDevice(sampleRate: Int, channels: Int, key: String): Boolean {
        // 刚失败过：这一秒内别再碰设备（见 [openFailedAt]）。
        val now = System.currentTimeMillis()
        if (openFailedAt != 0L && now - openFailedAt < OPEN_RETRY_COOLDOWN_MS) return false

        closeDevice()
        var lastError: String? = null
        val order = if (channels == 2) listOf(2, 1) else listOf(channels, 2, 1)
        for (ch in order.distinct()) {
            val format = AudioFormat(sampleRate.toFloat(), 16, ch, true, false)
            // 100 ms 的设备缓冲：太小会在 Windows 上被调度抖动打断，太大则关麦后还会响一下。
            val bufferBytes = sampleRate / 10 * 2 * ch
            openAttempts++
            val opened = runCatching { devices.open(format, bufferBytes) }
            val created = opened.getOrNull()
            if (created == null) {
                lastError = opened.exceptionOrNull()?.message ?: "拿不到 SourceDataLine"
                continue
            }
            line = created
            // ⚠️ 记**上游**的 key（见 [negotiatedKey]），不是下面这个实际声道数。
            negotiatedKey = key
            silence = ByteArray(sampleRate / 100 * 2 * ch)
            openFailedAt = 0L
            deviceNote = "sr=$sampleRate ch=$ch buf=${created.bufferSize}B"
            System.err.println("[rtc] 下行设备已打开：$deviceNote")
            if (playThread?.isAlive != true) {
                playThread = Thread({ playLoop() }, "audio-downlink").apply {
                    isDaemon = true
                    start()
                }
            }
            return true
        }
        openFailedAt = System.currentTimeMillis()
        deviceNote = "开不起来：${lastError ?: "-"}"
        System.err.println("[rtc] 下行设备打不开（按 $order 都试过）：$lastError")
        return false
    }

    private fun playLoop() {
        while (!closed) {
            val frame = runCatching { queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val device = line ?: continue
            if (frame == null) {
                // ── 空闲时也把静音写进去（2026-11-08）────────────────────────────
                // 原来空闲就什么都不写，设备于是反复 underrun（STOP/START 事件）。
                // 在部分 Windows 驱动上，一条流反复 start/stop 会让**整块端点**
                // 跟着顿一下 —— 表现为「对端说话间隙，影片声音一卡一卡」。
                // 保持连续供数就没有这个边界事件。
                val quiet = silence
                if (quiet.isEmpty()) continue
                if (writeAll(device, quiet)) silenceFrames++ else resetDevice()
                continue
            }
            val out = applyGain(frame)
            // 设备被拔掉 / 被独占时会抛，不能让它打死播放线程（否则重插也回不来）。
            if (!writeAll(device, out)) {
                resetDevice()
                continue
            }
            playedFrames++
            onRender?.invoke(out)
        }
    }

    /** 写完一整块（`write` 可能只写一部分）。失败返回 false，由调用方重置设备。 */
    private fun writeAll(device: AudioDevice, data: ByteArray): Boolean = runCatching {
        var offset = 0
        while (offset < data.size) {
            val n = device.write(data, offset, data.size - offset)
            if (n <= 0) return@runCatching false
            offset += n
        }
        true
    }.getOrDefault(false)

    /**
     * 丢掉当前设备。**也进冷却**：写失败通常意味着设备被拔掉/被别的程序独占，
     * 此时每一帧立刻重开就是「一秒一百次抢占声卡」的另一条路径。
     */
    private fun resetDevice() {
        closeDevice()
        negotiatedKey = ""
        silence = ByteArray(0)
        if (openFailedAt == 0L) openFailedAt = System.currentTimeMillis()
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

    private companion object {
        /** 空闲时的轮询间隔：等于一帧的长度，保证静音补得上。 */
        const val IDLE_POLL_MS = 10L

        /** 开设备失败后的冷却：这一秒内不再碰设备。 */
        const val OPEN_RETRY_COOLDOWN_MS = 1_000L
    }
}

/**
 * 一块已经打开的扬声器。只留播放线程真正用得到的三个动作。
 *
 * 抽成接口是为了让「开几次设备」「空闲时到底写不写」这两件事能在没有声卡的
 * 构建机上被测试钉住（本机 CI 与开发机都可能没有可用输出设备）。
 */
interface AudioDevice {
    val bufferSize: Int

    /** 返回真正写出去的字节数；设备坏了就抛。 */
    fun write(data: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/** 开一块扬声器。生产实现见 [System]。 */
fun interface AudioDeviceFactory {

    /** 失败就抛 —— 调用方靠异常区分「这块设备开不起来」。 */
    fun open(format: AudioFormat, bufferBytes: Int): AudioDevice

    companion object {
        /** 真的 `javax.sound.sampled` 设备。 */
        val System: AudioDeviceFactory = AudioDeviceFactory { format, bufferBytes ->
            val info = DataLine.Info(SourceDataLine::class.java, format, bufferBytes)
            val line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format, bufferBytes)
            line.start()
            object : AudioDevice {
                override val bufferSize: Int get() = line.bufferSize
                override fun write(data: ByteArray, offset: Int, length: Int): Int =
                    line.write(data, offset, length)

                override fun close() {
                    runCatching { line.stop() }
                    runCatching { line.flush() }
                    runCatching { line.close() }
                }
            }
        }
    }
}
