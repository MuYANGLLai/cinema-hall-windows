package com.cinemahall.core.session

/**
 * 播放同步的全部可调参数（《技术方案评审》§6.8 的可调参数汇总）。
 *
 * ── 为什么集中成一个可替换的对象，而不是散落的 `const val` ──────────────────
 * §6.8 的结论原文是「本表初值保留为**起点**而非定值」：阶段一 1.6 的同步验收
 * **必须在 TURN 中继路径上跑**，而中继多一跳，`heartbeatIntervalMs`、`rttWindow`
 * 与 `softDriftMs` 三个值都要按实测重估（§6.8 的 relay 调参提示）。
 * 做成一个整体替换的对象，真机调参时只改一处；同时单测可以直接构造出
 * 「窗口 2」「hard=soft+1」这类极端参数，不必去改全局常量。
 *
 * ── 为什么 `init` 里要 require ───────────────────────────────────────────────
 * 这些约束一旦被违反，症状是**静默的**：`hardDriftMs <= softDriftMs` 会让
 * 「变速追赶」这一档永远走不到（漂移一到 80ms 就直接 seek，画面每两秒硬跳一次），
 * 而日志里什么都看不出来。参数错误的正确时机是启动时崩，而不是真机上让人猜。
 */
data class CinemaSyncConfig(
    /**
     * 房主广播心跳的间隔（§6.8：任务书要求 2~3s）。同时也是「同步往返」的节拍。
     *
     * ⚠️ **这里的 2500ms 是引擎/单测的基线，不是 App 的实际默认值。** 2026-10-31 起
     * App 层的默认是 **5 秒**（设置 → 播放设置，用户口径），装配时由
     * `PlaybackSettings.toSyncConfig()` 覆盖。两个数字都不是「笔误」：一个是 §6.8 定下的
     * 真机调参起点，一个是用户选的默认观感 —— 改其中一个不该连带改另一个。
     */
    val heartbeatIntervalMs: Long = 2_500,

    /** 从端的本地校准频率（§6.8：比心跳更密，所以漂移能在两次心跳之间被纠住）。 */
    val localTickMs: Long = 500,

    /**
     * 本地校准（§6.3 的三档）总开关。**App 层的默认是关**，引擎层的默认是开。
     *
     * ── 两层默认值为什么不一样 ──────────────────────────────────────────────────
     * 这里（引擎）保留 `true` 是**历史行为与单测基线**：§6.3 的三档是本工程一路调出来的，
     * 换成 false 会让所有既有用例与「没有偏好时引擎怎么跑」一起变味。而用户
     * 2026-10-31 的口径是「设置里那个开关默认关掉」—— 那是 **App 层**的默认，
     * 由 `PlaybackPreference` 落盘，装配时通过 `CinemaSyncConfig.copy` 覆盖到这里。
     * 换句话说：这个字段是**能力开关**，用户的默认值是**偏好**，两者不该混为一谈。
     *
     * ── 关掉之后从端还剩什么 ────────────────────────────────────────────────────
     * 仍然会在每一条房主心跳到达时重设时间轴锚点（那是 §6.4 的语义，不是「校准」），
     * 仍然照常算并显示漂移；只是**不再变速追、不再硬 seek**。于是两次心跳之间的
     * 位置误差会一直累积到下一次心跳 —— 心跳越稀（比如设成 5 秒），这个误差越大。
     * 这是用户可选的取舍，不是缺陷。
     */
    val localCalibrationEnabled: Boolean = true,

    /** 低于这个漂移什么都不做（§6.3 第 1 档）。 */
    val softDriftMs: Long = 80,

    /** 达到这个漂移直接硬 seek（§6.3 第 3 档）。 */
    val hardDriftMs: Long = 300,

    /** 变速追赶的幅度上限（§6.8：±5% 是 Sonic 时间伸缩的音质边界）。 */
    val maxAdjustSpeed: Double = 0.05,

    /**
     * seek 之后的静默期（§6.3）：这段时间内不判漂移。
     *
     * 必要性：`seekTo` 返回后 `currentPosition` 还要过一会儿才落在目标上，
     * 中途读到的中间值会被算成几百毫秒的漂移，于是触发第二次 seek —— 一次
     * 主动 seek 变成连续抽动的画面。`EXACT` seek（§6.6）越精确，这个中间值
     * 的跨度越长，所以这一步不能省。
     */
    val seekSettleMs: Long = 400,

    /**
     * 跳转后等多久再做**落点确认**。
     *
     * ── 它兜的是什么 ────────────────────────────────────────────────────────────
     * 跳转用的是精确 seek（`SeekParameters.EXACT`），但**没有可用索引 / 只有开头
     * 关键帧的片源**上，播放器实际只能落到它能到的地方（实测会退到 0）。那时时间轴
     * 若仍按请求值走，从端算出的漂移会 ≥ `hardDriftMs` ⇒ 每 500ms 硬跳一次。
     *
     * 0.8 秒足够让 `seekTo` 之后的过渡值稳定下来，又短到用户无感；真正的下限是
     * `max(本值, seekSettleMs)`。
     */
    val seekConfirmDelayMs: Long = 800,

    /**
     * 落点偏移超过多少才重新对齐。
     *
     * 判据不是「实际位置 − 请求值」（播放中会被这期间的播放推进污染），而是
     * **「实际位置 − 当前 tick 的时间轴目标」**：两侧都按同一斜率外推，所以差值
     * 恰好就是落点偏移。取 250ms 是为了高于正常抖动（几十毫秒）而不吞掉真实偏移。
     */
    val seekLandingToleranceMs: Long = 250,

    /**
     * 「实际落点比目标早超过这么多」就认为读数不可信，**放弃这次确认**。
     *
     * ── 为什么需要这道上限（2026-10-25 真机事故）────────────────────────────────
     * `seekTo` 之后有一段时间 `currentPosition` 还是 0（缓冲中 / seek 未 apply）。
     * 若把那当成落点，锚点会被重设到 0 并补发给对端 ⇒ **两端一起回到开头**。
     * 实测出问题的那部片索引完好、关键帧间隔**最长 15.02 秒**（1646 个 cue 全对），
     * 所以「退了几十秒」这种读数只可能是没落定。
     *
     * 取 20 秒：比实测最坏 GOP（15.02s）留出余量，又远小于「退到 0」那种量级。
     */
    val maxPlausibleLandingBackMs: Long = 20_000,
    /** min-RTT 滤波窗口（§6.1：保留最近 N 个样本，只采用 RTT 最小的那个）。 */
    val rttWindow: Int = 8,

    /**
     * 两次时钟探测之间的最小间隔。
     *
     * ── 这个值不是「性能优化」，它是**打断正反馈的必需品** ──────────────────
     * 探测的节拍是：从端发 `cinema.sync_ack` → 房主**立刻**回一条 `cinema.heartbeat`
     * → 从端收到后又发一条 `cinema.sync_ack`…
     * 房主的「立刻回」是必须的：若它等自己的心跳定时器（2500ms）才回，
     * 量出来的 RTT 里 99% 是定时器等待，时钟估计就成了噪声。
     * 但「立刻回」配上「收到就探测」就是一个会自持的对轰循环。
     * 用 1000ms 的下限把它掐断：定时心跳（2500ms）每次都触发探测，
     * 而房主那条立刻回复（≈RTT，几十毫秒）不触发。两端各 2.5s 交换三条短消息。
     */
    val syncProbeMinIntervalMs: Long = 1_000,

    /**
     * `cinema.speed` 的最小广播间隔。
     *
     * 倍速选择器是可以连点的（1.5 → 2.0 → 1.5），而每一条都会让从端把锚点按旧斜率
     * 外推一次再换斜率。单次无害，但连点会在两秒内挤出十几条，从端的位置曲线被反复
     * 打折。合并成「只发最终值」即可 —— 本机画面不受影响（播放器是立刻改的）。
     *
     * 它也决定「连点之后对端最晚多久收到」：待发值只在 [CinemaSyncEngine.onTick]
     * 里冲刷，所以实际延迟是 `max(本值, localTickMs)`。
     */
    val speedBroadcastThrottleMs: Long = 400,

    /**
     * `cinema.seek` 的最小广播间隔。
     *
     * ±10s 与进度条都是可以连点的，而每一次广播都让对端做一次 **EXACT** seek
     * （§6.6：大 MKV 上单次数百 ms 到 1s）并进入 400ms 静默期。连点五次若逐条发出去，
     * 对端会在两秒里连续硬跳五次，每次都白等一个静默期 —— 画面像在抽搐。
     * 合并成一条「最终位置」即可，本机画面仍然是立刻跳的。
     */
    val seekBroadcastThrottleMs: Long = 250,
) {
    init {
        require(heartbeatIntervalMs > 0) { "heartbeatIntervalMs 必须为正" }
        require(localTickMs > 0) { "localTickMs 必须为正" }
        require(softDriftMs > 0) { "softDriftMs 必须为正" }
        require(hardDriftMs > softDriftMs) {
            "hardDriftMs($hardDriftMs) 必须大于 softDriftMs($softDriftMs)：" +
                "否则 §6.3 的「变速追赶」一档永远走不到，漂移一到 soft 就直接 seek"
        }
        require(maxAdjustSpeed > 0.0 && maxAdjustSpeed < 1.0) { "maxAdjustSpeed 必须在 (0,1) 内" }
        require(seekSettleMs >= 0) { "seekSettleMs 不能为负" }
        require(rttWindow >= 1) { "rttWindow 至少为 1" }
        require(syncProbeMinIntervalMs > 0) { "syncProbeMinIntervalMs 必须为正" }
        require(speedBroadcastThrottleMs >= 0) { "speedBroadcastThrottleMs 不能为负" }
        require(seekBroadcastThrottleMs >= 0) { "seekBroadcastThrottleMs 不能为负" }
    }

    companion object {
        /** §6.8 的初值。真机（尤其 relay 路径）实测后整体替换，不要逐处改字段。 */
        val DEFAULT = CinemaSyncConfig()
    }
}
