package com.cinemahall.core.network

import com.cinemahall.core.model.IceServer
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RoomSummary
import com.cinemahall.core.model.RtcSignal
import com.cinemahall.core.model.UserId
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 接缝 1：网络层对外只暴露这些（《技术方案评审》§4.2）。
 *
 * 本模块不依赖任何 `feature:*`，也不认识任何玩法概念 ——
 * [Signaler.send] 只认 `type` 字符串，不解析 `payload`。
 * 该约束由根构建脚本的**配置期断言**强制，不是靠注释。
 */
sealed interface SignalState {
    data object Disconnected : SignalState
    data object Connecting : SignalState
    data object Online : SignalState
    data object Reconnecting : SignalState
}

/**
 * 瞬态通知。**订阅必须早于 [Signaler.connect]** —— 这是 `SharedFlow` 的语义，
 * 没有订阅者时发出的值直接丢弃，不会补发。
 *
 * 因此「当前房间」这类**需要随时可读**的状态不放在这里，而放在
 * [Signaler.room]（`StateFlow`，晚订阅也能读到当前值）。
 */
sealed interface SignalEvent {

    data class PeerJoined(val userId: UserId, val nickname: String) : SignalEvent

    data class PeerLeft(val userId: UserId) : SignalEvent

    data class RoomClosed(val reason: String) : SignalEvent

    /**
     * 服务端下发了（或续签了）中继凭证。
     *
     * 消费方**必须显式 `restartIce()`** 才会生效 —— libwebrtc 不会自行重取
     * `iceServers`（§3.7.7）。消费在阶段一 1.4。
     *
     * [turnAvailable] 为 false 表示这次没有中继，按 §8.4 必须明确提示、不能空转。
     * 注意它有两种成因，[servers] 是否为空可区分：
     *  - `servers` 非空而 [turnAvailable] 为 false ⇒ 只给了 STUN；
     *  - `servers` **为空** ⇒ 服务端一条都没下发（没配 `TURN_HOST`），连 STUN 都没有。
     */
    data class IceServersUpdated(
        val servers: List<IceServer>,
        val turnAvailable: Boolean,
    ) : SignalEvent

    /**
     * 正在退避重连。带着第几次和等多久，UI 才能显示「正在重连（第 2 次，4 秒后）」
     * 而不是笼统的「网络异常」。
     */
    data class Reconnecting(val attempt: Int, val delayMs: Long) : SignalEvent

    /** 服务端拒绝或本地失败。[code] 取自服务端 `sys.error`；本地错误用 [SignalErrors] 的 `CLIENT_*`。 */
    data class Error(val code: String, val message: String) : SignalEvent
}

interface Signaler {

    val state: StateFlow<SignalState>

    /**
     * 当前房间。未进房、已离开、房间被解散时为 null。
     *
     * 用 `StateFlow` 而不是事件，是因为「我现在在哪个房间」必须能在任意时刻读到 ——
     * 事件流会漏掉订阅之前发生的事。
     */
    val room: StateFlow<Room?>

    /**
     * 本连接的会话代号（§5.1）：**每建一条新连接（含重连）递增一次**，从 1 开始。
     *
     * 之所以要暴露给玩法：`cinema.state_snapshot` 的 `sessionEpoch` 字段要填它（§5.3），
     * 从端靠它区分「这条快照是房主重连之前还是之后发的」。这与 §4.2 给 `seq` 的定位一致 ——
     * **会话级信息由底座产生**，玩法拿不到、也不该自己编一个（自己编就会有两个各自计数的代次）。
     *
     * 做成只读的属性而不是 `StateFlow`：它不会「变化后通知谁」，消费方式是「发消息那一刻
     * 顺手读一下」。等到变化通知再读，读到的已经不是你发那条消息时的值了。
     */
    val sessionEpoch: Long

    val events: SharedFlow<SignalEvent>

    /**
     * 对端经服务端转发的 RTC 信令：`rtc.offer` / `rtc.answer` / `rtc.ice` / `rtc.renegotiate`。
     *
     * §4.2 说「`rtc.*` 由底座自己在 `core:network` 内消化，不进 `MessageRouter`」——
     * 消化的含义是**底座负责拆信封、校验并转成 [RtcSignal]**，而不是把原始 JSON 丢给玩法层。
     * 玩法层（`core:rtc`）因此完全不认识 [com.cinemahall.core.protocol.Envelope]。
     *
     * ⚠️ 与 [events] 同样的 `SharedFlow` 语义：**订阅必须早于 [connect]**。
     * 这里比 [events] 更要紧 —— 丢一条 offer 的后果是这一轮协商直接卡死
     * （对端在等 answer），而丢一条 `peer_joined` 只是 UI 慢一拍。
     * 所以消费方（`app` 的 `PeerCoordinator`）应当在 `connect()` 之前就挂上订阅。
     *
     * 注意 [SignalState] 与 [room] 都不是这里的事 —— 它们是 `StateFlow`，任何时刻都能读。
     */
    val rtcSignals: SharedFlow<RtcSignal>

    /**
     * 建立连接。[endpoint] 支持 `wss://` / `ws://` / `https://` / `http://`，
     * 也可以只给主机名（按 `wss://` 处理），见 [normalizeSignalEndpoint]。
     *
     * 重复调用会先关掉上一条连接。**不会**重连到旧地址 —— 换地址即换服务器。
     */
    suspend fun connect(endpoint: String, userId: UserId)

    /**
     * 建房。
     *
     * [roomId] 缺省或为 null ⇒ 房号由服务端生成；给了 ⇒ 服务端**必须**用它，
     * 被占用时回 [SignalErrors.ROOM_CODE_TAKEN]（绝不静默换号）。
     *
     * 字段放在 **payload** 内（`payload.roomId`），信封的 `roomId` 在 `create_room`
     * 这一路上恒为 null —— 信封那份是 `join_room` / `resume` 的语义，服务端的
     * `handleCreateRoom` 也只读 payload。
     *
     * ⚠️ 传了 [roomId] 时**实现必须校验回执**：协议无版本号，旧服务端会把
     * `payload.roomId` 静默忽略并回一个随机号，所以要拿回执与请求值比对，
     * 不等就抛 [SignalErrors.CLIENT_ROOM_ID_MISMATCH]，而不是照单全收。
     */
    suspend fun createRoom(nickname: String, password: String?, roomId: String? = null): Room

    suspend fun joinRoom(roomId: String, nickname: String, password: String?): Room

    suspend fun resume(roomId: String, userId: UserId, sessionEpoch: Long): Room

    /**
     * 拉一次当前**可加入**的房间清单（阶段一 1.8）。
     *
     * ⚠️ 这是本接口里**唯一一个「不指定房号就能拿到房间信息」的方法**。在此之前
     * 房号本身就是访问凭据（猜不中只会拿到 `ROOM_NOT_FOUND`，服务端还有失败退避）。
     * 服务端为这条消息单独限流，且只下发投影字段（**没有密码明文**，只有
     * [com.cinemahall.core.model.RoomSummary.hasPassword]）。
     *
     * 语义是**一次性查询**，不是订阅：要不要轮询、多久轮一次由调用方决定 ——
     * 底座不替上层维护定时器，也不主动推（服务端不会主动推房间清单）。
     *
     * 未连接时抛 [SignalException]（`CLIENT_NOT_CONNECTED`）；服务端限流时抛
     * `RATE_LIMITED`；旧服务端不认识这条消息时抛 `UNKNOWN_TYPE` ——
     * **刻意不把后者吞成空清单**，否则界面会显示「没有房间」，而真相是
     * 「这台服务端根本不支持查询」，两者的处置完全不同。
     */
    suspend fun listRooms(): List<RoomSummary>

    /**
     * 底座只认识 type 字符串，不解析 payload —— 这是接缝 2 的物理边界。
     *
     * 未连接时抛 [SignalException]（`CLIENT_NOT_CONNECTED`）。
     * 阶段一 1.6 若需要「断线期间的播放指令先缓存、连上再补发」，那是有界出站队列的事，
     * 在本层之外加，不改变这个语义。
     */
    suspend fun send(type: String, payload: JsonObject? = null)

    /**
     * ── 出站 `rtc.*`（阶段一 1.4）────────────────────────────────────────────
     *
     * §4.2 说「`rtc.*` 由底座自己消化」。**构包也是消化的一部分** —— 有 [rtcSignals]
     * 负责拆、就得有人负责装，否则 `rtc.*` 的 payload 字段名会分裂成两处实现
     * （解析在 `core:network`、构包在 `app`），改一处漏一处。
     *
     * 这四个方法解决的就是这件事：上层（`app` 的 `PeerCoordinator`）只需把
     * `core:rtc` 的 [com.cinemahall.core.rtc.RtcSignalSink] 一对一转发过来，
     * 完全不需要认识 `Envelope`、也不需要认识 `sdp` / `sdpMLineIndex` 这些键名。
     *
     * 与 [send] 一样：**未连接时抛 [SignalException]**（`CLIENT_NOT_CONNECTED`）。
     * `core:rtc` 那边把「发送失败」设计成尽力而为、不抛异常，转换在这一层之上做。
     *
     * ⚠️ 顺序即语义：[com.cinemahall.core.rtc.PeerLink] 保证 `sendRtcOffer` 一定先于
     * 随之而来的 `sendRtcIce`，本层只负责如实按调用次序写入同一条 WebSocket。
     *
     * ── 阶段二 v2：这些方法全部带 [to]（契约 §6）────────────────────────────────
     * 多人房间里 `rtc.*` **只单播给一个座位**，服务端按信封的 `to` 转发：缺 / 非法
     * ⇒ `BAD_PAYLOAD`，目标不在房 ⇒ `PEER_OFFLINE`。所以 [to] 不是可选参数 ——
     * 没有它就根本发不出去。这是与 `cinema.*` 最大的差别：那一边还有广播消息，
     * 这一边**一条都没有**。
     *
     * 每条链路一个 [com.cinemahall.core.rtc.RtcSignalSink] 实例、各自绑死自己的目标，
     * 所以 sink 的签名本身不加 `to`（契约 §6 最后一条）。
     */
    suspend fun sendRtcOffer(to: UserId, sdp: String)

    suspend fun sendRtcAnswer(to: UserId, sdp: String)

    /**
     * [candidate] 为 null 表示 **end-of-candidates**（见 [com.cinemahall.core.model.RtcSignal.Ice]）。
     * 此时仍要发出这条信封 —— 整个 payload 缺失才会被对端判为协议错配。
     */
    suspend fun sendRtcIce(to: UserId, candidate: String?, sdpMid: String?, sdpMLineIndex: Int?)

    /**
     * 请求对端（offer 方）重新发起协商。见 [com.cinemahall.core.model.RtcSignal.Renegotiate]。
     *
     * [reason] **是行为判据**（2026-10-14 起）：`ice`（或 null，兼容旧包）= 重启 ICE；
     * `audio` = 只要一轮普通 re-offer（关麦时停掉音频 m 行的发送方向走它）。
     * 常量在 [com.cinemahall.core.model.RtcSignal] 的伴生对象里，别在这里写字面量。
     */
    suspend fun requestRenegotiation(to: UserId, reason: String?)

    /**
     * `rtc.reset`：**整条链路对称重建**（阶段二 2.4，2026-10-14）。
     * 见 [com.cinemahall.core.model.RtcSignal.Reset]。
     *
     * 与 [requestRenegotiation] 的唯一区别是「本端拆不拆」：那条只请对端重新协商、
     * 本端连接**保留**（`重启 ICE` 走它）；这条是**两端都拆、从头再来**（`重建链路` 走它）。
     *
     * [reason] 只进对端日志，可空。
     */
    suspend fun sendRtcReset(to: UserId, reason: String?)

    /**
     * 主动离开当前房间（房主离开会导致房间解散，对端收到 `sys.room_closed`）。
     *
     * 与 [close] 的区别：本方法**保持连接**，之后还能建房或加入。
     */
    suspend fun leave()

    /** 主动断开并停止重连。之后要再用必须重新 [connect]。 */
    suspend fun close()
}
