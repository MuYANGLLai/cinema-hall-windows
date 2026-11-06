package com.cinemahall.core.account

/**
 * 账号 HTTP 接口（契约 §8.5，**签名冻结**）。
 *
 * 全部 `suspend`：实现内部用 OkHttp 的同步调用包在 `withContext(Dispatchers.IO)` 里，
 * 不给调用方留「在哪个线程上跑」的疑问。
 *
 * ── `nickname` 只在 activate 上出现 ──────────────────────────────────────────
 * `recover` **刻意不收** nickname（契约 v1.2 P2 = B+ 的裁决）：恢复码只把已有账号搬到
 * 新设备，账号名已经存在；允许它改名等于让任何拿到恢复码的人重命名你的账号。
 * 改名走 [updateDisplayName]。
 */
interface AccountApi {

    /** 入场码 → 新账号 + 设备 + token。`nickname` 不给则服务端生成「影友XXXX」。 */
    suspend fun activate(
        code: String,
        deviceName: String,
        platform: String,
        appVersion: String,
        nickname: String? = null,
    ): AccountSession

    /** 恢复码 → 在**已有账号**下加一台设备。刻意没有 nickname 参数。 */
    suspend fun recover(
        code: String,
        deviceName: String,
        platform: String,
        appVersion: String,
    ): AccountSession

    /** 校验 token 并取回资料 + 设备列表。401/403 表示 token 已不可用。 */
    suspend fun me(token: String): AccountProfile

    /** `PATCH /api/me`，body `{"displayName":"…"}`，返回改后的完整资料。 */
    suspend fun updateDisplayName(token: String, displayName: String): AccountProfile

    /** `DELETE /api/devices/{id}`；服务端回 204（无 body）。 */
    suspend fun revokeDevice(token: String, deviceId: String)

    /** `POST /api/codes/recovery`；**旧的恢复码立即作废**，原文只返回这一次。 */
    suspend fun createRecoveryCode(token: String): String

    /** `POST /api/logout`：只撤销当前会话，不动设备与账号。 */
    suspend fun logout(token: String)
}

/**
 * 账号接口的一次失败。
 *
 * [code] 是**服务端错误码原样**（`AUTH_INVALID` / `CODE_EXPIRED` / …），
 * 网络层的失败用本类自己的两个码（[NETWORK] / [TIMEOUT]）—— 这样界面只需要
 * 认一套码，不必区分「HTTP 抛的」与「OkHttp 抛的」。
 *
 * [httpStatus] 为 0 表示请求根本没拿到响应（断网、DNS、TLS、超时）。
 */
class AccountApiException(
    val code: String,
    override val message: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** token 已经不能用了 —— 启动流程据此清凭据并回激活页。 */
    val isAuthFailure: Boolean
        get() = httpStatus == 401 || httpStatus == 403 ||
            code == AccountErrorCodes.AUTH_INVALID ||
            code == AccountErrorCodes.DEVICE_REVOKED ||
            code == AccountErrorCodes.ACCOUNT_SUSPENDED

    /** 请求没到服务端（可重试，不该清凭据）。 */
    val isTransportFailure: Boolean
        get() = code == NETWORK || code == TIMEOUT

    companion object {
        /** 本地码：连接失败 / 读超时。服务端没有这两个码。 */
        const val NETWORK = "NETWORK"
        const val TIMEOUT = "TIMEOUT"
    }
}

/**
 * 服务端错误码（`docs/账号系统方案（待审核）.md` §6.3）。
 *
 * 只列客户端**需要分支**的那些；其余（`BAD_PAYLOAD` / `NOT_FOUND` / `INTERNAL`）
 * 统一按「原样展示 message」处理，不在这里造常量。
 */
object AccountErrorCodes {
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val AUTH_INVALID = "AUTH_INVALID"
    const val DEVICE_REVOKED = "DEVICE_REVOKED"
    const val ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED"
    const val CODE_INVALID = "CODE_INVALID"
    const val CODE_EXPIRED = "CODE_EXPIRED"
    const val CODE_USED_UP = "CODE_USED_UP"
    const val CODE_REVOKED = "CODE_REVOKED"
    const val DEVICE_LIMIT = "DEVICE_LIMIT"
    const val TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS"
    const val BAD_PAYLOAD = "BAD_PAYLOAD"
}
