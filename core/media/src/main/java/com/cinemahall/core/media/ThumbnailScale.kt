package com.cinemahall.core.media

/**
 * `MovieThumbnailer` 里那两个「算错了不会报错、只会让封面变糊或变黑」的纯函数。
 *
 * Phase 1 从 Android 的 `MovieThumbnailer` 抽到 `core:media`，于是它们能在 JVM 上
 * 逐条钉住；Android 的取帧实现仍在 `core:media-android`。
 */

/**
 * 抽稀倍数（`BitmapFactory.Options.inSampleSize` 只认 2 的幂）。
 *
 * 取的是**最大的**那个 2 的幂，使得宽高各除以它之后仍然不小于 [target] ——
 * 也就是「短边不小于 target」这个条件本身。再大一档就会把图缩到目标尺寸以下，
 * 之后由 Compose 放大回去，白丢清晰度。
 *
 * 尺寸未知（0 或负）时给 1：宁可多占一点内存，也不要因为一次元数据缺失把图缩成 1×1。
 */
fun sampleSize(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0 || target <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
    return sample
}

/**
 * 取帧的位置：片头约 10% 处（见 `MovieThumbnailer` 文件头）。
 *
 * 时长未知时给 0 —— 那时只能取第一帧，总比不取强。
 */
fun framePositionUs(durationMs: Int): Long =
    if (durationMs > 0) durationMs.toLong() * 1000L / FRAME_POSITION_DIVISOR else 0L

/**
 * 取帧的目标框：**短边正好 [sizePx]**，长边按片源真实比例。
 *
 * ── 为什么要把旋转算进来 ────────────────────────────────────────────────────
 * 竖着拍的片子（`rotation = 90 / 270`）在 `METADATA_KEY_VIDEO_WIDTH/HEIGHT` 里报的是
 * **旋转前**的宽高，而 `getScaledFrameAtTime` 交出来的是旋转后的画面。不换这一下，
 * 目标框的横竖就与画面相反：`getScaledFrameAtTime` 只保证「装得进这个框」，
 * 于是 9:16 的画面被塞进 16:9 的框，短边只拿到目标的 1/3 像素，方卡裁出来是糊的。
 *
 * 宽高有一个不是正数（元数据缺失）时退回正方形框：`getScaledFrameAtTime` 会自己
 * 按比例装进去，结果至少是能看的。
 */
fun frameBox(videoWidth: Int, videoHeight: Int, rotationDegrees: Int, sizePx: Int): Pair<Int, Int> {
    val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
    val width = if (quarterTurn) videoHeight else videoWidth
    val height = if (quarterTurn) videoWidth else videoHeight
    if (width <= 0 || height <= 0) return sizePx to sizePx
    return if (width >= height) {
        // 宽片：裁切裁的是左右，所以定高、按比例放宽。
        (sizePx.toLong() * width / height).toInt().coerceAtLeast(sizePx) to sizePx
    } else {
        sizePx to (sizePx.toLong() * height / width).toInt().coerceAtLeast(sizePx)
    }
}

/** 取帧位置的分母：片长的 1/10。 */
private const val FRAME_POSITION_DIVISOR = 10L
