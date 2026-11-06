package com.cinemahall.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IceServer` 的三个派生属性（阶段二 2.1 补）。
 *
 * ── 为什么这个纯数据类值得一组用例 ──────────────────────────────────────────
 * 它们不是装饰性的 getter，而是**凭证续签判据的全部依据**：
 *  - `isRelay` 决定「服务端到底给了中继没有」（§8.4 的降级判定）；
 *  - `expiryEpochSeconds` 是「还剩多久」的唯一来源（诊断页与续签判据共用）；
 *  - `earliestExpiryEpochSeconds` 决定「该不该去要新凭证」。
 *
 * 而 `expiryEpochSeconds` 解析的是 TURN REST 约定里的 username
 * （服务端 `turn.mjs` 的 `mintTurnCredential`：`` `${expiry}:${userId}` ``）。
 * 这个格式**没有在协议里写过一行文档**，只活在两端的实现里 —— 一旦有人改格式
 * （比如换成 base64），这里解析出的就是 null，而 null 的语义是「没有 TURN 凭证」，
 * 于是续签兜底会**静默失效**：不报错、不提示，只是过了一小时连不上。
 */
class IceServerTest {

    @Test
    fun 只认turn与turns前缀() {
        assertTrue(IceServer(urls = listOf("turn:1.2.3.4:3478?transport=udp")).isRelay)
        assertTrue(IceServer(urls = listOf("turns:1.2.3.4:5349?transport=tcp")).isRelay)
        assertFalse("STUN 不是中继", IceServer(urls = listOf("stun:1.2.3.4:3478")).isRelay)
        assertFalse("空配置不是中继", IceServer(urls = emptyList()).isRelay)
    }

    @Test
    fun 一条里只要有一个中继地址就算中继() {
        // 服务端把 udp/tcp/turns 三条 URL 装在**同一个条目**里（`buildIceServers`），
        // 所以判据必须是「任一 URL」而不是「第一条 URL」。
        val mixed = IceServer(
            urls = listOf(
                "turn:1.2.3.4:3478?transport=udp",
                "turn:1.2.3.4:3478?transport=tcp",
                "turns:1.2.3.4:5349?transport=tcp",
            ),
            username = "1700000000:me",
            credential = "x",
        )
        assertTrue(mixed.isRelay)
    }

    @Test
    fun 从username第一段解析过期时刻() {
        assertEquals(1_700_000_000L, IceServer(urls = listOf("turn:a"), username = "1700000000:me").expiryEpochSeconds)
    }

    @Test
    fun 解析不出来时是null而不是0() {
        // null = 「没有 TURN 凭证」，0 = 「1970 年就过期了」。两者语义相反：
        // 前者不该触发续签（没有中继可续），后者是「立刻去要新凭证」。
        // 混成一个值，coturn 探不通的部署会在每一轮巡检上白打一次信令。
        assertNull("STUN 条目没有 username", IceServer(urls = listOf("stun:a")).expiryEpochSeconds)
        assertNull("username 不是数字开头", IceServer(urls = listOf("turn:a"), username = "me:1700000000").expiryEpochSeconds)
        assertNull("空 username", IceServer(urls = listOf("turn:a"), username = "").expiryEpochSeconds)
    }

    @Test
    fun 取最早过期的而不是最新的() {
        val servers = listOf(
            IceServer(urls = listOf("turn:a"), username = "1700000900:me"),
            IceServer(urls = listOf("turn:b"), username = "1700000100:me"),
            IceServer(urls = listOf("stun:c")),
        )
        assertEquals(
            "只要有一条先过期，对着它重新 allocation 就会失败 ⇒ 必须按最早的算",
            1_700_000_100L,
            IceServer.earliestExpiryEpochSeconds(servers),
        )
    }

    @Test
    fun 没有任何凭证时取最早返回null() {
        assertNull(IceServer.earliestExpiryEpochSeconds(emptyList()))
        assertNull(IceServer.earliestExpiryEpochSeconds(listOf(IceServer(urls = listOf("stun:a")))))
    }

    @Test
    fun hasRelay扫整组而不是第一条() {
        val stunOnly = listOf(IceServer(urls = listOf("stun:a")))
        val withTurn = listOf(
            IceServer(urls = listOf("stun:a")),
            IceServer(urls = listOf("turn:b"), username = "1700000000:me", credential = "x"),
        )
        assertFalse(IceServer.hasRelay(stunOnly))
        assertTrue(IceServer.hasRelay(withTurn))
    }
}
