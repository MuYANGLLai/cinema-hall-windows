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
    /**
     * 两人房自动同权（2026-11-07 用户口径）。
     *
     * > 「房间只有两个人的时候，从端自动获得跟房主一样的控制权限，2 个人以上则收回权限」
     *
     * ── 为什么这是一个**本地推导**字段，而不是又一条协议 ─────────────────────────
     * 它的判据只有「房间里坐了几个人」，而那个数字每个客户端从 `Room.members` 都读得到，
     * 也必然读到同一个值。为此加一条广播消息，只会多出一条「房主那一份和收到的
     * 那一份打架」的同步问题 —— 而它本来没有同步问题。
     *
     * 因此它**不上线**（见 [toWire]）：`cinema.control_policy` 载荷里没有这个键。
     * [CinemaHandler] 每次应用策略时都按当前房间重算一遍，所以「第三个人进房」
     * 那一刻所有客户端的这一位同时变 false —— 权限当场收回，不需要任何人按按钮。
     *
     * ⚠️ 它只影响「允不允许控制」，不绕过 [ControlPolicyTable.canControl] 里的
     * `watching` 判据：本机还没选到同一部片的人，按钮仍然是灰的（那时按下去本来就
     * 只是一次静默空操作）。
     */
    val pairAutoAllow: Boolean = false,
) {

    /** 这个人按策略能不能控制（两人房自动同权 → `overrides` → [defaultAllow]）。 */
    fun allowedFor(userId: UserId): Boolean =
        pairAutoAllow || (overrides[userId] ?: defaultAllow)

    /**
     * 线上形态（`overrides` 的键是 userId 字符串）。
     *
     * [pairAutoAllow] **刻意不带**：它是本地推导出来的，见那个字段的 KDoc。
     */
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
