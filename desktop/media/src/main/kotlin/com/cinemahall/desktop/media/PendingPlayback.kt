package com.cinemahall.desktop.media

/**
 * 「起播前设置的值」暂存区（W1 的已知坑，Phase 0 §2.1 实测）。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────────
 * libVLC 在 `MediaPlayer` 还没进入 `Playing` / `LengthChanged` 之前：
 *  - `setTime(ms)` 直接返回 false（没有可 seek 的输入）；
 *  - `setRate(rate)` 返回 false（输入未打开，速率无处可施加）；
 *  - `setSpu(id)` 也常常无效果（轨道表还没解析出来）。
 *
 * 而同步引擎（§6）的动作顺序恰恰是「先按目标位置摆好 → 再起播」：`setMediaItem`
 * 之后立刻 `seekTo(resumeMs)`、`setSpeed(rate)`。如果把这几次调用原样转给 VLC，
 * 它们会**静默失败**，症状是「从 0 开始播」「倍速没生效」，而且日志里什么都没有。
 *
 * 所以这一层把失败的请求**留在待办里**，等 `mediaPlayerReady` / `playing` /
 * `timeChanged` 这些事件到来时重放，直到施加成功为止。
 *
 * ── 为什么单独成类 ──────────────────────────────────────────────────────────
 * 它是这个模块里唯一能脱离 libVLC 单测的部分：真值就是「哪些值还在待办、哪些已经被
 * 清掉」。把这段逻辑埋在 `VlcPlaybackTarget` 里就只能靠真机手点，而 Phase 0 已经
 * 证明这类“没反应”很难在屏幕上分辨。
 */
class PendingPlayback {

    private var seekMs: Long? = null
    private var rate: Float? = null
    private var subtitleIndex: Int? = null

    val pendingSeekMs: Long? get() = seekMs
    val pendingRate: Float? get() = rate
    val pendingSubtitle: Int? get() = subtitleIndex

    /** 后一次请求覆盖前一次：用户连点两次进度条时，只有最后一个位置有意义。 */
    fun requestSeek(positionMs: Long) {
        seekMs = positionMs.coerceAtLeast(0L)
    }

    fun requestRate(speed: Float) {
        rate = speed
    }

    fun requestSubtitle(index: Int) {
        subtitleIndex = index
    }

    fun clearSeek() {
        seekMs = null
    }

    fun clearRate() {
        rate = null
    }

    fun clearSubtitle() {
        subtitleIndex = null
    }

    /**
     * 尝试把所有待办施加一遍，**只有施加成功的才被清掉**。
     *
     * 三个 applier 的返回值就是播放器自己的回答（vlcj 的 `setRate` / `setTime` /
     * `setTrack` 都返回 boolean）；返回 false 的留在待办，下一次事件再试。
     *
     * 顺序刻意是 速率 → 位置 → 字幕：速率会影响随后位置换算的时间基，先定速率再跳位置
     * 才不会“跳过去之后又被旧速率的推算拉回来”；字幕与位置无关，放最后。
     *
     * @return 本次实际清掉的待办条数（测试与日志都用它）
     */
    fun apply(
        applyRate: (Float) -> Boolean = { true },
        applySeek: (Long) -> Boolean = { true },
        applySubtitle: (Int) -> Boolean = { true },
    ): Int {
        var cleared = 0
        rate?.let { value ->
            if (applyRate(value)) {
                rate = null
                cleared++
            }
        }
        seekMs?.let { value ->
            if (applySeek(value)) {
                seekMs = null
                cleared++
            }
        }
        subtitleIndex?.let { value ->
            if (applySubtitle(value)) {
                subtitleIndex = null
                cleared++
            }
        }
        return cleared
    }
}
