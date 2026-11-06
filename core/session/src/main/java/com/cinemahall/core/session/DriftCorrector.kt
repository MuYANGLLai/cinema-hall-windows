package com.cinemahall.core.session

import kotlin.math.abs

/**
 * 一次漂移判断的处置（§6.3 的三档）。
 *
 * 做成 sealed interface 而不是「返回一个 Float 速度」：三档里只有一档是变速，
 * 另两档（不动 / seek）用「速度 1.0」也能表达，但那样调用方就没法在日志里
 * 区分「判断为不用动」和「判断为要 seek 但因为别的原因跳过了」。而 §6.8 明说
 * 这些阈值要在真机上重估 —— 重估的依据正是「这一刻到底走了哪一档」。
 */
sealed interface DriftAction {

    /** 第 1 档：`|drift| < softDriftMs`，什么都不做。 */
    data object None : DriftAction

    /**
     * 第 2 档：变速追赶，**不变调**。
     *
     * @param speedFactor 叠加在**整场倍速**之上的乘子（`1.0 ∓ k`）。**不是**直接
     *   交给播放器的绝对速度 —— 播放器最终收到的是「整场倍速 × 这个乘子」。
     *   做成相对量是倍速播放的硬要求：`maxAdjustSpeed = 0.05` 描述的是「快 5%」，
     *   而在 2x 下「快 5%」是 2.1x 而不是 1.05x。若这里给绝对值，2x 播放时
     *   追赶幅度会缩到原来的 1/40，一条 200ms 的漂移要追几分钟。
     * @param gain 实际用的 k，仅用于诊断展示（决定「还有多久追平」）
     */
    data class AdjustSpeed(val speedFactor: Float, val gain: Double) : DriftAction

    /** 第 3 档：`|drift| >= hardDriftMs`，直接跳。 */
    data class SeekTo(val positionMs: Long) : DriftAction
}

/**
 * 漂移三档校准（《技术方案评审》§6.3）。
 *
 * ── 为什么阈值是这三档，而不是「越准越好」───────────────────────────────────
 * 人耳对**音高变化**比对**几十毫秒的错位**敏感得多。所以：
 *  - 80ms 以内：两端几乎听不出差别，动它只会引入不必要的音质损失 → 不动；
 *  - 80~300ms：用 ±5% 变速悄悄追平（300ms 用 5% 追，约 6 秒到位）；
 *  - ≥300ms：变速要追十几秒以上，期间音高一直是偏的 → 不如直接 seek。
 *
 * `pitch` 固定 1.0（§6.3 的细节）：Media3 走 Sonic 做**时间伸缩**，变速不变调。
 * 若顺手改了 pitch，就变成「用变调掩盖错位」——那是听得出、且很廉价的做法。
 *
 * 本类只给**相对乘子**，最终速度由调用方与整场倍速相乘（见 [DriftAction.AdjustSpeed]）。
 * 这样「±5%」这个物理含义在 1x 与 2x 下都成立。
 *
 * ── `k` 实际上几乎恒等于 `maxAdjustSpeed`，这不是 bug ─────────────────────
 * §6.3 给的是 `k = clamp(|drift| / 2000.0, 0.02, 0.05)`。代入本档的区间
 * （80~300ms）得 0.04~0.15，恒被上限夹到 0.05。也就是说公式里的 `/2000`
 * 只在「软阈值被调得很小」或「上限被调得很大」时才有作用 —— 它是为真机重估
 * 参数（§6.8）留的形状，照实实现，不擅自简化。
 *
 * ── 为什么这里**不加**迟滞（2026-10-12 试过又撤了）─────────────────────────
 * 「进档 80 / 出档 40」的施密特触发器看着能治「速度在 1.0 与 1.0∓4% 之间反复跳」，
 * 实测方向是反的：出档门槛一降，修正就跑得更久，残留漂移从「贴着 80」被拉到 40，
 * 于是**修正次数变多**而不是变少 —— 而每次修正都是一次速率变化。
 * 真正让速度不再反复跳的是**抖动不再进入目标位置**：见 [ClockSync.slewOffset] 的
 * 限速跟随，它把单次重锚注入的漂移压到 40ms 以内，漂移于是根本到不了 80。
 * 阈值这一层保持「进档即出档」的原样（§6.3 的原文），不做没有依据的改动。
 */
class DriftCorrector(private val config: CinemaSyncConfig = CinemaSyncConfig.DEFAULT) {

    fun decide(driftMs: Long, isPlaying: Boolean, targetPositionMs: Long): DriftAction {
        val magnitude = abs(driftMs)

        if (magnitude < config.softDriftMs) return DriftAction.None

        // 暂停时不变速（§6.3 的细节）：速度乘 0 位置的导数恒为 0，变速在暂停下
        // 数学上就没有意义；播放器也不会播得更快。此时只能 seek。
        if (!isPlaying) return DriftAction.SeekTo(targetPositionMs)

        if (magnitude >= config.hardDriftMs) return DriftAction.SeekTo(targetPositionMs)

        val gain = (magnitude / FULL_CORRECTION_MS).coerceIn(MIN_GAIN, config.maxAdjustSpeed)

        // driftMs = 本机位置 − 目标位置。为正说明**本机跑在前面**，要放慢；
        // 为负说明落后，要加快。方向写反的症状很隐蔽：漂移不会变小，
        // 而是被推到另一侧、来回震荡，看起来像「阈值调得不对」。
        val signed = if (driftMs > 0) -gain else gain
        return DriftAction.AdjustSpeed(
            speedFactor = (1.0 + signed).toFloat(),
            gain = gain,
        )
    }

    companion object {
        /** §6.3 里 `/2000.0` 的那个分母：全速追平一个漂移所需的时间尺度（毫秒）。 */
        const val FULL_CORRECTION_MS = 2000.0

        /** §6.3 的 `clamp` 下界，防止极小漂移算出小于 2% 的速度（比不追还慢）。 */
        const val MIN_GAIN = 0.02
    }
}
