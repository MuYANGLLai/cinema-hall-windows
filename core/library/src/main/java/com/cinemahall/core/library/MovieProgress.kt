package com.cinemahall.core.library

import kotlinx.serialization.Serializable

/**
 * 一部影片的观看进度（§16.3）。
 *
 * 三个字段缺一不可：位置与时长一起存才聊得出比例与「还剩多久」，
 * 而时长**只有播放器知道** —— 影片管理页不加载影片，不在这里存下来，
 * 那个页面就只能显示一个孤零零的秒数（§16.10）。
 *
 * [updatedAtMs] 是墙上时钟毫秒（不是单调时钟：跨重启要读），给「上次看到」一个时间戳，
 * 也是将来「以哪一台为准」的取舍依据（后写的赢）。
 */
@Serializable
data class MovieProgress(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
) {

    /**
     * 观看比例 0f..1f。
     *
     * [durationMs] 小于等于 0（时长未知 / 元数据没解出来）时返回 0f：**不能拿 0 当分母** ——
     * 那会得到 NaN 或 Infinity，落进界面就是一条画不出来的进度条，而真实语义只是「不知道」。
     *
     * 上界夹到 1f：位置理论上不会超过时长，但从播放器读到的位置偶尔会略微超出
     * （关键帧 / 时间基误差），不夹的话界面会画出超过 100% 的进度。
     */
    val fraction: Float
        get() = if (durationMs <= 0L) {
            0f
        } else {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * 全部进度记录，按**进度键**索引（§16.6）。
 *
 * 用 `Map` 而不是 `List`：[com.cinemahall.core.library.MovieLibraryStore.rememberProgress]
 * 是播放中每 15 秒一次的热路径（§16.10），「同一部片直接覆盖」必须是一次查表 ——
 * 否则一部片看两小时就会在盘上攒出几百条重复记录。
 *
 * 键不是 URI 而是摘要算出来的字符串，理由见 [MovieProgressKey] 的注释。
 * [byKey] 带默认值 `emptyMap()`：与库里其它结构一样，缺这个键时要能解出「空进度」
 * 而不是解析失败（§16.4）。
 */
@Serializable
data class MovieProgressBook(
    val byKey: Map<String, MovieProgress> = emptyMap(),
)
