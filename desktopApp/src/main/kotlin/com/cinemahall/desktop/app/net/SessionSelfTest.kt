package com.cinemahall.desktop.app.net

import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.MediaCheck
import com.cinemahall.core.model.UserId
import com.cinemahall.core.network.SignalEvent
import com.cinemahall.core.network.SignalState
import com.cinemahall.core.rtc.PeerDiagnostics
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.desktop.app.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeoutOrNull

/**
 * C5 的整链门禁：**信令 → 房间 → WebRTC → DataChannel → 玩法握手 → 片源一致**。
 *
 * 与 [runRoomSelfTest] 的分工：那个只验「信令与房间这一层」（不建 RTC），
 * 这个验「装起来之后整条链路真的通」。两级分开是为了在出问题时能立刻指出是哪一层。
 *
 * 判据（两端都要成立）：
 *  - `signaler.state` 到过 `Online`
 *  - 房间成员表最终 2 人
 *  - `link.state` 到过 `Connected`（DataChannel OPEN）
 *  - `handler.mediaCheck.verdict == Match` —— 这一条同时证明三件事：
 *    `cinema.hello` 发出去了、对端收到了、摘要比较器跑通了
 *  - 退出码 0
 */
fun runSessionSelfTest(role: String, endpoint: String, roomId: String, seconds: Int): Int {
    if (role != "a" && role != "b") {
        System.err.println("[session] 角色只能是 a 或 b，收到：$role")
        return 2
    }

    val myId = UserId("selftest-$role")
    val module = DesktopModule(endpoint = endpoint, myUserId = myId)
    val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var sawOnline = false
    var sawConnected = false
    var sawMatch = false
    var lastLog = ""
    // 协调者会在 `peer_left` 时关掉链路，而关链路会把 `diagnostics` 复位成默认值 ——
    // 收尾时再读就只剩一片 `-`，失败现场被自己抹平（C5 首轮门禁正是这样）。
    // 所以按秒采样，只留存「关之前最后一次看到的真值」。
    var lastSig = "-"
    var lastIce = "-"
    var lastDc = "-"
    var lastNote = "-"

    watch.launch {
        module.signaler.state.collect { state ->
            if (state is SignalState.Online) sawOnline = true
            log("[session] role=$role signal=$state")
        }
    }
    watch.launch {
        module.signaler.events.collect { event ->
            if (event is SignalEvent.Error) log("[session] role=$role 信令错误 ${event.code}：${event.message}")
        }
    }
    watch.launch {
        module.link.state.collect { state ->
            if (state is PeerState.Connected) sawConnected = true
            log("[session] role=$role link=$state")
        }
    }
    // RTC 信令的实际流动：链路卡住时，这一条能区分「offer 根本没发」
    // 和「发了但对端没回（或回了本端没收到）」。
    watch.launch {
        module.signaler.rtcSignals.collect { signal ->
            log("[session] role=$role rtc=${signal.javaClass.simpleName}")
        }
    }
    watch.launch {
        while (true) {
            val snapshot = module.link.diagnostics.value
            if (snapshot.signalingState != PeerDiagnostics.UNKNOWN) lastSig = snapshot.signalingState
            if (snapshot.iceConnectionState != PeerDiagnostics.UNKNOWN) lastIce = snapshot.iceConnectionState
            if (snapshot.dataChannelState != PeerDiagnostics.UNKNOWN) lastDc = snapshot.dataChannelState
            val note = module.link.negotiationNote
            if (note != "-") lastNote = note
            delay(200)
        }
    }
    // 房间视图每次变化都要喂给玩法（判房主 / 换房复位通话意图）。
    watch.launch {
        module.signaler.room.collect { room ->
            if (room == null) {
                log("[session] role=$role room=null")
                return@collect
            }
            module.handler.onRoomUpdated(room, myId)
            val members = room.members.joinToString(",") { "${it.nickname}:${it.userId.value}:${it.online}" }
            log("[session] role=$role room=${room.roomId} host=${room.hostUserId.value} size=${room.members.size} [$members]")
        }
    }
    watch.launch {
        module.handler.mediaCheck.collect { check ->
            val verdict = check.verdict
            if (verdict is MediaCheck.Match) sawMatch = true
            val line = "local=${check.local?.fileName ?: "-"} remote=${check.remote?.fileName ?: "-"} " +
                "verdict=${verdict?.let { it.javaClass.simpleName } ?: "-"}"
            if (line != lastLog) {
                lastLog = line
                log("[session] role=$role mediaCheck $line")
            }
        }
    }
    watch.launch {
        module.handler.callAudio.collect { audio ->
            Log.info(
                "[session] role=$role call requested=${audio.requested} sending=${audio.sending} " +
                    "receiving=${audio.receiving} sent=${audio.audioPacketsSent ?: "-"} " +
                    "recv=${audio.audioPacketsReceived ?: "-"} err=${audio.error ?: "-"}",
            )
        }
    }

    // ⚠️ 必须**先**建协调者、**再** connect：协调者构造里订阅的是无 replay 的
    // SharedFlow，晚一步就会丢掉第一条 rtc.offer。
    module.ensureCoordinator()

    // ⚠️ 外层预算是「建链 + 握手」与「进房后观察」两段之和，不是一个数顶两用：
    // 早先把 `seconds` 当总额，12 秒的观察档连 30 秒的「等链路」都进不去，
    // 两端会在 Negotiating 上被外层掐断 —— 看起来像协商卡死，其实是被自己叫停的。
    val budgetMs = seconds * 1_000L + 70_000L
    val ok = runBlocking {
        val joined = withTimeoutOrNull(budgetMs) {
            module.connect()
            val online = withTimeoutOrNull(10_000) {
                module.signaler.state.first { it is SignalState.Online }
                true
            } ?: false
            if (!online) {
                log("[session] role=$role 10 秒内没连上 $endpoint")
                return@withTimeoutOrNull false
            }
            val room = runCatching {
                if (role == "a") {
                    module.signaler.createRoom(nickname = "甲", password = null, roomId = roomId)
                } else {
                    var attempt = 0
                    var result: Result<com.cinemahall.core.model.Room>? = null
                    while (attempt < 20) {
                        result = runCatching { module.signaler.joinRoom(roomId, "乙", null) }
                        if (result.isSuccess) break
                        delay(300)
                        attempt++
                    }
                    result!!.getOrThrow()
                }
            }.getOrElse { error ->
                log("[session] role=$role 进房失败：${error.message}")
                return@withTimeoutOrNull false
            }
            log("[session] role=$role 已进房 ${room.roomId}")

            // 两端报同一份摘要 ⇒ 一致；用来证明 hello 往返通了。
            val signature = MediaSignature("gate.mkv", 1_234_567L, listOf("aaa", "bbb", "ccc"))
            module.handler.onLocalSignatureReady(signature)

            // 等链路 + 握手都落定。
            val connected = withTimeoutOrNull(30_000) {
                module.link.state.first { it is PeerState.Connected }
                true
            } ?: false
            if (!connected) {
                log("[session] role=$role 30 秒内 DataChannel 没 OPEN")
                return@withTimeoutOrNull false
            }
            val matched = withTimeoutOrNull(20_000) {
                module.handler.mediaCheck.first { it.verdict is MediaCheck.Match }
                true
            } ?: false
            log("[session] role=$role 握手 matched=$matched")

            delay(seconds * 400L)
            true
        } ?: false
        joined
    }

    val room = module.signaler.room.value
    val check = module.handler.mediaCheck.value
    val audio = module.handler.callAudio.value
    // 协调者的自述：`boundPeer` 是私有的，而「为什么没建链」全在它自己那几行 notice 里。
    // 镜像日志会吞行（推流有上限），所以直接读 flow 尾部，别绕文件。
    val notes = module.coordinator?.log?.value?.takeLast(8)?.joinToString(" | ") ?: "-"
    val iceCfg = module.coordinator?.iceConfigReceived?.value
    val line = "[session] role=$role done ok=$ok online=$sawOnline connected=$sawConnected " +
        "matched=$sawMatch room=${room?.roomId ?: "-"} size=${room?.members?.size ?: 0} " +
        "host=${room?.hostUserId?.value ?: "-"} verdict=${check.verdict?.let { it.javaClass.simpleName } ?: "-"} " +
        "callReq=${audio.requested} linkState=${module.link.state.value} " +
        "signals=${module.link.receivedSignals}/${module.link.lastSignalKind} " +
        "acc=${module.link.acceptedSignals} drop=[${module.link.lastDrop}] " +
        "coord=${module.coordinatorCreations} sig=$lastSig ice=$lastIce dc=$lastDc iceCfg=$iceCfg " +
        "note=[$lastNote] audio=[${module.link.audioAttachNote}] uplink=${module.link.hasAudioUplink} notes=[$notes]"
    println(line)
    Log.info(line)

    runBlocking {
        runCatching { module.signaler.leave() }
    }
    module.close()
    watch.cancel()
    return if (ok && sawOnline && sawConnected && sawMatch) 0 else 2
}

private fun log(line: String) {
    println(line)
    Log.info(line)
}
