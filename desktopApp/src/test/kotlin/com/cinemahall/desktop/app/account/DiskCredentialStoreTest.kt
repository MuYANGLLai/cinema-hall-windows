package com.cinemahall.desktop.app.account

import com.cinemahall.core.account.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `DiskCredentialStore` 的落盘行为（纯 JVM，不碰网络）。
 *
 * 钉住三条**会静默错**的性质：
 * 1. 按端点分桶 —— 生产 token 不能被发到本机开发服务；
 * 2. 坏文件当成「没凭据」而不是崩 —— 半截 JSON 不该让人闪退；
 * 3. 写完不残留 `.tmp` —— 残留会被下一次 `ATOMIC_MOVE` 覆盖，看不出问题，
 *    但用户备份 `data/` 时会把旧 token 一起带走。
 */
class DiskCredentialStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun credential(token: String) = StoredCredential(
        accountId = "ac_test",
        deviceId = "dv_test",
        token = token,
        displayName = "测试",
        expiresAt = 1_800_000_000_000,
    )

    @Test
    fun `save then load roundtrips`() = runTest {
        val file = File(tmp.root, "credentials.json")
        val store = DiskCredentialStore(file, "https://h:8443")

        store.save(credential("tok-a"))

        assertEquals("tok-a", store.load()?.token)
        assertTrue("凭据文件应当落盘", file.isFile)
    }

    @Test
    fun `different endpoints do not share a credential`() = runTest {
        val file = File(tmp.root, "credentials.json")
        val prod = DiskCredentialStore(file, "https://prod:8443")
        val local = DiskCredentialStore(file, "http://127.0.0.1:8080")

        prod.save(credential("prod-token"))
        local.save(credential("local-token"))

        assertEquals("prod-token", prod.load()?.token)
        assertEquals("local-token", local.load()?.token)
    }

    @Test
    fun `clear only clears its own endpoint`() = runTest {
        val file = File(tmp.root, "credentials.json")
        val prod = DiskCredentialStore(file, "https://prod:8443")
        val local = DiskCredentialStore(file, "http://127.0.0.1:8080")
        prod.save(credential("prod-token"))
        local.save(credential("local-token"))

        local.clear()

        assertNull(local.load())
        assertEquals("prod-token", prod.load()?.token)
    }

    @Test
    fun `corrupt file is treated as no credential`() = runTest {
        val file = File(tmp.root, "credentials.json")
        file.writeText("{ 这不是 JSON")

        assertNull(DiskCredentialStore(file, "https://prod:8443").load())
        // 坏文件不删：里面可能还有别的端点，下一次 save 会整体覆盖。
        assertTrue(file.isFile)
    }

    @Test
    fun `save leaves no temp file behind`() = runTest {
        val file = File(tmp.root, "credentials.json")
        val store = DiskCredentialStore(file, "https://prod:8443")

        store.save(credential("tok-a"))

        val leftovers = tmp.root.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("不应残留临时文件：$leftovers", leftovers.isEmpty())
    }
}
