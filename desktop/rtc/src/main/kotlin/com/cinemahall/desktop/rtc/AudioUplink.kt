package com.cinemahall.desktop.rtc

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
enum class AudioUplinkState { OFF, STARTING, ON, FAILED }

/**
 * 上行采集（W7 C3）：把「麦克风 PCM」这一件事抽象出来。
 *
 * 为什么要一个接口：门禁（C3）要能在**没有麦克风的 CI 机器**上跑状态机，
 * 而生产实现是「起一个子进程去采集」。接口把这两件事分开，[DesktopPeerLink] 只认
 * 「能不能开始推 PCM」。
 */
interface AudioUplink : AutoCloseable {

    /**
     * 开始采集。[onBlock] 会在**读取线程**上被调用，块长固定 960 字节（48k/16/1ch，10 ms）。
     *
     * @return true = 已经采到第一块（真的在推）；false = 起不来或超时
     */
    fun start(onBlock: (ByteArray) -> Unit, onState: (AudioUplinkState, String?) -> Unit): Boolean

    fun stop()

    val running: Boolean
}

/**
 * [`--audio-helper`] 子进程的控制器（W7 C3）。
 *
 * ── 为什么必须是**子进程** ──────────────────────────────────────────────────
 * Phase 0 实测（`docs/Windows-音频独立进程-设计.md` §6）：Windows 的麦克风使用记录
 * 绑在**进程**的音频会话上，webrtc-java 的 ADM 在 PC 存活期间不会真正停录 ——
 * 软件层面任何「停采集」都不作数。只有 kill 掉承载采集的进程，注册表的
 * `LastUsedTimeStop` 才会更新（实测 6–139 ms）。
 *
 * ── 命令由调用方给 ──────────────────────────────────────────────────────────
 * 打包态是 `<exe> --audio-helper`（jpackage 的 runtime 里**没有 `bin\java.exe`**，
 * 不能用 `java -cp`）；开发态是 `java -cp <classpath> MainKt --audio-helper`。
 * 这个差异属于装配点，不属于控制器。
 *
 * ── 崩溃后的重试 ────────────────────────────────────────────────────────────
 * 按设计文档 §4：最多 3 次，间隔 500 / 1000 / 2000 ms。仍失败就停在 [AudioUplinkState.FAILED]，
 * 等用户手动再点一次 —— 无限重试会变成看不见的崩溃循环，把日志刷满。
 */
class ProcessAudioHelper(
    private val command: List<String>,
    private val blockBytes: Int = 960,
    private val firstBlockTimeoutMs: Long = 2_500,
    private val maxAttempts: Int = 3,
) : AudioUplink {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var stopping = false

    override val running: Boolean
        get() = process?.isAlive == true

    /** 当前子进程 pid（门禁用：要能在外部 kill 它）。 */
    @Volatile
    var pid: Long = -1
        private set

    override fun start(
        onBlock: (ByteArray) -> Unit,
        onState: (AudioUplinkState, String?) -> Unit,
    ): Boolean {
        stopping = false
        var attempt = 1
        while (attempt <= maxAttempts) {
            onState(AudioUplinkState.STARTING, "第 $attempt 次尝试")
            val firstBlock = CountDownLatch(1)
            val spawned = runCatching { spawn(onBlock, onState, firstBlock) }.getOrNull()
            if (spawned == null) {
                onState(AudioUplinkState.FAILED, "起不来：${command.firstOrNull() ?: "?"}")
                return false
            }

            val gotAudio = firstBlock.await(firstBlockTimeoutMs, TimeUnit.MILLISECONDS)
            if (gotAudio) {
                onState(AudioUplinkState.ON, null)
                return true
            }

            // 起来了但 2.5 s 没有 PCM：可能是设备被独占 / 权限被拒。杀掉重试。
            onState(AudioUplinkState.FAILED, "启动后 ${firstBlockTimeoutMs}ms 没有收到 PCM")
            kill()
            if (attempt == maxAttempts) return false
            Thread.sleep(500L * (1 shl (attempt - 1)))
            attempt += 1
        }
        return false
    }

    private fun spawn(
        onBlock: (ByteArray) -> Unit,
        onState: (AudioUplinkState, String?) -> Unit,
        firstBlock: CountDownLatch,
    ): Process {
        val builder = ProcessBuilder(command)
            // PCM 走 stdout（**一个字节的文本都不能混进去**）；helper 的日志一律 stderr。
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        val created = builder.start()
        process = created
        pid = runCatching { created.pid() }.getOrDefault(-1L)

        reader = Thread({ readLoop(created, onBlock, onState, firstBlock) }, "audio-helper-reader")
            .apply { isDaemon = true }
            .also { it.start() }
        return created
    }

    private fun readLoop(
        source: Process,
        onBlock: (ByteArray) -> Unit,
        onState: (AudioUplinkState, String?) -> Unit,
        firstBlock: CountDownLatch,
    ) {
        val input: InputStream = source.inputStream
        val buffer = ByteArray(blockBytes)
        var blocks = 0L
        try {
            while (!stopping) {
                var read = 0
                while (read < blockBytes) {
                    val n = input.read(buffer, read, blockBytes - read)
                    if (n < 0) return@readLoop
                    read += n
                }
                blocks += 1
                onBlock(buffer.copyOf())
                if (blocks == 1L) firstBlock.countDown()
            }
        } catch (error: Throwable) {
            if (!stopping) onState(AudioUplinkState.FAILED, "读取 PCM 失败：${error.message}")
        } finally {
            // 只报**当前**这个子进程的结束：`kill()` 之后 `process` 已经置 null，
            // 而重新开麦时旧 reader 线程可能晚一步才读到 EOF —— 不设这道闸，旧的
            // EOF 会把刚起来的新会话打成 FAILED（表现为「一开麦就报错但声音正常」）。
            if (!stopping && process === source) {
                // 采集进程自己没了（被杀 / 崩）。父进程**不跟着死**，也不重连 PC。
                onState(AudioUplinkState.FAILED, "采集进程已结束（读了 $blocks 块）")
            }
            runCatching { input.close() }
        }
    }

    override fun stop() {
        stopping = true
        kill()
    }

    private fun kill() {
        val target = process ?: return
        runCatching { target.destroyForcibly() }
        runCatching { target.waitFor(2, TimeUnit.SECONDS) }
        process = null
        reader = null
    }

    override fun close() = stop()
}
