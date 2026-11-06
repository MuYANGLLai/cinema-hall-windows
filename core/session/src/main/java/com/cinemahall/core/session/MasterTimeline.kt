package com.cinemahall.core.session

/**
 * 虚拟主时间轴（《技术方案评审》§6.2）—— 播放同步里**唯一**的权威位置来源。
 *
 * ── 为什么是「外推的本地轴」而不是「追踪房主的实时位置」──────────────────────
 * 房主每 2500ms 才广播一次心跳。若从端把「上一次收到的 positionMs」直接当目标，
 * 位置就成了一条每 2.5 秒跳一步的阶梯：画面在两次心跳之间会静止不动，然后
 * 猛跳一下。多次心跳之间还会各带不同的传输延迟，阶梯就变成锯齿。
 *
 * 正确的做法是：收到一次权威位置时记下**两个量** —— 「那一刻本机的单调时钟」
 * 与「那一刻影片应该播到的位置」。之后任意时刻的位置都从这两个量**外推**出来，
 * 中间完全不需要房主再说话。房主的下一条心跳到达时只是把锚点**更新**一次，
 * 而不是构造一条新曲线。
 *
 * ── 为什么锚点要 `+ flightMs`（§6.2 的补偿）────────────────────────────────
 * 房主在它发送的那一刻播放到 `P`，消息飞行 `flight` 毫秒之后才到从端，**此时**房主的
 * 实际位置已经推进到 `P + flight`。不做补偿的话，从端会稳定落后这一段飞行时间，
 * 而且这个落后量会随每次重锚抖动（每条消息飞的时间都不一样），于是 §6.3 的漂移判断
 * 永远在一个几十到几百毫秒的偏置上做判断 —— 中继路径下这个偏置足以让漂移常年越过
 * 80ms 的软阈值，甚至越过 300ms 的硬阈值：画面反复变速，每次重锚还硬 seek 一次。
 *
 * `flight` 必须是**这一条消息实测的飞行时间**（[ClockSync.flightMsOf]），
 * **不能**拿「窗口内最小 RTT 的一半」代替：后者是「这一段时间里最走运的那一次」，
 * 与「这一刻」在中继路径上差得很远，差值直接变成上面那个偏置。
 * 真机报的「P2P 直连不卡、切强制中继从端就卡（画面与声音一起断）」正是这一条。
 *
 * ── 外推速率为什么是倍速，而不是恒为 1.0 ────────────────────────────────────
 * 「整场倍速」（`cinema.speed`）改变的是影片时间相对真实时间的流逝速度。外推
 * 仍按 1.0 算的话，2x 播放时目标位置每秒会比真实推进**少**一秒，于是从端算出
 * 一个每秒增长 1000ms 的假漂移，一路吃满硬阈值 —— 每几百毫秒硬 seek 一次。
 * 所以锚点必须同时记住「那一刻的速率」。
 */
class MasterTimeline {

    /** 锚点：本机单调时钟与那一刻的影片位置。只在类内部改，对外只给派生量。 */
    private var anchorLocalClockMs: Long = 0L
    private var anchorMediaMs: Long = 0L

    /**
     * 锚点速率：播放时是 **倍速**（1.0 / 2.0…），暂停时 0.0。§6.2 的 `anchorSpeed`。
     *
     * 它与 [rate] 分开存：`anchorSpeed` 是外推真正用到的斜率（要含「暂停 = 0」），
     * 而 [rate] 是「这场电影现在以几倍速在放」，暂停期间它依然有意义
     * （暂停前是 2x，恢复播放后还该是 2x）。
     */
    private var anchorSpeed: Double = 0.0

    /** 整场倍速。与是否在播无关，见 [anchorSpeed] 的说明。 */
    private var rate: Double = 1.0

    /** 还没锚定过时，[targetPositionMs] 返回 0 —— 界面据此显示「尚未同步」。 */
    var isAnchored: Boolean = false
        private set

    /** 锚点处的影片位置（已含这条消息的飞行时间补偿）。诊断页用它解释目标位置从哪来。 */
    val anchoredMediaMs: Long get() = anchorMediaMs

    /** 锚点处的本地时钟。 */
    val anchoredLocalClockMs: Long get() = anchorLocalClockMs

    /** 锚点上对端是不是在播。 */
    val isRunning: Boolean get() = anchorSpeed > 0.0

    /** 当前记录的整场倍速（暂停期间也保留）。 */
    val playbackSpeed: Double get() = rate

    /**
     * 重设锚点。**每一次**权威位置到达都走这里：play / pause / seek / heartbeat /
     * state_snapshot（§6.4 的表里这五条的动作都可以归结为「重设锚点 + 本机 player 对齐」）。
     *
     * @param nowClockMs 本机当前单调时钟（调用方给，便于单测注入）
     * @param positionMs 对端在**它的发送时刻**播到的位置。本机自己的操作在从端
     *   也会走这里（双向控制之后本机也可能是某个动作的权威），那时它是本机位置。
     * @param flightMs **这条消息实测的飞行时间**（[ClockSync.flightMsOf]）：从对端发出
     *   到本机收到的这一段。本机自己发起的操作传 0 —— 那是本机此刻的位置，没有任何
     *   网络延迟要补偿，补了的话目标位置会平白多出一段，刚按完快进就是一次硬 seek
     * @param isPlaying 对端此刻是播还是停。它决定 [targetPositionMs] 之后是外推还是冻结
     * @param playbackSpeed 整场倍速。默认 1.0 让「还没收到过 `cinema.speed`」的
     *   调用方（以及既有单测）保持旧行为。
     */
    fun anchor(
        nowClockMs: Long,
        positionMs: Long,
        flightMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Double = 1.0,
    ) {
        anchorLocalClockMs = nowClockMs
        anchorMediaMs = positionMs + flightMs
        rate = playbackSpeed
        anchorSpeed = if (isPlaying) playbackSpeed else 0.0
        isAnchored = true
    }

    /**
     * 只改整场倍速，**保持位置连续**。
     *
     * ── 为什么不能「先改倍速、等下一次心跳再重锚」────────────────────────────────
     * 那 2.5 秒里斜率已经变了而锚点还按旧斜率的外推值，位置会凭空出现一个
     * 「（新速率 − 旧速率）× 2.5s」的台阶：1x → 2x 是 2.5 秒，直接落在硬阈值
     * 之外 → 立刻一次硬 seek。所以这里把锚点重设到「按**旧**斜率外推出来的
     * 当前位置」，再换斜率 —— 位置连续，只有斜率变。
     *
     * 未锚定时什么都不做：还没有曲线可改，等第一条权威位置到达时自然带上新倍速。
     */
    fun setPlaybackSpeed(nowClockMs: Long, playbackSpeed: Double) {
        rate = playbackSpeed
        if (!isAnchored) return
        val wasRunning = isRunning
        anchorMediaMs = targetPositionMs(nowClockMs)
        anchorLocalClockMs = nowClockMs
        anchorSpeed = if (wasRunning) playbackSpeed else 0.0
    }

    /**
     * 任意时刻的目标位置（§6.2 的 `targetPositionMs`）。
     *
     * 暂停时 `anchorSpeed = 0`，于是它退化成「返回锚点位置」—— 这正是需要的：
     * 暂停期间两端都冻结在同一个数字上，不需要任何比较。
     *
     * `coerceAtLeast(0)`：影片开头附近，`positionMs + flightMs` 可能算出
     * 一个很小的负数（房主刚起播、位置 0，减去一点点）。负数位置对
     * `seekTo` 是非法输入，且 §6.3 的漂移判据会把它算成一个巨大的漂移。
     */
    fun targetPositionMs(nowClockMs: Long): Long {
        if (!isAnchored) return 0L
        val elapsed = nowClockMs - anchorLocalClockMs
        val target = anchorMediaMs + (elapsed * anchorSpeed).toLong()
        return if (target < 0L) 0L else target
    }

    /** 换链路 / 换对端时清空。 */
    fun reset() {
        isAnchored = false
        anchorLocalClockMs = 0L
        anchorMediaMs = 0L
        anchorSpeed = 0.0
        rate = 1.0
    }
}
