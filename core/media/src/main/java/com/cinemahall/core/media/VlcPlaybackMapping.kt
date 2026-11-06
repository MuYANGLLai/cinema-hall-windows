package com.cinemahall.core.media

import kotlin.math.roundToInt

/**
 * [VlcPlaybackTarget] 里那几件「能脱离 native 讲清楚」的换算（2026-10-26 换内核）。
 *
 * ── 为什么要单独抽出来 ──────────────────────────────────────────────────────
 * libVLC 的实现本体碰的是 native 播放器，JVM 单测跑不起来 —— 这一点没法改变。但
 * 换内核真正容易写错的**不是**调哪个方法，而是这几个**标度与配对**：
 *
 *  - 音量：端口是 `0f..1f`，libVLC 是 `0..100`（整数，而且是它可以超过 100 的那个量纲）；
 *  - 宽高比：libVLC 给的是「解码尺寸 + 像素宽高比（sarNum/sarDen）」，直接用
 *    `width / height` 在非方形像素的片源上会算错；
 *  - 字幕轨：libVLC 给的是 `(id, name)` 列表，而端口要的是「按顺序的语言码」——
 *    语言码在另一处（`IMedia.Track.language`），只能**按 id 配对**，不能按顺序假设。
 *
 * 这三件事都能用纯函数表达，于是把它们放在这里逐条钉住；[VlcPlaybackTarget] 只剩下
 * 「什么时候调、调完读哪个字段」。**本文件刻意不引用任何 libvlc 类型** —— 一旦引用，
 * 这些单测就得起 native 库，等于没测。
 */
object VlcPlaybackMapping {

    /** 端口音量的上限（`1f` = 原音量）。与 [PlaybackTarget.DEFAULT_VOLUME] 同一个取值。 */
    private const val PORT_VOLUME_MAX = 1f

    /** libVLC 的音量上限：`100` = 原音量（更大是放大，本项目不用）。 */
    const val VLC_VOLUME_MAX = 100

    /**
     * `0f..1f` → libVLC 的 `0..100`。
     *
     * 三条边界都是实测会碰到的：
     *  - **越界要夹**：界面滑块的临时值、以及「整场倍速 × 漂移乘子」那条路径都可能
     *    算出 `> 1` 的中间值；libVLC 对超范围值不报错，只会静默按边界处理，于是诊断页
     *    会显示一个与真实生效值不同的数字。
     *  - **`NaN` 给满音量**：与 [PlaybackTarget.volume] 默认 `1f` 同一个取向 ——
     *    「算不出来」不能变成「静音」，那会让人以为音频坏了。
     */
    fun volumeToVlc(volume: Float): Int {
        if (volume.isNaN()) return VLC_VOLUME_MAX
        val clamped = volume.coerceIn(0f, PORT_VOLUME_MAX)
        return (clamped * VLC_VOLUME_MAX).roundToInt()
    }

    /**
     * libVLC 的 `0..100` → 端口音量。
     *
     * `getVolume()` 在音频输出还没建起来时返回 `-1`，所以这里也夹一次：负数按 0 算的
     * 话是「静音」，而那是错的答案 —— 端口那一侧会把它显示成「音量 0」。返回
     * [PlaybackTarget.DEFAULT_VOLUME] 更诚实：还没建输出 = 还没有音量这回事。
     */
    fun volumeFromVlc(volume: Int): Float {
        if (volume < 0) return PlaybackTarget.DEFAULT_VOLUME
        return volume.coerceAtMost(VLC_VOLUME_MAX) / VLC_VOLUME_MAX.toFloat()
    }

    /**
     * 显示宽高比（宽/高），算不出来给 `null`（界面用 16:9 兜底）。
     *
     * @param width 解码宽度
     * @param height 解码高度
     * @param sarNum 像素宽高比的分子（libVLC 用 0 表示「未知」）
     * @param sarDen 像素宽高比的分母
     *
     * 为什么不能只算 `width / height`：DVD 转录、老港片、部分动画压制都是非方形像素
     * （典型 720×480 存 16:9 画面，sarNum/sarDen = 32/27）。只按解码尺寸算会把 16:9
     * 的片子画成 3:2，全屏时两侧被切或上下多出黑边 —— 而「画面比例不对」正是这一节
     * 要保住的观感。
     */
    fun aspectRatio(width: Int, height: Int, sarNum: Int, sarDen: Int): Float? {
        if (width <= 0 || height <= 0) return null
        val ratio = if (sarNum > 0 && sarDen > 0) {
            width.toFloat() * sarNum / (height.toFloat() * sarDen)
        } else {
            width.toFloat() / height
        }
        return ratio.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * 字幕轨的语言码列表（顺序 = 端口里的「字幕序号」）。
     *
     * @param spuIds libVLC `getSpuTracks()` 里的 id 序列，**已经过滤掉 `-1`（Disable）**
     * @param languageById `id → 语言码` 的表（来自 `IMedia.Track.language`）
     *
     * ── 为什么按 id 配对而不是按顺序 ────────────────────────────────────────────
     * 两个列表来自 libVLC 的两个不同 API（`getSpuTracks()` 与 `media.getTrack()`），
     * 文档没有承诺同序。按顺序 zip 在「容器里字幕轨与音频轨交错」的片源上会把语言码
     * 贴到错误的按钮上 —— 而按钮上的字是验收口径（`字幕 2（eng）`）。配不上时给空串，
     * 界面会退化成「字幕 2」，这是诚实的缺失，不是猜。
     */
    fun subtitleLanguages(spuIds: List<Int>, languageById: Map<Int, String?>): List<String> =
        spuIds.map { id -> languageById[id]?.trim().orEmpty() }

    /**
     * 端口给的「字幕序号」→ libVLC 的轨道 id。越界给 `null`（安静忽略，不发一条错消息）。
     *
     * 这一步存在的原因：端口沿用的是 `0.1.15` 起就定下的「内封文字轨组序号」，而
     * libVLC 的 `setSpuTrack()` 要的是 **ES id**。两个数在大多数片源上不相等
     * （字幕轨前面若有一条音频轨，id 就从 1 起），直接透传会切到别的轨或切不动。
     */
    fun spuIdAt(spuIds: List<Int>, index: Int): Int? = spuIds.getOrNull(index)
}
