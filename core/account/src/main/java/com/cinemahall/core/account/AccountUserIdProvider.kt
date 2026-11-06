package com.cinemahall.core.account

import com.cinemahall.core.model.UserId
import com.cinemahall.core.model.UserIdProvider

/**
 * 账号体系下的本机身份（契约 §8.5）：返回**设备 id**。
 *
 * ── 为什么是 deviceId 而不是 accountId ──────────────────────────────────────
 * 信令的 `from` 必须等于 token 绑定的 `device_id`（方案 §6.2：`from` 与 token 不符
 * 直接 4401 关闭）。同一个账号可以在多台设备上同时在线，用 accountId 会让两条连接
 * 长得一模一样，服务端无法区分谁是谁，客户端也无法判断「这条 offer 是不是给我的」。
 *
 * ── 未激活时为什么抛异常 ────────────────────────────────────────────────────
 * 未激活不连信令（启动流程保证），所以这个方法在正常路径上不会被调到。
 * 真被调到时说明装配错了 —— 抛一个带原因的异常比返回一个假 id 更容易定位：
 * 假 id 会让服务端回 `AUTH_INVALID` 并关连接，看起来像「账号坏了」。
 */
class AccountUserIdProvider(
    private val repository: AccountRepository,
) : UserIdProvider {

    override suspend fun current(): UserId {
        val credential = repository.currentCredential()
            ?: throw IllegalStateException("尚未激活，没有可用于信令的设备身份")
        return UserId(credential.deviceId)
    }
}
