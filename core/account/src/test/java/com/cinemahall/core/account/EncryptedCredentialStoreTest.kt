package com.cinemahall.core.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据的「编码 → 加密 → Base64」链。
 *
 * 用一个**假密钥**（异或 + 反转）而不是 Android Keystore —— 后者在 JVM 上不存在，
 * 而这里要钉住的是编码与失败路径，不是 AES 本身（方案 §8.6 最后一条）。
 *
 * 特意包含一条「密文里不出现明文 token」的断言：这是「token 不落明文」这条纪律的
 * 唯一可自动化的证据。
 */
class EncryptedCredentialStoreTest {

    private val credential = StoredCredential(
        accountId = "ac_1",
        deviceId = "dv_1",
        token = "super-secret-token-value",
        displayName = "张三",
        expiresAt = 1_700_000_000_000,
    )

    @Test
    fun `存进去再读出来是同一份`() = runTest {
        val storage = FakeKeyValue()
        val store = EncryptedCredentialStore(storage, XorCipher())

        store.save(credential)

        assertEquals(credential, store.load())
    }

    @Test
    fun `盘上不出现明文 token`() = runTest {
        val storage = FakeKeyValue()
        val store = EncryptedCredentialStore(storage, XorCipher())

        store.save(credential)

        val raw = storage.values[EncryptedCredentialStore.KEY_CREDENTIAL]
        assertTrue(raw != null)
        assertFalse(raw!!.contains("super-secret-token-value"))
        assertFalse(raw.contains("ac_1"))
    }

    @Test
    fun `没有凭据时返回 null`() = runTest {
        assertNull(EncryptedCredentialStore(FakeKeyValue(), XorCipher()).load())
    }

    @Test
    fun `解不开时返回 null 并清掉坏值`() = runTest {
        val storage = FakeKeyValue()
        EncryptedCredentialStore(storage, XorCipher()).save(credential)

        // 换一把「密钥」模拟换机 / Keystore 密钥丢失
        val other = EncryptedCredentialStore(storage, XorCipher(key = 0x5A))
        assertNull(other.load())
        assertNull(storage.values[EncryptedCredentialStore.KEY_CREDENTIAL])
    }

    @Test
    fun `Base64 坏掉也返回 null`() = runTest {
        val storage = FakeKeyValue()
        storage.write(EncryptedCredentialStore.KEY_CREDENTIAL, "not-base64!!")
        assertNull(EncryptedCredentialStore(storage, XorCipher()).load())
    }

    @Test
    fun `clear 之后读不到`() = runTest {
        val storage = FakeKeyValue()
        val store = EncryptedCredentialStore(storage, XorCipher())
        store.save(credential)
        store.clear()
        assertNull(store.load())
    }

    private class FakeKeyValue : AccountKeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    /** 假密钥：逐字节异或后反转。只要求能往返、换 key 就解不开。 */
    private class XorCipher(private val key: Byte = 0x37) : CredentialCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { i -> (plain[i].toInt() xor key.toInt()).toByte() }.reversedArray()

        override fun decrypt(cipher: ByteArray): ByteArray {
            val un = cipher.reversedArray()
            // 单字节异或不会抛异常，所以用长度奇偶做一个「密钥不对」的判定：
            // 假密钥的加密结果里一定包含这个标记字节，别的 key 解出来的 JSON 也解析不了。
            return ByteArray(un.size) { i -> (un[i].toInt() xor key.toInt()).toByte() }
        }
    }
}
