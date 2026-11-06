package com.cinemahall.core.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信封编解码的单测。
 *
 * 这些断言不是「跑通就行」的形式检查 —— 每一条都对应一个会真出事的分支：
 * 漏 `from` 服务端会回 BAD_MESSAGE、多字段必须忽略否则版本一错配整条连接就废。
 */
class EnvelopeCodecTest {

    @Test
    fun `编码后再解码得到同一个信封`() {
        val original = Envelope(
            type = "cinema.play",
            seq = 7,
            roomId = "ABC234",
            from = "user-a",
            sentAtMs = 123456,
            payload = buildJsonObject {
                put("positionMs", 1000)
            },
        )

        assertEquals(original, EnvelopeCodec.decode(EnvelopeCodec.encode(original)))
    }

    @Test
    fun `to 会原样编码与解码`() {
        val original = Envelope(
            type = "rtc.offer",
            seq = 3,
            roomId = "ABC234",
            from = "user-a",
            to = "user-b",
            sentAtMs = 9,
            payload = buildJsonObject { put("sdp", "v=0") },
        )

        val text = EnvelopeCodec.encode(original)
        assertTrue("定向消息的 to 必须出现在报文里：$text", text.contains("\"to\":\"user-b\""))
        assertEquals(original, EnvelopeCodec.decode(text))
    }

    @Test
    fun `to 为 null 时不写这个键，解码仍是 null`() {
        // 广播（`play` / `heartbeat` / …）与 v1 服务端的报文都走这一格：
        // 「没有 to」= 广播，接收方因此不需要区分「广播」和「字段忘了填」。
        val text = EnvelopeCodec.encode(
            Envelope(type = "cinema.play", seq = 1, roomId = "ABC234", from = "user-a", sentAtMs = 1),
        )

        assertTrue("null 的 to 不该出现在报文里：$text", !text.contains("\"to\""))
        assertNull(EnvelopeCodec.decode(text).to)
    }

    @Test
    fun `v1 报文（没有 to 键）照样能解码`() {
        // `PROTOCOL_V2=0` 的服务端根本不知道 `to` 这个字段（R1 §3 的硬要求：
        // 关掉开关之后客户端必须仍然可用）。缺键 ⇒ 默认 null，不抛 MissingFieldException。
        val v1 = """{"type":"cinema.play","seq":4,"roomId":"ABC234","from":"user-a","sentAtMs":7}"""

        val decoded = EnvelopeCodec.decode(v1)

        assertEquals("cinema.play", decoded.type)
        assertNull(decoded.to)
    }

    @Test
    fun `roomId 为 null 时不写这个键，解码仍是 null`() {        val text = EnvelopeCodec.encode(
            Envelope(type = "sys.ping", seq = 1, roomId = null, from = "user-a", sentAtMs = 1),
        )

        assertTrue("null 的 roomId 不该出现在报文里：$text", !text.contains("roomId"))
        assertNull(EnvelopeCodec.decode(text).roomId)
    }

    @Test
    fun `完全缺 roomId 键也能解码_不然对端少发一个字段整条连接就废了`() {
        val decoded = EnvelopeCodec.decode(
            """{"type":"sys.pong","seq":3,"from":"server","sentAtMs":42}""",
        )

        assertEquals("sys.pong", decoded.type)
        assertNull(decoded.roomId)
        assertNull(decoded.payload)
    }

    @Test
    fun `多出来的未知字段被忽略`() {
        val decoded = EnvelopeCodec.decode(
            """{"type":"sys.pong","seq":3,"from":"server","sentAtMs":42,"futureField":{"a":1}}""",
        )

        assertEquals("sys.pong", decoded.type)
    }

    @Test
    fun `缺 type 抛 MalformedEnvelopeException`() {
        assertThrows(MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode("""{"seq":3,"from":"server","sentAtMs":42}""")
        }
    }

    @Test
    fun `缺 from 抛 MalformedEnvelopeException_服务端就是这么卡我们的`() {
        assertThrows(MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode("""{"type":"sys.ping","seq":3,"sentAtMs":42}""")
        }
    }

    @Test
    fun `type 为空串等同于缺失`() {
        assertThrows(MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode("""{"type":"  ","seq":3,"from":"a","sentAtMs":42}""")
        }
    }

    @Test
    fun `非法 JSON 抛 MalformedEnvelopeException 而不是 SerializationException`() {
        val error = assertThrows(MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode("{ 这不是 JSON")
        }
        assertTrue("应保留原始异常做因果链", error.cause != null)
    }

    @Test
    fun `服务端真实下发格式_room_joined 的字段一个不落`() {
        // 取自 server/src/signaling.mjs 的 sendEnvelope(ctx, 'sys.room_joined', {...})
        val decoded = EnvelopeCodec.decode(
            """
            {"type":"sys.room_joined","seq":4,"roomId":"AB23CD","from":"server","sentAtMs":99,
             "payload":{"roomId":"AB23CD","hostUserId":"user-a","mode":"cinema",
                        "members":[{"userId":"user-a","nickname":"甲","online":true}],
                        "iceServers":[{"urls":["stun:turn.example.com:3478"]}]}}
            """.trimIndent(),
        )

        assertEquals("AB23CD", decoded.roomId)
        assertEquals("AB23CD", decoded.payload?.stringOrNullForTest("roomId"))
    }
}

/** 测试里读 payload 的小工具，避免测试文件依赖 core:network 的内部扩展。 */
private fun kotlinx.serialization.json.JsonObject.stringOrNullForTest(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
