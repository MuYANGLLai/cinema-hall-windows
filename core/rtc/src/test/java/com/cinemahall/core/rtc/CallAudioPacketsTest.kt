package com.cinemahall.core.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音频包收发计数的逐格单测（阶段一 1.7，2026-10-11 补）。
 *
 * ── 为什么这两格值得单独测 ──────────────────────────────────────────────────
 * 它们是为「`通话状态 = 可听可说` 却一个字都听不见」这件事补的证据，而它们自己错了
 * **不会有任何症状** —— 屏幕上少一个数字、或多一句不该出现的注解，功能一切正常。
 * 真机上没人会怀疑到一个读数头上，只会去查通话本身。所以四种组合（收到 0 / 发出 0 /
 * 都非 0 / 还没读到）都钉住。
 *
 * ── 为什么这里不需要 native 库 ──────────────────────────────────────────────
 * [CallAudioState.audioPacketsText] 是 `CallAudioState` 上的纯 Kotlin 方法，
 * [LinkStatsParser.parseAudioRtp] 是纯函数（输入是「每条 stats 记录一个 Map」）。
 * 两者都不碰 `org.webrtc` 类型，所以能在 JVM 上跑。
 */
class CallAudioPacketsTest {

    // ── 文案 ───────────────────────────────────────────────────────────────

    @Test
    fun `还没读到计数时是未知`() {
        // `CinemaHandler` 在还没接上 RTC 时发的就是这个默认值。它必须是 `-`，
        // 而不是「收到 0 包 / 发出 0 包」—— 后者是一句**看起来像事实的谎**。
        assertEquals(CallAudioState.UNKNOWN, CallAudioState().audioPacketsText())
    }

    @Test
    fun `两个计数都在时如实印出来_先收到后发出`() {
        val call = CallAudioState(audioPacketsSent = 567L, audioPacketsReceived = 1234L)
        assertEquals("收到 1234 包 / 发出 567 包", call.audioPacketsText())
    }

    @Test
    fun `只缺一路时那一格是未知_另一格照印`() {
        val call = CallAudioState(audioPacketsSent = null, audioPacketsReceived = 12L)
        assertEquals("收到 12 包 / 发出 - 包", call.audioPacketsText())
    }

    @Test
    fun `收到音轨但一个包都没到时要加注`() {
        // 这一格就是本次修的那个洞：`receiving` 只证明「有一个对端音轨对象」，
        // 不证明「有字节到达」。0 包必须说出来。
        val call = CallAudioState(
            requested = true,
            receiving = true,
            audioPacketsSent = 900L,
            audioPacketsReceived = 0L,
        )
        assertEquals("收到 0 包 / 发出 900 包（对端一个音频包都还没到）", call.audioPacketsText())
    }

    @Test
    fun `本机在发送但一个包都没出去时要加注`() {
        // 另一半：answer 方的音频 m 行曾协商成 recvonly，`sending` 却是 true
        // （发送端上确实挂着音轨），那时包是一个都发不出去的。同一类静默失败。
        val call = CallAudioState(
            requested = true,
            sending = true,
            audioPacketsSent = 0L,
            audioPacketsReceived = 700L,
        )
        assertEquals("收到 700 包 / 发出 0 包（本机一个音频包都没发出去）", call.audioPacketsText())
    }

    @Test
    fun `两个方向都是零包时两句注解都要有`() {
        val call = CallAudioState(
            requested = true,
            sending = true,
            receiving = true,
            audioPacketsSent = 0L,
            audioPacketsReceived = 0L,
        )
        assertEquals(
            "收到 0 包 / 发出 0 包（对端一个音频包都还没到；本机一个音频包都没发出去）",
            call.audioPacketsText(),
        )
    }

    @Test
    fun `没在发送时发出零包不加注`() {
        // 麦克风没开时「发出 0 包」是**正常的**，给它加注只会制造噪声 ——
        // 而噪声读数的代价是让人不再相信这一行（「已接 蓝牙通话」的教训）。
        val call = CallAudioState(
            requested = true,
            sending = false,
            receiving = true,
            audioPacketsSent = 0L,
            audioPacketsReceived = 42L,
        )
        assertEquals("收到 42 包 / 发出 0 包", call.audioPacketsText())
    }

    @Test
    fun `没收到音轨时收到零包不加注`() {
        val call = CallAudioState(
            requested = true,
            sending = true,
            receiving = false,
            audioPacketsSent = 42L,
            audioPacketsReceived = 0L,
        )
        assertEquals("收到 0 包 / 发出 42 包", call.audioPacketsText())
    }

    // ── 解析 ───────────────────────────────────────────────────────────────

    @Test
    fun `音频的收发两条记录各取各的计数`() {
        val counters = LinkStatsParser.parseAudioRtp(
            listOf(
                outboundAudio(packetsSent = 1234L),
                inboundAudio(packetsReceived = 9876L),
                // 视频那两条必须被忽略：把视频包数当音频报，读数就彻底失去意义了。
                outboundVideo(),
                inboundVideo(),
            ),
        )
        assertEquals(1234L, counters?.packetsSent)
        assertEquals(9876L, counters?.packetsReceived)
    }

    @Test
    fun `没有任何音频流时返回 null_而不是零`() {
        // 「没有这一路」与「这一路走了 0 个包」是两件事：前者该显示 `-`，
        // 后者该显示 0 并可能加注。合成一个 0 会把前者说成后者。
        assertNull(LinkStatsParser.parseAudioRtp(listOf(outboundVideo(), inboundVideo())))
        assertNull(LinkStatsParser.parseAudioRtp(emptyList()))
        assertNull(LinkStatsParser.parseAudioRtp(listOf(mapOf("id" to "T1", "type" to "transport"))))
    }

    @Test
    fun `只有一路音频时另一路是 null`() {
        // 只收听不发言（本机没开通话）就是这个形状。
        val counters = LinkStatsParser.parseAudioRtp(listOf(inboundAudio(packetsReceived = 5L)))
        assertNull(counters?.packetsSent)
        assertEquals(5L, counters?.packetsReceived)
    }

    @Test
    fun `老版本的 mediaType 也认`() {
        // 现版本叫 `kind`，老版本叫 `mediaType`。本机 AAR 里两个字面量都在
        // （见 parseAudioRtp 的注释），所以两个都得认。
        val counters = LinkStatsParser.parseAudioRtp(
            listOf(
                mapOf(
                    "id" to "RTCOutboundRTPAudioStream_1",
                    "type" to "outbound-rtp",
                    "mediaType" to "audio",
                    "packetsSent" to 7L,
                ),
            ),
        )
        assertEquals(7L, counters?.packetsSent)
    }

    @Test
    fun `媒体类型缺失的条目不算音频`() {
        // 宁可少报一个读数，也不要把不知道是什么的包数当音频报。
        assertNull(
            LinkStatsParser.parseAudioRtp(
                listOf(mapOf("id" to "X1", "type" to "outbound-rtp", "packetsSent" to 3L)),
            ),
        )
    }

    @Test
    fun `多个同向条目求和`() {
        val counters = LinkStatsParser.parseAudioRtp(
            listOf(outboundAudio(packetsSent = 10L), outboundAudio(packetsSent = 32L)),
        )
        assertEquals(42L, counters?.packetsSent)
    }

    @Test
    fun `计数是整数类型时也认`() {
        // org.webrtc 的 `members` 是 `Map<String, Object>`，数字到 Java 侧可能是
        // Integer 也可能是 Long（取决于 native 侧的类型宽度）。解析器必须两种都吃，
        // 否则症状是「这个读数一直是 `-`」，而功能完全正常。
        val counters = LinkStatsParser.parseAudioRtp(
            listOf(
                mapOf(
                    "id" to "RTCOutboundRTPAudioStream_1",
                    "type" to "outbound-rtp",
                    "kind" to "audio",
                    "packetsSent" to 15,
                ),
                mapOf(
                    "id" to "RTCInboundRTPAudioStream_1",
                    "type" to "inbound-rtp",
                    "kind" to "audio",
                    "packetsReceived" to 0,
                ),
            ),
        )
        assertEquals(15L, counters?.packetsSent)
        assertEquals(0L, counters?.packetsReceived)
    }

    @Test
    fun `条目在但字段缺失时那一格是 null`() {
        val counters = LinkStatsParser.parseAudioRtp(
            listOf(mapOf("id" to "RTCOutboundRTPAudioStream_1", "type" to "outbound-rtp", "kind" to "audio")),
        )
        assertNull(counters?.packetsSent)
        assertNull(counters?.packetsReceived)
    }

    // ── 回声抑制（ERLE）────────────────────────────────────────────────────
    // 与包计数同一条原则：`null` 是「还没读到」，`0.0` 是「读到了、AEC 一点都没消」。
    // 这两个数在屏幕上必须分得开 —— 前者是再等等，后者是该去查 AEC 为什么没生效。

    @Test
    fun `回声抑制取自音频的 media-source 条目`() {
        assertEquals(
            12.4,
            LinkStatsParser.parseEchoSuppression(
                listOf(
                    mediaSource(12.4),
                    // 视频那条必须被忽略：把视频的读数当音频报，这一格就彻底失去意义了。
                    mapOf(
                        "id" to "RTCVideoSource_1",
                        "type" to "media-source",
                        "kind" to "video",
                        "echoReturnLossEnhancement" to 99.9,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `没有 media-source 条目时是 null 而不是零`() {
        // 「没有这一路」与「这一路一点都没消」是两件事。合成一个 0.0 会把前者说成后者，
        // 于是「AEC 没生效」这个结论会在根本没通话的时候被得出来。
        assertNull(LinkStatsParser.parseEchoSuppression(emptyList()))
        assertNull(LinkStatsParser.parseEchoSuppression(listOf(outboundAudio(packetsSent = 1L))))
        assertNull(LinkStatsParser.parseEchoSuppression(listOf(mapOf("id" to "T1", "type" to "transport"))))
    }

    @Test
    fun `media-source 在但字段缺失时是 null`() {
        assertNull(
            LinkStatsParser.parseEchoSuppression(
                listOf(mapOf("id" to "RTCAudioSource_1", "type" to "media-source", "kind" to "audio")),
            ),
        )
    }

    @Test
    fun `回声抑制是整数或字符串类型时也认`() {
        // 与包计数同一个理由：`members` 是 `Map<String, Object>`，数字到 Java 侧可能是
        // Integer、Long 或 Double；而少数平台的 stats 里数值就是字符串。
        // 只吃 Double 的症状是「这一格永远是 `-`」，功能完全正常。
        assertEquals(15.0, LinkStatsParser.parseEchoSuppression(listOf(mediaSourceInt(15))))
        assertEquals(8.5, LinkStatsParser.parseEchoSuppression(listOf(mediaSourceText("8.5"))))
    }

    // ── 夹具 ───────────────────────────────────────────────────────────────
    // 形状照着 libwebrtc 真实给的字段写（`id` / `type` / `kind` / 计数）。

    private fun outboundAudio(packetsSent: Long): Map<String, Any?> = mapOf(
        "id" to "RTCOutboundRTPAudioStream_1234567890",
        "type" to "outbound-rtp",
        "kind" to "audio",
        "packetsSent" to packetsSent,
        "bytesSent" to packetsSent * 80,
    )

    private fun inboundAudio(packetsReceived: Long): Map<String, Any?> = mapOf(
        "id" to "RTCInboundRTPAudioStream_1234567890",
        "type" to "inbound-rtp",
        "kind" to "audio",
        "packetsReceived" to packetsReceived,
        "bytesReceived" to packetsReceived * 80,
    )

    private fun outboundVideo(): Map<String, Any?> = mapOf(
        "id" to "RTCOutboundRTPVideoStream_1",
        "type" to "outbound-rtp",
        "kind" to "video",
        "packetsSent" to 999_999L,
    )

    private fun inboundVideo(): Map<String, Any?> = mapOf(
        "id" to "RTCInboundRTPVideoStream_1",
        "type" to "inbound-rtp",
        "kind" to "video",
        "packetsReceived" to 999_999L,
    )

    /**
     * `media-source`（audio）—— ERLE 挂在这一条上。
     *
     * 形状照抄 AAR 里实证过的字面量：`media-source` 与 `echoReturnLossEnhancement` 都在
     * `libjingle_peerconnection_so.so` 的串表里，而短的 `echoReturnLoss` **不在**
     * （见 [LinkStatsParser.parseEchoSuppression] 的注释），所以夹具只用长名。
     */
    private fun mediaSource(echoReturnLossEnhancement: Double): Map<String, Any?> = mapOf(
        "id" to "RTCAudioSource_1234567890",
        "type" to "media-source",
        "kind" to "audio",
        "trackIdentifier" to "audio0",
        "echoReturnLossEnhancement" to echoReturnLossEnhancement,
    )

    /** 同一个夹具，但数值是整数类型（`members` 是 `Map<String, Object>`，可能是 Integer）。 */
    private fun mediaSourceInt(echoReturnLossEnhancement: Int): Map<String, Any?> =
        mediaSource(echoReturnLossEnhancement.toDouble()).toMutableMap()
            .apply { this["echoReturnLossEnhancement"] = echoReturnLossEnhancement }

    /** 同一个夹具，但数值是字符串（少数平台的 stats 就是字符串）。 */
    private fun mediaSourceText(echoReturnLossEnhancement: String): Map<String, Any?> =
        mediaSource(0.0).toMutableMap()
            .apply { this["echoReturnLossEnhancement"] = echoReturnLossEnhancement }
}
