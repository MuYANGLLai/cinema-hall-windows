package com.cinemahall.core.network

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 退避重连的延时序列（《技术方案评审》§6.8：`1s→2s→4s→8s→上限 30s`）。
 *
 * **抖动只向下**：结果落在 `[base×(1-jitter), base]`，`base` 本身已被 `maxMs` 截断，
 * 所以「上限 30s」是硬上限，不是期望值。做成向下抖动是因为两个客户端 + 一个刚
 * `systemd` 重启完的服务端是最典型的场景：都同时重连、同时打过来没有好处。
 * 反向的（向上抖动）会让上限不再成立，验收口径就糊了。
 *
 * [random] 可注入，测试里给固定 seed 或 `jitterRatio = 0.0` 就能钉死序列。
 */
class ReconnectBackoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val jitterRatio: Double = 0.25,
    private val random: Random = Random.Default,
) {

    private var attempt = 0

    /** 已经取过几次延时（从 1 开始计）。用于 UI 显示「第 N 次重连」。 */
    val attemptCount: Int get() = attempt

    /** 取下一次的等待毫秒数，并推进一次尝试计数。 */
    fun nextDelayMs(): Long {
        // 2^attempt 在 attempt 很大时会溢出，先截断到 maxMs 再算，幂次也夹一下
        val factor = 2.0.pow(min(attempt, 40))
        val capped = min(baseMs.toDouble() * factor, maxMs.toDouble())
        attempt += 1

        val high = capped.toLong().coerceAtLeast(1L)
        val low = (capped * (1.0 - jitterRatio)).toLong().coerceIn(1L, high)
        return if (low >= high) high else random.nextLong(low, high + 1L)
    }

    /** 连上了就归零 —— 否则一次偶发断线会让之后每次重连都等 30 秒。 */
    fun reset() {
        attempt = 0
    }
}
