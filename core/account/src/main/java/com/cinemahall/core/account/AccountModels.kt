package com.cinemahall.core.account

import kotlinx.serialization.Serializable

/**
 * 一次成功的激活 / 恢复之后，服务端给的会话（契约 §8.5）。
 *
 * `expiresAt` 是**墙上时钟毫秒**（服务端 `nowMs + sessionTtlMs`），不是单调时钟 ——
 * 客户端只用它做「大概还剩多久」的展示与「明显过期就直接清」的兜底，真正的有效性
 * 判据永远是服务端的 401/403（见 [AccountRepository] 的启动流程）。
 *
 * 字段名与服务端 JSON 逐字相同（`server/src/accounts.mjs` 的 `return {...}`），
 * 所以这里直接 `@Serializable`，不做 DTO 映射 —— 多一层映射只会多一处能写错的地方，
 * 而契约已经把形状冻结了。
 */
@Serializable
data class AccountSession(
    val accountId: String,
    val displayName: String,
    val deviceId: String,
    val token: String,
    val expiresAt: Long,
)

/** 账号下的一台设备（`GET /api/me` 的 `devices[]`）。 */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String = "",
    val platform: String = "",
    val lastSeenAt: Long? = null,
    /** 服务端按「当前 Bearer token 的 device_id」算出来的，客户端不自己猜。 */
    val current: Boolean = false,
)

/** 账号资料（`GET /api/me` / `PATCH /api/me` 的响应体）。 */
@Serializable
data class AccountProfile(
    val accountId: String,
    val displayName: String,
    val devices: List<DeviceInfo> = emptyList(),
)

/**
 * 落盘的凭据（契约 §8.5）。**只在加密后写入** —— 见 [EncryptedCredentialStore]。
 *
 * 比 [AccountSession] 少一个字段也不需要多一个：两者字段恰好相同，但刻意分成两个类型，
 * 因为「会话」是内存里的运行态，「凭据」是磁盘上的持久态，生命周期与失效规则不同
 * （凭据可能比会话活得久：断网启动时读到的就是它）。
 */
@Serializable
data class StoredCredential(
    val accountId: String,
    val deviceId: String,
    val token: String,
    val displayName: String,
    val expiresAt: Long,
)

/** 便于把一次激活的结果直接落盘，不用在各处手抄字段。 */
fun AccountSession.toStoredCredential(): StoredCredential = StoredCredential(
    accountId = accountId,
    deviceId = deviceId,
    token = token,
    displayName = displayName,
    expiresAt = expiresAt,
)

/** 落盘的凭据 → 内存里的会话。`displayName` 可能已被 `GET /api/me` 刷新过。 */
fun StoredCredential.toSession(): AccountSession = AccountSession(
    accountId = accountId,
    displayName = displayName,
    deviceId = deviceId,
    token = token,
    expiresAt = expiresAt,
)
