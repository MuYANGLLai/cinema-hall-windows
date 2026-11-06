package com.cinemahall.core.protocol

import com.cinemahall.core.model.Room
import com.cinemahall.core.model.UserId

/**
 * 接缝 2：玩法域的接入点（《技术方案评审》§4.2）。
 *
 * 底座只认识 [mode] 字符串与 `type` 前缀，不解析 [Envelope.payload]。
 * 这是「新增玩法不改底座」的物理边界。
 *
 * 底座域（`sys.*` / `rtc.*`）由 `core:network` 自己消化，**不会**进到这里。
 */
interface PlayModeHandler {

    /** 前缀名，必须与 `type` 的域部分一致，例："cinema"。 */
    val mode: String

    suspend fun onPeerJoined(userId: UserId, nickname: String)

    suspend fun onPeerLeft(userId: UserId)

    /** [action] 已剥离前缀，例如 `cinema.play` 进来时这里收到 "play"。 */
    suspend fun onMessage(action: String, env: Envelope)

    /**
     * **进房事件**：底座确认本机进了一个房间（建房 / 加入那一条应答的成功路径）。
     *
     * ── 与 [onSessionRestored] 的分工，以及为什么必须有这一条 ────────────────────
     * [onSessionRestored] 是「断线重连、回到**原来那个**房间」—— 玩法层要保住会话状态
     * （影厅的通话意图就是刻意跨断线存活的）。本条是「进入一个**新的**房间」——
     * 玩法层要把上一场留下的东西清掉。
     *
     * 两者**不能合并成一条**：服务端对 `sys.join_room` 与 `sys.resume` 回的是同一个
     * 应答类型（`sys.room_joined`），只能按「进的是不是同一个房号」分开。
     *
     * 更关键的是它**为什么不能靠 [Room] 的状态变化代替**：`Signaler.room` 是
     * StateFlow（合并 + 按相等去重），「退房后很快重进同一个房号」这一轮变化会被
     * 去重成「没有变化」，玩法层那时**收不到任何通知** —— 上一场的通话意图于是跟进了
     * 新的一场（真机缺陷：进房就自动开着通话）。事件去重不了，状态流会。
     */
    suspend fun onRoomEntered(room: Room)

    suspend fun onSessionRestored(room: Room)
}
