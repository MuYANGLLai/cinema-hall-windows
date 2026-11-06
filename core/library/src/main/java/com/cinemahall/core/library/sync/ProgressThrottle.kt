package com.cinemahall.core.library.sync

/**
 * 什么时刻该把一次播放进度发出去（任务书 W5–W6 的口径）。
 *
 * ── 三条规则，来自用户口径 ────────────────────────────────────────────────────
 * | 触发 | 规则 |
 * |---|---|
 * | 播放中 | **每 5 分钟一次，并且位置变化超过 30 秒**（两个条件都要满足） |
 * | 暂停 | **立即**（这一次是他真正停下来退出去之前的最后一次心跳） |
 * | 退房 / 关掉播放器 | **立即**（收尾） |
 *
 * ── 为什么这两个条件要「与」而不是「或」 ──────────────────────────────────────
 * 只用时间间隔：一部片暂停在同一个画面上 5 分钟也会上报一条一模一样的进度，
 * 服务端账本里多一条 `updated_at` 更新的同值记录，会让另一台设备无谓地刷一次界面。
 *
 * 只用变化量：看一部 4K 片时用户手动拖进度条十几次，每拖一次都发一个请求 ——
 * 服务端限流是 60 次/分钟（`LIBRARY_PER_MINUTE`），拖快一点就会撞上 429，
 * 而那一次 429 恰好会把「真正重要的那一条」挤掉。
 *
 * 两个一起用，语义是「真的往前看了，而且过了足够久」——这才是「进度到达」。
 *
 * ── 为什么暂停/退房不做任何节流 ────────────────────────────────────────────────
 * 那两种触发是**低频且用户可见**的：他按下暂停、他退出影厅，接下来他会换一台设备
 * 继续看。这时候少发一条，另一台设备看到的就是上一次 5 分钟前的进度，
 * 用户会说「我明明看到 40 分，它却从 35 分开始」。
 *
 * 纯函数 + 参数化：调用方传「上次发出去的位置与时刻」，所以能在 JVM 单测里
 * 逐条钉住（不需要真的播 5 分钟）。
 */
object ProgressThrottle {

    /** 播放中的最小间隔。 */
    const val PLAYING_INTERVAL_MS: Long = 5 * 60_000L

    /** 播放中的最小位置变化（严格大于）。 */
    const val MIN_DELTA_MS: Long = 30_000L

    /**
     * 该不该发。
     *
     * [lastSentPositionMs] 为 null 表示**这条片从来没上报过**（本地新片、或刚同步下来
     * 但还没轮到发送）。此时播放中的第一跳不卡「变化量」：没有基准可比，
     * 卡住只会让第一条进度等到 5 分钟之后。
     */
    fun shouldSend(
        trigger: ProgressTrigger,
        positionMs: Long,
        nowMs: Long,
        lastSentPositionMs: Long?,
        lastSentAtMs: Long,
    ): Boolean = when (trigger) {
        ProgressTrigger.PLAYING -> {
            val intervalReached = nowMs - lastSentAtMs >= PLAYING_INTERVAL_MS
            val changedEnough = lastSentPositionMs == null ||
                kotlin.math.abs(positionMs - lastSentPositionMs) > MIN_DELTA_MS
            intervalReached && changedEnough
        }

        // 暂停 / 退房：位置没变过就不用重发同一份值（例如刚发过就退房）。
        ProgressTrigger.PAUSED, ProgressTrigger.LEFT ->
            lastSentPositionMs == null || lastSentPositionMs != positionMs
    }
}

/** 进度上报的触发点。`PLAYING` 是热路径，另两个是用户动作。 */
enum class ProgressTrigger {
    PLAYING,
    PAUSED,
    LEFT,
}
