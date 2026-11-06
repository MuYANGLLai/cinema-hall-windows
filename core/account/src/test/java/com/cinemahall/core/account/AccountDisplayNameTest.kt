package com.cinemahall.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 显示名归一化（契约 §8.5 P2 = B+）。
 *
 * 服务端的规则是「1..32、拒绝控制字符、非法一律 BAD_PAYLOAD」。这里逐条对齐，
 * 并且**不做静默截断** —— 用户输入 40 个字时应当看到报错，而不是名字被悄悄砍短。
 */
class AccountDisplayNameTest {

    @Test
    fun `普通名字原样通过`() {
        assertEquals("张三", AccountDisplayName.normalize("张三"))
    }

    @Test
    fun `去首尾空白但保留中间空格`() {
        assertEquals("张 三", AccountDisplayName.normalize("  张 三  "))
    }

    @Test
    fun `三十二个字刚好通过`() {
        val name = "啊".repeat(32)
        assertEquals(name, AccountDisplayName.normalize(name))
    }

    @Test
    fun `三十三个字被拒绝而不是截断`() {
        assertNull(AccountDisplayName.normalize("啊".repeat(33)))
    }

    @Test
    fun `空与全空白被拒绝`() {
        assertNull(AccountDisplayName.normalize(""))
        assertNull(AccountDisplayName.normalize("   "))
        assertNull(AccountDisplayName.normalize(null))
    }

    @Test
    fun `换行与制表被拒绝`() {
        assertNull(AccountDisplayName.normalize("张\n三"))
        assertNull(AccountDisplayName.normalize("张\t三"))
    }

    @Test
    fun `零宽字符被拒绝`() {
        assertNull(AccountDisplayName.normalize("张\u200B三"))
        assertNull(AccountDisplayName.normalize("张\uFEFF三"))
    }

    @Test
    fun `合法名字没有校验消息`() {
        assertNull(AccountDisplayName.validationMessage("张三"))
    }

    @Test
    fun `过长与空各有自己的消息`() {
        assertNotNull(AccountDisplayName.validationMessage("啊".repeat(33)))
        assertNotNull(AccountDisplayName.validationMessage("  "))
    }
}
