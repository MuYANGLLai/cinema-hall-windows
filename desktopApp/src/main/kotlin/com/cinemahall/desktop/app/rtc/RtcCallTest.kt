package com.cinemahall.desktop.app.rtc

import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.rtc.RtcConfig
import com.cinemahall.desktop.app.AppPaths
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.rtc.AecChain
import com.cinemahall.desktop.rtc.AudioUplink
import com.cinemahall.desktop.rtc.AudioUplinkState
import com.cinemahall.desktop.rtc.DesktopPeerLink
import com.cinemahall.desktop.rtc.ProcessAudioHelper
import com.cinemahall.desktop.rtc.SingleThreadDispatcher
import com.cinemahall.desktop.rtc.WebRtcBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.sin

/**
 * 通话门禁自检。两个模式：
 *
 * ── 真实麦克风模式（C3）────────────────────────────────────────────────────
 * A 开麦（helper 采集 → AEC → `pushAudio`），B 只收，双方打印包计数。
 * ```text
 *  t=0     （已连通）
 *  t=1     A: setCallEnabled(true)  → helper 起、开始推
 *  t≈7     （外部脚本在这一刻 kill helper，验「崩溃降级」）
 *  t=10    A: 打印 after-crash（验父进程没跟着降级）
 *  t=10    A: setCallEnabled(false) → 杀 helper（验注册表 5 秒内释放）
 *  t=16    A: setCallEnabled(true)  → 重开麦（验对端 RTP 重新增长）
 *  t=26    退出
 * ```
 * disable 到 re-enable 之间**刻意留 6 秒不碰采集**：这一段是「kill 之后多久释放麦克风」
 * 的干净观测窗口（只有 3 秒时会被重新 start 覆盖，读数作废）。
 *
 * ── 固定信号模式（C4，`--rtc-tone`）────────────────────────────────────────
 * 两端都不碰麦克风，各自推 **440 Hz 正弦**并各自开麦。这样「听到了」可以用数字证明：
 * 对端送来的 PCM 峰值必须超过阈值（`loud` 帧数 > 0），而不是靠人对着麦克风说话。
 * 「可听」这件事本身仍然是主观的，所以这里验的是**链路把有声内容送到了扬声器缓冲**。
 */
fun runRtcCallTest(role: String, port: Int, seconds: Int, tone: Boolean = false): Int {
    if (role != "a" && role != "b") {
        System.err.println("[call] 角色只能是 a 或 b，收到：$role")
        return 2
    }

    val myId = com.cinemahall.core.model.UserId("selftest-$role")
    val peerId = com.cinemahall.core.model.UserId(if (role == "a") "selftest-b" else "selftest-a")

    val dispatcher = SingleThreadDispatcher("call-rtc")
    val uplink: AudioUplink? = when {
        // 固定信号模式下两端都有上行，而且都不是麦克风。
        tone -> ToneUplink()
        role == "a" -> ProcessAudioHelper(Command.audioHelper())
        else -> null
    }
    // tone 模式**不装 AEC**：两端的「麦克风」都是正弦发生器，而 AEC3 的职责正是
    // 把「扬声器里放出来的同频信号」从近端里消掉 —— 装上之后它会把对端的正弦当回声
    // 干掉，验出来的电平就不能代表链路了。真实麦克风模式才需要它。
    val aec = if (tone || uplink == null) null else AecChain()
    val backend = WebRtcBackend()
    val link = DesktopPeerLink(backend, dispatcher, uplink = uplink, aec = aec)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val signaling = LoopbackSignaling(role, port)

    val connectedAt = AtomicLong(0)
    scope.launch {
        link.state.collect { state ->
            if (state == PeerState.Connected) connectedAt.compareAndSet(0, System.currentTimeMillis())
        }
    }
    scope.launch {
        link.messages.collect { /* 通话自检不测 DataChannel 内容 */ }
    }

    signaling.onSignal = { link.onSignal(it) }
    signaling.start()

    link.start(myId, peerId, RtcConfig(), signaling)
    log("[call] role=$role my=$myId port=$port helper=${uplink is ProcessAudioHelper} tone=$tone")

    val ok = runBlocking {
        val connected = withTimeoutOrNull(seconds * 1_000L) {
            while (connectedAt.get() == 0L) delay(100)
            true
        } ?: false
        if (!connected) return@runBlocking false

        val t0 = connectedAt.get()
        fun elapsed() = (System.currentTimeMillis() - t0) / 1000

        // 采样协程：两端都每 2 秒刷一次统计并打一行。
        val sampler = launch {
            while (true) {
                link.refreshStats()
                val audio = link.callAudio.value
                log(
                    "[call] role=$role t=${elapsed()}s sent=${audio.audioPacketsSent ?: "-"} " +
                        "received=${audio.audioPacketsReceived ?: "-"} sending=${audio.sending} " +
                        "receiving=${audio.receiving} requested=${audio.requested} " +
                        "pid=${(uplink as? ProcessAudioHelper)?.pid ?: -1} " +
                        "error=${audio.error ?: "-"} | ${backend.audioDebug()} | ${backend.audioSinkDebug()}",
                )
                delay(2_000)
            }
        }

        when {
            tone -> {
                // 两端都开麦：各自发正弦、各自收对方的正弦。
                delay(1_000)
                log("[call] tone-on@${System.currentTimeMillis()}")
                link.setCallEnabled(true)
                // 给「编码 → 传输 → 抖动缓冲 → 设备」整条链留足启动时间。
                delay(seconds * 1_000L - 4_000)
            }

            role == "a" -> {
                delay(1_000)
                log("[call] enable@${System.currentTimeMillis()}")
                link.setCallEnabled(true)
                log("[call] helper pid=${(uplink as? ProcessAudioHelper)?.pid ?: -1}")
                delay(3_000)
                log("[call] sending=${link.callAudio.value.sending}")

                // 等外部脚本 kill helper（t≈7s），并确认父进程没跟着降级。
                delay(6_000)
                val crashAudio = link.callAudio.value
                log(
                    "[call] after-crash running=${uplink?.running} state=${link.state.value} " +
                        "sending=${crashAudio.sending} error=${crashAudio.error ?: "-"}",
                )

                // 主动关麦：注册表的 LastUsedTimeStop 应该在这一刻之后 5 秒内更新。
                log("[call] disable@${System.currentTimeMillis()}")
                link.setCallEnabled(false)
                // 6 秒内什么都不做 —— 外部脚本靠这段窗口取「释放麦克风」的干净读数。
                delay(6_000)

                log("[call] re-enable@${System.currentTimeMillis()}")
                link.setCallEnabled(true)
                log("[call] helper pid=${(uplink as? ProcessAudioHelper)?.pid ?: -1}")
                // t=16 之后留到 seconds 秒结束，给「重开麦后对端是否重新收包」留出观察时间。
                delay(seconds * 1_000L - 16_000)
            }

            else -> delay(seconds * 1_000L - 1_000)
        }

        sampler.cancel()
        true
    }

    val audio = link.callAudio.value
    // C4 的硬判据在这里：`loud` 是「收到的帧里峰值超过阈值的数量」。
    // 固定信号模式下它必须 > 0，否则就是「包到了但没声音」或「根本没到」。
    val loudOk = !tone || backend.receivedLoudFrames > 0
    val line = "[call] role=$role done ok=$ok state=${link.state.value} " +
        "sent=${audio.audioPacketsSent ?: "-"} received=${audio.audioPacketsReceived ?: "-"} " +
        "sending=${audio.sending} receiving=${audio.receiving} error=${audio.error ?: "-"} | " +
        "play=${backend.playedAudioFrames} loud=${backend.receivedLoudFrames} " +
        "peak=${backend.renderedPeak} writeFail=${backend.downlinkFailures} loudOk=$loudOk"
    println(line)
    Log.info(line)

    signaling.close()
    link.close()
    Thread.sleep(300)
    dispatcher.close()
    scope.cancel()
    return if (ok && loudOk) 0 else 2
}

private fun log(line: String) {
    println(line)
    Log.info(line)
}

/**
 * 门禁用的上行源：不碰麦克风，按 10 ms 一帧推 **440 Hz 正弦**（幅度 8000 ≈ −12 dBFS）。
 *
 * 为什么需要它：`CustomAudioSource` 在没人 push 时会产出静音帧，所以「对端收到了包」
 * 不能证明「对端收到了声音」。给一个已知的信号，判据就变成「峰值超过阈值」——
 * 这是本机能给出的最强证据，不依赖房间里有没有人说话。
 *
 * 时钟用 `System.nanoTime` 累加而不是 `sleep(10)`：后者每次至少多睡一个调度粒度，
 * 60 秒能漂出好几秒，发送端会周期性欠载。
 */
private class ToneUplink(
    private val sampleRate: Int = 48_000,
    private val frequency: Double = 440.0,
    private val amplitude: Int = 8_000,
) : AudioUplink {

    @Volatile
    private var active = false

    private var thread: Thread? = null

    override val running: Boolean
        get() = thread?.isAlive == true

    override fun start(onBlock: (ByteArray) -> Unit, onState: (AudioUplinkState, String?) -> Unit): Boolean {
        if (thread?.isAlive == true) return true
        onState(AudioUplinkState.STARTING, null)
        this.active = true
        val frameBytes = sampleRate / 100 * 2
        val created = Thread({
            val block = ByteArray(frameBytes)
            var phase = 0.0
            val step = 2.0 * PI * frequency / sampleRate
            var next = System.nanoTime()
            while (active) {
                var i = 0
                while (i + 1 < frameBytes) {
                    val value = (sin(phase) * amplitude).toInt()
                    block[i] = (value and 0xFF).toByte()
                    block[i + 1] = ((value shr 8) and 0xFF).toByte()
                    phase += step
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    i += 2
                }
                onBlock(block.copyOf())
                next += 10_000_000L
                val wait = next - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else next = System.nanoTime()
            }
        }, "tone-uplink").apply {
            isDaemon = true
            start()
        }
        thread = created
        onState(AudioUplinkState.ON, null)
        return true
    }

    override fun stop() {
        active = false
        thread = null
    }

    override fun close() = stop()
}

/** helper 的命令行：打包态用同一个 exe 自举，开发态用 java -cp。 */
internal object Command {

    fun audioHelper(): List<String> {
        val self = ProcessHandle.current().info().command().orElse(null)
        if (AppPaths.packaged && !self.isNullOrBlank()) {
            // jpackage 的 runtime 里**没有 `bin\java.exe`**，只能同 exe 自举。
            return listOf(self, "--audio-helper")
        }
        val java = self ?: File(System.getProperty("java.home"), "bin/java.exe").absolutePath
        return listOf(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "com.cinemahall.desktop.app.MainKt",
            "--audio-helper",
        )
    }
}
