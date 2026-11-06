package com.cinemahall.core.model

import kotlinx.serialization.json.JsonObject

/**
 * 接缝 3：房间模型通用化（《技术方案评审》§4.2）。
 *
 * 底座不认识任何玩法概念。**播放进度（position、isPlaying 等）一律不进入本类** ——
 * 它只存在于 `feature:cinema` 自己的 `CinemaPlaybackState` 里，对外只以 [Room.modeState]
 * 这个**不透明 JSON** 的形式出现，底座不解包、不解释。
 *
 * 这一条直接对应任务书 §六.3。
 */
data class Member(
    val userId: UserId,
    val nickname: String,
    val online: Boolean,
)

/**
 * 房间的在场状态（契约 §5「服务端 `refreshState` 的硬要求」，阶段二 v2）。
 *
 * ── 为什么客户端也要有一份 ────────────────────────────────────────────────────
 * 服务端在 `rooms.mjs` 的 `refreshState` 里算的正是这三个值，`sweep` 的等待超时
 * 也只看 [WAITING]。但**客户端拿不到它**：`sys.room_created` / `sys.room_joined` /
 * `sys.members_updated` 的载荷里都没有 `state` 字段（2026-11-05 逐行核对
 * `signaling.mjs`）。于是界面要显示「等待别人加入」就只能自己从成员表推一遍 ——
 * [derive] 就是把服务端那段判定**照抄一份**，两边同源同序。
 *
 * 一旦哪天服务端把 `state` 下发下来（契约 §5 里它是权威字段），客户端优先用下发的值：
 * 解析在 `WsSignaler.parseRoom`，[RoomState.parse] 认不出来时回落到 [derive]。
 *
 * ⚠️ 判定**顺序**是语义的一部分，不能换：先看「有没有人掉线」（任意座位
 * `online=false` ⇒ [DEGRADED]），再看在线人数够不够成局。6 座里 2 人在线、
 * 4 个空位是**正常**的 [ACTIVE] —— 没坐满不等于没开局。
 */
enum class RoomState {
    /** 只有一个人在座（或一个人都没有）。服务端的等待超时只在这种状态下计时。 */
    WAITING,

    /** 至少两人在线，且没有掉线的座位。 */
    ACTIVE,

    /** 成员表里有离线座位（掉线但座位还在，§5.4 的宽限期内）。 */
    DEGRADED,

    /** 还不知道：本地还没进房，或载荷里既没给 `state` 也推不出来。 */
    UNKNOWN,
    ;

    companion object {
        /** 线上值（服务端 `ROOM_STATE` 用的是小写）。认不出来返回 null，由调用方回落。 */
        fun parse(raw: String?): RoomState? = when (raw?.trim()?.lowercase()) {
            "waiting" -> WAITING
            "active" -> ACTIVE
            "degraded" -> DEGRADED
            else -> null
        }

        /** 与服务端 `RoomManager#refreshState` 的 v2 分支逐行同序（见类注释）。 */
        fun derive(members: List<Member>): RoomState {
            if (members.isEmpty()) return WAITING
            val online = members.count { it.online }
            return when {
                online < members.size -> DEGRADED
                online >= 2 -> ACTIVE
                else -> WAITING
            }
        }
    }
}

data class Room(
    val roomId: String,
    val hostUserId: UserId,
    val mode: String,                 // "cinema"
    val members: List<Member>,
    val modeState: JsonObject?,       // 玩法私有状态 —— 底座不解包、不解释

    /**
     * 在场状态。服务端下发了就用它，没下发就是 [RoomState.derive] 推出来的
     * （见 [RoomState] 的注释：当前服务端确实不下发）。
     */
    val state: RoomState = RoomState.UNKNOWN,

    /**
     * 「房间里只有我一个人」这件事从什么时候开始算 —— **墙上时钟毫秒**（epoch ms），
     * 不是单调时钟。null = 此刻不止一个人（或还不知道）。
     *
     * 契约 §5 的语义是「等待超时用 `aloneSince`，不再用 `createdAt`」：服务端在
     * `onlineCount == 1` 时立起它、`>= 2` 时清零，`sweep` 到点就关房（`sys.room_closed`）。
     *
     * 客户端要它只为一件事：把「还要等多久」显示出来。否则用户看到的永远是一句
     * 「等待别人加入」，分不清是刚建好房还是已经等了九分钟、马上要被服务端收掉。
     *
     * ⚠️ 服务端目前**不下发**这个字段（载荷里没有），所以这里放的是**本机**在
     * 「人数变成 1」那一刻记下的墙上时钟。真出现了下发值，`WsSignaler` 会改用它；
     * 两种来路都是墙上时钟，`now - aloneSince` 才成立。
     */
    val aloneSince: Long? = null,

    /**
     * 房间容量。契约 §5 写死 v2 = 6（`sys.room_list` 也带这个投影字段）。
     *
     * 需求里「6 座成员表」就靠它：界面画的座位数 = 这个值，而不是 `members.size` ——
     * 后者只会告诉你「已经来了几个人」，画不出「还空着几把椅子」。
     */
    val maxSeats: Int = MAX_SEATS,
) {
    /** 在线人数（服务端 `onlineCount` 的同一口径）。 */
    val onlineCount: Int get() = members.count { it.online }

    /** 座位是否已经坐满（用于「房间已满」这类提示）。 */
    val isFull: Boolean get() = members.size >= maxSeats

    companion object {
        /** 契约 §5 / §9：`PROTOCOL_V2=1` 时服务端的房间容量。 */
        const val MAX_SEATS = 6
    }
}
