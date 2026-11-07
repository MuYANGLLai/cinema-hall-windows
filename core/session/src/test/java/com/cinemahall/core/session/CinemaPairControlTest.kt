package com.cinemahall.core.session

import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import com.cinemahall.core.protocol.PeerMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两人房自动同权（2026-11-07 用户口径）+ 逐成员麦克风状态（同日）。
 *
 * ── 这两件事为什么放在一个文件里 ──────────────────────────────────────────────
 * 它们都是「房间成员」那一张卡的数据来源：左边两列是控制权，最右一列是麦克风。
 * 分成两个文件会让人以为它们各自独立，而真实的产品口径是一起看的那一屏。
 */
class CinemaPairControlTest {

    private val host = UserId("host-user")
    private val me = UserId("me-user")
    private val third = UserId("third-user")

    // ── 两人房自动同权 ─────────────────────────────────────────────────────

    @Test
    fun `房间里只有两个人时从端自动拿到控制权`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)

        handler.onRoomUpdated(room(listOf(member(host), member(me))), me)

        assertTrue("两人房不该再要房主去逐个授权", handler.controlPolicy.value.pairAutoAllow)
        assertTrue(handler.controlPolicy.value.allowedFor(me))
        // 房主当然照旧有控制权。
        assertTrue(ControlPolicyTable.canControl(handler.controlPolicy.value, host, host, watching = true))
    }

    @Test
    fun `第三个人进房的那一刻权限收回`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.onRoomUpdated(room(listOf(member(host), member(me))), me)
        assertTrue(handler.controlPolicy.value.allowedFor(me))

        handler.onRoomUpdated(room(listOf(member(host), member(me), member(third))), me)

        assertFalse("三个人就不再自动同权", handler.controlPolicy.value.pairAutoAllow)
        assertFalse(handler.controlPolicy.value.allowedFor(me))
    }

    @Test
    fun `两人房仍然要求片源在看`() {
        // 自动同权只回答「允不允许」，不绕过 `watching`：本机还没选到同一部片时，
        // 按下去本来就只是一次静默空操作，按钮应当照旧是灰的。
        val policy = ControlPolicy(pairAutoAllow = true)

        assertFalse(ControlPolicyTable.canControl(policy, host, me, watching = false))
    }

    @Test
    fun `两人房里房主关掉某个人_那个人就不许`() {
        // 2026-11-07 第二版用户口径：「双人房间下改成房主仍可调节成员的控制权限」。
        // 上一版把两人房写成「一律允许」，房主那一列的开关拨下去没有任何效果。
        // 现在 `overrides` 优先于 `pairAutoAllow`。
        val policy = ControlPolicy(pairAutoAllow = true, overrides = mapOf(me to false))

        assertFalse(policy.allowedFor(me))
        assertFalse(ControlPolicyTable.canControl(policy, host, me, watching = true))
    }

    @Test
    fun `两人房里房主可以单独再打开某个人`() {
        val policy = ControlPolicy(
            defaultAllow = false,
            pairAutoAllow = true,
            overrides = mapOf(me to true),
        )

        assertTrue(policy.allowedFor(me))
    }

    @Test
    fun `两人房这一位不上线`() {
        // 判据只有人数，两端各自从同一份 `Room.members` 推出来即可。进了载荷就会变成
        // 「房主那一份 / 收到的那一份」打架 —— 那正是这一条要避免的。
        val wire = ControlPolicy(pairAutoAllow = true).toWire()

        assertEquals(CinemaControlPolicy(), wire)
        assertFalse(ControlPolicy.fromWire(wire).pairAutoAllow)
    }

    // ── 逐成员麦克风 ───────────────────────────────────────────────────────

    @Test
    fun `收到成员的通话状态后成员卡片能读到`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)

        runBlocking {
            handler.onMessage(
                CinemaPlayback.ACTION_CALL_STATE,
                envelope(
                    from = third.value,
                    payload = CinemaPlaybackCodec.encodeCallState(
                        CinemaCallState(callOn = true, micOn = true),
                    ),
                ),
            )
        }

        assertEquals(
            CinemaCallState(callOn = true, micOn = true),
            handler.peerCall.value[third],
        )
    }

    @Test
    fun `人走了麦克风那一格跟着消失`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)
        runBlocking {
            handler.onMessage(
                CinemaPlayback.ACTION_CALL_STATE,
                envelope(
                    from = third.value,
                    payload = CinemaPlaybackCodec.encodeCallState(
                        CinemaCallState(callOn = true, micOn = true),
                    ),
                ),
            )
            handler.onPeerLeft(third)
        }

        assertTrue("掉线的人不该留着一枚「麦开着」的图标", handler.peerCall.value.isEmpty())
    }

    @Test
    fun `开麦与静音之间翻转时才广播一次`() {
        // 「麦克风输入音量」这根条挂在滑块上，拖一次几十次回调。每条都广播会把
        // DataChannel 塞满，而对端那一枚图标只关心「有麦 / 没麦」。
        val messenger = RecordingMessenger()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindPeerMessenger(messenger) }

        handler.setCallEnabled(true)
        val afterOpen = messenger.ofType(CinemaPlayback.TYPE_CALL_STATE)
        assertEquals(1, afterOpen.size)
        assertEquals(
            CinemaCallState(callOn = true, micOn = true),
            CinemaPlaybackCodec.decodeCallState(afterOpen.single().second),
        )

        // 40% → 30%：同一个「有麦」区间，不该再发。
        handler.setMicVolume(0.4f)
        handler.setMicVolume(0.3f)
        assertEquals(1, messenger.ofType(CinemaPlayback.TYPE_CALL_STATE).size)

        // 拖到 0 = 静音：翻了一位，要发。
        handler.setMicVolume(0f)
        val afterMute = messenger.ofType(CinemaPlayback.TYPE_CALL_STATE)
        assertEquals(2, afterMute.size)
        assertEquals(
            CinemaCallState(callOn = true, micOn = false),
            CinemaPlaybackCodec.decodeCallState(afterMute.last().second),
        )
    }

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private fun member(id: UserId) = Member(userId = id, nickname = id.value, online = true)

    private fun room(members: List<Member>) = Room(
        roomId = "AB23CD",
        hostUserId = host,
        mode = "cinema",
        members = members,
        modeState = null,
    )

    private fun envelope(from: String, payload: JsonObject?) = Envelope(
        type = CinemaPlayback.TYPE_CALL_STATE,
        seq = 1,
        roomId = "AB23CD",
        from = from,
        sentAtMs = 1,
        payload = payload,
    )

    private class RecordingMessenger : PeerMessenger {
        val sent = mutableListOf<Triple<String, JsonObject?, UserId?>>()

        fun ofType(type: String) = sent.filter { it.first == type }

        override fun send(type: String, payload: JsonObject?, to: UserId?): Boolean {
            sent += Triple(type, payload, to)
            return true
        }
    }
}
