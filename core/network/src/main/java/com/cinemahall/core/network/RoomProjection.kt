package com.cinemahall.core.network

import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RoomState

/**
 * 房间视图的三个投影字段（契约 §5）：`state` / `aloneSince` / `maxSeats`。
 *
 * ── 为什么需要这一层（2026-11-05 逐行核对服务端后加）────────────────────────────
 * 契约 §5 把这三个字段都列为房间状态的一部分，但**服务端至今一个都没下发**：
 * `sys.room_created` 的载荷是 `{roomId, hostUserId, mode, iceServers}`、
 * `sys.room_joined` 再加 `members[]`、`sys.members_updated` 只有
 * `{hostUserId, members}`（`signaling.mjs` 三处逐行确认）。`maxSeats` 只出现在
 * `sys.room_list` 的投影里，`state` / `aloneSince` 更是从来没离开过 `rooms.mjs`
 * 的进程内存。
 *
 * 而界面必须要它们：「6 座里还空几把椅子」「我已经一个人等了多久、房间快被服务端
 * 收掉了」都推不出来。所以客户端按服务端 `refreshState` 的**同一套判据**自己算一遍，
 * 一旦载荷里出现了权威值就改用权威值 —— 这三种情况的处置全在这里，一处改、两处用。
 *
 * ⚠️ 这里**不再**重复实现「v1 也是 DEGRADED 优先」那套 v1 分支：契约 §9 说发布前
 * 服务端固定在 `PROTOCOL_V2=1`，而 `RoomState.derive` 抄的是 v2 那一支。v1 下
 * `PROTOCOL_V2=0` 的服务端容量是 2，客户端仍按 2 人房工作（`members` 只有两个），
 * 判定结论与 v1 的 `seats.length >= maxSeats` 只在「2 人房满员」这一种情况下不同 ——
 * 那时 v2 判 ACTIVE、v1 也判 ACTIVE，结论一致。
 */
internal object RoomProjection {

    /** 服务端给了 `state` 就用它，否则按成员表现推（口径见 [RoomState.derive]）。 */
    fun state(declared: RoomState?, members: List<Member>): RoomState =
        declared ?: RoomState.derive(members)

    /**
     * 「只剩我一个人」的起点。
     *
     * 三个来路按优先级：
     *  1. [declared]：服务端下发值（现在没有，将来会有）；
     *  2. [previous]：本机上一份视图里的值 —— 必须保留，否则每收到一条
     *     `members_updated`（服务端在任何人上下线时都会重发全量）就重置一次，
     *     计时永远从 0 开始，「已等待 9 分钟」永远不会出现；
     *  3. [nowMs]：真的第一次变成一个人。
     *
     * 0 人在线时**不改**：服务端的 `refreshState` 也只在 `>= 2` 时清零、`== 1` 时立起，
     * 0 人走的是宽限期回收，不是等待超时。
     */
    fun aloneSince(declared: Long?, previous: Long?, onlineCount: Int, nowMs: Long): Long? = when {
        declared != null -> declared
        onlineCount >= 2 -> null
        onlineCount == 1 -> previous ?: nowMs
        else -> previous
    }

    /** 载荷里给了合法容量就用它，否则按契约 §5 的 6 座。 */
    fun maxSeats(declared: Int?): Int = declared?.takeIf { it > 0 } ?: Room.MAX_SEATS
}
