package com.cinemahall.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 码归一化必须与 `server/src/accounts.mjs` 的 `normalizeCode` 逐条一致。
 *
 * 不一致的症状是「客户端说格式正确、服务端说无效」—— 用户只会觉得这套东西莫名其妙。
 */
class AccountCodeNormalizerTest {

    @Test
    fun `八位入场码原样通过`() {
        assertEquals("ABCD2345", AccountCodeNormalizer.normalize("ABCD2345"))
    }

    @Test
    fun `去分隔符与空白`() {
        assertEquals("ABCDEFGHJKMN", AccountCodeNormalizer.normalize(" ABCD-EFGH-JKMN "))
    }

    @Test
    fun `小写转大写`() {
        assertEquals("ABCD2345", AccountCodeNormalizer.normalize("abcd2345"))
    }

    @Test
    fun `剔掉零与一这类易混字符`() {
        // 0/O/1/I/L 都不在字母表里
        assertNull(AccountCodeNormalizer.normalize("ABCD234O"))
        assertNull(AccountCodeNormalizer.normalize("ABCD234I"))
        assertNull(AccountCodeNormalizer.normalize("ABCD234L"))
        assertNull(AccountCodeNormalizer.normalize("ABCD2340"))
        assertNull(AccountCodeNormalizer.normalize("ABCD2341"))
    }

    @Test
    fun `长度不是 8 或 12 一律拒绝`() {
        assertNull(AccountCodeNormalizer.normalize("ABCD234"))
        assertNull(AccountCodeNormalizer.normalize("ABCD23456"))
        assertNull(AccountCodeNormalizer.normalize("ABCDEFGHJKMNP"))
    }

    @Test
    fun `十二位恢复码通过`() {
        assertEquals("ABCDEFGHJKMN", AccountCodeNormalizer.normalize("ABCD-EFGH-JKMN"))
    }

    @Test
    fun `空与 null 返回 null`() {
        assertNull(AccountCodeNormalizer.normalize(null))
        assertNull(AccountCodeNormalizer.normalize(""))
        assertNull(AccountCodeNormalizer.normalize("   "))
    }

    @Test
    fun `恢复码展示成四字一组`() {
        assertEquals("ABCD-EFGH-JKMN", AccountCodeNormalizer.formatForDisplay("ABCDEFGHJKMN"))
    }

    @Test
    fun `入场码展示不分组`() {
        assertEquals("ABCD2345", AccountCodeNormalizer.formatForDisplay("ABCD2345"))
    }

    @Test
    fun `非法码展示时原样返回`() {
        assertEquals("abc", AccountCodeNormalizer.formatForDisplay("abc"))
    }
}
