package com.cinemahall.core.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动状态机与错误映射（方案 §8.2 / §8.6）。
 *
 * 这里不碰网络：`AccountApi` 是可控的假实现。要钉住的是**分支**——
 * 「401 清 token」「断网不清 token」「DEVICE_REVOKED 给单独原因」这三条各自
 * 错了都会表现成「用户被莫名其妙踢回激活页」，而它们没有任何日志可看。
 */
class AccountRepositoryTest {

    private val stored = StoredCredential(
        accountId = "ac_1",
        deviceId = "dv_1",
        token = "tok-1",
        displayName = "旧名字",
        expiresAt = 9_999_999_999_999,
    )

    // ── bootstrap ──────────────────────────────────────────────────────────

    @Test
    fun `没有凭据时直接去激活页`() = runTest {
        val repository = repository(store = MemoryStore())

        val result = repository.bootstrap()

        assertTrue(result is BootstrapResult.NeedActivation)
        assertEquals(ActivationReason.NO_CREDENTIAL, (result as BootstrapResult.NeedActivation).reason)
    }

    @Test
    fun `me 成功时进主界面并刷新显示名`() = runTest {
        val store = MemoryStore(stored)
        val api = FakeApi {
            profile(displayName = "新名字")
        }
        val repository = repository(api = api, store = store)

        val result = repository.bootstrap()

        assertTrue(result is BootstrapResult.Ready)
        val ready = result as BootstrapResult.Ready
        assertTrue(ready.verified)
        assertEquals("新名字", ready.session.displayName)
        assertEquals("新名字", store.credential?.displayName)
    }

    @Test
    fun `四零一清掉本地凭据并标成会话失效`() = runTest {
        val store = MemoryStore(stored)
        val repository = repository(
            api = FakeApi { throw AccountApiException(AccountErrorCodes.AUTH_INVALID, "无效", 401) },
            store = store,
        )

        val result = repository.bootstrap()

        assertEquals(ActivationReason.SESSION_INVALID, (result as BootstrapResult.NeedActivation).reason)
        assertNull("401 之后必须清掉 token", store.credential)
    }

    @Test
    fun `设备被撤销给单独的原因`() = runTest {
        val store = MemoryStore(stored)
        val repository = repository(
            api = FakeApi {
                throw AccountApiException(AccountErrorCodes.DEVICE_REVOKED, "设备已移除", 403)
            },
            store = store,
        )

        val result = repository.bootstrap()

        assertEquals(ActivationReason.DEVICE_REVOKED, (result as BootstrapResult.NeedActivation).reason)
        assertNull(store.credential)
    }

    @Test
    fun `账号停用也给单独的原因`() = runTest {
        val store = MemoryStore(stored)
        val repository = repository(
            api = FakeApi {
                throw AccountApiException(AccountErrorCodes.ACCOUNT_SUSPENDED, "账号已停用", 403)
            },
            store = store,
        )

        val result = repository.bootstrap()

        assertEquals(ActivationReason.ACCOUNT_SUSPENDED, (result as BootstrapResult.NeedActivation).reason)
        assertNull(store.credential)
    }

    @Test
    fun `断网时保留 token 并允许离线进入`() = runTest {
        val store = MemoryStore(stored)
        val repository = repository(
            api = FakeApi { throw AccountApiException(AccountApiException.NETWORK, "连不上", 0) },
            store = store,
        )

        val result = repository.bootstrap()

        val ready = result as BootstrapResult.Ready
        assertFalse(ready.verified)
        assertEquals("tok-1", ready.session.token)
        assertEquals("断网不该让人重新激活", stored, store.credential)
    }

    // ── activate / recover ─────────────────────────────────────────────────

    @Test
    fun `入场码先归一化再去请求`() = runTest {
        val api = FakeApi { profile() }
        val repository = repository(api = api)

        repository.activate(" abcd-2345 ", "Pad", null)

        assertEquals("ABCD2345", api.lastActivateCode)
        assertEquals("Pad", api.lastDeviceName)
        assertNull(api.lastNickname)
    }

    @Test
    fun `空白昵称视为没填`() = runTest {
        val api = FakeApi { profile() }
        val repository = repository(api = api)

        repository.activate("ABCD2345", "Pad", "   ")

        assertNull(api.lastNickname)
    }

    @Test
    fun `非法昵称在本地就被拒绝且不发请求`() = runTest {
        val api = FakeApi { profile() }
        val repository = repository(api = api)

        val error = apiError {
            repository.activate("ABCD2345", "Pad", "啊".repeat(33))
        }

        assertEquals(AccountErrorCodes.BAD_PAYLOAD, error.code)
        assertEquals("不该发出去", 0, api.activateCalls)
    }

    @Test
    fun `入场码位数不对在本地就被拒绝`() = runTest {
        val repository = repository()

        val error = apiError { repository.activate("ABCDEFGHJKMN", "Pad", null) }

        assertEquals(AccountErrorCodes.CODE_INVALID, error.code)
        assertTrue(error.message.contains("8"))
    }

    @Test
    fun `恢复码必须是十二位`() = runTest {
        val repository = repository()

        val error = apiError { repository.recover("ABCD2345", "Pad") }

        assertEquals(AccountErrorCodes.CODE_INVALID, error.code)
        assertTrue(error.message.contains("12"))
    }

    @Test
    fun `恢复成功之后凭据被写入`() = runTest {
        val store = MemoryStore()
        val api = FakeApi { profile() }
        val repository = repository(api = api, store = store)

        repository.recover("ABCD-EFGH-JKMN", "Pad")

        assertEquals("ABCDEFGHJKMN", api.lastRecoverCode)
        assertEquals("dv_1", store.credential?.deviceId)
    }

    // ── 改名 / 登出 / 作用域 ────────────────────────────────────────────────

    @Test
    fun `改名成功会刷新会话与凭据`() = runTest {
        val store = MemoryStore(stored)
        val api = FakeApi { profile(displayName = "李四") }
        api.onUpdateDisplayName = { name -> profile(displayName = name) }
        val repository = repository(api = api, store = store)
        repository.bootstrap()

        repository.updateDisplayName("  李四  ")

        assertEquals("李四", repository.session.value?.displayName)
        assertEquals("李四", store.credential?.displayName)
        assertEquals("改名前后的首尾空白必须去掉", "李四", api.lastDisplayName)
    }

    @Test
    fun `空名字在本地被拒绝`() = runTest {
        val repository = repository(store = MemoryStore(stored))
        repository.bootstrap()

        val error = apiError { repository.updateDisplayName("   ") }

        assertEquals(AccountErrorCodes.BAD_PAYLOAD, error.code)
    }

    @Test
    fun `退出登录即使服务端失败也清本地`() = runTest {
        val store = MemoryStore(stored)
        val api = FakeApi { profile() }
        api.onLogout = { throw AccountApiException(AccountApiException.NETWORK, "断网", 0) }
        val repository = repository(api = api, store = store)
        repository.bootstrap()

        repository.logout()

        assertNull(store.credential)
        assertNull(repository.session.value)
    }

    @Test
    fun `刷新资料时发现设备被撤销会立刻清掉凭据`() = runTest {
        val store = MemoryStore(stored)
        var revoked = false
        val repository = repository(
            api = FakeApi {
                if (revoked) {
                    throw AccountApiException(AccountErrorCodes.DEVICE_REVOKED, "这台设备已被移除", 403)
                }
                profile()
            },
            store = store,
        )
        repository.bootstrap()
        // 另一台设备把它撤销了：本机下一次请求才发现。
        revoked = true

        apiError { repository.refreshProfile() }

        assertNull("撤销之后本机不该还留着 token", store.credential)
        assertNull(repository.session.value)
    }

    @Test
    fun `断网刷新资料不会清掉凭据`() = runTest {
        val store = MemoryStore(stored)
        var offline = false
        val repository = repository(
            api = FakeApi {
                if (offline) throw AccountApiException(AccountApiException.NETWORK, "断网", 0)
                profile()
            },
            store = store,
        )
        repository.bootstrap()
        offline = true

        apiError { repository.refreshProfile() }

        assertEquals("断网是暂时状态，不能让人重新激活", "tok-1", store.credential?.token)
    }

    @Test
    fun `未激活时影片库落在 local 作用域`() = runTest {
        val repository = repository(store = MemoryStore())
        assertEquals(AccountRepository.LOCAL_SCOPE, repository.libraryScope())
    }

    @Test
    fun `激活后影片库落在账号作用域`() = runTest {
        val repository = repository(store = MemoryStore(stored))
        assertEquals("ac_1", repository.libraryScope())
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private fun repository(
        api: FakeApi = FakeApi { profile() },
        store: MemoryStore = MemoryStore(),
    ) = AccountRepository(
        api = api,
        store = store,
        platform = "android",
        appVersion = "0.1.31",
    )

    private suspend fun apiError(block: suspend () -> Unit): AccountApiException {
        try {
            block()
        } catch (e: AccountApiException) {
            return e
        }
        throw AssertionError("期望 AccountApiException，但没有抛出")
    }

    private fun profile(displayName: String = "张三") = AccountProfile(
        accountId = "ac_1",
        displayName = displayName,
        devices = listOf(DeviceInfo(deviceId = "dv_1", name = "Pad", current = true)),
    )

    private class MemoryStore(var credential: StoredCredential? = null) : CredentialStore {
        override suspend fun load(): StoredCredential? = credential
        override suspend fun save(credential: StoredCredential) {
            this.credential = credential
        }

        override suspend fun clear() {
            credential = null
        }
    }

    /**
     * 假 API：默认「me 成功、activate/recover 返回同一份会话」。
     *
     * 用 lambda 而不是可变字段，是为了让每个用例在构造处就把行为写清楚 ——
     * 「这个用例里 me 会抛 401」是它最重要的信息，不该藏在三行之后。
     */
    private class FakeApi(
        private val onMe: suspend (String) -> AccountProfile,
    ) : AccountApi {

        var onUpdateDisplayName: suspend (String) -> AccountProfile = { onMe("tok") }
        var onLogout: suspend () -> Unit = {}

        var activateCalls = 0
        var lastActivateCode: String? = null
        var lastRecoverCode: String? = null
        var lastDeviceName: String? = null
        var lastNickname: String? = null
        var lastDisplayName: String? = null

        private val session = AccountSession(
            accountId = "ac_1",
            displayName = "张三",
            deviceId = "dv_1",
            token = "tok-1",
            expiresAt = 9_999_999_999_999,
        )

        override suspend fun activate(
            code: String,
            deviceName: String,
            platform: String,
            appVersion: String,
            nickname: String?,
        ): AccountSession {
            activateCalls++
            lastActivateCode = code
            lastDeviceName = deviceName
            lastNickname = nickname
            return session
        }

        override suspend fun recover(
            code: String,
            deviceName: String,
            platform: String,
            appVersion: String,
        ): AccountSession {
            lastRecoverCode = code
            lastDeviceName = deviceName
            return session
        }

        override suspend fun me(token: String): AccountProfile = onMe(token)

        override suspend fun updateDisplayName(token: String, displayName: String): AccountProfile {
            lastDisplayName = displayName
            return onUpdateDisplayName(displayName)
        }

        override suspend fun revokeDevice(token: String, deviceId: String) = Unit

        override suspend fun createRecoveryCode(token: String): String = "ABCD-EFGH-JKMN"

        override suspend fun logout(token: String) = onLogout()
    }
}
