package com.cinemahall.core.account

/**
 * 账号准入模式（契约 §8.5：客户端调试开关，发布为 [REQUIRED]）。
 *
 * - [OFF]：完全跳过账号。走旧行为（`DeviceIdProvider` 生成本机 UUID、直连信令），
 *   用于本机开发、模拟器、以及「服务器还没起」时的界面调试。
 * - [OPTIONAL]：有凭据就用，没有就按 [OFF] 走。**不主动要求激活**。
 * - [REQUIRED]：发布态。没有有效凭据就只能停在激活页。
 *
 * 为什么要有 [OPTIONAL]：它的语义是「联网功能需要账号，但本机功能不需要」——
 * 与方案 §8.2 的产品原则一致。但本轮用户口径是「无 token 去激活页」，
 * 所以默认值是 [REQUIRED]；[OPTIONAL] 留着给后续「先看本地影片」的入口用。
 */
enum class AuthMode {
    OFF,
    OPTIONAL,
    REQUIRED;

    companion object {
        val DEFAULT = REQUIRED

        fun fromStorage(raw: String?): AuthMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
