package com.cinemahall.core.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 启动时的账号判定结果（方案 §8.2）。
 *
 * 三种结局对应三条互斥的启动路径：
 *  - [Ready]        → 连信令，进主界面；
 *  - [NeedActivation] → 激活页（不连信令）；
 *  - 没有第三种：`AuthMode.OFF` 的跳过由装配层在**调用本方法之前**处理，
 *    不混进账号状态机 —— 否则「关掉账号」这件事会渗透进每个分支。
 */
sealed interface BootstrapResult {

    /**
     * 有可用凭据。
     *
     * [verified] = 这次真的问过服务端并拿到了 200；false 表示网络不可达，
     * 用的是本地凭据（**不清 token**，因为「连不上」与「token 失效」是两件事）。
     */
    data class Ready(
        val session: AccountSession,
        val profile: AccountProfile?,
        val verified: Boolean,
    ) : BootstrapResult

    /** 没有凭据，或凭据已被服务端判定失效。 */
    data class NeedActivation(val reason: ActivationReason = ActivationReason.NO_CREDENTIAL) : BootstrapResult
}

/** 为什么落到激活页 —— 文案由界面决定，这里只给分类。 */
enum class ActivationReason {
    NO_CREDENTIAL,
    SESSION_INVALID,
    DEVICE_REVOKED,
    ACCOUNT_SUSPENDED,
}

/**
 * 账号的客户端门面：登录态、启动校验、资料维护、错误映射。
 *
 * ── 线程 ────────────────────────────────────────────────────────────────────
 * 全部 `suspend`，内部只碰 [AccountApi] 与 [CredentialStore]（都是 suspend）。
 * 状态用 [StateFlow]，界面可以安全地跨线程订阅。
 *
 * ── 为什么 `bootstrap()` 返回结果而不是直接改界面 ──────────────────────────────
 * 「启动该去哪一页」是导航决策，属于 `app`；本类只回答「凭据是否可用、为什么不可用」。
 * 把导航塞进来会让这个纯 JVM 模块反过来依赖界面模型。
 */
class AccountRepository(
    private val api: AccountApi,
    private val store: CredentialStore,
    /** 上报给服务端的平台标识，例如 `android`。 */
    private val platform: String,
    /** 上报给服务端的版本号，例如 `0.1.31`。 */
    private val appVersion: String,
) {

    private val _session = MutableStateFlow<AccountSession?>(null)
    val session: StateFlow<AccountSession?> = _session.asStateFlow()

    private val _profile = MutableStateFlow<AccountProfile?>(null)
    val profile: StateFlow<AccountProfile?> = _profile.asStateFlow()

    /**
     * 启动判定（方案 §8.2）。
     *
     * ```
     * 读 token
     *  ├─ 无        → NeedActivation(NO_CREDENTIAL)
     *  └─ 有 → GET /api/me（3 秒）
     *        ├─ 200       → Ready(verified = true)
     *        ├─ 401/403   → 清 token → NeedActivation(SESSION_INVALID)
     *        │   · DEVICE_REVOKED / ACCOUNT_SUSPENDED 单独给原因
     *        └─ 断网/超时 → Ready(verified = false)，**保留 token**
     * ```
     *
     * 最后一条是刻意与「401 清 token」分开的：地铁里打开应用不该让人重新激活。
     */
    suspend fun bootstrap(): BootstrapResult {
        val stored = store.load() ?: return BootstrapResult.NeedActivation()

        val profile = try {
            api.me(stored.token)
        } catch (e: AccountApiException) {
            return when {
                e.code == AccountErrorCodes.DEVICE_REVOKED -> {
                    store.clear()
                    BootstrapResult.NeedActivation(ActivationReason.DEVICE_REVOKED)
                }
                e.code == AccountErrorCodes.ACCOUNT_SUSPENDED -> {
                    store.clear()
                    BootstrapResult.NeedActivation(ActivationReason.ACCOUNT_SUSPENDED)
                }
                e.isAuthFailure -> {
                    store.clear()
                    BootstrapResult.NeedActivation(ActivationReason.SESSION_INVALID)
                }
                // 传输失败：token 很可能还是好的，只是现在问不到。
                else -> {
                    _profile.value = null
                    _session.value = stored.toSession()
                    return BootstrapResult.Ready(stored.toSession(), profile = null, verified = false)
                }
            }
        }

        // 服务端的显示名是权威（可能在别的设备上改过）；顺手把本地凭据刷新。
        val refreshed = stored.copy(displayName = profile.displayName)
        store.save(refreshed)
        _session.value = refreshed.toSession()
        _profile.value = profile
        return BootstrapResult.Ready(refreshed.toSession(), profile, verified = true)
    }

    /**
     * 入场码激活。
     *
     * @param nickname 可选；空白视为「没填」，交给服务端生成「影友XXXX」。
     * @throws AccountApiException 码非法 / 过期 / 次数用尽 / 网络失败。
     */
    suspend fun activate(code: String, deviceName: String, nickname: String?): AccountSession {
        val normalized = normalizeOrThrow(code, AccountCodeNormalizer.INVITE_LENGTH, "入场码")
        val cleanedName = nickname?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedName != null && AccountDisplayName.normalize(cleanedName) == null) {
            throw AccountApiException(
                AccountErrorCodes.BAD_PAYLOAD,
                AccountDisplayName.validationMessage(cleanedName) ?: "昵称不合法",
                400,
            )
        }
        val session = api.activate(
            code = normalized,
            deviceName = deviceName,
            platform = platform,
            appVersion = appVersion,
            nickname = cleanedName,
        )
        persist(session)
        return session
    }

    /** 恢复码：把**已有账号**搬到本机。刻意没有 nickname 参数（契约 v1.2）。 */
    suspend fun recover(code: String, deviceName: String): AccountSession {
        val normalized = normalizeOrThrow(code, AccountCodeNormalizer.RECOVERY_LENGTH, "恢复码")
        val session = api.recover(
            code = normalized,
            deviceName = deviceName,
            platform = platform,
            appVersion = appVersion,
        )
        persist(session)
        return session
    }

    /** 重新拉一次资料（设置页下拉刷新 / 改名之后）。 */
    suspend fun refreshProfile(): AccountProfile = guarded {
        val token = requireToken()
        val profile = api.me(token)
        _profile.value = profile
        val current = _session.value
        if (current != null && current.displayName != profile.displayName) {
            val updated = current.copy(displayName = profile.displayName)
            _session.value = updated
            store.save(updated.toStoredCredential())
        }
        profile
    }

    suspend fun updateDisplayName(displayName: String): AccountProfile {
        val cleaned = AccountDisplayName.normalize(displayName)
            ?: throw AccountApiException(
                AccountErrorCodes.BAD_PAYLOAD,
                AccountDisplayName.validationMessage(displayName) ?: "昵称不合法",
                400,
            )
        return guarded {
            val profile = api.updateDisplayName(requireToken(), cleaned)
            _profile.value = profile
            _session.value?.let { session ->
                store.save(session.copy(displayName = profile.displayName).toStoredCredential())
                _session.value = session.copy(displayName = profile.displayName)
            }
            profile
        }
    }

    suspend fun revokeDevice(deviceId: String) = guarded {
        api.revokeDevice(requireToken(), deviceId)
        // 撤销的若是本机，token 立刻失效；下一次 bootstrap 会走 401 分支。
        // 这里不主动清，因为服务端也可能拒绝撤销（404 / 限流），让它自己说。
        refreshProfile()
        Unit
    }

    /** 生成新恢复码（旧的立即作废，原文只此一次）。 */
    suspend fun createRecoveryCode(): String = guarded { api.createRecoveryCode(requireToken()) }

    /**
     * 退出登录：先告诉服务端撤销当前会话，再清本地凭据。
     *
     * 服务端失败也照样清本地 —— 用户点了「退出」，本机必须立刻退出。
     * 服务端那枚 token 会随 `expiresAt` 自然过期；下一次成功联网时若还能用，
     * 它也只是「一枚没人持有的 token」。
     */
    suspend fun logout() {
        val token = _session.value?.token ?: store.load()?.token
        if (token != null) {
            runCatching { api.logout(token) }
        }
        store.clear()
        _session.value = null
        _profile.value = null
    }

    /** 当前凭据（给影片库分键、`AccountUserIdProvider` 用）。 */
    suspend fun currentCredential(): StoredCredential? = store.load()

    /**
     * 影片库的账号作用域：账号 id，未激活为 `local`（方案 §8.4）。
     *
     * 用 `accountId` 而不是 `deviceId`：换设备后片单应当跟着账号走。
     */
    suspend fun libraryScope(): String = store.load()?.accountId ?: LOCAL_SCOPE

    /**
     * 换成内存里已有的会话（`bootstrap` 之外，激活成功之后也已经写盘）。
     * 只用于测试与「进程内热切换账号」的极端路径。
     */
    fun adopt(session: AccountSession) {
        _session.value = session
    }

    private suspend fun persist(session: AccountSession) {
        store.save(session.toStoredCredential())
        _session.value = session
        // 激活/恢复之后立刻拉一次资料：设置页要用设备列表，而这一次的往返
        // 恰好也是「token 确实可用」的验证。
        _profile.value = runCatching { api.me(session.token) }.getOrNull()
    }

    /**
     * 包住所有「需要 token」的调用：服务端一旦说这个 token 不行了，立刻清本地凭据。
     *
     * ── 为什么非有这一层不可（门禁「撤销设备后立刻失效」）──────────────────────
     * 撤销是在**另一台设备**上做的，本机收不到任何推送。下一次请求就是唯一的发现点：
     * 服务端回 401/403 + `DEVICE_REVOKED`。若只把异常抛给界面，本机的 token 还留在盘上，
     * 用户重启应用时 `bootstrap` 会再走一遍失败流程 —— 期间界面还显示着账号资料，
     * 像是「撤销没生效」。
     *
     * 清掉之后界面下一次组合 `session`/`profile` 为 null；设置页那一屏会显示错误，
     * 而 `AppShell` 的启动门在下次进入时直接落到激活页。
     */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: AccountApiException) {
        if (e.isAuthFailure) {
            store.clear()
            _session.value = null
            _profile.value = null
        }
        throw e
    }

    /**
     * 当前这枚 token（**同步**读，给信令握手用）。
     *
     * 与 [requireToken] 的区别：那个是 `suspend`，且会读盘 + 在缺失时抛异常，适合
     * HTTP 请求路径；信令的 `Authorization` 只能贴在**建连那一刻**（`WsSignaler`
     * 的同步路径），既不能等挂起，也不该为一次握手去解密凭据。所以这里只读内存
     * 里已经 bootstrap / 激活出来的会话：拿不到就返回 null（不贴头）。
     */
    fun tokenOrNull(): String? = _session.value?.token

    private suspend fun requireToken(): String =
        _session.value?.token
            ?: store.load()?.token
            ?: throw AccountApiException(
                AccountErrorCodes.AUTH_REQUIRED,
                "尚未激活",
                401,
            )

    private fun normalizeOrThrow(raw: String, expectedLength: Int, label: String): String {
        val normalized = AccountCodeNormalizer.normalize(raw)
            ?: throw AccountApiException(
                AccountErrorCodes.CODE_INVALID,
                "$label 格式不正确",
                400,
            )
        if (normalized.length != expectedLength) {
            throw AccountApiException(
                AccountErrorCodes.CODE_INVALID,
                "$label 应为 $expectedLength 位",
                400,
            )
        }
        return normalized
    }

    companion object {
        /** 未激活时的影片库作用域。 */
        const val LOCAL_SCOPE = "local"

        const val PLATFORM_ANDROID = "android"
    }
}
