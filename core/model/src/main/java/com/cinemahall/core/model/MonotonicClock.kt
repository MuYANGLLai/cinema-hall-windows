package com.cinemahall.core.model

/**
 * 单调时钟（契约 §8.1）。
 *
 * 同步算法只认单调时间差：信封的 `sentAtMs`、心跳的 `senderClockMs`、
 * 漂移校准的 now，都必须来自同一条单调时间轴。
 *
 * 平台实现：
 *  - Android：`SystemClock.elapsedRealtime()`
 *  - 桌面：`System.nanoTime() / 1_000_000`
 *
 * **不要**用墙钟（`System.currentTimeMillis()`）代替它；TURN 凭证过期那类
 * 需要服务端墙钟的地方才用墙钟。
 */
interface MonotonicClock {
    fun nowMs(): Long
}
