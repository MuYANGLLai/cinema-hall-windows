package com.cinemahall.core.network

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RoomState
import com.cinemahall.core.model.RoomSummary
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import com.cinemahall.core.protocol.EnvelopeCodec
import com.cinemahall.core.protocol.MalformedEnvelopeException
import com.cinemahall.core.protocol.MessageRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [Signaler] 的 WebSocket 实现（阶段一 1.3）。
 *
 * 协议真相在服务端：`server/src/signaling.mjs` + `server/src/rooms.mjs`。
 * 本文件里每处字段名都对得上那边的 `sendEnvelope(...)`，改协议要两边一起改。
 *
 * **本类刻意不碰任何 Android API**（连 `SystemClock` 都不用，见 [monotonicMs]）。
 * 这样整个信令客户端能在纯 JVM 单测里真跑起来 —— 包括退避重连与 `resume`，
 * 而这两条恰恰是最容易写错、又最需要用真行为而不是肉眼去验的部分。
 */
class WsSignaler(
    /**
     * 玩法域消息与房间事件的去处。可以为 null（纯信令自检场景）。
     * 注意它只吃 `cinema.*` 这类玩法域消息，`sys.*` 由本类自己消化（§4.2）。
     */
    private val router: MessageRouter? = null,
    /**
     * 握手用的账号令牌（契约 §8.5 / §3.2：WS 与 HTTP 共用一枚 token，服务端只从
     * 升级请求的 `Authorization: Bearer` 头里取，不从 URL 查询串、也不从首条消息取）。
     *
     * 做成 `() -> String?` 而不是构造时定值，是因为**建连（含自动重连）发生在同步
     * 路径里**，那一刻才该问「现在这枚 token 是什么」：激活、恢复、换账号之后不必
     * 重建 signaler；返回 null/空串表示不贴头（`AUTH_MODE=off` 的开发档）。
     *
     * 漏贴头的症状是服务端在 `create_room` 上回 `AUTH_REQUIRED`「这台设备还没有
     * 激活，请先输入入场码」—— 而界面上明明已经登录了（真机验收时踩过）。
     */
    private val authToken: () -> String? = { null },
    /**
     * 单调时钟（毫秒）。信封的 `sentAtMs` 用它，而不是墙上时钟（§5.1）。
     *
     * 默认用 `System.nanoTime()`：它在 JVM 与 Android 上都是单调的，因此不必
     * 依赖 `SystemClock.elapsedRealtime()`，本类也就能在纯 JVM 单测里跑。
     * `nanoTime` 不累计深度睡眠时间，`elapsedRealtime` 累计 —— 对本层的用途
     * （算超时、给诊断加时间戳）两者都成立，真要跨设备比对时间轴时，
     * 那是 §6.1 的同步算法自己带时间戳的事。
     */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val reconnectBackoff: ReconnectBackoff = ReconnectBackoff(),
    private val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    private val pongTimeoutMs: Long = DEFAULT_PONG_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * 墙上时钟（毫秒）。**只**用来给「房间里只剩我一个人」这件事记起点
     * （[Room.aloneSince]），也就是 `now - aloneSince` 里那个 `now`。
     *
     * 为什么不能复用 [monotonicMs]：服务端将来下发 `aloneSince` 时用的是墙上时钟
     * （`rooms.mjs` 的 `this.now()`），拿单调时钟去减它就等于把一个任意零点减去
     * 一个真实日期 —— 界面上会出现「已等待 500000 小时」。
     *
     * 做成注入点只是为了单测能给一个固定的「现在」。
     */
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) : Signaler {

    /**
     * OkHttp 客户端是**纯私有实现细节**：它不出现在本类的任何公开签名里，
     * 因此 `core:network` 对 OkHttp 的依赖是 `implementation` 而不是 `api` ——
     * 换掉 WebSocket 实现不会波及任何调用方（§4.2 接缝 1）。
     * 超时/心跳这些真正需要外部可调的东西，已经以本类自己的参数暴露（见下方各 default）。
     */
    private val okHttp: OkHttpClient = defaultOkHttpClient()

    private val _state = MutableStateFlow<SignalState>(SignalState.Disconnected)
    override val state: StateFlow<SignalState> = _state.asStateFlow()

    private val _room = MutableStateFlow<Room?>(null)
    override val room: StateFlow<Room?> = _room.asStateFlow()

    private val _events = MutableSharedFlow<SignalEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<SignalEvent> = _events.asSharedFlow()

    /**
     * `rtc.*` 的去处（阶段一 1.4）。§4.2：`rtc.*` 由底座消化 —— 这里的「消化」
     * 指**拆信封 + 校验 + 转成类型化信号**，玩法层因此不需要认识 `Envelope`。
     *
     * [EVENT_BUFFER] 给得比 `events` 更宽：一条 offer 被丢掉就是一轮协商卡死，
     * 而 `events` 丢一条最多是 UI 慢一拍。
     */
    private val _rtcSignals = MutableSharedFlow<RtcSignal>(extraBufferCapacity = RTC_BUFFER)
    override val rtcSignals: SharedFlow<RtcSignal> = _rtcSignals.asSharedFlow()

    /**
     * 收报路径专用调度器：**parallelism = 1**，报文严格按到达顺序处理。
     *
     * 为什么非这样不可（阶段一 1.4 抓到的缺陷）：`scope` 默认是 `Dispatchers.IO`，
     * 直接 `scope.launch { handleIncoming(text) }` 会让相邻两条报文落到**两个线程上并发跑**，
     * 完成顺序与到达顺序无关。
     *
     * 对 `sys.*` 那时无所谓（room_created / peer_joined 各自独立），但 `rtc.*` 不是：
     *  - 对端发出 `rtc.offer` 后紧接着发一串 `rtc.ice`。若某条 ice 抢在 offer 前面处理，
     *    `addIceCandidate` 会在 `remoteDescription` 仍为 null 时被调用 —— libwebrtc
     *    此时**直接返回 false、不缓存**（和浏览器 JS API 会抛异常不同），那条候选就永久丢了。
     *    丢掉最好的那条候选，结果就是「连不上」或「绕远路走中继」；
     *  - 症状是概率性的：多数时候顺序恰好正确，所以手测很难复现，只会觉得「有时慢、有时要重试」。
     *
     * 队列是 FIFO 的，而 `onMessage` 由 OkHttp 的读线程**串行**回调，所以「提交顺序 == 到达顺序」。
     *
     * 用例：`WsSignalerTest.rtc 报文按到达顺序处理_ICE 不得越过 offer`。
     */
    private val receivePath = Dispatchers.IO.limitedParallelism(1)

    /** 一次只允许一个请求-应答在飞；服务端本来就是一问一答的协议。 */
    private val actionMutex = Mutex()

    private class PendingRequest(val deferred: CompletableDeferred<Envelope>)

    private val pending = AtomicReference<PendingRequest?>(null)

    @Volatile
    private var endpoint: String? = null

    @Volatile
    private var userId: UserId? = null

    /** 每条连接的会话代次，换连接就 +1。`sys.resume` 要带上它（§5.2）。 */
    @Volatile
    private var epochCounter: Long = 0

    /**
     * 对外只读暴露（见 `Signaler.sessionEpoch`）。玩法要填 `cinema.state_snapshot` 的
     * `sessionEpoch` 字段（§5.3），而它是**会话级**信息 —— 只有底座知道。
     */
    override val sessionEpoch: Long get() = epochCounter

    /** 发送序号，**每条连接内**从 1 重新开始（§5.1）。 */
    private var seq: Long = 0

    /** 用户主动关闭：置位后不再自动重连。 */
    @Volatile
    private var closing = false

    @Volatile
    private var socket: WebSocket? = null

    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var lastPingSentAt = 0L

    @Volatile
    private var lastPongAt = 0L

    // ── 对外 API ───────────────────────────────────────────────────────────

    override suspend fun connect(endpoint: String, userId: UserId) {
        val normalized = normalizeSignalEndpoint(endpoint)
            ?: throw SignalException(SignalErrors.CLIENT_BAD_ENDPOINT, "服务器地址不合法：$endpoint")

        actionMutex.withLock {
            closing = false
            this.endpoint = normalized
            this.userId = userId
            reconnectJob?.cancel()
            // 换服务器前先把旧连接收掉：不然它的读线程和 TCP 连接会一直挂着没人管。
            // 先置空再关，这样旧连接随后的 onClosing/onClosed 会被 onSocketGone 的身份检查丢掉。
            socket?.let { old ->
                socket = null
                runCatching { old.close(NORMAL_CLOSURE, "switching endpoint") }
            }
            // 换服务器即换会话：本地房间视图作废，否则会把上一个房间的 roomId 发去 resume
            _room.value = null
            reconnectBackoff.reset()
            openSocket(reconnecting = false)
        }
    }

    override suspend fun createRoom(nickname: String, password: String?, roomId: String?): Room {
        val payload = buildJsonObject {
            put("nickname", nickname)
            if (password != null) put("password", password)
            // 房号只放 payload、不放信封：服务端 handleCreateRoom 只读 payload.roomId，
            // 信封那份是 join/resume 的语义（create 的信封 roomId 恒为 null）。
            if (roomId != null) put("roomId", roomId)
        }
        request("sys.create_room", payload, roomId = null)
        // 服务端在 sys.room_created 里**不给 members[]**（只有 roomId / hostUserId / mode / iceServers），
        // 所以建房这一路的首个成员列表由本地按「我是房主」补出来。加入/恢复那两路有真 members[]。
        val room = _room.value
            ?: throw SignalException(SignalErrors.CLIENT_INTERNAL, "服务端未返回房间信息")

        // 防静默降级（《后端改动说明-自定义房间号》§9.2）：协议没有版本号，旧服务端
        // 会把 payload.roomId 忽略掉并回一个随机号。不比对的话，用户以为自己拿到了
        // 指定的号，实际进了另一个房间 —— 而且这个错在界面上完全看不出来。
        if (roomId != null && !room.roomId.equals(roomId, ignoreCase = true)) {
            // 先退出这个「不是我要的」房间再抛：否则调用方会收到异常，界面却显示
            // 「已在房间里」（`room` 是个 StateFlow，已经被 onResponseEnvelope 写进去了）。
            // leave() 内部是 runCatching 且会清空房间视图，所以它不会把这条异常盖掉。
            runCatching { leave() }
            throw SignalException(
                SignalErrors.CLIENT_ROOM_ID_MISMATCH,
                "服务端不支持自定义房间号：请求 $roomId，实际拿到 ${room.roomId}",
            )
        }
        return room
    }

    override suspend fun joinRoom(roomId: String, nickname: String, password: String?): Room {
        val payload = buildJsonObject {
            put("roomId", roomId)
            put("nickname", nickname)
            if (password != null) put("password", password)
        }
        request("sys.join_room", payload, roomId = roomId)
        return _room.value
            ?: throw SignalException(SignalErrors.CLIENT_INTERNAL, "服务端未返回房间信息")
    }

    override suspend fun resume(roomId: String, userId: UserId, sessionEpoch: Long): Room {
        val payload = buildJsonObject {
            put("roomId", roomId)
            put("userId", userId.value)
            put("sessionEpoch", sessionEpoch)
        }
        request("sys.resume", payload, roomId = roomId)
        return _room.value
            ?: throw SignalException(SignalErrors.CLIENT_INTERNAL, "服务端未返回房间信息")
    }

    /**
     * 房间清单（阶段一 1.8）。
     *
     * 信封的 `roomId` 刻意是 **null**：清单是全局查询，不针对任何一个房间。
     * 服务端也不看这个字段（`handleListRooms` 不读 `ctx.roomId`），这里写 null
     * 是为了让抓包与日志一眼能分出「这条查询与房间无关」。
     *
     * 解析走 `mapNotNull`：**单条坏数据只丢它自己**，不把整份清单变成异常。
     * 服务端是同一个仓库里改的，但协议没有版本号 —— 新旧混跑时（旧服务端 + 新客户端，
     * 或反过来）宁可少列一个房间，也不要让整页报错。
     */
    override suspend fun listRooms(): List<RoomSummary> {
        val envelope = requestEnvelope("sys.list_rooms", buildJsonObject { }, roomId = null)
        val array = envelope.payload?.get("rooms") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("roomId") ?: return@mapNotNull null
            RoomSummary(
                roomId = id,
                // 缺字段时按「没有密码」处理：有密码的房间本来就会在点进去时被服务端
                // 拒一次（BAD_PASSWORD），代价只是多试一次；反过来默认「有密码」，
                // 就会给每个房间平白弹一个密码框。
                hasPassword = (obj["hasPassword"] as? JsonPrimitive)?.booleanOrNull ?: false,
                memberCount = obj.intOrNull("memberCount") ?: 0,
                maxSeats = obj.intOrNull("maxSeats") ?: 0,
                hostNickname = obj.stringOrNull("hostNickname").orEmpty(),
            )
        }
    }

    override suspend fun send(type: String, payload: JsonObject?) {
        sendOrThrow(type, payload, roomId = _room.value?.roomId)
    }

    override suspend fun sendRtcOffer(to: UserId, sdp: String) =
        sendRtc(to, "rtc.offer", buildJsonObject { put("sdp", sdp) })

    override suspend fun sendRtcAnswer(to: UserId, sdp: String) =
        sendRtc(to, "rtc.answer", buildJsonObject { put("sdp", sdp) })

    override suspend fun sendRtcIce(to: UserId, candidate: String?, sdpMid: String?, sdpMLineIndex: Int?) =
        sendRtc(
            to,
            "rtc.ice",
            buildJsonObject {
                // 三个字段**先判空再 put**。缺 candidate 是有意义的（end-of-candidates），
                // 而显式写出 `"candidate": null` 虽然解析结果相同，却会让抓包与日志
                // 分不清「结束标记」和「字段忘了填」。
                if (candidate != null) put("candidate", candidate)
                if (sdpMid != null) put("sdpMid", sdpMid)
                if (sdpMLineIndex != null) put("sdpMLineIndex", sdpMLineIndex)
            },
        )

    // `reason` 与 `rtc.reset` 同一条口径：没有时整个键都不出现（缺键 = `ice`，兼容旧包）。
    override suspend fun requestRenegotiation(to: UserId, reason: String?) = sendRtc(
        to,
        "rtc.renegotiate",
        buildJsonObject { if (reason != null) put("reason", reason) },
    )

    // `reason` 省略整个键（而不是写成 null）：对端解析的是 `payload.stringOrNull("reason")`，
    // 两种写法都得到 null，但少一个键更像「这条消息本来就不需要它」。
    override suspend fun sendRtcReset(to: UserId, reason: String?) = sendRtc(
        to,
        "rtc.reset",
        buildJsonObject { if (reason != null) put("reason", reason) },
    )

    /**
     * 出站 `rtc.*` 的唯一出口，键名只在这里出现一次。
     *
     * 与 [send] 的差别有两层：一是**多一层语义化的收口**，二是信封的 `to` 必填
     * （契约 §6）。房间号一律取当前房间 —— 服务端对不在房间里的 `rtc.*` 回
     * `NOT_IN_ROOM`，对缺 `to` 的回 `BAD_PAYLOAD`，所以这里不做本地预判，
     * 让服务端当唯一裁判。
     */
    private fun sendRtc(to: UserId, type: String, payload: JsonObject) {
        sendOrThrow(type, payload, roomId = _room.value?.roomId, to = to.value)
    }

    override suspend fun leave() {
        if (_room.value == null) return
        // `sys.leave_room` 成功时**服务端不回执**（A6/A7 用例：房主离开时对端收 room_closed、
        // 非房主离开时对端收 peer_left，自己这边什么都没有）。所以这里只能发完即走，
        // 不能等回复 —— 等的话必然超时。
        runCatching { sendOrThrow("sys.leave_room", buildJsonObject { }, roomId = _room.value?.roomId) }
        _room.value = null
    }

    override suspend fun close() {
        actionMutex.withLock {
            closing = true
            reconnectJob?.cancel()
            reconnectJob = null
            pingJob?.cancel()
            pingJob = null
            socket?.close(NORMAL_CLOSURE, "client closing")
            socket = null
            _room.value = null
            pending.getAndSet(null)
                ?.deferred
                ?.completeExceptionally(SignalException(SignalErrors.CLIENT_NOT_CONNECTED, "连接已由本地关闭"))
            _state.value = SignalState.Disconnected
        }
    }

    // ── 发送 ───────────────────────────────────────────────────────────────

    private fun sendOrThrow(type: String, payload: JsonObject?, roomId: String?, to: String? = null) {
        val webSocket = socket
        if (webSocket == null || _state.value != SignalState.Online) {
            throw SignalException(SignalErrors.CLIENT_NOT_CONNECTED, "尚未连接，无法发送 $type")
        }
        seq += 1
        val text = EnvelopeCodec.encode(
            Envelope(
                type = type,
                seq = seq,
                roomId = roomId,
                from = (userId ?: throw SignalException(SignalErrors.CLIENT_NOT_CONNECTED, "缺少 userId")).value,
                // 契约 §4：`to` 只在定向消息上出现。广播（`play`/`heartbeat`/…）与
                // 所有 `sys.*` 请求都保持缺省，于是抓包里「有没有 to」直接就是
                // 「这条是单播还是广播」。
                to = to,
                sentAtMs = monotonicMs(),
                payload = payload,
            ),
        )
        if (!webSocket.send(text)) {
            throw SignalException(SignalErrors.CLIENT_NOT_CONNECTED, "发送 $type 失败：连接已关闭")
        }
    }

    /**
     * 发一条需要应答的请求，返回**服务端的应答信封**本身。
     *
     * 这一层只管「一问一答」，不认识任何一种应答的语义 —— 把应答变成副作用
     * （房间视图 / 房间清单）是调用方的事。分成两层是 1.8 加的 `sys.list_rooms`
     * 逼出来的：它同样是一问一答，但**不碰 `_room`**（拉一次清单不等于进了房间）。
     */
    private suspend fun requestEnvelope(type: String, payload: JsonObject, roomId: String?): Envelope {
        actionMutex.withLock {
            // 顺序要紧：**先登记等待，再发送**。反过来写的话，一个足够快的应答
            // （进程内假服务端、回环链路）会在等待登记之前就到，然后被当成
            // 「没人等它的异步通知」丢掉 —— 于是调用方白等到超时。
            val request = PendingRequest(CompletableDeferred())
            pending.set(request)
            try {
                sendOrThrow(type, payload, roomId)
                return try {
                    withTimeout(requestTimeoutMs) { request.deferred.await() }
                } catch (_: TimeoutCancellationException) {
                    throw SignalException(
                        SignalErrors.CLIENT_TIMEOUT,
                        "$type 等待服务端回复超时（${requestTimeoutMs}ms）",
                    )
                }
            } finally {
                pending.compareAndSet(request, null)
            }
        }
    }

    /**
     * 发一条需要应答的请求，并把应答的 payload 解析结果留在副作用里（房间视图）。
     *
     * **请求类型要一起带下去**（1.11 改）：服务端对 `sys.join_room` 与 `sys.resume`
     * 回的是同一个应答类型（`signaling.mjs` 两条路都发 `sys.room_joined`），
     * 「进新房」与「回到原房」只能靠**发起方自己记着的这个类型**分开 —— 详见
     * [isEnteringRoom] 里那条判据为什么不能只看房号。
     */
    private suspend fun request(type: String, payload: JsonObject, roomId: String?) {
        onResponseEnvelope(requestEnvelope(type, payload, roomId), requestType = type)
    }

    /**
     * 应答里的房间信息落到 [_room] —— 创建 / 加入 / 恢复三条路共用。
     *
     * @param requestType 这条应答是回哪一个请求的。`null` = 没有对应的待应答请求
     *   （只有主动推送那条路，见 [emitRoomEvent]）。
     */
    private suspend fun onResponseEnvelope(envelope: Envelope, requestType: String? = null) {
        val selfUserId = userId ?: return
        val previous = _room.value
        val room = parseRoom(envelope, selfUserId, previous?.members?.firstOrNull { it.userId == selfUserId }?.nickname)
        _room.value = room
        emitIceServers(envelope.payload)

        if (isEnteringRoom(requestType, previous, room)) {
            // 进房是**事件**，不是状态。`_room` 是 StateFlow（合并 + 按相等去重）：
            // 「退房后很快重进同一个房号」这一轮变化可能被去重掉，玩法层那时收不到
            // 任何通知 —— 上一场的通话意图于是跟进了新的一场（真机缺陷：进房就自动
            // 开着通话）。这里直接派发，状态流去重不了事件。
            router?.roomEntered(room.mode, room)
        } else if (envelope.type == "sys.room_joined") {
            // 走到这一支意味着「回到的是原来那个房间」（上面 `isEnteringRoom` 为假），
            // 也就是 **resume 成功回来**这条路：告诉玩法层重建本地视图（§9.4）。
            router?.sessionRestored(room.mode, room)
        }
    }

    // ── WebSocket 回调 ─────────────────────────────────────────────────────

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            onSocketOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 回调线程不能阻塞，转进协程处理。**必须走 receivePath（parallelism = 1）**：
            // 用 scope 默认的 Dispatchers.IO 会让两条报文并发跑，`rtc.offer` 与 `rtc.ice`
            // 的先后就没了保证 —— 见 receivePath 的注释，那条候选是会真丢的。
            scope.launch(receivePath) { handleIncoming(text) }
        }

        /**
         * 对端发起关闭握手。
         *
         * **必须在这里回一个 close 帧把握手续完。** OkHttp 不会替我们回：实测收到的只有
         * `onClosing`，之后 `onClosed` 与 `onFailure` **一个都不会来**，连接停在「半关闭」——
         * 不再收消息，也不报错，客户端连「掉线」都不知道，于是永远不重连
         * （`close 之后不再重连` 那条用例之所以通过，只是因为 `closing` 本来就该什么都不做）。
         * 服务端主动优雅关闭是真实会发生的一类事件：Caddy reload、Node 优雅停机、
         * `ws` 的 1001 going away，都会走这条路。
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            onSocketGone(webSocket, "对端开始关闭（$code $reason）")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketGone(webSocket, "对端正常关闭（$code $reason）")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onSocketGone(
                webSocket,
                buildString {
                    append("连接异常：")
                    append(t.message ?: t::class.java.simpleName)
                    response?.let { append("（HTTP ${it.code}）") }
                },
            )
        }
    }

    private fun onSocketOpen() {
        epochCounter += 1
        seq = 0
        lastPongAt = monotonicMs()
        lastPingSentAt = 0L
        reconnectBackoff.reset()
        _state.value = SignalState.Online
        startPing()

        // 重连后自动恢复会话（§9.4）。这是「拔网再连能 resume」那条验收的实现所在。
        val roomId = _room.value?.roomId
        val selfUserId = userId
        if (roomId != null && selfUserId != null) {
            scope.launch {
                try {
                    resume(roomId, selfUserId, epochCounter)
                } catch (error: SignalException) {
                    // 宽限期已过 / 房间没了：清掉本地视图，让 UI 回到首页而不是卡在「恢复中」
                    _room.value = null
                    _events.emit(SignalEvent.Error(error.code, error.message ?: "会话恢复失败"))
                }
            }
        }
    }

    /**
     * 一条连接终结了。`onClosing` / `onClosed` / `onFailure` 会接踵而至，所以这里
     * **只认当前那条 socket 的通知，后到的直接丢掉**：否则迟到的旧通知会把刚重建好的
     * 新连接当成旧的给拆掉，退化成「连上就断」的循环。
     */
    private fun onSocketGone(from: WebSocket, why: String) {
        if (socket !== from) return
        socket = null
        pingJob?.cancel()
        pingJob = null

        pending.getAndSet(null)?.deferred?.completeExceptionally(
            SignalException(SignalErrors.CLIENT_NOT_CONNECTED, "连接已断开：$why"),
        )

        if (closing) {
            _state.value = SignalState.Disconnected
            return
        }

        _state.value = SignalState.Reconnecting
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delayMs = reconnectBackoff.nextDelayMs()
            _events.emit(SignalEvent.Reconnecting(reconnectBackoff.attemptCount, delayMs))
            delay(delayMs)
            if (!closing) openSocket(reconnecting = true)
        }
    }

    private fun openSocket(reconnecting: Boolean) {
        val url = endpoint ?: return
        _state.value = if (reconnecting) SignalState.Reconnecting else SignalState.Connecting
        seq = 0
        val request = Request.Builder()
            .url(url)
            .header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION)
            .apply {
                authToken()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { header("Authorization", "Bearer $it") }
            }
            .build()
        socket = okHttp.newWebSocket(request, listener)
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                if (_state.value != SignalState.Online) continue

                val now = monotonicMs()
                val pingAwaitingPong = lastPingSentAt != 0L && lastPongAt < lastPingSentAt
                if (pingAwaitingPong && now - lastPingSentAt > pongTimeoutMs) {
                    // 上一轮 ping 一直没等到 pong。服务端 75s 没收到任何消息就会把座位标离线，
                    // 与其等它踢我们，不如主动重连 —— 顺序可控，对端看到的事件也更早。
                    _events.emit(
                        SignalEvent.Error("PING_TIMEOUT", "心跳超时（${pongTimeoutMs}ms 未收到 pong），主动重连"),
                    )
                    socket?.cancel()
                    continue
                }

                lastPingSentAt = now
                runCatching { sendOrThrow("sys.ping", buildJsonObject { put("t", now) }, _room.value?.roomId) }
            }
        }
    }

    // ── 收报处理 ───────────────────────────────────────────────────────────

    private suspend fun handleIncoming(text: String) {
        val envelope = try {
            EnvelopeCodec.decode(text)
        } catch (error: MalformedEnvelopeException) {
            // 协议错配不打断连接（§4.2）：报出去 + 丢弃，连接照常
            _events.emit(SignalEvent.Error(SignalErrors.CLIENT_MALFORMED, error.message ?: "报文格式错误"))
            return
        }

        when (envelope.type) {
            "sys.pong" -> {
                lastPongAt = monotonicMs()
            }

            "sys.room_created", "sys.room_joined" -> {
                pending.getAndSet(null)?.deferred?.complete(envelope)
                    ?: emitRoomEvent(envelope)
            }

            "sys.room_list" -> {
                // 房间清单的应答（阶段一 1.8）。它**只**可能是一条应答 —— 服务端
                // 不会主动推（推的是 sys.ice_servers 那类）。没人等它说明是上一次
                // 查询超时后才到的迟到应答，丢掉即可；**不能**走 emitRoomEvent，
                // 那会把一个不含房间信息的 payload 当成 room_created 去解析。
                pending.getAndSet(null)?.deferred?.complete(envelope)
            }

            "sys.ice_servers" -> emitIceServers(envelope.payload)

            "sys.peer_joined" -> {
                val peerId = envelope.payload.stringOrNull("userId") ?: return
                val nickname = envelope.payload.stringOrNull("nickname").orEmpty()
                markMemberOnline(peerId, nickname, online = true)
                _events.emit(SignalEvent.PeerJoined(UserId(peerId), nickname))
                currentMode()?.let { router?.peerJoined(it, UserId(peerId), nickname) }
            }

            "sys.peer_left" -> {
                val peerId = envelope.payload.stringOrNull("userId") ?: return
                markMemberOnline(peerId, nickname = null, online = false)
                _events.emit(SignalEvent.PeerLeft(UserId(peerId)))
                currentMode()?.let { router?.peerLeft(it, UserId(peerId)) }
            }

            "sys.members_updated" -> {
                // §5 v2 的全量成员表。**不派发玩法事件**：谁进了谁走了这件事，
                // 玩法的入口在 `Room` 这个状态流上（`CinemaHandler.onRoomUpdated`），
                // 而不是在这里再发明一个事件类型 —— 两条路会让「成员表」有两份真相。
                applyMembersUpdated(envelope.payload)
            }

            "sys.room_closed" -> {
                val reason = envelope.payload.stringOrNull("reason").orEmpty()
                _room.value = null
                _events.emit(SignalEvent.RoomClosed(reason))
            }

            "sys.error" -> {
                val code = envelope.payload.stringOrNull("code") ?: SignalErrors.INTERNAL
                val message = envelope.payload.stringOrNull("message").orEmpty()
                val request = pending.getAndSet(null)
                if (request != null) {
                    // 这是某次调用的回复 → 交给调用方（它会抛 SignalException），不再重复发事件
                    request.deferred.completeExceptionally(SignalException(code, message))
                } else {
                    // 没人等它 → 属于异步通知（例如凭证续签失败），走事件流
                    _events.emit(SignalEvent.Error(code, message))
                }
            }

            else -> if (envelope.type.startsWith("rtc.")) {
                // §4.2：rtc.* 由底座自己消化 —— 拆成类型化信号交给 core:rtc，
                // **不进** MessageRouter（那是玩法域的入口）。
                handleRtcSignal(envelope)
            } else {
                // 未知域：交给 MessageRouter 计数（它会把没人认领的 type 记到 RouteMetrics）
                router?.route(envelope)
            }
        }
    }

    // ── rtc.* 消化（阶段一 1.4） ───────────────────────────────────────────

    /**
     * 把对端的 `rtc.*` 信封转成 [RtcSignal]。§4.2 说 `rtc.*`「由底座自己消化」——
     * 所谓消化就是这一步：拆信封 + 校验 + 转成自己的类型，玩法层因此完全不认识 `Envelope`。
     *
     * 三条防御，都是为了「一条坏消息不该打断整条连接」（§4.2 同一条原则）：
     *  - `offer` / `answer` 缺 `sdp`、`ice` 整个 payload 缺失 ⇒ 报 `CLIENT_MALFORMED` 并丢弃；
     *  - 未知的 `rtc.*` 动作 ⇒ 交回 [MessageRouter] 走「未知前缀计数」，不抛异常；
     *  - `from` 为空的信封在 [EnvelopeCodec.decode] 那一步就已经被挡住，到不了这里。
     *
     * 另外**主动丢弃自己发出的回声**。服务端只把 `rtc.*` 转发给房间对端
     * （`signaling.mjs` 的 `peerSeat`），单人房间里会直接回 `PEER_OFFLINE`，
     * 正常情况下收不到自己的消息。这里比对一次是廉价的兜底：一旦哪天服务端
     * 改了转发策略，我们至少不会把「对端」设成自己。
     */
    private suspend fun handleRtcSignal(envelope: Envelope) {
        if (envelope.from == userId?.value) return

        // 契约 §4：`to != null && to != 自己` ⇒ **丢弃，不记错**。
        //
        // 这条路在 v1 服务端上也会走到（那一边没有 `to`，字段恒为 null，于是直接放行），
        // 所以两个开关位置都能用同一份代码 —— 这正是「客户端先按 v2 收」的好处。
        // 判据刻意不报 `CLIENT_MALFORMED`：多人房里三个人的候选互相转发一轮是**正常**
        // 的噪声，把它记成协议错误会让诊断页在自己好着的时候满屏红字。
        val to = envelope.to
        if (to != null && to != userId?.value) return

        val from = UserId(envelope.from)
        val payload = envelope.payload

        val signal: RtcSignal? = when (envelope.type) {
            "rtc.offer" -> payload.stringOrNull("sdp")?.let { RtcSignal.Offer(from, it) }

            "rtc.answer" -> payload.stringOrNull("sdp")?.let { RtcSignal.Answer(from, it) }

            // `reason` 缺键 = `ice`（旧包发这条消息时没有 payload）—— 判据在
            // `WebRtcPeerLink.onSignal` 里，这里如实转达「有没有给值」。
            "rtc.renegotiate" -> RtcSignal.Renegotiate(from, payload.stringOrNull("reason"))

            // `reason` 缺失 / 写成 null 都是合法的（它只进日志）。
            "rtc.reset" -> RtcSignal.Reset(from, payload.stringOrNull("reason"))

            // 这里**故意不要求 candidate 非空**：candidate 为 null 表示
            // end-of-candidates，是一条有意义的信号（见 [RtcSignal.Ice] 的 KDoc），
            // 丢了它远端候选集合就永远不会被标记为完整。会丢的只有「整个 payload 都不在」。
            "rtc.ice" -> if (payload == null) {
                null
            } else {
                RtcSignal.Ice(
                    from = from,
                    candidate = payload.stringOrNull("candidate"),
                    sdpMid = payload.stringOrNull("sdpMid"),
                    sdpMLineIndex = payload.intOrNull("sdpMLineIndex"),
                )
            }

            else -> {
                // 未知的 rtc.* 动作：交给 MessageRouter 走「未知前缀：计数 + 丢弃」（§4.2）
                router?.route(envelope)
                return
            }
        }

        if (signal == null) {
            _events.emit(
                SignalEvent.Error(SignalErrors.CLIENT_MALFORMED, "${envelope.type} 缺少必要字段，已丢弃"),
            )
        } else {
            _rtcSignals.emit(signal)
        }
    }

    private fun currentMode(): String? = _room.value?.mode?.takeIf { it.isNotBlank() }

    /**
     * 服务端在没有匹配请求时主动推 `sys.room_created` / `sys.room_joined`
     * （例如 resume 的回复早于请求注册的竞态）。
     *
     * ── 1.11 补：这条路上**也要派发玩法事件**（原来只改 `_room`，不通知任何人）──
     * 原来这里只写 `_room`，指望玩法层从 `Signaler.room` 这个状态流上看见变化。
     * 而它正是 1.10 认定的那个不可靠通道（StateFlow 合并 + 按相等去重）：主动推送
     * 一个**与当前视图完全相同**的房间时，状态流一次都不发，玩法层于是**什么都收不到** ——
     * 包括「这是一场新会话、通话意图要清零」那件事。真机缺陷「进房之后通话还是打开状态、
     * 麦克风绿点亮着」正落在这一类上。
     *
     * 判据用 [isEnteringRoom] 的「房号比较」那一支：这条路上没有请求类型可看，
     * 只能按「本机原来在不在房间里 / 房号变没变」分开，与 [onResponseEnvelope] 一致。
     */
    private suspend fun emitRoomEvent(envelope: Envelope) {
        val selfUserId = userId ?: return
        val previous = _room.value
        val room = parseRoom(envelope, selfUserId, nickname = null)
        _room.value = room
        emitIceServers(envelope.payload)

        if (isEnteringRoom(requestType = null, previous = previous, room = room)) {
            router?.roomEntered(room.mode, room)
        } else {
            router?.sessionRestored(room.mode, room)
        }
    }

    private suspend fun emitIceServers(payload: JsonObject?) {
        // 区分「这条消息不带 ICE 配置」与「带了但是空的」：
        //  - 键不存在 → 本条消息与此事无关，什么都不说；
        //  - 键存在但 `[]` → **服务端明确说了没有中继**（`turn.mjs` 在没配 `TURN_HOST`
        //    时就是返回 `[]`），这正是 §8.4 要提示的状态，不能当「没消息」丢掉 ——
        //    丢掉的话 UI 只会显示「未收到 ICE 配置」，而用户真正需要知道的是「现在没有中继」。
        val array = payload?.get("iceServers") as? JsonArray ?: return
        val servers = try {
            EnvelopeCodec.json.decodeFromJsonElement(ListSerializer(IceServer.serializer()), array)
        } catch (_: Exception) {
            return
        }
        _events.emit(SignalEvent.IceServersUpdated(servers, IceServer.hasRelay(servers)))
    }

    // ── 房间视图维护 ───────────────────────────────────────────────────────

    private fun parseRoom(envelope: Envelope, selfUserId: UserId, nickname: String?): Room {
        val payload = envelope.payload
        val roomId = payload.stringOrNull("roomId")
            ?: throw SignalException(SignalErrors.CLIENT_MALFORMED, "${envelope.type} 缺 roomId")

        // room_created 没有 members[]：按「我是房间唯一成员」补出来，别让 UI 拿到空列表
        val members = parseMembers(payload)
            ?: listOf(Member(userId = selfUserId, nickname = nickname.orEmpty(), online = true))

        return project(
            roomId = roomId,
            hostUserId = UserId(payload.stringOrNull("hostUserId") ?: selfUserId.value),
            mode = payload.stringOrNull("mode").orEmpty(),
            members = members,
            declaredState = payload.stringOrNull("state"),
            declaredAloneSince = payload.longOrNull("aloneSince"),
            declaredMaxSeats = payload.intOrNull("maxSeats"),
            // 进房 / 恢复都是「重新拿一份全量」：上一场留下的 aloneSince 不许跟过来，
            // 否则换房之后界面会显示一个别人房间的等待时长。
            previousAloneSince = null,
        )
    }

    /**
     * `members[]` 的解析。**只丢坏元素、不丢整条消息** —— 与 `sys.room_list` 同一取向：
     * 协议没有版本号，新旧混跑时宁可少显示一个人，也不要让整个房间视图变成异常。
     */
    private fun parseMembers(payload: JsonObject?): List<Member>? {
        val array = payload?.get("members") as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("userId") ?: return@mapNotNull null
            Member(
                userId = UserId(id),
                nickname = obj.stringOrNull("nickname").orEmpty(),
                online = (obj["online"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * 把「服务端给了什么」+「成员表说明了什么」合成一个 [Room]。
     *
     * 三个投影字段（§5）的取值优先级都在 [RoomProjection] 里，本方法只负责
     * 「从载荷里取、取不到就传 null」。这样单测可以直接测投影规则本身，
     * 不必造一条 WebSocket。
     */
    private fun project(
        roomId: String,
        hostUserId: UserId,
        mode: String,
        members: List<Member>,
        declaredState: String?,
        declaredAloneSince: Long?,
        declaredMaxSeats: Int?,
        previousAloneSince: Long?,
    ): Room = Room(
        roomId = roomId,
        hostUserId = hostUserId,
        mode = mode,
        members = members,
        // §5.4：底座不解释玩法状态，服务端此刻也不会下发它
        modeState = null,
        state = RoomProjection.state(RoomState.parse(declaredState), members),
        aloneSince = RoomProjection.aloneSince(
            declared = declaredAloneSince,
            previous = previousAloneSince,
            onlineCount = members.count { it.online },
            nowMs = wallClockMs(),
        ),
        maxSeats = RoomProjection.maxSeats(declaredMaxSeats),
    )

    /**
     * `sys.members_updated`（契约 §5，v2 才有）：**全量**成员表，不是增量。
     *
     * 与服务端一样，这里也整份替换而不是逐条 merge —— 增量对比在 6 人房里
     * 会退化成「客户端自己维护一份可能与服务端不同的名单」，而两边名单一旦分叉，
     * 症状是「有人明明在房间里，我这边看不见他」。
     *
     * 还没进房时（`_room == null`）直接丢弃：没有可更新的视图，凭空造一个
     * 反而会让界面以为自己在房间里。
     */
    private fun applyMembersUpdated(payload: JsonObject?) {
        val current = _room.value ?: return
        val members = parseMembers(payload) ?: return
        val host = payload.stringOrNull("hostUserId")

        _room.value = project(
            roomId = current.roomId,
            hostUserId = host?.let(::UserId) ?: current.hostUserId,
            mode = current.mode,
            members = members,
            declaredState = payload.stringOrNull("state"),
            declaredAloneSince = payload.longOrNull("aloneSince"),
            declaredMaxSeats = payload.intOrNull("maxSeats"),
            previousAloneSince = current.aloneSince,
        )
    }

    private fun markMemberOnline(memberId: String, nickname: String?, online: Boolean) {
        val current = _room.value ?: return
        val exists = current.members.any { it.userId.value == memberId }
        val members = when {
            exists -> current.members.map {
                if (it.userId.value == memberId) it.copy(online = online) else it
            }
            // 对端在线而本地房间视图里没有它（例如漏了一条 peer_joined）：补进来，
            // 但**不猜昵称** —— 补出来的成员昵称允许为空，UI 用「对方」兜底。
            online -> current.members + Member(UserId(memberId), nickname.orEmpty(), online = true)
            else -> current.members
        }
        if (members === current.members) return
        // v1 的 `peer_joined` / `peer_left` 走这条路。**同样要重算投影**：
        // 在线人数变了，`state` 与 `aloneSince` 就变了 —— 少了这一步，
        // 兼容模式（`PROTOCOL_V2=0`）下「等待」横幅永远不出现。
        _room.value = project(
            roomId = current.roomId,
            hostUserId = current.hostUserId,
            mode = current.mode,
            members = members,
            declaredState = null,
            declaredAloneSince = null,
            declaredMaxSeats = null,
            previousAloneSince = current.aloneSince,
        )
    }

    companion object {
        /**
         * 契约 §3.2 的握手头：`X-Protocol-Version: 2`（「v2 必填」）。
         *
         * 服务端在 `AUTH_MODE=required` 下**只认 `"2"`**，缺失或不符直接 426
         * `PROTOCOL_MISMATCH`（`verifyClient` 里是等值比较，不解析版本区间）。
         * `off/optional` 下服务端不校验这个头，所以无条件带上不会有副作用。
         *
         * 它描述的是**线上协议代次**，与 `PROTOCOL_V2` 开关（房间容量 2/6 座）是
         * 两件事：后者由服务端环境变量决定，客户端发不发这个头都改变不了。
         */
        const val PROTOCOL_VERSION_HEADER = "X-Protocol-Version"
        const val PROTOCOL_VERSION = "2"

        /** §6.8 `SIGNAL_PING_MS`。 */
        const val DEFAULT_PING_INTERVAL_MS = 30_000L

        /** 与服务端 `LIVENESS_TIMEOUT_MS`（75s）对齐。 */
        const val DEFAULT_PONG_TIMEOUT_MS = 75_000L

        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L

        private const val EVENT_BUFFER = 64

        /**
         * `rtc.*` 的缓冲。给 256 而不是 64 —— 一次建连会连着来一串 ICE 候选
         * （本机实测的 SDP 里 host/srflx/relay 各有若干条），一轮协商下来几十条是常态。
         * 丢一条候选不致命（还有别的），丢一条 offer 就是整轮卡死，所以宁可给宽。
         */
        private const val RTC_BUFFER = 256

        private const val NORMAL_CLOSURE = 1000

        /**
         * 连接超时给 10s 而不是默认的 10s 以下：日常链路要经 Caddy → Node，
         * 且热点刚重开时首个 TCP 连接常要重试，太短会在能连上的场景里假失败。
         *
         * `pingInterval` 显式关掉：心跳走应用层 `sys.ping`（§5.2），WS 层再叠一套
         * 只会让「谁先发现链路死了」变得不可预期。
         *
         * 可见性是 `internal` 而不是 `public`：返回值是 OkHttp 类型，公开出去就等于
         * 把它塞回本模块的 API 面，`implementation` 依赖也就不成立了。
         */
        internal fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // WS 是长连接：不设读超时，活性由应用层心跳判定
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

// ── JsonObject 取值小工具（只在 core:network 内部用）──────────────────────────

internal fun JsonObject?.stringOrNull(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

/** 同上，给 `rtc.ice` 的 `sdpMLineIndex` 用。非数字（含 JSON 里写成字符串）一律 null。 */
internal fun JsonObject?.intOrNull(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/**
 * 同上，给 `aloneSince` 用（墙上时钟毫秒，可能超过 `Int.MAX_VALUE`）。
 *
 * 单独写一个而不是复用 [intOrNull]：`aloneSince` 是 epoch 毫秒，`toIntOrNull()`
 * 会溢出成负数，而负数减出来是「已等待 -2000000 秒」。
 */
internal fun JsonObject?.longOrNull(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

// ── 进房判据（纯函数，可单测）────────────────────────────────────────────────

/**
 * 这条房间应答算不算「一次真正的进房」。
 *
 * ── 为什么不能只看房号（1.11 改）────────────────────────────────────────────
 * 1.10 的判据是「原来不在房间里 **或** 房号与原来不同」。它把「进新房」与「回到原房」
 * 全押在房号上，而房号是**间接证据**：服务端对 `sys.join_room` 与 `sys.resume`
 * 回的是同一个应答类型，真正区分这两件事的信息只有**发起方自己知道**——
 * 发出去的是哪个请求。用间接证据就必然有漏：
 *
 *  本机已经在 8 号房（`_room` 还是那个房，例如对端掉线后用户退回大厅但**没有按
 *  「离开房间」**），此时再发一次 `sys.join_room` 加入 8 号房 —— 房号与原来相同，
 *  1.10 的判据判它「不是进房」，于是派发的是 [MessageRouter.sessionRestored]，
 *  而那条路**刻意不复位通话**（§9.4 宽限期要保住断线前的通话）。结果就是
 *  「进房之后通话还是打开状态、麦克风绿点亮着」—— 用户没按过任何开关。
 *
 * 现在按请求类型判：`sys.resume` 是「回到原房」，其余（建房 / 加入）都是进房。
 * 房号比较只留作**兜底**，服务那条没有请求类型可看的主动推送（见 [emitRoomEvent]）。
 *
 * @param requestType 发起方发出的请求类型；`null` = 主动推送（无待应答请求）。
 * @param previous 处理这条应答**之前**本机的房间视图。
 */
internal fun isEnteringRoom(requestType: String?, previous: Room?, room: Room): Boolean {
    // ① 有请求类型：`sys.resume` 之外的每一条都是「进房」。
    //    这一支与房号无关 —— 这正是它比房号判据强的地方。
    if (requestType != null) return requestType != "sys.resume"
    // ② 主动推送（没有请求类型）：只能按房号比较。原来不在房间里、或房号变了，
    //    都说明本机此刻在的已经不是原来的那场会话。
    return previous == null || !previous.roomId.equals(room.roomId, ignoreCase = true)
}
