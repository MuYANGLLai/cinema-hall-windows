package com.cinemahall.core.protocol

import com.cinemahall.core.model.Room
import com.cinemahall.core.model.UserId

/**
 * 未知消息前缀的观测点。
 *
 * 底座遇到不认识的域时**计数 + 丢弃，不抛异常**（§4.2）—— 否则一个版本错配的
 * 对端就能把整条连接打断。把计数抽成接口是为了让 `core:protocol` 不依赖
 * 任何日志/指标实现，真正的实现放在 `app`。
 */
interface RouteMetrics {

    fun unknownType(type: String)

    /** 默认实现：什么都不做。 */
    object Noop : RouteMetrics {
        override fun unknownType(type: String) = Unit
    }
}

/**
 * 按 `type` 前缀把消息派发给对应的 [PlayModeHandler]（《技术方案评审》§4.2）。
 *
 * 除了 `cinema.*` 这类消息派发，它还负责把**房间事件**（有人进出、会话恢复）
 * 转给玩法处理器 —— 这些事件来自底座域 `sys.*`，不是玩法域消息，所以不能走
 * [route]。
 *
 * **只发给房间所属玩法**（`room.mode`），不做全体广播：将来若同时存在两种玩法，
 * 象棋处理器不该收到影厅房间的 `peer_joined`。
 */
class MessageRouter(
    private val handlers: List<PlayModeHandler>,
    private val metrics: RouteMetrics = RouteMetrics.Noop,
) {

    private val byMode: Map<String, PlayModeHandler> = handlers.associateBy { it.mode }

    suspend fun route(env: Envelope) {
        val mode = env.type.substringBefore('.', missingDelimiterValue = "")
        val action = env.type.substringAfter('.', missingDelimiterValue = "")
        val handler = byMode[mode]

        if (handler == null) {
            metrics.unknownType(env.type)   // 未知前缀：计数 + 丢弃，不抛异常
        } else {
            handler.onMessage(action, env)
        }
    }

    suspend fun peerJoined(mode: String, userId: UserId, nickname: String) {
        byMode[mode]?.onPeerJoined(userId, nickname)
    }

    suspend fun peerLeft(mode: String, userId: UserId) {
        byMode[mode]?.onPeerLeft(userId)
    }

    /**
     * 本机进了一个新房间（建房 / 加入成功）。与 [sessionRestored] 是**两条路**：
     * 这条是「进新房 ⇒ 清掉上一场」，那条是「回到原房 ⇒ 保住会话」。理由见
     * [PlayModeHandler.onRoomEntered]。
     */
    suspend fun roomEntered(mode: String, room: Room) {
        byMode[mode]?.onRoomEntered(room)
    }

    /** 断线恢复后重建本地视图（§9.4「重连成功后 sys.resume 恢复会话」）。 */
    suspend fun sessionRestored(mode: String, room: Room) {
        byMode[mode]?.onSessionRestored(room)
    }
}
