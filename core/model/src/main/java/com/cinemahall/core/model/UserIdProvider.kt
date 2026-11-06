package com.cinemahall.core.model

/**
 * 接缝 4：当前用户的标识从哪来（《技术方案评审》§4.2）。
 *
 * 底座只依赖这个接口，不关心背后是设备 UUID 还是账号 ID。
 *
 * Phase 1 起本文件是**纯 JVM**：不再 import android.content.*。
 * Android 的 `DeviceIdProvider` 在 `core:model-android`；账号体系上线后由
 * `core:account` 的 `AccountUserIdProvider` 实现同一接口。
 */
interface UserIdProvider {
    suspend fun current(): UserId
}
