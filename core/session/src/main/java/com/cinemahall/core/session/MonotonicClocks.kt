package com.cinemahall.core.session

import com.cinemahall.core.model.MonotonicClock

/**
 * 纯 JVM 的兜底单调时钟：`System.nanoTime() / 1_000_000`。
 *
 * ── 它只是默认值，不是生产实现 ──────────────────────────────────────────────
 * Android 上的生产装配**必须**显式注入 `SystemClock.elapsedRealtime()`
 * （见 `core:model-android` 的 `AndroidMonotonicClock`），因为 Phase 1 之前
 * `PeerCoordinator.send` 与 `CinemaHandler.attachPlayback` 用的就是它 ——
 * 行为零变化要求换实现时不动时基。
 *
 * ── 为什么默认值可以放心用 ──────────────────────────────────────────────────
 * 同步算法只认**差值**，不认绝对原点（见 `MonotonicClock` 的 KDoc）。而本模块里
 * 需要时钟的两处（信封的 `sentAtMs` 与引擎的 `clock`）都由装配点注入**同一个**
 * 实例，所以即使有人漏传，全链仍然落在同一条时间轴上，量出来的 RTT 依旧自洽。
 * 唯一的差别是数值与 Android 的 elapsedRealtime 不同 —— 而那不参与任何判据。
 *
 * `System.nanoTime()` 在 Android 上也单调，但**不含休眠时间**；elapsedRealtime
 * 含。本项目没有跨休眠的同步需求（休眠时链路本来就断了），所以两者都可用。
 */
object DefaultMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
}
