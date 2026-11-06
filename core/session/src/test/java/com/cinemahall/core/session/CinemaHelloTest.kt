package com.cinemahall.core.session

import com.cinemahall.core.media.MediaCheck
import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.MismatchReason
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import com.cinemahall.core.protocol.PeerMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5 的玩法侧：`cinema.hello` 的键名约定，以及「选片 → 宣告 → 出结论」这条链。
 */
class CinemaHelloTest {

    /** 对端 userId。阶段二起 `hello` 是定向消息，用例要能断言「发给了谁」。 */
    private val PEER = UserId("peer-user")

    private val head = "a".repeat(64)
    private val middle = "b".repeat(64)
    private val tail = "c".repeat(64)

    private fun signature(
        fileName: String = "movie.mkv",
        sizeBytes: Long = 4L * 1024 * 1024,
        blocks: List<String> = listOf(head, middle, tail),
    ) = MediaSignature(fileName, sizeBytes, blocks)

    private fun envelope(payload: JsonObject?) = Envelope(
        type = CinemaHello.TYPE,
        seq = 1,
        roomId = "AB23CD",
        from = "peer-user",
        sentAtMs = 1,
        payload = payload,
    )

    private class RecordingMessenger(private val accepts: Boolean = true) : PeerMessenger {
        val sent = mutableListOf<Triple<String, JsonObject?, UserId?>>()

        override fun send(type: String, payload: JsonObject?, to: UserId?): Boolean {
            sent += Triple(type, payload, to)
            return accepts
        }
    }

    // ── 键名是跨端约定，直接断言而不是只做 round-trip ──────────────────────

    @Test
    fun `hello 的键名是 mediaSignature 与 nickname`() {
        val json = CinemaHelloCodec.encode(CinemaHello(signature(), "阿黎"))

        assertEquals(setOf("mediaSignature", "nickname"), json.keys)
        assertEquals(
            setOf("fileName", "size", "blocks"),
            (json.getValue("mediaSignature") as JsonObject).keys,
        )
    }

    @Test
    fun `hello 能往返`() {
        val original = CinemaHello(signature(fileName = "片.mkv", sizeBytes = 1024), "阿黎")

        assertEquals(original, CinemaHelloCodec.decode(CinemaHelloCodec.encode(original)))
    }

    @Test
    fun `缺少 mediaSignature 解成 null`() {
        assertNull(CinemaHelloCodec.decode(buildJsonObject { put("nickname", "阿黎") }))
    }

    @Test
    fun `多出来的未知字段被忽略`() {
        val json = CinemaHelloCodec.encode(CinemaHello(signature(), "阿黎"))
        val extended = buildJsonObject {
            json.forEach { (key, value) -> put(key, value) }
            put("clientVersion", 2)
        }

        assertEquals("阿黎", CinemaHelloCodec.decode(extended)?.nickname)
    }

    // ── 「选片 → 宣告」 ────────────────────────────────────────────────────

    @Test
    fun `选好文件后立刻宣告一次`() {
        val messenger = RecordingMessenger()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply {
            bindPeerMessenger(messenger)
            localNickname = "阿黎"
        }

        assertTrue(handler.onLocalSignatureReady(signature()))

        assertEquals(listOf(CinemaHello.TYPE), messenger.sent.map { it.first })
        val payload = messenger.sent.single().second!!
        assertEquals(setOf("mediaSignature", "nickname"), payload.keys)
        assertEquals(
            "movie.mkv",
            CinemaHelloCodec.decode(payload)?.mediaSignature?.fileName,
        )
    }

    @Test
    fun `链路未通时不崩且返回 false`() {
        // 出站口还没绑定（链路根本没建）与「绑定了但发送失败」必须都是 false，而不是抛异常。
        val unbound = CinemaHandler(Dispatchers.Unconfined)

        assertFalse(unbound.onLocalSignatureReady(signature()))
        // 状态照常记下 —— 消息没发出去，不代表本机没选文件。
        assertEquals("movie.mkv", unbound.mediaCheck.value.local?.fileName)
    }

    @Test
    fun `发送被拒时仍保留本机摘要`() {
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindPeerMessenger(RecordingMessenger(accepts = false)) }

        assertFalse(handler.onLocalSignatureReady(signature()))

        assertEquals("movie.mkv", handler.mediaCheck.value.local?.fileName)
    }

    @Test
    fun `DataChannel 打开时会补发一次`() {
        // 这条覆盖的正是真机上的尴尬场景：选文件时链路还没通，hello 丢了，
        // 于是界面永远停在「等待对端」。补发挂在「通道真的 OPEN」上。
        val messenger = RecordingMessenger()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindPeerMessenger(messenger) }
        handler.onLocalSignatureReady(signature())
        messenger.sent.clear()

        assertTrue(handler.onPeerChannelOpened(PEER))

        assertEquals(1, messenger.sent.size)
        // 阶段二 v2：`hello` 是**定向**消息（契约 §4），`to` 必须是刚打通的那条链路的对端。
        // 广播出去的话，六人房里每个人都会收到每个人的自我介绍，房主的「对端摘要」
        // 会被最后到达的那条覆盖。
        assertEquals("hello 必须点名发给刚打通的那条链路", PEER, messenger.sent.single().third)
    }

    @Test
    fun `还没选文件时不会空发 hello`() {
        val messenger = RecordingMessenger()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindPeerMessenger(messenger) }

        assertFalse(handler.onPeerChannelOpened(PEER))
        assertTrue(messenger.sent.isEmpty())
    }

    // ── 「收到 → 出结论」 ──────────────────────────────────────────────────

    @Test
    fun `两端摘要一致时结论是通过`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.onLocalSignatureReady(signature())

        runBlocking {
            handler.onMessage(CinemaHello.ACTION, envelope(CinemaHelloCodec.encode(CinemaHello(signature(), "对面"))))
        }

        assertEquals(MediaCheck.Match, handler.mediaCheck.value.verdict)
        assertTrue(handler.mediaCheck.value.canStartPlayback)
        assertEquals("对面", handler.mediaCheck.value.remoteNickname)
    }

    @Test
    fun `对端改名时结论是文件名不一致`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.onLocalSignatureReady(signature(fileName = "movie.mkv"))

        runBlocking {
            handler.onMessage(
                CinemaHello.ACTION,
                envelope(
                    CinemaHelloCodec.encode(
                        CinemaHello(signature(fileName = "movie.1080p.mkv"), "对面"),
                    ),
                ),
            )
        }

        val reason = (handler.mediaCheck.value.verdict as MediaCheck.Mismatch).reason
        assertTrue("实得 $reason", reason is MismatchReason.FileName)
        assertFalse(handler.mediaCheck.value.canStartPlayback)
    }

    @Test
    fun `对端改中间字节时结论指向中段`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.onLocalSignatureReady(signature())

        runBlocking {
            handler.onMessage(
                CinemaHello.ACTION,
                envelope(
                    CinemaHelloCodec.encode(
                        CinemaHello(signature(blocks = listOf(head, "d".repeat(64), tail)), "对面"),
                    ),
                ),
            )
        }

        val reason = (handler.mediaCheck.value.verdict as MediaCheck.Mismatch).reason
        assertTrue("实得 $reason", reason is MismatchReason.Block)
        assertEquals("中段", (reason as MismatchReason.Block).label)
    }

    @Test
    fun `只有一边有摘要时是等待而不是不一致`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)

        // 界面必须能区分「还在等」与「已经判定不一致」，否则用户会以为文件选错了。
        assertNull(handler.mediaCheck.value.verdict)
        assertFalse(handler.mediaCheck.value.bothKnown)

        handler.onLocalSignatureReady(signature())
        assertNull(handler.mediaCheck.value.verdict)
    }

    @Test
    fun `重选文件后对端摘要仍然有效`() {
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.onLocalSignatureReady(signature(fileName = "a.mkv"))
        runBlocking {
            handler.onMessage(CinemaHello.ACTION, envelope(CinemaHelloCodec.encode(CinemaHello(signature(fileName = "a.mkv"), "对面"))))
        }
        assertEquals(MediaCheck.Match, handler.mediaCheck.value.verdict)

        handler.clearLocalSignature()

        assertNull(handler.mediaCheck.value.local)
        assertEquals("a.mkv", handler.mediaCheck.value.remote?.fileName)
        assertNull(handler.mediaCheck.value.verdict)
    }

    @Test
    fun `坏载荷不会破坏已有状态也不会抛异常`() {
        // 显式给一个调度器：这条用例会把 `cinema.play` 送进 `onMessage`，而那条分支
        // 要经 `withContext` 落到「引擎线程」上。默认值是 `Dispatchers.Main.immediate`，
        // 而 JVM 单测里没有 Android 主线程 —— 用它只会得到一句
        // 「Module with the Main dispatcher had failed to initialize」。
        // `Dispatchers.Unconfined` 就地执行，正是这条用例想要的（它验的是载荷解析，
        // 不是线程）。线程那一侧由 `CinemaHandlerThreadTest` 专门钉。
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.onLocalSignatureReady(signature())
        runBlocking {
            handler.onMessage(CinemaHello.ACTION, envelope(CinemaHelloCodec.encode(CinemaHello(signature(), "对面"))))
        }

        runBlocking {
            // 结构坏掉的 hello，以及一个完全陌生的动作
            handler.onMessage(CinemaHello.ACTION, envelope(buildJsonObject { put("nickname", "对面") }))
            handler.onMessage("play", envelope(null))
        }

        assertEquals(MediaCheck.Match, handler.mediaCheck.value.verdict)
        assertEquals(listOf("hello", "hello", "play"), handler.receivedActions.value)
    }
}