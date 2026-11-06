package com.cinemahall.desktop.rtc

import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig

/**
 * 本机音频处理链（W7 C3）：**AEC3 + NS + AGC + 高通**，自己跑，不靠 ADM。
 *
 * ── 为什么必须自己跑 ────────────────────────────────────────────────────────
 * `CustomAudioSource` 推的 PCM **不经过** WebRTC 内部的 APM（官方 Custom Audio Source
 * 文档明说）。父进程又是 headless ADM（没有采集，也就没有内部处理链）。所以：
 * 不自己跑 AEC，外放时对端就会听到自己的回声，而且**没有任何报错**。
 *
 * ── 两条流的方向别搞反 ──────────────────────────────────────────────────────
 * - `processReverseStream`：**参考信号** = 本机正在放出来的声音（对端语音，C4 才有）。
 *   AEC3 靠它预测「麦克风会收到什么」。
 * - `processStream`：**近端信号** = 麦克风采到的 PCM，处理完才 `pushAudio`。
 *
 * 顺序必须是「先喂参考、再处理近端」：同一帧的参考先进入 AEC 的内部缓冲，
 * 近端处理时才有东西可比。反了不会报错，只会表现为「AEC 好像没生效」。
 *
 * ── 帧长是硬的 ──────────────────────────────────────────────────────────────
 * 48 kHz / 单声道 / 16-bit 下，10 ms = **960 字节**。APM 只吃 10 ms 帧；
 * 长度不对时这里**原样透传**（而不是崩）：采集格式变了也不该把通话打死，
 * 代价只是那一段没有回声消除。
 */
class AecChain(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
) : AutoCloseable {

    private val frameBytes = sampleRate / 100 * 2 * channels
    private val config = AudioProcessingStreamConfig(sampleRate, channels)
    private val processing = AudioProcessing()
    private val output = ByteArray(frameBytes)

    /** 参考流的输出缓冲：`processReverseStream` 要一个 dst，内容用不上，但别每帧新建。 */
    private val renderOut = ByteArray(frameBytes)

    /** 没有远端声音时也要喂静音参考：APM 需要「这一帧没有渲染」这个事实，而不是被跳过。 */
    private val silence = ByteArray(frameBytes)

    /** 自上次处理近端以来有没有喂过参考（AEC3 要求两条流**同帧数**）。 */
    private var renderFed = false

    @Volatile
    private var closed = false

    init {
        runCatching {
            val cfg = AudioProcessingConfig().apply {
                echoCanceller.enabled = true
                noiseSuppression.enabled = true
                noiseSuppression.level = AudioProcessingConfig.NoiseSuppression.Level.HIGH
                gainControl.enabled = true
                highPassFilter.enabled = true
            }
            processing.applyConfig(cfg)
        }
    }

    /**
     * 喂一帧「本机正在播出来的声音」作为 AEC 参考（C4 从远端音轨的 sink 拿）。
     * 长度不符或还没接下行时，用静音代替。
     */
    fun feedRender(pcm: ByteArray) {
        if (closed) return
        val frame = if (pcm.size == frameBytes) pcm else silence
        runCatching {
            processing.processReverseStream(frame, config, config, renderOut)
        }
        renderFed = true
    }

    fun feedSilenceRender() = feedRender(silence)

    /**
     * 处理一帧采集数据（960 字节）。返回处理后的新数组；长度不符时原样返回入参。
     *
     * ⚠️ 调用方必须**单线程**、按帧序调用（helper 的读取线程天然满足）。
     */
    fun capture(pcm: ByteArray): ByteArray {
        if (closed || pcm.size != frameBytes) return pcm
        // AEC3 靠「参考帧 ↔ 近端帧」的帧数对齐来估延迟。C3 还没有下行（没人喂参考），
        // 不补静音的话 libwebrtc 会认定这台机器没有 render 信号、把回声消除关掉 ——
        // 而且不报错。每帧补一帧静音参考，C4 接上下行之后自然接管。
        if (!renderFed) feedSilenceRender()
        renderFed = false
        val result = runCatching {
            val code = processing.processStream(pcm, config, config, output)
            if (code == 0) output.copyOf() else null
        }.getOrNull()
        return result ?: pcm
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { processing.dispose() }
    }
}
