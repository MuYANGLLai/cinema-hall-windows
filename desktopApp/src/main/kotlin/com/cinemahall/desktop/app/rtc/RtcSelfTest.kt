package com.cinemahall.desktop.app.rtc

import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.rtc.RtcConfig
import com.cinemahall.core.rtc.RtcSignalSink
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.rtc.DesktopPeerLink
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * C2 的门禁自检：**两个真实进程**通过回环 TCP 交换 SDP/ICE，建出一条真 WebRTC 链路。
 *
 * ── 为什么自己造一条信令 ────────────────────────────────────────────────────
 * 生产信令是 `core:network` 的 `WsSignaler`（要服务端、要账号、要房间）。C2 要回答的
 * 只有一个问题：「webrtc-java + headless ADM 这条链在本机能建到 DataChannel OPEN，
 * 且全程不碰麦克风」。为了这个去起一整套服务端，会让门禁变成「服务端也能连」的复合题，
 * 出错时还得先分辨是信令还是 RTC。所以这里只做**一条只给自检用的**回环管道：
 * 换行分隔的 JSON，四类消息（offer / answer / ice / echo 请求），没有任何协议语义。
 *
 * ⚠️ 它**不是**产品路径：产品用 `WsSignaler` + `PeerCoordinator`（C5）。这个文件
 * 只在 `--rtc-selftest` 分支里被调用。
 *
 * 用法（两个终端，A 先起）：
 * ```text
 * cinema-hall.exe --rtc-selftest=a
 * cinema-hall.exe --rtc-selftest=b
 * ```
 * 两端各自打印一行 `[selftest] ...` 并退出：`0` = 通过，`2` = 超时/失败。
 */
fun runRtcSelfTest(role: String, port: Int, seconds: Int): Int {
    if (role != "a" && role != "b") {
        System.err.println("[selftest] 角色只能是 a 或 b，收到：$role")
        return 2
    }

    val myId = UserId("selftest-$role")
    val peerId = UserId(if (role == "a") "selftest-b" else "selftest-a")

    val dispatcher = SingleThreadDispatcher("selftest-rtc")
    val link = DesktopPeerLink(WebRtcBackend(), dispatcher)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val signaling = LoopbackSignaling(role, port)

    val connected = AtomicBoolean(false)
    val echoed = AtomicBoolean(false)
    val receivedFromPeer = AtomicBoolean(false)

    /** 曾经进过 `Connected`。**终态不算判据**：先跑完的那一端会主动 close，另一端随后就会看到 CLOSED。 */
    val reachedConnected = AtomicBoolean(false)

    // ⚠️ 订阅必须早于 start：`messages` 是 SharedFlow，晚订阅的消息直接丢。
    val collector = scope.launch {
        link.messages.collect { text ->
            when {
                text.startsWith("probe:") -> {
                    receivedFromPeer.set(true)
                    // 回一层就够 —— 两层会互相无限对轰（与 PeerCoordinator 的 echo 同一条纪律）。
                    link.send("echo:$text")
                }
                text.startsWith("echo:") -> echoed.set(true)
            }
        }
    }
    val stateWatcher = scope.launch {
        link.state.collect { state ->
            if (state == PeerState.Connected) {
                reachedConnected.set(true)
                if (connected.compareAndSet(false, true)) {
                    // 通道一开就双向各发一拍：只发不收证明不了「双向」。
                    link.send("probe:$myId")
                }
            }
        }
    }

    signaling.onSignal = { link.onSignal(it) }
    signaling.start()

    println("[selftest] role=$role my=$myId peer=$peerId port=$port")
    link.start(myId, peerId, RtcConfig(), signaling)

    val deadlineMs = seconds * 1_000L
    val ok = runBlocking {
        withTimeoutOrNull(deadlineMs) {
            while (!(connected.get() && echoed.get() && receivedFromPeer.get())) delay(100)
            true
        } ?: false
    }

    // 统计：C2 还没有音频轨，所以「音频 RTP 条目」应当是空的 —— 这正是「父进程没采集」的旁证。
    repeat(3) {
        link.refreshStats()
        Thread.sleep(250)
    }
    val stats = link.stats.value
    val diagnostics = link.diagnostics.value
    val state = link.state.value

    val line = "[selftest] role=$role ok=$ok reachedConnected=${reachedConnected.get()} " +
        "state=$state channel=${diagnostics.dataChannelState} " +
        "ice=${diagnostics.iceConnectionState} pc=${diagnostics.peerConnectionState} " +
        "path=${stats?.pathLabel ?: "-"} bytesSent=${stats?.bytesSent ?: "-"} " +
        "bytesReceived=${stats?.bytesReceived ?: "-"} " +
        // C2 还没有音频轨：这一格必然说「无」。它正是「父进程没采集」的旁证 ——
        // 有音频轨才可能出现 OUTBOUND_RTP，那要等 C3 把 helper 的 PCM 接进来。
        "audioTrack=${if (stats == null) "未读到统计" else "无（C2 未接 audio track）"} " +
        "echoed=${echoed.get()} heardPeer=${receivedFromPeer.get()}"
    println(line)
    // 打包态的 exe 是 GUI 子系统，**没有控制台**：`println` 会直接丢掉（W6 踩过一次）。
    // 门禁要能取数，所以同一行也进 `data/logs/app.log`。两处必须同源，别再各拼一遍。
    Log.info(line)

    collector.cancel()
    stateWatcher.cancel()
    signaling.close()
    link.close()
    Thread.sleep(300)
    dispatcher.close()
    scope.cancel()
    return if (ok) 0 else 2
}

/**
 * 只给自检用的回环信令：A 监听、B 连接，换行分隔 JSON。
 *
 * 两端都不重连、不重试：自检要么一次通，要么就是坏的 —— 加重试只会把「建链失败」
 * 变成「等了 20 秒才失败」。
 */
internal class LoopbackSignaling(
    private val role: String,
    private val port: Int,
) : RtcSignalSink, AutoCloseable {

    var onSignal: (RtcSignal) -> Unit = {}

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val closed = AtomicBoolean(false)

    /** 连接建立前的写请求先攒着（offer 可能比 B 的 connect 先算出来）。 */
    private val pending = ArrayDeque<String>()

    fun start() {
        Thread({
            runCatching {
                val connected = if (role == "a") acceptAsServer() else connectAsClient()
                socket = connected
                writer = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
                synchronized(lock) {
                    pending.forEach { writeLine(it) }
                    pending.clear()
                }
                readLoop(connected)
            }.onFailure { error ->
                System.err.println("[selftest] 信令通道结束：${error.message}")
            }
        }, "selftest-signal").apply { isDaemon = true }.start()
    }

    private fun acceptAsServer(): Socket {
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", port))
            println("[selftest] 等待对端连接 127.0.0.1:$port")
            server.soTimeout = 20_000
            return server.accept().also { println("[selftest] 对端已连接") }
        }
    }

    private fun connectAsClient(): Socket {
        repeat(20) { attempt ->
            val candidate = runCatching { Socket("127.0.0.1", port) }.getOrNull()
            if (candidate != null) {
                println("[selftest] 已连接 127.0.0.1:$port")
                return candidate
            }
            Thread.sleep(200)
            if (attempt == 19) error("连不上 127.0.0.1:$port")
        }
        error("不可达")
    }

    private fun readLoop(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        while (!closed.get()) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val payload = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue
            when (payload["k"]?.jsonPrimitive?.contentOrNull) {
                "offer" -> onSignal(RtcSignal.Offer(farSide(), payload.str("sdp")))
                "answer" -> onSignal(RtcSignal.Answer(farSide(), payload.str("sdp")))
                "ice" -> onSignal(
                    RtcSignal.Ice(
                        farSide(),
                        payload["candidate"]?.jsonPrimitive?.contentOrNull,
                        payload["sdpMid"]?.jsonPrimitive?.contentOrNull,
                        payload["sdpMLineIndex"]?.jsonPrimitive?.int,
                    ),
                )
            }
        }
    }

    private fun farSide(): UserId = UserId(if (role == "a") "selftest-b" else "selftest-a")

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun send(payload: JsonObject) {
        val line = payload.toString()
        synchronized(lock) {
            if (writer == null) pending += line else writeLine(line)
        }
    }

    private fun writeLine(line: String) {
        val out = writer ?: return
        runCatching {
            out.write(line)
            out.newLine()
            out.flush()
        }
    }

    override fun sendOffer(sdp: String) {
        send(buildJsonObject {
            put("k", "offer")
            put("sdp", sdp)
        })
    }

    override fun sendAnswer(sdp: String) {
        send(buildJsonObject {
            put("k", "answer")
            put("sdp", sdp)
        })
    }

    override fun sendIce(candidate: String?, sdpMid: String?, sdpMLineIndex: Int?) {
        // end-of-candidates 在自检里没有意义（对端也知道只可能是这一条链路），直接丢。
        if (candidate.isNullOrEmpty()) return
        send(buildJsonObject {
            put("k", "ice")
            put("candidate", candidate)
            put("sdpMid", sdpMid.orEmpty())
            put("sdpMLineIndex", sdpMLineIndex ?: 0)
        })
    }

    override fun requestRenegotiation(reason: String?) {
        // 自检不测重协商：C2 的门禁只有「建到 DataChannel OPEN + echo」。
    }

    override fun close() {
        closed.set(true)
        runCatching { writer?.close() }
        runCatching { socket?.close() }
    }
}
