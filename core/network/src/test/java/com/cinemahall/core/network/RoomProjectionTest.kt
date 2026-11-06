package com.cinemahall.core.network

import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RoomState
import com.cinemahall.core.model.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 房间视图三个投影字段的判定（契约 §5）：`state` / `aloneSince` / `maxSeats`。
 *
 * ── 为什么这些用例值得写 ──────────────────────────────────────────────────────
 * 服务端**一个都没下发**（`signaling.mjs` 三处载荷逐行确认，2026-11-05），所以
 * 「6 座里还空几把椅子」「我已经一个人等了多久、房间快被收掉了」这两句话全靠
 * 客户端自己推。推错了不会报错，只会让界面说假话 —— 而等待超时那条尤其要命：
 * 用户看着「刚等了一会儿」，服务端那边已经到点把房间关了。
 */
class RoomProjectionTest {

    private val me = Member(UserId("me"), "我", online = true)
    private val alice = Member(UserId("alice"), "阿黎", online = true)
    private val bobOffline = Member(UserId("bob"), "老鲍", online = false)

    // ── state ─────────────────────────────────────────────────────────────

    @Test
    fun `两个人都在线就是 ACTIVE`() {
        assertEquals(RoomState.ACTIVE, RoomState.derive(listOf(me, alice)))
    }

    @Test
    fun `只有自己在就是 WAITING`() {
        assertEquals(RoomState.WAITING, RoomState.derive(listOf(me)))
    }

    @Test
    fun `有人掉线就是 DEGRADED`() {
        // 判定**顺序**是语义：先看有没有人掉线，再看在线人数够不够成局。
        // 反过来写的话「2 人在线 + 1 人掉线」会判成 ACTIVE，等待横幅永远不出现。
        assertEquals(RoomState.DEGRADED, RoomState.derive(listOf(me, alice, bobOffline)))
    }

    @Test
    fun `一个人都没有也算 WAITING`() {
        assertEquals(RoomState.WAITING, RoomState.derive(emptyList()))
    }

    @Test
    fun `服务端下发的 state 优先于本地推导`() {
        // 将来服务端把 `state` 发下来时（契约 §5 里它就是权威字段），
        // 本地推导不许覆盖它 —— 不然两套判据一旦分叉，界面会一直说反话。
        val declared = RoomState.parse("degraded")

        assertEquals(
            RoomState.DEGRADED,
            RoomProjection.state(declared, listOf(me, alice)),
        )
    }

    @Test
    fun `认不出来的 state 值回落到本地推导`() {
        assertNull(RoomState.parse("paused"))
        assertNull(RoomState.parse(null))

        assertEquals(RoomState.ACTIVE, RoomProjection.state(RoomState.parse("paused"), listOf(me, alice)))
    }

    // ── aloneSince ────────────────────────────────────────────────────────

    @Test
    fun `第一次只剩自己时立起 aloneSince`() {
        assertEquals(1_000L, RoomProjection.aloneSince(declared = null, previous = null, onlineCount = 1, nowMs = 1_000))
    }

    @Test
    fun `还是自己一个人时不重置计时`() {
        // 这条最关键：服务端在任何人上下线时都会重发**全量** members_updated，
        // 每收一条就重置一次的话，「已等待 9 分钟」永远不会出现。
        assertEquals(
            1_000L,
            RoomProjection.aloneSince(declared = null, previous = 1_000L, onlineCount = 1, nowMs = 500_000),
        )
    }

    @Test
    fun `第二个人进来时清零`() {
        assertNull(RoomProjection.aloneSince(declared = null, previous = 1_000L, onlineCount = 2, nowMs = 2_000))
    }

    @Test
    fun `全员离线时既不立也不清`() {
        // 0 人走的是宽限期回收，不是等待超时（服务端 `refreshState` 只在 ==1 时立）。
        assertNull(RoomProjection.aloneSince(declared = null, previous = null, onlineCount = 0, nowMs = 1_000))
        assertEquals(
            1_000L,
            RoomProjection.aloneSince(declared = null, previous = 1_000L, onlineCount = 0, nowMs = 5_000),
        )
    }

    @Test
    fun `服务端给了 aloneSince 就用它`() {
        // 服务端与客户端用的是**两种**时钟（一个是它的墙钟，一个是我方墙钟），
        // 它一旦开始下发，就必须用它给的那一个 —— 否则「还剩多久」里混着两台机器的时差。
        assertEquals(
            42_000L,
            RoomProjection.aloneSince(declared = 42_000L, previous = 1_000L, onlineCount = 1, nowMs = 9_999_999),
        )
    }

    // ── maxSeats ──────────────────────────────────────────────────────────

    @Test
    fun `没有下发容量时按契约的 6 座`() {
        assertEquals(Room.MAX_SEATS, RoomProjection.maxSeats(null))
        assertEquals(6, RoomProjection.maxSeats(null))
    }

    @Test
    fun `非法的容量值不采信`() {
        // 0 或负数会让界面画出「0 把椅子」，比不知道更糟。
        assertEquals(6, RoomProjection.maxSeats(0))
        assertEquals(6, RoomProjection.maxSeats(-1))
    }

    @Test
    fun `下发了容量就用下发的值`() {
        assertEquals(2, RoomProjection.maxSeats(2))
    }

    // ── Room 上的两个便利属性 ──────────────────────────────────────────────

    @Test
    fun `在线人数与坐满判据`() {
        val room = Room(
            roomId = "ABC234",
            hostUserId = me.userId,
            mode = "cinema",
            members = listOf(me, alice, bobOffline),
            modeState = null,
        )

        assertEquals("在线人数只数 online 的", 2, room.onlineCount)
        assertEquals("3 个座位没坐满 6 座", false, room.isFull)
    }
}
