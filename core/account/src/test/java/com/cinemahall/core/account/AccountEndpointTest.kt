package com.cinemahall.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 信令地址 → HTTP base URL 的推导。
 *
 * 这一条错了的症状是 **404**（`/ws/api/activate`），而它看起来像「服务端没部署」——
 * 所以逐条钉住：协议前缀、端口保留、path 丢弃、IPv6。
 */
class AccountEndpointTest {

    @Test
    fun `wss 换成 https 并保留端口`() {
        assertEquals(
            "https://101.133.234.226:8443",
            AccountEndpoint.httpBase("wss://101.133.234.226:8443/ws"),
        )
    }

    @Test
    fun `ws 换成 http 并保留端口`() {
        assertEquals(
            "http://192.168.1.7:8080",
            AccountEndpoint.httpBase("ws://192.168.1.7:8080/ws"),
        )
    }

    @Test
    fun `没写端口就不补端口`() {
        assertEquals("https://example.com", AccountEndpoint.httpBase("wss://example.com/ws"))
    }

    @Test
    fun `丢弃信令的 ws 路径`() {
        // 留着 /ws 会拼出 /ws/api/activate —— 这条断言就是防它。
        assertEquals("https://h:8443", AccountEndpoint.httpBase("wss://h:8443/ws"))
    }

    @Test
    fun `带查询串也不影响`() {
        assertEquals("https://h:8443", AccountEndpoint.httpBase("wss://h:8443/ws?token=x"))
    }

    @Test
    fun `https 地址原样可用`() {
        assertEquals("https://h:8443", AccountEndpoint.httpBase("https://h:8443/api"))
    }

    @Test
    fun `IPv6 字面量保留方括号`() {
        assertEquals("https://[::1]:8443", AccountEndpoint.httpBase("wss://[::1]:8443/ws"))
    }

    @Test
    fun `不支持的协议要快速失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountEndpoint.httpBase("ftp://h:21/ws")
        }
    }

    @Test
    fun `空地址要快速失败`() {
        assertThrows(IllegalArgumentException::class.java) { AccountEndpoint.httpBase("   ") }
    }

    @Test
    fun `缺主机名要快速失败`() {
        assertThrows(IllegalArgumentException::class.java) { AccountEndpoint.httpBase("wss:///ws") }
    }

    @Test
    fun `拼路径时不会出现双斜杠或缺斜杠`() {
        assertEquals("https://h:8443/api/me", AccountEndpoint.url("https://h:8443", "/api/me"))
        assertEquals("https://h:8443/api/me", AccountEndpoint.url("https://h:8443/", "api/me"))
    }
}
