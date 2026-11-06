package com.cinemahall.core.session

import com.cinemahall.core.media.MediaSignature
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.3 的线格式。这里逐个断言**键名本身**，而不只是「编了能解回来」——
 * 键名写错是这类协议最贵的一类错误：两端各自都能自洽地编解码，
 * 只有真机上两个版本碰面时才表现为「消息收到了但什么都不发生」。
 */
class CinemaPlaybackCodecTest {

    private val signature = MediaSignature(
        fileName = "movie.mkv",
        sizeBytes = 4L * 1024 * 1024,
        blocks = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64)),
    )

    @Test
    fun type_字符串与规格书一字不差() {
        assertEquals("cinema.play", CinemaPlayback.TYPE_PLAY)
        assertEquals("cinema.pause", CinemaPlayback.TYPE_PAUSE)
        assertEquals("cinema.seek", CinemaPlayback.TYPE_SEEK)
        assertEquals("cinema.speed", CinemaPlayback.TYPE_SPEED)
        assertEquals("cinema.heartbeat", CinemaPlayback.TYPE_HEARTBEAT)
        assertEquals("cinema.sync_ack", CinemaPlayback.TYPE_SYNC_ACK)
        assertEquals("cinema.state_snapshot", CinemaPlayback.TYPE_STATE_SNAPSHOT)
        assertEquals("cinema.subtitle", CinemaPlayback.TYPE_SUBTITLE)

        assertEquals("cinema.play", CinemaPlayback.typeOf(CinemaPlayback.ACTION_PLAY))
        assertEquals("cinema.state_snapshot", CinemaPlayback.typeOf(CinemaPlayback.ACTION_STATE_SNAPSHOT))
    }

    @Test
    fun 动作集合是十条且含_speed() {
        // 集合漏一条的症状是「某一类消息静默地不生效」，而它看起来完全像对端没发。
        // 多一条同样糟：`CinemaHandler` 会把它当成播放同步的消息交给引擎。
        //
        // 阶段二从八条变十条：`control_policy` 与 `media_status`（契约 §7）。
        // 它们虽然由玩法层消化，但**分流入口只有这一个集合** —— 不在这里列出来的话
        // 两条消息会静默落进 `when` 之外（症状：房主改了策略，从端的按钮一动不动）。
        assertEquals(10, CinemaPlayback.ACTIONS.size)
        assertTrue(CinemaPlayback.ACTIONS.contains(CinemaPlayback.ACTION_SPEED))
        assertEquals(
            (CinemaPlayback.ACTIONS).sorted(),
            listOf(
                CinemaPlayback.ACTION_HEARTBEAT,
                CinemaPlayback.ACTION_PAUSE,
                CinemaPlayback.ACTION_PLAY,
                CinemaPlayback.ACTION_SEEK,
                CinemaPlayback.ACTION_SPEED,
                CinemaPlayback.ACTION_STATE_SNAPSHOT,
                CinemaPlayback.ACTION_SUBTITLE,
                CinemaPlayback.ACTION_SYNC_ACK,
                CinemaPlayback.ACTION_CONTROL_POLICY,
                CinemaPlayback.ACTION_MEDIA_STATUS,
            ).sorted(),
        )
    }

    @Test
    fun play_pause_seek_共用同一个载荷形状() {
        val json = CinemaPlaybackCodec.encodePosition(
            CinemaPosition(positionMs = 1_234L, senderClockMs = 56_789L),
        )

        assertEquals(setOf("positionMs", "senderClockMs"), json.keys)
        assertEquals(1_234L, json["positionMs"]!!.jsonPrimitive.long)
        assertEquals(56_789L, json["senderClockMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun heartbeat_的字段名照规格书() {
        val json = CinemaPlaybackCodec.encodeHeartbeat(
            CinemaHeartbeat(isPlaying = true, positionMs = 900L, senderClockMs = 12_345L),
        )

        assertEquals(setOf("isPlaying", "positionMs", "senderClockMs"), json.keys)
    }

    @Test
    fun sync_ack_的字段名照规格书() {
        val json = CinemaPlaybackCodec.encodeSyncAck(
            CinemaSyncAck(ackSeq = 7L, clientPositionMs = 1_000L, driftMs = -42L, rttMs = 88L),
        )

        assertEquals(setOf("ackSeq", "clientPositionMs", "driftMs", "rttMs"), json.keys)
    }

    @Test
    fun speed_的字段名照规格书() {
        val json = CinemaPlaybackCodec.encodeSpeed(CinemaSpeed(speed = 1.5f))

        assertEquals(setOf("speed"), json.keys)
        assertEquals(1.5f, CinemaPlaybackCodec.decodeSpeed(json)!!.speed, 1e-6f)
    }

    @Test
    fun 旧版对端的快照里没有_playbackSpeed_也要能解出来并默认原速() {
        // 快照的用途是「对端中途重装 App 之后的全量恢复」。老版本发的载荷里没有
        // 这个字段，若它被当成必填，整条快照会被判成坏消息丢掉 —— 症状是
        // 「对端重装之后两端再也对不上」，而日志里只有一句「解不出来」。
        val legacy = buildJsonObject {
            put("isPlaying", true)
            put("positionMs", 1_000L)
            put("seq", 1L)
            put("sessionEpoch", 2L)
            put(
                "mediaSignature",
                CinemaPlaybackCodec.encodeStateSnapshot(
                    CinemaStateSnapshot(true, 1_000L, signature, 1L, 2L),
                )["mediaSignature"]!!,
            )
        }

        val decoded = CinemaPlaybackCodec.decodeStateSnapshot(legacy)!!
        assertEquals(1.0f, decoded.playbackSpeed, 1e-6f)
    }

    @Test
    fun state_snapshot_的字段名照规格书且内层摘要用规格书的线名() {
        val json = CinemaPlaybackCodec.encodeStateSnapshot(
            CinemaStateSnapshot(
                isPlaying = true,
                positionMs = 1_000L,
                mediaSignature = signature,
                seq = 9L,
                sessionEpoch = 3L,
                // 故意给一个**非默认**倍速：`playbackSpeed` 的默认值是 1.0f，而编解码器
                // 没开 `encodeDefaults` —— 默认值不上线（见下一条用例）。这里断言的是
                // 「这个键在线上叫 playbackSpeed」，所以必须让它真的出现。
                playbackSpeed = 2.0f,
            ),
        )

        assertEquals(
            setOf("isPlaying", "positionMs", "mediaSignature", "seq", "sessionEpoch", "playbackSpeed"),
            json.keys,
        )

        // 内层是 §7 的 MediaSignature：sizeBytes 在线上的名字是 `size`（§5.3）。
        val nested = json["mediaSignature"]!!.let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(setOf("fileName", "size", "blocks"), nested.keys)
    }

    @Test
    fun 原速时_playbackSpeed_不上线() {
        // 这不是缺陷，是 `encodeDefaults` 默认关闭 + 默认值 1.0f 的直接结果：绝大多数
        // 场次（原速）的快照里根本没有这个键，而对端的字段默认值同样是 1.0f，解出来
        // 正好是原速（上一条 `旧版对端的快照里没有_playbackSpeed_也要能解出来` 覆盖的
        // 就是这条路）。锁住它是为了让人抓包看到「没有 playbackSpeed」时不会以为
        // 倍速同步坏了，也不必去改序列化器把它强行塞上线。
        val json = CinemaPlaybackCodec.encodeStateSnapshot(
            CinemaStateSnapshot(
                isPlaying = true,
                positionMs = 1_000L,
                mediaSignature = signature,
                seq = 9L,
                sessionEpoch = 3L,
            ),
        )

        assertFalse("原速不该出现在线上", "playbackSpeed" in json)
        assertEquals(
            setOf("isPlaying", "positionMs", "mediaSignature", "seq", "sessionEpoch"),
            json.keys,
        )
    }

    @Test
    fun subtitle_的字段名照规格书() {
        val json = CinemaPlaybackCodec.encodeSubtitle(CinemaSubtitle(trackIndex = 2))
        assertEquals(setOf("trackIndex"), json.keys)
    }

    @Test
    fun 六类载荷都能原样往返() {
        val position = CinemaPosition(1_111L, 2_222L)
        assertEquals(
            position,
            CinemaPlaybackCodec.decodePosition(CinemaPlaybackCodec.encodePosition(position)),
        )

        val speed = CinemaSpeed(1.25f)
        assertEquals(speed, CinemaPlaybackCodec.decodeSpeed(CinemaPlaybackCodec.encodeSpeed(speed)))

        val heartbeat = CinemaHeartbeat(isPlaying = false, positionMs = 3_333L, senderClockMs = 4_444L)
        assertEquals(
            heartbeat,
            CinemaPlaybackCodec.decodeHeartbeat(CinemaPlaybackCodec.encodeHeartbeat(heartbeat)),
        )

        val ack = CinemaSyncAck(ackSeq = 5L, clientPositionMs = 6_666L, driftMs = 7L, rttMs = 8L)
        assertEquals(ack, CinemaPlaybackCodec.decodeSyncAck(CinemaPlaybackCodec.encodeSyncAck(ack)))

        val snapshot = CinemaStateSnapshot(true, 9_999L, signature, 11L, 12L, playbackSpeed = 2.0f)
        assertEquals(
            snapshot,
            CinemaPlaybackCodec.decodeStateSnapshot(CinemaPlaybackCodec.encodeStateSnapshot(snapshot)),
        )

        val subtitle = CinemaSubtitle(trackIndex = 1)
        assertEquals(
            subtitle,
            CinemaPlaybackCodec.decodeSubtitle(CinemaPlaybackCodec.encodeSubtitle(subtitle)),
        )
    }

    @Test
    fun 未知字段被忽略而不是当作坏消息() {
        val payload = buildJsonObject {
            put("positionMs", 500L)
            put("senderClockMs", 600L)
            put("futureField", "v2 才会有的东西")
        }

        val decoded = CinemaPlaybackCodec.decodePosition(payload)!!
        assertEquals(500L, decoded.positionMs)
        assertEquals(600L, decoded.senderClockMs)
    }

    @Test
    fun 空载荷与坏载荷都返回_null_而不是抛异常() {
        assertNull(CinemaPlaybackCodec.decodePosition(null))
        assertNull(CinemaPlaybackCodec.decodeSpeed(null))
        assertNull(CinemaPlaybackCodec.decodeHeartbeat(null))
        assertNull(CinemaPlaybackCodec.decodeSyncAck(null))
        assertNull(CinemaPlaybackCodec.decodeStateSnapshot(null))
        assertNull(CinemaPlaybackCodec.decodeSubtitle(null))

        // 缺字段
        assertNull(CinemaPlaybackCodec.decodeHeartbeat(buildJsonObject { put("isPlaying", true) }))
        // 类型不对
        assertNull(
            CinemaPlaybackCodec.decodePosition(
                buildJsonObject {
                    put("positionMs", "不是数字")
                    put("senderClockMs", 1L)
                },
            ),
        )
    }

    @Test
    fun 暂停时的位置为零与负漂移都能原样往返() {
        assertTrue(CinemaPlaybackCodec.decodeSyncAck(
            CinemaPlaybackCodec.encodeSyncAck(CinemaSyncAck(0L, 0L, -1_234L, 0L)),
        )!!.driftMs == -1_234L)
    }
}
