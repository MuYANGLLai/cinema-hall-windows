package com.cinemahall.desktop.app.net

import com.cinemahall.core.model.UserId
import com.cinemahall.core.network.SignalEvent
import com.cinemahall.core.network.SignalState
import com.cinemahall.core.network.WsSignaler
import com.cinemahall.desktop.app.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * C5 的地基自检：**只验信令与房间，不建 WebRTC 链路**。
 *
 * 为什么要单独一格：C5 的装配一旦出问题，「连不上服务端」「建房失败」「成员表不对」
 * 「RTC 建不起来」四种故障在界面上长得几乎一样。把第一层单独钉死之后，
 * 上层出问题就只剩后两种。
 *
 * 用法（固定房号，两个进程各占一个角色）：
 * ```text
 *  --room-test=a  建房（房号取自 --room-id），打印每一次成员变化
 *  --room-test=b  加入同一房号，打印自己看到的 hostUserId
 * ```
 *
 * 判据：
 *  - 两端 `signalState` 到过 `Online`；
 *  - a 的 `members` 出现 2 人、且 b 的 `online=true`；
 *  - b 读到的 `hostUserId` 等于 a 的 userId（主控权判据必须来自服务端，不能猜）；
 *  - b 离开后 a 看到它 `online=false`（**不是**成员数回到 1：座位是保留的，见 §5.4）。
 */
fun runRoomSelfTest(role: String, endpoint: String, roomId: String, seconds: Int): Int {
    if (role != "a" && role != "b") {
        System.err.println("[room] 角色只能是 a 或 b，收到：$role")
        return 2
    }

    val myId = UserId("selftest-$role")
    val signaler = WsSignaler()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var sawConnected = false
    var sawTwoMembers = false
    var sawHost = false
    /**
     * 对端离开后**本机看到它掉了在线**。
     *
     * ⚠️ 判据不是「成员数回到 1」：按 §5.4 座位保留，对端信令断开只是 `online=false`，
     * 它仍然在 `members` 里（`finalSize` 会一直是 2）。早先这里测的是 `size == 1`，
     * 于是这条永远不可能成立 —— 而它看起来像「peer_left 没处理」。
     */
    var sawPeerOffline = false
    var hostId: UserId? = null
    var lastMembers = ""

    scope.launch {
        signaler.state.collect { state ->
            // `SignalState` 里「连上了」叫 `Online`（不是 Connected）。
            if (state is SignalState.Online) sawConnected = true
            log("[room] role=$role signal=$state")
        }
    }
    scope.launch {
        // 瞬态错误：不订阅就永远看不到失败原因（SharedFlow 没有订阅者时直接丢弃）。
        signaler.events.collect { event ->
            if (event is SignalEvent.Error) log("[room] role=$role 错误 ${event.code}：${event.message}")
        }
    }
    scope.launch {
        signaler.room.collect { room ->
            if (room == null) {
                log("[room] role=$role room=null")
                return@collect
            }
            val members = room.members.joinToString(",") { "${it.nickname}:${it.userId.value}:${it.online}" }
            if (members != lastMembers) {
                lastMembers = members
                log(
                    "[room] role=$role room=${room.roomId} host=${room.hostUserId.value} " +
                        "size=${room.members.size} members=[$members]",
                )
            }
            if (room.hostUserId.value == "selftest-a") hostId = room.hostUserId
            if (room.members.size >= 2) sawTwoMembers = true
            if (sawTwoMembers && room.members.any { it.userId != myId && !it.online }) {
                sawPeerOffline = true
            }
        }
    }

    val ok = runBlocking {
        val joined = withTimeoutOrNull(seconds * 1_000L) {
            runCatching {
                signaler.connect(endpoint, myId)
                // ⚠️ `connect` 只发起连接、**不等连上**（它内部只是 openSocket）。
                // 不等这一步，紧接着的 createRoom 会撞「尚未连接，无法发送」。
                val online = withTimeoutOrNull(10_000) {
                    signaler.state.first { it is SignalState.Online }
                    true
                } ?: false
                check(online) { "10 秒内没连上 $endpoint" }

                if (role == "a") {
                    signaler.createRoom(nickname = "甲", password = null, roomId = roomId)
                } else {
                    // 等 a 把房建出来（同一个固定房号，先到先建）。
                    var attempt = 0
                    while (attempt < 20) {
                        val result = runCatching { signaler.joinRoom(roomId, "乙", null) }
                        if (result.isSuccess) return@withTimeoutOrNull result.getOrThrow()
                        delay(300)
                        attempt++
                    }
                    error("加入 $roomId 失败（20 次尝试）")
                }
            }.getOrElse { error ->
                log("[room] role=$role 失败：${error.message}")
                null
            }
        }
        if (joined == null) return@runBlocking false
        log("[room] role=$role 已进房 room=${joined.roomId} host=${joined.hostUserId.value}")

        // 让双方都有时间看到成员从 1 变 2（a 再多等一会儿看 b 掉线）。
        if (role == "a") {
            delay(seconds * 500L)
            // b 的窗口比 a 短 2.5 s，但两端启动不同步 —— 留 3 s 余量，避免 200 ms 的
            // 抖动把门禁判红。
            var waited = 0L
            while (!sawPeerOffline && waited < 3_000) {
                delay(200)
                waited += 200
            }
            log(
                "[room] role=a 观察窗口结束 size=${signaler.room.value?.members?.size} " +
                    "peerOffline=$sawPeerOffline",
            )
            delay(400)
        } else {
            delay(seconds * 500L - 2_500)
        }
        true
    }

    if (role == "a") {
        sawHost = hostId != null
    } else {
        // b 的判据：读到的房主必须是 a（服务端给的，不是本地猜的）。
        sawHost = signaler.room.value?.hostUserId?.value == "selftest-a"
    }
    val members = signaler.room.value?.members?.size ?: 0
    val offline = signaler.room.value?.members?.count { it.userId != myId && !it.online } ?: 0
    val line = "[room] role=$role done ok=$ok connected=$sawConnected " +
        "twoMembers=$sawTwoMembers peerOffline=$sawPeerOffline hostIsA=$sawHost " +
        "finalSize=$members offline=$offline host=${signaler.room.value?.hostUserId?.value ?: "-"}"
    println(line)
    Log.info(line)

    runBlocking {
        runCatching { signaler.leave() }
        runCatching { signaler.close() }
    }
    scope.cancel()
    // a 多一条：它必须真的看到 b 掉线（b 自己是先走的那个，看不到）。
    val pass = ok && sawConnected && sawTwoMembers && sawHost &&
        (role != "a" || sawPeerOffline)
    return if (pass) 0 else 2
}

private fun log(line: String) {
    println(line)
    Log.info(line)
}
