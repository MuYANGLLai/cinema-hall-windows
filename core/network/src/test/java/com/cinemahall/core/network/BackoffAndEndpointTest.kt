package com.cinemahall.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 退避序列的钉死测试。
 *
 * 为什么要单独测：这条序列是 §6.8 的验收口径之一（`1s→2s→4s→8s→上限 30s`），
 * 而且它错了以后在真机上**看不出来** —— 只是「重连有点慢」，没人会去数秒。
 */
class ReconnectBackoffTest {

    @Test
    fun `无抖动时严格是 1_2_4_8_16_30_30 秒`() {
        val backoff = ReconnectBackoff(jitterRatio = 0.0)

        val delays = (1..7).map { backoff.nextDelayMs() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `attemptCount 随每次取值递增，reset 后归零`() {
        val backoff = ReconnectBackoff(jitterRatio = 0.0)

        assertEquals(0, backoff.attemptCount)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        assertEquals(2, backoff.attemptCount)

        backoff.reset()

        assertEquals(0, backoff.attemptCount)
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun `有抖动时落在 75 百分比 到 base 之间，且永不超过上限`() {
        val backoff = ReconnectBackoff(jitterRatio = 0.25, random = Random(20261007))
        val bases = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)

        bases.forEach { base ->
            val value = backoff.nextDelayMs()
            val low = (base * 0.75).toLong()
            assertTrue("$value 应 >= $low（对应 base=$base）", value >= low)
            assertTrue("$value 应 <= $base（对应 base=$base）", value <= base)
        }
    }

    @Test
    fun `巨大的尝试次数不会溢出成负数或 0`() {
        val backoff = ReconnectBackoff(jitterRatio = 0.25)
        repeat(200) { backoff.nextDelayMs() }

        repeat(20) {
            val value = backoff.nextDelayMs()
            assertTrue("第 N 次重连的延时必须仍为正数，实际 $value", value > 0)
            assertTrue("仍须被上限截住，实际 $value", value <= 30_000L)
        }
    }

    @Test
    fun `自定义 base 与 max 生效_测试与后续调参都赖这个`() {
        val backoff = ReconnectBackoff(baseMs = 50, maxMs = 120, jitterRatio = 0.0)

        assertEquals(listOf(50L, 100L, 120L, 120L), (1..4).map { backoff.nextDelayMs() })
    }

    @Test
    fun `自定义 max 为 0 的退化输入不会返回 0_延迟`() {
        // 0 延时的重连会变成忙等打服务端。构造上不该出现，但真出现了也不能变成 0。
        val backoff = ReconnectBackoff(baseMs = 10, maxMs = 0, jitterRatio = 0.0)

        assertTrue(backoff.nextDelayMs() >= 1L)
    }
}

class SignalEndpointTest {

    @Test
    fun `只给主机名时按 wss 处理_生产上没有 TLS 会被服务端直接拒`() {
        assertEquals("wss://signal.example.com", normalizeSignalEndpoint("signal.example.com"))
    }

    @Test
    fun `https 映射成 wss，http 映射成 ws`() {
        assertEquals("wss://a.example.com/ws", normalizeSignalEndpoint("https://a.example.com/ws"))
        assertEquals("ws://127.0.0.1:8080", normalizeSignalEndpoint("http://127.0.0.1:8080"))
    }

    @Test
    fun `显式写的 ws 与 wss 原样保留`() {
        assertEquals("ws://127.0.0.1:8080/ws", normalizeSignalEndpoint("ws://127.0.0.1:8080/ws"))
        assertEquals("wss://a.example.com/ws", normalizeSignalEndpoint("wss://a.example.com/ws"))
    }

    @Test
    fun `两侧空白被去掉`() {
        assertEquals("wss://a.example.com", normalizeSignalEndpoint("  a.example.com  "))
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(normalizeSignalEndpoint(""))
        assertNull(normalizeSignalEndpoint("   "))
    }

    @Test
    fun `没有主机名或端口越界时返回 null`() {
        assertNull(normalizeSignalEndpoint("wss://"))
        assertNull(normalizeSignalEndpoint("wss:///only/path"))
        assertNull(normalizeSignalEndpoint("wss://host:99999"))
    }

    @Test
    fun `带路径与查询串的地址不被截断`() {
        val normalized = normalizeSignalEndpoint("https://a.example.com/ws?token=x")
        assertNotNull(normalized)
        assertEquals("wss://a.example.com/ws?token=x", normalized)
    }
}
