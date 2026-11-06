package com.cinemahall.core.session

import com.cinemahall.core.model.UserId

/**
 * 控制权（契约 §7，阶段二 P4）。
 *
 * `canControl(user) = user == 房主 || (在看 && (overrides[user] ?: defaultAllow))`
 *
 * ── 为什么 `overrides` 是「三态」而不是布尔 ────────────────────────────────────
 * 契约的真值表里「没有 override」和「override = false」结论一样（在
 * `defaultAllow=true` 时也一样：都变成「这一人不许」），但**语义不同**：
 *  - 没有 override ⇒ 跟随全局开关（房主改全局时这个人跟着变）；
 *  - override = false ⇒ 这个人是被单独踢出去的（全局怎么变他都不许）。
 * 真值表六行恰好靠这个区分成立（`true / false` 与 `true / 无` 都是「否」，但前者
 * 在房主把全局改成 false 之后仍然是「否」，后者会变成「跟随」—— 结论碰巧一样，
 * 可 `defaultAllow` 再改回 true 时两者又分开）。所以 `overrides` 用 `Map` 天然表达
 * 三态：**键不存在 = 无 override**。
 */
data class ControlPolicy(
    val defaultAllow: Boolean = false,
    val overrides: Map<UserId, Boolean> = emptyMap(),
) {

    /** 这个人按策略能不能控制（`overrides` 优先，缺省跟随 [defaultAllow]）。 */
    fun allowedFor(userId: UserId): Boolean = overrides[userId] ?: defaultAllow

    /** 线上形态（`overrides` 的键是 userId 字符串）。 */
    fun toWire(): CinemaControlPolicy = CinemaControlPolicy(
        defaultAllow = defaultAllow,
        overrides = overrides.mapKeys { it.key.value },
    )

    companion object {
        /** 房主独占 —— 契约 §7 的默认档，也是旧包的唯一行为。 */
        val HOST_ONLY = ControlPolicy()

        fun fromWire(wire: CinemaControlPolicy?): ControlPolicy =
            if (wire == null) {
                HOST_ONLY
            } else {
                ControlPolicy(
                    defaultAllow = wire.defaultAllow,
                    overrides = wire.overrides.mapKeys { UserId(it.key) },
                )
            }
    }
}

/**
 * 控制权判定的唯一实现（契约 §7 的真值表）。
 *
 * 抽成**纯函数**而不是散在收包分支里，是因为它有三处调用（房主执法、从端 UI 灰按钮、
 * 单测），而三处各写一遍必然分叉 —— 分叉的症状是「按钮亮着，但按下去没反应」，
 * 或者更糟：「按钮是灰的，可别人按了真的会动」。
 */
object ControlPolicyTable {

    fun canControl(
        policy: ControlPolicy,
        hostUserId: UserId?,
        userId: UserId,
        /** 这个人是不是「匹配且在看」（`cinema.media_status` 的结论）。 */
        watching: Boolean,
    ): Boolean = userId == hostUserId || (watching && policy.allowedFor(userId))
}
