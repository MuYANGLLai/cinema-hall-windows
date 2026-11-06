package com.cinemahall.core.model

/**
 * 「加入房间」页里的一行房间（阶段一 1.8）。
 *
 * 这是**服务端房间清单的对外投影**（`server/src/rooms.mjs` 的 `toRoomSummary`），
 * 不是 [Room] 的缩略版 —— 两者描述的是不同的东西：
 *  - [RoomSummary]：「一个我**还没进去**的房间」，来自 `sys.room_list`；
 *  - [Room]：「我**已经在里面**的那个房间」，来自 `sys.room_created` / `sys.room_joined`。
 *
 * 字段来源不同、生命周期不同（前者是一次查询的快照，后者要跟着 `peer_joined` 等
 * 事件演进），所以刻意不做继承、也不做转换 —— 那只会让「房间」这个词在代码里
 * 有两个含义。
 *
 * ⚠️ 服务端**不会**下发密码，只有 [hasPassword] 这个布尔，回答「点进去要不要先问密码」。
 * 密码的权威校验永远在服务端（`rooms.mjs` 的明文比对），客户端不做任何预判。
 */
data class RoomSummary(
    val roomId: String,
    /** 房间设了密码 ⇒ 加入前必须先问用户要密码。 */
    val hasPassword: Boolean,
    val memberCount: Int,
    val maxSeats: Int,
    /**
     * 房主昵称。只用来在清单里认出「哪个房间是那台设备开的」——
     * 一屏三四个 6 位房号是分不出谁是谁的。
     */
    val hostNickname: String,
) {
    /**
     * 还有空位。服务端**已经**过滤过（满员的根本不列），这里留着是给界面一个
     * 不必自己算的判据 —— 将来若服务端改成「满员也列但标灰」，界面不用跟着改。
     */
    val hasFreeSeat: Boolean get() = memberCount < maxSeats
}
