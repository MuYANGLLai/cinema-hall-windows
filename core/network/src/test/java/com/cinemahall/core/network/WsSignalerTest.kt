package com.cinemahall.core.network

import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import com.cinemahall.core.protocol.EnvelopeCodec
import com.cinemahall.core.protocol.MessageRouter
import com.cinemahall.core.protocol.PlayModeHandler
import com.cinemahall.core.protocol.RouteMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 信令客户端的**真行为**测试：进程内起一个假服务端（MockWebServer 的
 * `withWebSocketUpgrade`），让客户端真的走一遍 WebSocket 握手、收发、断线、退避、重连。
 *
 * 为什么不满足于「等真机验收」：1.3 里最容易错的两件事 —— 退避重连与自动 `resume` ——
 * 恰恰是**真机上最难复现、最难判断对错**的（拔网时机不好控、看上去都是「转圈」）。
 * 放在 JVM 上跑，它们变成可以逐个断言的确定行为。真机验收仍然要做，但那时它验的是
 * 「整套在真网络下的表现」，而不是「客户端状态机写没写对」。
 *
 * 事实证明这么做是对的：**这两条用例当场抓出一个会导致「永远不重连」的真缺陷**
 * （OkHttp 客户端不回 close 帧 → 连接停在半关闭 → `onClosed`/`onFailure` 都不来），
 * 而这种缺陷在真机上只会表现为「偶尔卡住不动」，几乎不可能靠手测定位。
 */
class WsSignalerTest {

    private lateinit var server: MockWebServer
    private lateinit var fake: FakeSignalingServer
    private var signaler: WsSignaler? = null
    private lateinit var handler: FakeCinemaHandler
    private lateinit var metrics: RecordingMetrics

    @Before
    fun setUp() {
        server = MockWebServer()
        fake = FakeSignalingServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(fake)
        }
        server.start()
    }

    @After
    fun tearDown() {
        signaler?.let { runBlocking { runCatching { it.close() } } }
        signaler = null
        runCatching { server.shutdown() }
    }

    private fun newSignaler(authToken: () -> String? = { null }): WsSignaler {
        handler = FakeCinemaHandler()
        metrics = RecordingMetrics()
        val created = WsSignaler(
            router = MessageRouter(listOf(handler), metrics),
            authToken = authToken,
            // 真机上退避是 1s 起、上限 30s；测试里压到 50~100ms，否则每个断线用例都要等好几秒
            reconnectBackoff = ReconnectBackoff(baseMs = 50, maxMs = 100, jitterRatio = 0.0),
            // 心跳拉到 10 分钟，保证测试期间它不会插进来
            pingIntervalMs = 600_000,
            pongTimeoutMs = 600_000,
            requestTimeoutMs = 5_000,
        )
        signaler = created
        return created
    }

    private fun wsUrl(): String = server.url("/ws").toString().replaceFirst("http://", "ws://")

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        fail("等待「$what」超时（${timeoutMs}ms）")
    }

    // ── 用例 ───────────────────────────────────────────────────────────────

    /**
     * 契约 §3.2 的鉴权顺序：TLS 断言 → **token 校验** → 协议版本校验，而 token
     * 只从升级请求的 `Authorization` 头取。漏贴这个头的症状是「界面上明明已登录，
     * 建房却收到 `AUTH_REQUIRED` 说这台设备还没激活」（真机验收时踩过）。
     */
    @Test
    fun `握手请求带 Authorization Bearer 令牌`() = runBlocking {
        val signaler = newSignaler(authToken = { "tok-abc" })
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val upgrade = server.takeRequest()
        assertEquals(
            "服务端只从升级请求头取 token",
            "Bearer tok-abc",
            upgrade.getHeader("Authorization"),
        )
        assertEquals(
            "契约 §3.2：X-Protocol-Version 是 v2 必填，required 模式下服务端只认 \"2\"（否则 426）",
            "2",
            upgrade.getHeader(WsSignaler.PROTOCOL_VERSION_HEADER),
        )
    }

    @Test
    fun `没有令牌时不贴 Authorization 头`() = runBlocking {
        val signaler = newSignaler(authToken = { null })
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val upgrade = server.takeRequest()
        assertNull(
            "AUTH_MODE=off 的开发档不该凭空造一个空 Bearer（服务端会把空串当坏 token）",
            upgrade.getHeader("Authorization"),
        )
    }

    @Test
    fun `连接建房_状态转 Online_拿到房间号与降级后的凭证`() = runBlocking {
        val signaler = newSignaler()
        val events = CopyOnWriteArrayList<SignalEvent>()
        val collector = launch { signaler.events.collect { events += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val room = signaler.createRoom("甲", null)

        assertEquals(FakeSignalingServer.ROOM_ID, room.roomId)
        assertEquals(UserId("user-a"), room.hostUserId)
        assertEquals("cinema", room.mode)
        // room_created 里没有 members[]，客户端必须补出「我是唯一成员」而不是给个空列表
        assertEquals(1, room.members.size)
        assertEquals(UserId("user-a"), room.members[0].userId)

        awaitUntil("收到 IceServersUpdated") { events.any { it is SignalEvent.IceServersUpdated } }
        val ice = events.filterIsInstance<SignalEvent.IceServersUpdated>().last()
        assertFalse("假服务端只给了 STUN，应判定为无中继（§8.4 要据此明确提示）", ice.turnAvailable)
        assertTrue(ice.servers.all { !it.isRelay })

        collector.cancel()
    }

    @Test
    fun `加入房间_members 来自服务端且带对端`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val room = signaler.joinRoom(FakeSignalingServer.ROOM_ID, "乙", "pw")

        assertEquals(2, room.members.size)
        assertEquals(UserId("user-a"), room.hostUserId)
        val peer = room.members.single { it.userId == UserId("user-b") }
        assertEquals("甲", peer.nickname)
        assertTrue(peer.online)
        // 加入这一路服务端给的是带 TURN 的完整配置
        assertEquals(room.roomId, signaler.room.value?.roomId)
    }

    /**
     * 「进房」与「恢复会话」必须是**两条路**（2026-10-12 真机缺陷）。
     *
     * 服务端对 `sys.join_room` 与 `sys.resume` 回的是**同一个**应答类型
     * （`sys.room_joined`，见 `signaling.mjs` 的两处 `sendEnvelope`），所以底座只能按
     * 「进的是不是同一个房号」分开：同号 = 断线恢复，异号（或本来就不在房间里）= 进房。
     *
     * 分开的理由是**语义相反**：进新房要清掉上一场（影厅的通话意图就挂在这上面），
     * 而 resume 必须保住会话状态（§9.4 的宽限期里通话不许被掐）。
     */
    @Test
    fun `建房与加入派发进房事件_resume 派发恢复事件`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        // ① 建房 = 一次真正的进房
        val room = signaler.createRoom("甲", null)
        awaitUntil("玩法层收到进房事件") { handler.entered.size == 1 }
        assertEquals(room.roomId, handler.entered.single().roomId)
        assertTrue("建房不是「恢复会话」", handler.restored.isEmpty())

        // ② 断线重连（自动 resume）回到**同一个房号** = 恢复，不是进房。
        //    这一条正是两条路必须分开的理由：若 resume 也被当成进房，宽限期里
        //    正在进行的通话会被无声关掉。
        fake.closeAllSessions()
        awaitUntil("重连后收到恢复事件", timeoutMs = 10_000) { handler.restored.size == 1 }
        assertEquals("resume 不许被当成一次进房", 1, handler.entered.size)

        // ③ 退房之后再进**同一个房号** = 又一次进房。真机上这一轮 `null` → 同一个 `Room`
        //    可能被 StateFlow 去重掉（玩法层收不到任何房间变化），所以复位只能挂在这个
        //    事件上 —— 这里钉住「事件确实会发」。
        signaler.leave()
        awaitUntil("本地房间视图已清空") { signaler.room.value == null }
        signaler.joinRoom(room.roomId, "乙", null)
        awaitUntil("再次收到进房事件") { handler.entered.size == 2 }
        assertEquals(room.roomId, handler.entered.last().roomId)
        assertEquals("恢复事件不该多出来一次", 1, handler.restored.size)
    }

    @Test
    fun `服务端主动关闭后自动退避重连_并带着原房间号发 resume`() = runBlocking {
        val signaler = newSignaler()
        val states = CopyOnWriteArrayList<SignalState>()
        val stateCollector = launch { signaler.state.collect { states += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }
        val room = signaler.createRoom("甲", null)
        assertEquals("假服务端应已捕获一条连接", 1, fake.sessions.size)
        val resumesBefore = fake.typesReceived().count { it == "sys.resume" }

        fake.closeAllSessions()   // ← 服务端主动关闭握手（Caddy reload / Node 优雅停机 / 1001 going away）

        awaitUntil("重连上线且发出了 sys.resume", timeoutMs = 10_000) {
            signaler.state.value == SignalState.Online &&
                fake.typesReceived().count { it == "sys.resume" } > resumesBefore
        }
        assertTrue("状态迁移应经过 Reconnecting，实际序列 $states", states.contains(SignalState.Reconnecting))

        val resume = fake.lastReceived("sys.resume")
        val payload = resume.payload
        assertNotNull("sys.resume 必须带 payload", payload)
        val roomId = (payload!!["roomId"] as JsonPrimitive).content
        assertEquals(room.roomId, roomId)
        assertEquals("user-a", (payload["userId"] as JsonPrimitive).content)
        // 换连接 = 换会话代次，resume 必须带上新的（§5.2 用它丢弃过期消息）
        val epoch = (payload["sessionEpoch"] as JsonPrimitive).content.toLong()
        assertTrue("sessionEpoch 应已递增，实际 $epoch", epoch >= 2L)
        // 恢复后房间视图还认得原来的房间
        awaitUntil("房间视图恢复") { signaler.room.value?.roomId == room.roomId }
        stateCollector.cancel()
    }

    /**
     * 与上一条**路径不同**且必须分开测：真「拔网」时没有 close 帧，TCP 直接断，
     * 客户端只能靠 `onFailure`（实测是 `EOFException`，4ms 内到达）发现掉线。
     *
     * 这个用例把服务端**整个关掉**，所以用一台专属的假服务端，不连累共享 fixture。
     */
    @Test
    fun `真拔网（无关闭帧_TCP 直接断）也走退避重连_房间视图保留待恢复`() = runBlocking {
        val own = MockWebServer()
        val ownFake = FakeSignalingServer()
        own.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(ownFake)
        }
        own.start()
        try {
            val signaler = newSignaler()
            val events = CopyOnWriteArrayList<SignalEvent>()
            val collector = launch { signaler.events.collect { events += it } }

            signaler.connect(own.url("/ws").toString().replaceFirst("http://", "ws://"), UserId("user-a"))
            awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }
            val room = signaler.createRoom("甲", null)
            assertEquals(1, ownFake.sessions.size)

            own.shutdown()   // ← 没有 close 帧，直接断 TCP

            awaitUntil("退避重连已启动（有 Reconnecting 事件）", timeoutMs = 10_000) {
                events.filterIsInstance<SignalEvent.Reconnecting>().isNotEmpty()
            }
            // 服务端已经没了，所以只能在 Reconnecting 与 Connecting 之间循环；关键是
            // **不能停在 Online**（那等于没发现掉线），也**不能自己变成 Disconnected**（没人要求关闭）
            awaitUntil("状态在重连路径上而非 Online/Disconnected") {
                signaler.state.value == SignalState.Reconnecting ||
                    signaler.state.value == SignalState.Connecting
            }
            // 拔网 ≠ 离开房间：resume 要用的房间号必须留着，否则重连回来也恢复不了
            assertEquals(room.roomId, signaler.room.value?.roomId)
            collector.cancel()
        } finally {
            runCatching { own.shutdown() }
        }
    }

    @Test
    fun `resume 被服务端拒绝时清空本地房间视图并报出原因`() = runBlocking {
        val signaler = newSignaler()
        val events = CopyOnWriteArrayList<SignalEvent>()
        val collector = launch { signaler.events.collect { events += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }
        signaler.createRoom("甲", null)

        fake.resumeFails.set(true)
        fake.closeAllSessions()

        awaitUntil("报出恢复失败", timeoutMs = 10_000) {
            events.filterIsInstance<SignalEvent.Error>().any { it.code == "RESUME_FAILED" }
        }
        // 宽限期已过、座位没了：本地必须回到「不在任何房间」，不能卡在假想的房间里
        assertNull(signaler.room.value)
        // 但连接本身是好的 —— 恢复失败不该被当成掉线
        awaitUntil("状态回到 Online") { signaler.state.value == SignalState.Online }
        collector.cancel()
    }

    @Test
    fun `空 iceServers 数组要点成没有中继_而不是当作没这条消息`() = runBlocking {
        val signaler = newSignaler()
        val events = CopyOnWriteArrayList<SignalEvent>()
        val collector = launch { signaler.events.collect { events += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        // 服务端没配 TURN_HOST 时 buildIceServers 就是返回 []（实测 turn.mjs:39）。
        // 这是「明确说了没有中继」，不是「没这条消息」—— §8.4 要求这种状态必须提示出来。
        fake.push("sys.ice_servers", buildJsonObject { put("iceServers", buildJsonArray { }) })

        awaitUntil("收到 IceServersUpdated") { events.any { it is SignalEvent.IceServersUpdated } }
        val ice = events.filterIsInstance<SignalEvent.IceServersUpdated>().last()
        assertTrue("空数组也要报出来，否则 UI 只会显示「未收到 ICE 配置」", ice.servers.isEmpty())
        assertFalse(ice.turnAvailable)
        collector.cancel()
    }

    @Test
    fun `对端进出同时发出事件并送达玩法处理器`() = runBlocking {
        val signaler = newSignaler()
        val events = CopyOnWriteArrayList<SignalEvent>()
        val collector = launch { signaler.events.collect { events += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }
        signaler.createRoom("甲", null)

        fake.pushPeerJoined("user-b", "乙")
        awaitUntil("玩法处理器收到 peerJoined") { handler.peerJoined.size == 1 }
        awaitUntil("事件流发出 PeerJoined") {
            events.filterIsInstance<SignalEvent.PeerJoined>().any { it.userId == UserId("user-b") }
        }
        awaitUntil("成员列表出现对端") {
            signaler.room.value?.members?.any { it.userId == UserId("user-b") && it.online } == true
        }

        fake.pushPeerLeft("user-b")
        awaitUntil("玩法处理器收到 peerLeft") { handler.peerLeft.size == 1 }
        awaitUntil("事件流发出 PeerLeft") {
            events.filterIsInstance<SignalEvent.PeerLeft>().any { it.userId == UserId("user-b") }
        }
        // §5.4 degraded：对端离线但**房间还在**，座位保留、改成离线
        awaitUntil("成员被标为离线而房间保留") {
            val room = signaler.room.value
            room != null && room.members.any { it.userId == UserId("user-b") && !it.online }
        }
        collector.cancel()
    }

    @Test
    fun `unknown sys 动作只计数_不打断连接`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.push("sys.foo", buildJsonObject { put("whatever", 1) })

        awaitUntil("未知类型被计数") { metrics.unknownTypes.contains("sys.foo") }
        assertEquals(SignalState.Online, signaler.state.value)

        // 后续业务照常
        signaler.send("cinema.play", buildJsonObject { put("positionMs", 1) })
        awaitUntil("cinema_play 已送达服务端") { fake.typesReceived().contains("cinema.play") }
        assertEquals(SignalState.Online, signaler.state.value)
    }

    @Test
    fun `玩法域消息进 MessageRouter_而不是被当成未知类型`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.push("cinema.pause", buildJsonObject { put("positionMs", 42) })

        awaitUntil("CinemaHandler 收到 pause") { handler.actions.contains("pause") }
        assertTrue("已知玩法域不该被计为未知类型：${metrics.unknownTypes}", metrics.unknownTypes.isEmpty())
    }

    @Test
    fun `sys_error 作为请求回复时抛出对应错误码_而不是只发个事件`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.createRoomErrorCode.set("CREATE_RATE_LIMITED")

        val error = try {
            signaler.createRoom("甲", null)
            null
        } catch (e: SignalException) {
            e
        }

        assertNotNull("建房被限流时必须抛 SignalException", error)
        assertEquals(SignalErrors.CREATE_RATE_LIMITED, error!!.code)
        assertNull(signaler.room.value)
    }

    // ── 自定义房号（`sys.create_room` 的 `payload.roomId`）─────────────────────

    @Test
    fun `指定房号建房时 payload 里恰好一个 roomId 且等于请求值`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val room = signaler.createRoom("甲", "pw", "CD45EF")

        assertEquals("CD45EF", room.roomId)

        val sent = fake.lastReceived("sys.create_room")
        // 房号**只在 payload 里**：信封那份是 join/resume 的语义，服务端也只读 payload。
        // 写进信封不算错、但会让「只读 payload」这条契约在抓包时分不清，所以钉住它是 null。
        assertNull("create_room 的信封 roomId 必须仍是 null", sent.roomId)
        assertEquals("CD45EF", (sent.payload?.get("roomId") as? JsonPrimitive)?.content)
        assertEquals("甲", (sent.payload?.get("nickname") as? JsonPrimitive)?.content)
        assertEquals("pw", (sent.payload?.get("password") as? JsonPrimitive)?.content)
    }

    @Test
    fun `不指定房号时 payload 里没有 roomId 键_而不是显式 null`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        signaler.createRoom("甲", null)

        val payload = fake.lastReceived("sys.create_room").payload
        assertNotNull(payload)
        // 「省略字段」才是与旧服务端互通的形态；显式 null 会被规范化当成空串、
        // 行为虽然一样，却让抓包分不清「没传」和「传了个空值」。
        assertFalse("省略房号时不该出现 roomId 键", payload!!.containsKey("roomId"))
    }

    @Test
    fun `旧服务端忽略房号时抛 CLIENT_ROOM_ID_MISMATCH 并退出那个不是我要的房间`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        // 协议没有版本号，「新客户端 → 旧服务端」时唯一的保护就是回执值校验（§9.2）
        fake.ignoreCreateRoomId.set(true)

        val error = try {
            signaler.createRoom("甲", null, "CD45EF")
            null
        } catch (e: SignalException) {
            e
        }

        assertNotNull("回执房号不等于请求值时必须抛异常，不能照单全收", error)
        assertEquals(SignalErrors.CLIENT_ROOM_ID_MISMATCH, error!!.code)
        assertTrue(
            "提示里要同时带上请求值与实际值，否则用户不知道发生了什么",
            error.message.orEmpty().contains("CD45EF") && error.message.orEmpty().contains("AB23CD"),
        )
        // 关键：`room` 是 StateFlow，onResponseEnvelope 已经把它写进去了。不清掉的话
        // 调用方收到异常、界面却显示「已在房间里」—— 用户会留在一个他没要的房间里。
        assertNull("识破降级后必须退出那个房间", signaler.room.value)
        awaitUntil("客户端补发 leave_room 真的退出去了") {
            fake.typesReceived().contains("sys.leave_room")
        }
    }

    @Test
    fun `房号被占用时 ROOM_CODE_TAKEN 透传给调用方`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.createRoomErrorCode.set(SignalErrors.ROOM_CODE_TAKEN)

        val error = try {
            signaler.createRoom("甲", null, "CD45EF")
            null
        } catch (e: SignalException) {
            e
        }

        // 冲突要能被界面分流成「换个号」，所以码必须原样透传、不能被吞成通用错误
        assertEquals(SignalErrors.ROOM_CODE_TAKEN, error?.code)
        assertNull(signaler.room.value)
    }

    // ── 房间清单（`sys.list_rooms`，阶段一 1.8）─────────────────────────────

    @Test
    fun `拉房间清单_解析出房号与有无密码_且不把当前房间写进去`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val rooms = signaler.listRooms()

        assertEquals(2, rooms.size)
        assertEquals(FakeSignalingServer.ROOM_ID, rooms[0].roomId)
        assertTrue("带密码的房间必须标出来 —— 界面据此弹密码框，否则用户会白点一次", rooms[0].hasPassword)
        assertEquals(1, rooms[0].memberCount)
        assertEquals(2, rooms[0].maxSeats)
        assertTrue(rooms[0].hasFreeSeat)
        assertEquals("甲", rooms[0].hostNickname)
        assertFalse(rooms[1].hasPassword)
        assertEquals("XY99ZW", rooms[1].roomId)

        // 查询**不是**进房。写进 _room 的话界面会立刻跳到「已在房间里」——
        // 用户只是想看看有哪些房间，结果被塞进了一个他没选的房间。
        assertNull("拉清单不该动当前房间视图", signaler.room.value)

        // 信封形态：清单是全局查询，与任何一个房间都无关
        val sent = fake.lastReceived("sys.list_rooms")
        assertNull("sys.list_rooms 的信封 roomId 应为 null", sent.roomId)
    }

    @Test
    fun `房间清单里的坏条目被丢掉_不把整份清单变成异常`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.listRoomsEntries.set(
            listOf(
                // 只有房号：其余字段全缺 → 走默认值，这一条仍然可用
                buildJsonObject { put("roomId", "AB23CD") },
                // 缺房号 → 整条丢掉（没有房号的房间行是没法点的）
                buildJsonObject { put("hasPassword", true) },
            ),
        )

        val rooms = signaler.listRooms()

        assertEquals("只该剩下那条有房号的", 1, rooms.size)
        assertEquals("AB23CD", rooms[0].roomId)
        assertFalse("hasPassword 缺失时按「没有密码」处理", rooms[0].hasPassword)
        assertEquals(0, rooms[0].memberCount)
        assertFalse(rooms[0].hasFreeSeat)
    }

    @Test
    fun `旧服务端不认识 list_rooms 时抛 UNKNOWN_TYPE_而不是静默给空清单`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        // 旧服务端的 KNOWN_SYS_ACTIONS 里没有 list_rooms，回的就是这个码
        fake.listRoomsErrorCode.set(SignalErrors.UNKNOWN_TYPE)

        val error = try {
            signaler.listRooms()
            null
        } catch (e: SignalException) {
            e
        }

        // 吞成空清单的话，界面会显示「现在没有房间」，而真相是「这台服务端不支持查询」——
        // 前者让用户一直点刷新，后者要他换服务端。两种处置完全不同，不能混。
        assertEquals(SignalErrors.UNKNOWN_TYPE, error?.code)
    }

    @Test
    fun `未连接时 send 抛 CLIENT_NOT_CONNECTED`() = runBlocking {
        val signaler = newSignaler()

        val error = try {
            signaler.send("cinema.play", null)
            null
        } catch (e: SignalException) {
            e
        }

        assertNotNull(error)
        assertEquals(SignalErrors.CLIENT_NOT_CONNECTED, error!!.code)
    }

    @Test
    fun `地址不合法时 connect 抛 CLIENT_BAD_ENDPOINT 且不发起连接`() = runBlocking {
        val signaler = newSignaler()

        val error = try {
            signaler.connect("   ", UserId("user-a"))
            null
        } catch (e: SignalException) {
            e
        }

        assertNotNull(error)
        assertEquals(SignalErrors.CLIENT_BAD_ENDPOINT, error!!.code)
        assertEquals(SignalState.Disconnected, signaler.state.value)
    }

    @Test
    fun `close 之后不再重连`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        signaler.close()
        val sessionsAtClose = fake.sessions.size

        delay(500)   // 远超测试用的 100ms 退避上限；若仍在重连，这里必然多出连接

        assertEquals(SignalState.Disconnected, signaler.state.value)
        assertEquals("close 后不该再建新连接", sessionsAtClose, fake.sessions.size)
    }

    // ── rtc.* 消化（阶段一 1.4）─────────────────────────────────────────────

    /**
     * §4.2 说 `rtc.*`「由底座自己消化，不进 MessageRouter」。这条用例就是在钉这个契约：
     * 五类动作各来一条，字段要逐个对上，且**一条都不能落进玩法处理器**。
     *
     * 之所以值得单独写：`rtc.ice` 的 `candidate` 允许为空（end-of-candidates），
     * 而 `sdpMLineIndex` 是数字。若把这两个当成「缺字段」丢掉，症状是协商偶发卡死 ——
     * 在真机上表现为「有时候能连上、有时候要重试」，极难定位。
     *
     * `rtc.renegotiate` 与 `rtc.reset` 各来两条：**带 `reason` 与缺键**两种形态都要认
     * （缺键 = 旧包发的，判据在对端，本层只如实转成 `null`）。
     */
    @Test
    fun `rtc 五个动作都转成类型化信号_且不进玩法处理器`() = runBlocking {
        val signaler = newSignaler()
        val signals = CopyOnWriteArrayList<RtcSignal>()
        val collector = launch { signaler.rtcSignals.collect { signals += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }
        signaler.joinRoom(FakeSignalingServer.ROOM_ID, "甲", null)

        fake.pushFromPeer("user-b", "rtc.offer", buildJsonObject { put("sdp", "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n") })
        fake.pushFromPeer(
            "user-b",
            "rtc.answer",
            buildJsonObject { put("sdp", "v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\n") },
        )
        fake.pushFromPeer(
            "user-b",
            "rtc.ice",
            buildJsonObject {
                put("candidate", "candidate:1 1 udp 2113937151 192.168.1.9 54321 typ host")
                put("sdpMid", "0")
                put("sdpMLineIndex", 0)
            },
        )
        // end-of-candidates：candidate 键不存在（libwebrtc 在上报结束时就是这么发的）
        fake.pushFromPeer("user-b", "rtc.ice", buildJsonObject { put("sdpMid", "0"); put("sdpMLineIndex", 0) })
        // `rtc.renegotiate`：**缺键**（旧包，含义 = ice）与带 `reason` 两种都要认。
        fake.pushFromPeer("user-b", "rtc.renegotiate", buildJsonObject { })
        fake.pushFromPeer("user-b", "rtc.renegotiate", buildJsonObject { put("reason", "audio") })
        // 阶段二 2.4：带 reason 与**不带 reason**（缺键）两种都要认。
        fake.pushFromPeer("user-b", "rtc.reset", buildJsonObject { put("reason", "manual") })
        fake.pushFromPeer("user-b", "rtc.reset", buildJsonObject { })

        awaitUntil("收齐 8 条 rtc 信号") { signals.size == 8 }

        val peer = UserId("user-b")
        assertEquals(RtcSignal.Offer(peer, "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n"), signals[0])
        assertEquals(RtcSignal.Answer(peer, "v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\n"), signals[1])
        assertEquals(
            RtcSignal.Ice(peer, "candidate:1 1 udp 2113937151 192.168.1.9 54321 typ host", "0", 0),
            signals[2],
        )
        assertEquals("candidate 为空是 end-of-candidates，不是错误", RtcSignal.Ice(peer, null, "0", 0), signals[3])
        assertEquals(
            "缺键 = 旧包（那时这条消息只有「重启 ICE」一种含义），原样转成 null 交给判据",
            RtcSignal.Renegotiate(peer, null),
            signals[4],
        )
        assertEquals(RtcSignal.Renegotiate(peer, "audio"), signals[5])
        assertEquals(RtcSignal.Reset(peer, "manual"), signals[6])
        assertEquals("reason 缺键就是 null，不是空串", RtcSignal.Reset(peer, null), signals[7])

        // 全部被底座吃掉：既没有进 handler，也没有被记成未知前缀
        assertTrue("rtc.* 不该进玩法处理器", handler.actions.isEmpty())
        assertEquals("rtc.* 不该被计成未知前缀", emptyList<String>(), metrics.unknownTypes)

        collector.cancel()
    }

    /**
     * 顺序回归用例（钉住 `WsSignaler.receivePath` 的 parallelism = 1）。
     *
     * 为什么值得单独立一条：真实协商就是「一条 offer 紧跟一串 ICE 候选」的连发形态，
     * 而**乱序会真的丢候选** —— `addIceCandidate` 在 `remoteDescription` 为空时
     * libwebrtc 直接返回 false 且不缓存。丢掉最好的那条候选，表现就是「有时连不上」
     * 或「绕远路走中继」，在真机上几乎无法归因。
     *
     * 这条用例用 30 条候选而不是 2 条：并发乱序是概率性的，报文越多越容易暴露；
     * 同时断言**整条序列逐项相等**，而不是「集合相等」。
     */
    @Test
    fun `rtc 报文按到达顺序处理_ICE 不得越过 offer`() = runBlocking {
        val signaler = newSignaler()
        val signals = CopyOnWriteArrayList<RtcSignal>()
        val collector = launch { signaler.rtcSignals.collect { signals += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val candidates = 30
        fake.pushFromPeer("user-b", "rtc.offer", buildJsonObject { put("sdp", "v=0\r\noffer\r\n") })
        repeat(candidates) { i ->
            fake.pushFromPeer(
                "user-b",
                "rtc.ice",
                buildJsonObject {
                    put("candidate", "candidate:$i 1 udp 1 192.168.1.9 5000$i typ host")
                    put("sdpMid", "0")
                    put("sdpMLineIndex", 0)
                },
            )
        }

        awaitUntil("收齐 1 + $candidates 条") { signals.size == candidates + 1 }

        val peer = UserId("user-b")
        assertEquals("offer 必须是第一条", RtcSignal.Offer(peer, "v=0\r\noffer\r\n"), signals[0])
        val expected = (0 until candidates).map {
            RtcSignal.Ice(peer, "candidate:$it 1 udp 1 192.168.1.9 5000$it typ host", "0", 0)
        }
        assertEquals("候选顺序必须与发送顺序逐一对应", expected, signals.drop(1))

        collector.cancel()
    }

    /**
     * 两种「不完整」要区别对待：`candidate` 缺失是**正常语义**，
     * 而整个 payload 缺失是对端实现有问题 —— 后者报协议错配并丢弃，连接照常。
     */
    @Test
    fun `rtc_ice_缺整个_payload_算协议错配_而缺_candidate_不算`() = runBlocking {
        val signaler = newSignaler()
        val signals = CopyOnWriteArrayList<RtcSignal>()
        val events = CopyOnWriteArrayList<SignalEvent>()
        val rc = launch { signaler.rtcSignals.collect { signals += it } }
        val ec = launch { signaler.events.collect { events += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.pushFromPeer("user-b", "rtc.ice", null)
        fake.pushFromPeer("user-b", "rtc.offer", buildJsonObject { put("candidate", "no-sdp-here") })

        awaitUntil("收到两条协议错配") {
            events.count { it is SignalEvent.Error && it.code == SignalErrors.CLIENT_MALFORMED } == 2
        }
        assertEquals("错的报文不该进信号流", emptyList<RtcSignal>(), signals)
        assertEquals("一条坏消息不该打断连接", SignalState.Online, signaler.state.value)

        rc.cancel()
        ec.cancel()
    }

    /** 未知的 `rtc.*` 动作走 §4.2 的「未知前缀：计数 + 丢弃」，不抛异常、不断连接。 */
    @Test
    fun `未知的_rtc_动作计入未知前缀且连接不断`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.pushFromPeer("user-b", "rtc.hologram", buildJsonObject { put("x", 1) })

        awaitUntil("计入未知前缀") { metrics.unknownTypes.contains("rtc.hologram") }
        assertEquals(SignalState.Online, signaler.state.value)
    }

    /**
     * 服务端只把 `rtc.*` 转发给对端，正常收不到自己的消息；这里防的是
     * 「万一哪天服务端改成广播，我们会把自己的 offer 当对端的」——
     * 那会让 libwebrtc 把远端描述设成自己刚发的 offer，协商直接错乱。
     */
    @Test
    fun `来自自己的_rtc 消息被丢弃`() = runBlocking {
        val signaler = newSignaler()
        val signals = CopyOnWriteArrayList<RtcSignal>()
        val collector = launch { signaler.rtcSignals.collect { signals += it } }

        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        fake.pushFromPeer("user-a", "rtc.offer", buildJsonObject { put("sdp", "v=0\r\n") })
        // 紧跟一条对端的，用来确认「不是整体坏了，只是自己的被丢」
        fake.pushFromPeer("user-b", "rtc.offer", buildJsonObject { put("sdp", "v=0\r\n") })

        awaitUntil("收到对端那条") { signals.size == 1 }
        assertEquals(RtcSignal.Offer(UserId("user-b"), "v=0\r\n"), signals[0])

        collector.cancel()
    }

    // ── rtc.* 构包（阶段一 1.4，出站方向）───────────────────────────────────

    /**
     * 出站方向的对称用例：底座既负责拆包、也负责装包，键名只实现一次。
     *
     * 为什么值得写：入站的键名已由上面几条钉住，但出站若写在别处（例如 `app` 里手拼
     * JSON），两边就会各自演化。而**键名写错不会报任何错** —— 对端解析出的
     * `candidate` 永远是 null，于是每条 `rtc.ice` 都被当成 end-of-candidates，
     * 远端候选集合始终是空的，表现为「协商看起来很顺但就是连不上」。
     *
     * 同时钉住 `seq` 严格递增：服务端用它做陈旧报文判定（`staleDropped`），
     * 乱掉的序号会让合法报文被当作过期丢掉。
     */
    @Test
    fun `出站 rtc 五类信令的信封形态与载荷键名`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }
        signaler.joinRoom(FakeSignalingServer.ROOM_ID, "甲", null)

        // 阶段二 v2：`rtc.*` **全部**带 `to`（契约 §6）。目标是谁不影响本用例要验的
        // 东西（键名与顺序），所以固定一个人 —— 但信封上那个 `to` 必须真的出现。
        val peer = UserId("user-b")
        signaler.sendRtcOffer(peer, "v=0\r\noffer\r\n")
        signaler.sendRtcAnswer(peer, "v=0\r\nanswer\r\n")
        signaler.sendRtcIce(peer, "candidate:1 1 udp 1 192.168.1.9 5000 typ host", "0", 0)
        // end-of-candidates：三个字段全空，但**这条信封必须照发**（对端据此才知道
        // 远端候选集合已完整）—— 被本地吞掉的话对端会一直等。
        signaler.sendRtcIce(peer, null, null, null)
        // 两种 `reason` 都按值发出去：对端按它决定要不要重启 ICE（§5.2 的行为判据）。
        signaler.requestRenegotiation(peer, RtcSignal.RENEGOTIATE_REASON_AUDIO)
        signaler.requestRenegotiation(peer, RtcSignal.RENEGOTIATE_REASON_ICE)
        signaler.sendRtcReset(peer, "manual")
        // 不带 reason：整个 `reason` 键都不该出现（对端读到的同样是 null）。
        signaler.sendRtcReset(peer, null)

        awaitUntil("服务端收齐 8 条 rtc") { fake.rtcReceived().size == 8 }

        val rtc = fake.rtcReceived()
        assertEquals(
            "动作与调用顺序必须一致",
            listOf(
                "rtc.offer",
                "rtc.answer",
                "rtc.ice",
                "rtc.ice",
                "rtc.renegotiate",
                "rtc.renegotiate",
                "rtc.reset",
                "rtc.reset",
            ),
            rtc.map { it.type },
        )

        assertEquals(buildJsonObject { put("sdp", "v=0\r\noffer\r\n") }, rtc[0].payload)
        assertEquals(buildJsonObject { put("sdp", "v=0\r\nanswer\r\n") }, rtc[1].payload)
        assertEquals(
            buildJsonObject {
                put("candidate", "candidate:1 1 udp 1 192.168.1.9 5000 typ host")
                put("sdpMid", "0")
                put("sdpMLineIndex", 0)
            },
            rtc[2].payload,
        )
        assertEquals("三个字段都为空时不该凭空补出 `candidate: null`", buildJsonObject { }, rtc[3].payload)
        assertEquals(buildJsonObject { put("reason", "audio") }, rtc[4].payload)
        assertEquals(buildJsonObject { put("reason", "ice") }, rtc[5].payload)
        assertEquals(buildJsonObject { put("reason", "manual") }, rtc[6].payload)
        assertEquals("没有 reason 时整个键都不出现，而不是 `reason: null`", buildJsonObject { }, rtc[7].payload)

        // 信封其余字段照旧：`from` 是自己（服务端绑定，客户端不可伪造），
        // roomId 取当前房间 —— 服务端对不在房间里的 rtc.* 回 NOT_IN_ROOM。
        assertEquals("user-a", rtc[0].from)
        // §6：`to` 必填，且必须逐字进信封 —— 服务端靠它找座位，缺了直接 BAD_PAYLOAD。
        assertEquals("每一条 rtc.* 的信封都必须带 to", List(8) { "user-b" }, rtc.map { it.to })
        assertEquals(FakeSignalingServer.ROOM_ID, rtc[0].roomId)
        assertEquals(
            "seq 必须严格递增，否则服务端会当陈旧报文丢掉",
            rtc.map { it.seq }.sorted(),
            rtc.map { it.seq },
        )
        assertEquals("seq 不该重复", 8, rtc.map { it.seq }.toSet().size)
    }

    /**
     * 「本机还停在同一房号里，又发了一次加入请求」= **进房**，不是恢复会话。
     *
     * ── 为什么单靠上一条用例不够 ─────────────────────────────────────────────
     * 上一条走的是「`leave()` 之后再进同一个房号」：`previous` 是 `null`，
     * 房号判据（`previous == null ⇒ 进房`）恰好也能判对。而 1.11 修掉的真机缺陷
     * 走的是**另一格**：本机的房间视图**还停在这个房号上**（`_room` 没被清空），
     * 用户又发了一次 `sys.join_room` —— 房号前后**必然**相同，旧判据于是把
     * 它判成「回到原房」，派发的是 `sessionRestored`。
     *
     * 后果就是用户报的那一幕：玩法层收不到「这是一场新会话、通话意图要清零」，
     * 上一场的 `callRequested`（进程级、刻意跨链路存活）跟进了新的一场，
     * 下一次建链时麦克风自己接回来 —— 「进房之后通话还是打开状态、绿点亮着」，
     * 而用户没按过任何开关。
     */
    @Test
    fun `本机仍在同号房里再发加入请求也派发进房事件`() = runBlocking {
        val signaler = newSignaler()
        signaler.connect(wsUrl(), UserId("user-a"))
        awaitUntil("连接进入 Online") { signaler.state.value == SignalState.Online }

        val room = signaler.createRoom("甲", null)
        awaitUntil("建房派发进房事件") { handler.entered.size == 1 }

        // 刻意**不** leave：房间视图就停在这个房号上，这正是旧判据误判的那一段。
        signaler.joinRoom(room.roomId, "乙", null)

        awaitUntil("同一房号再进也派发进房事件") { handler.entered.size == 2 }
        assertEquals(room.roomId, handler.entered.last().roomId)
        assertTrue("同号重进不是「恢复会话」", handler.restored.isEmpty())
    }

    /**
     * 判据本身的完整真值表（纯函数，不起连接）。
     *
     * 两支都要钉住：**请求类型是主判据**（发起方自己记着这条请求是什么，与房号无关），
     * 房号比较只留作服务端**主动推送**那条路的兜底（那时没有请求类型可看）。
     *
     * 每一条对应一个具体的真机场景，注释里写明是哪一个 —— 判据表最容易在
     * 「顺手简化一下」时被改回按房号比较，而那样改完**没有任何报错**，
     * 只在真机上表现为「进房又自己开着通话」。
     */
    @Test
    fun `进房判据_请求类型优先_房号只作主动推送的兜底`() {
        val same = roomOf("AB23CD")
        val other = roomOf("ZZ99YY")

        // ① 有请求类型：`sys.resume` 之外的每一条都是进房 —— **与房号无关**。
        //    第一条就是旧判据漏掉的那一格（同号 + 加入请求），也就是上面那条用例的纯函数版。
        assertTrue("同号重进也是进房（旧判据在这里判成恢复）", isEnteringRoom("sys.join_room", same, same))
        assertTrue("建房没有上一场可比", isEnteringRoom("sys.create_room", null, same))
        assertTrue("异号加入当然进房", isEnteringRoom("sys.join_room", other, same))

        // ② `sys.resume` 永远不是进房，**哪怕本机此刻不在任何房间里**：
        //    断线重连时本地视图可能已被清空，把 resume 当进房会掐掉 §9.4 宽限期里
        //    正在进行的通话（对端什么都没感觉到，自己这边麦就没了）。
        assertFalse("resume 是「回到原房」", isEnteringRoom("sys.resume", same, same))
        assertFalse("本地视图没了，resume 也不是进房", isEnteringRoom("sys.resume", null, same))

        // ③ 没有请求类型（服务端主动推 `sys.room_created` / `sys.room_joined`）：只能比房号。
        assertTrue("原来不在房里 ⇒ 进房", isEnteringRoom(null, null, same))
        assertTrue("房号变了 ⇒ 进房", isEnteringRoom(null, other, same))
        assertFalse("同号 ⇒ 不是进房（那是回来）", isEnteringRoom(null, same, same))

        // ④ 房号比较大小写不敏感：服务端会把用户输入原样回下来，而用户可能输的是小写。
        assertFalse("大小写不同仍是同一个房号", isEnteringRoom(null, roomOf("ab23cd"), same))
    }

    private fun roomOf(roomId: String) = Room(
        roomId = roomId,
        hostUserId = UserId("user-a"),
        mode = "cinema",
        members = emptyList(),
        modeState = null,
    )
}

// ── 测试替身 ───────────────────────────────────────────────────────────────

private class RecordingMetrics : RouteMetrics {
    val unknownTypes = CopyOnWriteArrayList<String>()
    override fun unknownType(type: String) {
        unknownTypes += type
    }
}

private class FakeCinemaHandler : PlayModeHandler {
    override val mode: String = "cinema"
    val actions = CopyOnWriteArrayList<String>()
    val peerJoined = CopyOnWriteArrayList<UserId>()
    val peerLeft = CopyOnWriteArrayList<UserId>()
    val restored = CopyOnWriteArrayList<Room>()
    val entered = CopyOnWriteArrayList<Room>()

    override suspend fun onPeerJoined(userId: UserId, nickname: String) {
        peerJoined += userId
    }

    override suspend fun onPeerLeft(userId: UserId) {
        peerLeft += userId
    }

    override suspend fun onMessage(action: String, env: Envelope) {
        actions += action
    }

    override suspend fun onRoomEntered(room: Room) {
        entered += room
    }

    override suspend fun onSessionRestored(room: Room) {
        restored += room
    }
}

/**
 * 进程内假信令服务端。应答形态**照抄** `server/src/signaling.mjs` 的 `sendEnvelope`，
 * 所以这里对不上的字段，在真服务端上一样对不上。
 */
private class FakeSignalingServer : WebSocketListener() {

    val sessions = CopyOnWriteArrayList<WebSocket>()
    private val receivedEnvelopes = CopyOnWriteArrayList<Envelope>()

    val resumeFails = java.util.concurrent.atomic.AtomicBoolean(false)
    val createRoomErrorCode = AtomicReference<String?>(null)

    /**
     * 非 null ⇒ `sys.list_rooms` 回 `sys.error`（用来验「旧服务端不认识这条消息」：
     * 真服务端的 `KNOWN_SYS_ACTIONS` 里没有它，回的就是 `UNKNOWN_TYPE`）。
     */
    val listRoomsErrorCode = AtomicReference<String?>(null)

    /** 覆盖 `sys.room_list` 的条目（null = 用 [defaultRoomListEntries]）。 */
    val listRoomsEntries = AtomicReference<List<JsonObject>?>(null)

    /**
     * true ⇒ 装成**旧服务端**：`sys.create_room` 只读 `nickname`/`password`，
     * 多余的 `payload.roomId` 被静默忽略、回一个随机号（= 客户端的防静默降级用例要识破的形态）。
     */
    val ignoreCreateRoomId = java.util.concurrent.atomic.AtomicBoolean(false)

    private var serverSeq = 0L

    override fun onOpen(webSocket: WebSocket, response: Response) {
        sessions += webSocket
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // OkHttp **两侧**都不会自动回 close 帧（真服务端用的 Node `ws` 会，所以线上不会出问题）。
        // 这里不回的话，服务端这条 socket 停在半关闭，MockWebServer.shutdown() 要空等 5 秒
        // 才放弃 —— 实测每个用例白花 5014ms，整个类多花 40 秒。
        webSocket.close(code, reason)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val env = EnvelopeCodec.decode(text)
        receivedEnvelopes += env

        when (env.type) {
            "sys.create_room" -> {
                val errorCode = createRoomErrorCode.get()
                if (errorCode != null) {
                    send(webSocket, "sys.error", null, buildJsonObject {
                        put("code", errorCode)
                        put("message", "假服务端按测试要求拒绝建房")
                    })
                } else {
                    // 真服务端（cd693f0 起）：payload 指定了房号就**必须**用它，回执 roomId == 请求值。
                    // ignoreCreateRoomId 打开时走旧行为：只读 nickname/password，回随机号。
                    val requested = env.payload.stringOrNull("roomId")
                    val granted =
                        if (!ignoreCreateRoomId.get() && requested != null) requested else ROOM_ID
                    send(webSocket, "sys.room_created", granted, buildJsonObject {
                        put("roomId", granted)
                        put("hostUserId", env.from)
                        put("mode", "cinema")
                        put("iceServers", iceServersOf(turnAvailable = false))
                    })
                }
            }

            "sys.join_room", "sys.resume" -> {
                if (env.type == "sys.resume" && resumeFails.get()) {
                    send(webSocket, "sys.error", null, buildJsonObject {
                        put("code", "RESUME_FAILED")
                        put("message", "座位已不存在")
                    })
                } else {
                    send(webSocket, "sys.room_joined", ROOM_ID, buildJsonObject {
                        put("roomId", ROOM_ID)
                        put("hostUserId", "user-a")
                        put("mode", "cinema")
                        put("members", buildJsonArray {
                            add(buildJsonObject {
                                put("userId", env.from)
                                put("nickname", "乙")
                                put("online", true)
                            })
                            add(buildJsonObject {
                                put("userId", "user-b")
                                put("nickname", "甲")
                                put("online", true)
                            })
                        })
                        put("iceServers", iceServersOf(turnAvailable = true))
                    })
                }
            }

            "sys.ping" -> send(webSocket, "sys.pong", null, buildJsonObject { put("t", 0) })

            "sys.list_rooms" -> {
                val errorCode = listRoomsErrorCode.get()
                if (errorCode != null) {
                    send(webSocket, "sys.error", null, buildJsonObject {
                        put("code", errorCode)
                        put("message", "假服务端按测试要求拒绝清单查询")
                    })
                } else {
                    val entries = listRoomsEntries.get() ?: defaultRoomListEntries()
                    send(webSocket, "sys.room_list", null, buildJsonObject {
                        put("rooms", buildJsonArray { entries.forEach { add(it) } })
                    })
                }
            }

            // 服务端在 leave_room 成功时不回执（与 signaling.mjs 一致），这里刻意什么都不发
            "sys.leave_room" -> Unit

            // 其余（cinema.* / rtc.*）也不回执 —— 它们本来就靠对端转发，服务端不产生回复
            else -> Unit
        }
    }

    fun typesReceived(): List<String> = receivedEnvelopes.map { it.type }

    fun lastReceived(type: String): Envelope = receivedEnvelopes.last { it.type == type }

    /** 出站 `rtc.*` 信封，按发送顺序。用于钉住构包形态（阶段一 1.4）。 */
    fun rtcReceived(): List<Envelope> = receivedEnvelopes.filter { it.type.startsWith("rtc.") }

    fun closeAllSessions() {
        // 只能用 close()。**不能用 cancel()**：MockWebServer 造出来的服务端 socket
        // 内部 `call` 为空，`RealWebSocket.cancel()` 会直接 NPE（实测）。
        // close() 走的是 WS 关闭握手，代表「服务端主动优雅关闭」这条路 —— 也就是
        // 客户端必须自己在 onClosing 里回 close 帧的那条（OkHttp 不会替我们回，
        // 见 WsSignaler.onClosing 的注释）。真「拔网」（无关闭帧、TCP 直接断）走的是
        // onFailure，由 `真拔网…` 那条用例单独覆盖，两条路都要有用例，别互相代替。
        sessions.forEach { it.close(1001, "simulated server shutdown") }
    }

    fun push(type: String, payload: kotlinx.serialization.json.JsonObject?) {
        val socket = sessions.lastOrNull() ?: return
        send(socket, type, ROOM_ID, payload)
    }

    fun pushPeerJoined(userId: String, nickname: String) = pushCounterpart("sys.peer_joined", userId, nickname)

    /**
     * 模拟服务端把**对端**的消息转发过来。
     *
     * 与 [push] 的区别只在信封的 `from`：真服务端转发 `rtc.*` 时用的是
     * `forwardToSeat(peer, env)` —— 原样转发对端那只信封，所以 `from` 是对端的 userId，
     * 不是 `"server"`。这个细节必须还原：客户端要靠 `from` 认出「这条是我的对端发来的」，
     * 也要靠它丢弃自己的回声。
     */
    fun pushFromPeer(peerUserId: String, type: String, payload: kotlinx.serialization.json.JsonObject?) {
        val socket = sessions.lastOrNull() ?: return
        serverSeq += 1
        socket.send(
            EnvelopeCodec.encode(
                Envelope(
                    type = type,
                    seq = serverSeq,
                    roomId = ROOM_ID,
                    from = peerUserId,
                    sentAtMs = 0,
                    payload = payload,
                ),
            ),
        )
    }

    fun pushPeerLeft(userId: String) = pushCounterpart("sys.peer_left", userId, null)

    private fun pushCounterpart(type: String, userId: String, nickname: String?) {
        val socket = sessions.lastOrNull() ?: return
        send(socket, type, ROOM_ID, buildJsonObject {
            put("userId", userId)
            if (nickname != null) put("nickname", nickname)
        })
    }

    private fun send(webSocket: WebSocket, type: String, roomId: String?, payload: kotlinx.serialization.json.JsonObject?) {
        serverSeq += 1
        webSocket.send(
            EnvelopeCodec.encode(
                Envelope(
                    type = type,
                    seq = serverSeq,
                    roomId = roomId,
                    from = "server",
                    sentAtMs = 0,
                    payload = payload,
                ),
            ),
        )
    }

    /** 默认的房间清单：一条带密码、一条不带。形态照抄 `rooms.mjs` 的 `toRoomSummary`。 */
    private fun defaultRoomListEntries(): List<JsonObject> = listOf(
        buildJsonObject {
            put("roomId", ROOM_ID)
            put("hasPassword", true)
            put("memberCount", 1)
            put("maxSeats", 2)
            put("hostNickname", "甲")
        },
        buildJsonObject {
            put("roomId", "XY99ZW")
            put("hasPassword", false)
            put("memberCount", 1)
            put("maxSeats", 2)
            put("hostNickname", "乙")
        },
    )

    private fun iceServersOf(turnAvailable: Boolean) = buildJsonArray {
        add(buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive("stun:turn.example.com:3478")) }) })
        if (turnAvailable) {
            add(
                buildJsonObject {
                    put("urls", buildJsonArray {
                        add(JsonPrimitive("turn:turn.example.com:3478?transport=udp"))
                        add(JsonPrimitive("turns:turn.example.com:5349?transport=tcp"))
                    })
                    put("username", "1799999999:user-a")
                    put("credential", "ZmFrZQ==")
                },
            )
        }
    }

    companion object {
        const val ROOM_ID = "AB23CD"
    }
}
