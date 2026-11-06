package com.cinemahall.desktop.app.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cinemahall.core.account.AccountApiException
import com.cinemahall.core.account.AccountEndpoint
import com.cinemahall.core.account.AccountErrorCodes
import com.cinemahall.core.account.AccountRepository
import com.cinemahall.core.account.ActivationReason
import com.cinemahall.core.account.BootstrapResult
import com.cinemahall.core.account.HttpAccountApi
import com.cinemahall.core.model.UserId
import com.cinemahall.desktop.app.DESKTOP_APP_VERSION
import com.cinemahall.desktop.app.DESKTOP_PLATFORM
import com.cinemahall.desktop.app.Log
import java.io.File

/**
 * 启动门的三态。
 *
 * 只有 [Ready] 才允许建 `DesktopModule` / 连信令 —— 「没有 token 就别去敲服务端的门」
 * 是这次改动的核心，因为生产是 `AUTH_MODE=required`：没有 `Authorization` 头的
 * WebSocket 握手会被 `verifyClient` 直接 401，而 `WsSignaler` 会把它当成普通断线
 * 一直退避重连（症状：`Connecting → Reconnecting` 循环，永远到不了建房那一步）。
 */
sealed interface AccountPhase {

    /** 还在读本地凭据 / 校验 token。界面显示启动态，**不连信令**。 */
    data object Loading : AccountPhase

    /** 需要激活。[reason] 决定文案，[message] 是可选的服务端原话。 */
    data class NeedsActivation(
        val reason: ActivationReason = ActivationReason.NO_CREDENTIAL,
        val message: String? = null,
    ) : AccountPhase

    /**
     * 可用。
     *
     * [verified] = 这次真的问过服务端（`GET /api/me` 拿到 200）；`false` 表示断网启动，
     * 用的是本地凭据 —— 与 [AccountRepository.bootstrap] 的语义一致，**不清 token**。
     */
    data class Ready(
        val displayName: String,
        val deviceId: String,
        val verified: Boolean,
    ) : AccountPhase
}

/**
 * 桌面端的账号门面（W7-C7）。
 *
 * ── 它只做三件事 ────────────────────────────────────────────────────────────
 * 1. 按**当前信令地址**装配 `HttpAccountApi` + `DiskCredentialStore` + `AccountRepository`
 *    （HTTP base 由 `AccountEndpoint.httpBase` 从 `wss://…/ws` 推导，见契约 §6.4）；
 * 2. 把 `bootstrap()` 的三态翻成 [AccountPhase]，供界面做启动门；
 * 3. 给信令握手提供**同步**取 token 的钩子（`WsSignaler(authToken = …)` 只能同步取）。
 *
 * ── 为什么 `myUserId` 取 `deviceId` 而不是 `identity.txt` ────────────────────
 * 服务端在 `AUTH_MODE != off` 时用**握手时鉴权出来的 deviceId** 当房间身份
 * （`server/src/signaling.mjs`：`ctx.userId = auth?.deviceId ?? null`）。
 * 客户端若还拿 `identity.txt` 里那个 `desktop-xxxxxxxx` 当自己的 userId，
 * 两边对「我是谁」的答案就不一致：成员表里显示的是 deviceId，而本机按 identity
 * 判断房主/座位，症状是「我不是房主却按房主渲染」这类错位。所以激活之后
 * 身份一律以凭据里的 `deviceId` 为准；只有 `AUTH_MODE=off` 的本地开发路径
 * 才继续用 `identity.txt`。
 *
 * ── 线程 ────────────────────────────────────────────────────────────────────
 * 方法都是 `suspend`（内部就是 `HttpAccountApi` 的 `withContext(IO)`）；
 * [phase] 是 Compose 状态，只能在主线程读、在任意线程改（Compose 快照会处理）。
 * token **不进日志**：本类所有日志只打账号 id / 设备 id 的短前缀。
 */
class DesktopAccount(
    /** 凭据文件。按 profile 分开传，见 `Main` 的说明。 */
    dataFile: File,
) {

    var phase: AccountPhase by mutableStateOf(AccountPhase.Loading)
        private set

    /** 当前生效的信令地址（原样，含 `/ws`）。 */
    var signalEndpoint: String by mutableStateOf("")
        private set

    private val credentialFile: File = dataFile

    private var api: HttpAccountApi? = null
    private var repository: AccountRepository? = null

    /** 上一次 [configure] 用的地址；相同就不重建（避免每次重组都换一套 store）。 */
    private var configuredFor: String? = null

    /**
     * 按信令地址装配账号层。地址没变就**什么都不做** —— 它会被放在
     * `LaunchedEffect(settings.signalEndpoint)` 里，重复装配会把正在进行的激活打断。
     *
     * @return 地址是否可用（false = 地址填错了，[phase] 已是 [AccountPhase.NeedsActivation]）
     */
    fun configure(endpoint: String): Boolean {
        val raw = endpoint.trim()
        if (raw == configuredFor && repository != null) return true
        configuredFor = raw
        signalEndpoint = raw

        val base = runCatching { AccountEndpoint.httpBase(raw) }.getOrElse { error ->
            // 地址不合法就不建账号层：这时候去激活只会得到「连不上」，不如直接说地址错了。
            api = null
            repository = null
            phase = AccountPhase.NeedsActivation(message = error.message ?: "信令地址不合法")
            Log.warn("[account] 信令地址不可用：$raw（${error.message}）")
            return false
        }

        val store = DiskCredentialStore(credentialFile, base)
        val created = HttpAccountApi(base)
        api = created
        repository = AccountRepository(
            api = created,
            store = store,
            platform = DESKTOP_PLATFORM,
            appVersion = DESKTOP_APP_VERSION,
        )
        phase = AccountPhase.Loading
        Log.info("[account] 账号层已装配：base=$base 凭据=${credentialFile.name}")
        return true
    }

    /**
     * 装配 + （必要时）启动判定。**幂等**，供 `LaunchedEffect(信令地址)` 调用。
     *
     * ── 为什么不能直接「configure(); bootstrap()」（踩过）──────────────────────
     * `bootstrap()` 一上来就把 [phase] 置回 [AccountPhase.Loading]，于是
     * `--activate` 刚设好的 `Ready` 会被打回 Loading：界面重组回启动态、
     * 已经在跑的自动连接看到 `phase !is Ready` 就中止，而它已经把
     * `autoConnectPending` 消费掉了 —— 症状是「激活成功、界面停在影厅页的连接框，
     * 一整天都不去连」。所以地址没变、且已经是 Ready 时，这里什么都不做。
     */
    suspend fun ensureReady(endpoint: String) {
        val target = endpoint.trim()
        val changed = signalEndpoint != target || repository == null
        if (!configure(target)) return
        if (changed || phase !is AccountPhase.Ready) bootstrap()
    }

    /**
     * 启动判定：读凭据 → `GET /api/me`。
     *
     * 断网时**保留** token 并放行（`verified=false`）：地铁里打开应用不该让人重新激活。
     * 只有服务端明确说这枚 token 不行了（401/403 / `DEVICE_REVOKED` / `ACCOUNT_SUSPENDED`）
     * 才清凭据回激活页。
     */
    suspend fun bootstrap() {
        val repo = repository ?: return
        phase = AccountPhase.Loading
        when (val result = repo.bootstrap()) {
            is BootstrapResult.Ready -> {
                phase = AccountPhase.Ready(
                    displayName = result.session.displayName,
                    deviceId = result.session.deviceId,
                    verified = result.verified,
                )
                Log.info(
                    "[account] 启动判定：已激活 account=${short(result.session.accountId)} " +
                        "device=${short(result.session.deviceId)} verified=${result.verified}",
                )
            }

            is BootstrapResult.NeedActivation -> {
                phase = AccountPhase.NeedsActivation(result.reason)
                Log.info("[account] 启动判定：需要激活 reason=${result.reason}")
            }
        }
    }

    /**
     * 入场码激活。成功后 [phase] 变 [AccountPhase.Ready]，调用方接着建模块即可。
     *
     * @return 失败原因（已按 [AccountMessages] 翻译成人话）；null = 成功。
     */
    suspend fun activate(code: String, deviceName: String, nickname: String?): String? {
        val repo = repository ?: return "账号层未就绪（信令地址不可用）"
        return try {
            val session = repo.activate(code, deviceName, nickname)
            phase = AccountPhase.Ready(
                displayName = session.displayName,
                deviceId = session.deviceId,
                verified = true,
            )
            Log.info(
                "[account] 激活成功 account=${short(session.accountId)} " +
                    "device=${short(session.deviceId)} name=${session.displayName}",
            )
            null
        } catch (e: AccountApiException) {
            Log.warn("[account] 激活失败 code=${e.code} status=${e.httpStatus}")
            AccountMessages.activation(e)
        }
    }

    /**
     * 用服务端做一次权威校验（信令连不上时调用）。
     *
     * @return true = token 仍然可用（或只是网络不通）；false = 已失效，
     *         [phase] 已翻成 [AccountPhase.NeedsActivation]，调用方应停掉信令模块。
     */
    suspend fun revalidate(): Boolean {
        val client = api ?: return false
        val token = tokenOrNull() ?: return false
        return try {
            client.me(token)
            true
        } catch (e: AccountApiException) {
            if (e.isAuthFailure) {
                Log.warn("[account] token 已失效 code=${e.code} → 回激活页")
                phase = AccountPhase.NeedsActivation(AccountMessages.reasonOf(e.code), AccountMessages.activation(e))
                // 清盘：`AccountRepository.guarded` 只在它自己的调用路径上清，
                // 这里是直接问 api，要自己清。
                repository?.logout()
                false
            } else {
                // 传输失败：可能是服务端没起 / 断网，token 未必有问题。
                true
            }
        }
    }

    /** 退出登录（诊断页用）：先让服务端撤销会话，再清本地凭据。 */
    suspend fun logout() {
        repository?.logout()
        phase = AccountPhase.NeedsActivation()
        Log.info("[account] 已退出登录")
    }

    /** 给信令握手用：**同步**读，只读内存里的会话。 */
    fun tokenOrNull(): String? = repository?.tokenOrNull()

    /**
     * 房间身份。激活态 = 凭据里的 `deviceId`；未激活 = null（调用方回退到
     * `identity.txt`，那是 `AUTH_MODE=off` 的本地开发路径）。
     */
    fun userIdOrNull(): UserId? {
        val deviceId = (phase as? AccountPhase.Ready)?.deviceId ?: return null
        return UserId(deviceId)
    }

    fun displayNameOrNull(): String? = (phase as? AccountPhase.Ready)?.displayName

    /** 日志里只打短前缀：完整的 deviceId 与 accountId 属于可识别信息，没必要落盘。 */
    private fun short(value: String): String =
        if (value.length <= 8) value else value.take(8) + "…"
}

/**
 * 账号错误码 → 中文文案。
 *
 * 与 `SignalErrors` 那套是**并列的两套**：那个管房间/信令，这个管账号/激活。
 * 码值逐字来自服务端（`server/src/accounts.mjs` 的 `ACCOUNT_ERRORS`），不在这里造新码。
 */
object AccountMessages {

    /** 激活页的错误文案。 */
    fun activation(error: AccountApiException): String = when (error.code) {
        AccountErrorCodes.CODE_INVALID -> "入场码格式不对：8 位字母数字（不含 0/O/1/I/L），分隔符可以不填"
        AccountErrorCodes.CODE_EXPIRED -> "入场码已过期，请让对方重新发一个"
        AccountErrorCodes.CODE_USED_UP -> "入场码的使用次数已用完"
        AccountErrorCodes.CODE_REVOKED -> "入场码已被撤销"
        AccountErrorCodes.DEVICE_LIMIT -> "这个账号的设备数已满，先在别的设备上移除一台"
        AccountErrorCodes.TOO_MANY_REQUESTS -> "尝试太频繁了，等一分钟再试"
        AccountErrorCodes.BAD_PAYLOAD -> error.message
        AccountErrorCodes.AUTH_REQUIRED -> "需要先激活"
        AccountErrorCodes.AUTH_INVALID -> "凭据已失效，请重新激活"
        AccountErrorCodes.DEVICE_REVOKED -> "这台设备已被移除，请重新激活"
        AccountErrorCodes.ACCOUNT_SUSPENDED -> "账号已被停用，请联系管理员"
        AccountApiException.NETWORK -> "连不上服务器：检查服务器地址与网络"
        AccountApiException.TIMEOUT -> "服务器响应超时，稍后再试"
        else -> error.message
    }

    /** 服务端码 → 激活页的「为什么回到这里」。 */
    fun reasonOf(code: String): ActivationReason = when (code) {
        AccountErrorCodes.DEVICE_REVOKED -> ActivationReason.DEVICE_REVOKED
        AccountErrorCodes.ACCOUNT_SUSPENDED -> ActivationReason.ACCOUNT_SUSPENDED
        AccountErrorCodes.AUTH_INVALID, AccountErrorCodes.AUTH_REQUIRED -> ActivationReason.SESSION_INVALID
        else -> ActivationReason.SESSION_INVALID
    }

    /** 激活页顶部的常驻说明（按 reason）。 */
    fun notice(reason: ActivationReason): String? = when (reason) {
        ActivationReason.NO_CREDENTIAL -> null
        ActivationReason.SESSION_INVALID -> "上次的登录已失效，请重新激活。"
        ActivationReason.DEVICE_REVOKED -> "这台设备已被从账号里移除，请重新激活。"
        ActivationReason.ACCOUNT_SUSPENDED -> "账号已被停用，请联系管理员。"
    }

}
