package com.cinemahall.desktop.media

import uk.co.caprica.vlcj.factory.MediaPlayerFactory

/**
 * libVLC 的进程级入口：所有播放器/解析器共用一个 `MediaPlayerFactory`。
 *
 * ── 为什么必须共用 ──────────────────────────────────────────────────────────
 * 每 `new MediaPlayerFactory()` 都会起一套 libVLC 线程池与插件扫描（3.0.21 在本机
 * 约 120–200 ms）。扫描 500 部片时如果每部建一个 factory，光启动开销就是分钟级 ——
 * W2 的「500 部可接受」直接不成立。共享一个 instance 后，单片的 `Media.parse`
 * 只剩 demux 的开销。
 *
 * ── 那三条选项为什么是固定的（任务书 W1 点名）────────────────────────────────
 *  - `--no-video-title-show`：libVLC 默认会在画面左上角烧一行文件名，而我们的画面区
 *    上会放自己的控制层；那行字会跟着“已播/未播”忽隐忽现。
 *  - `--no-sub-autodetect-file`：**必须关**。libVLC 会自动加载同名 `.srt`，而本项目的
 *    字幕是「房主主控 + `cinema.subtitle` 广播」的语义 —— 自动探测会让每台机器看到的
 *    字幕不一样，§7 的“同一部片同一时刻同一句字幕”当场失效。
 *  - `--audio-time-stretch`：变速时保持音高（§6.3 要求 pitch 固定 1.0）。关掉它 1.5×
 *    会变成“花栗鼠”。
 */
object VlcEngine {

    val defaultOptions: List<String> = listOf(
        "--no-video-title-show",
        "--no-sub-autodetect-file",
        "--audio-time-stretch",
    )

    fun newFactory(extra: List<String> = emptyList()): MediaPlayerFactory =
        MediaPlayerFactory(defaultOptions + extra)
}
