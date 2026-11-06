package com.cinemahall.desktop.app

import com.cinemahall.core.media.MediaRef
import com.cinemahall.desktop.media.VlcDiscovery
import com.cinemahall.desktop.media.VlcEngine
import com.cinemahall.desktop.media.VlcMediaProbe
import com.cinemahall.desktop.media.VlcPlaybackTarget
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.io.File

/**
 * 播放器的装配点（W1 的消费方）。
 *
 * ── 为什么允许 target 为 null ───────────────────────────────────────────────
 * 找不到 libVLC 时窗口仍要能打开：用户要看到「VLC 没装/路径不对 + 去哪里放」，
 * 而不是双击之后什么都不发生。库扫描同理 —— 没有 VLC 只是拿不到时长，
 * 文件名与目录照样能列出来。
 */
class PlayerController {

    val vlcDir: File? = VlcDiscovery.locate()

    private val factory: MediaPlayerFactory?

    val target: VlcPlaybackTarget?

    val initError: String?

    private val prober: VlcMediaProbe?

    init {
        val dir = vlcDir
        if (dir == null) {
            factory = null
            target = null
            prober = null
            initError = "未找到 libVLC：请把 vlc/ 目录放到程序同级（或设置 CINEMA_VLC_DIR）"
            Log.error(initError)
        } else {
            VlcDiscovery.install(dir)
            val created = runCatching { VlcEngine.newFactory() }
            factory = created.getOrNull()
            target = factory?.let { f ->
                runCatching {
                    // vlc 的事件与挂载诊断统一走 app.log：打包后的 exe 没有控制台，
                    // stdout 里的东西一律丢失（曾因此查不出「第二个实例停在 0:00」）。
                    VlcPlaybackTarget(factory = f, log = { line -> Log.info(line) })
                }.getOrNull()
            }
            prober = factory?.let { f ->
                VlcMediaProbe(
                    factory = f,
                    poolSize = probeParallelism,
                    // 失败要能落到日志里：扫描结束时只有「failed=N」一个数字的话，
                    // 「文件名里带 # 号」「网络盘掉线」「片子真坏」三种情况在日志里
                    // 长得一模一样，用户报「有几部片没时长」时无法定位。
                    onFailure = { path, reason -> Log.warn("时长探测失败：$path → $reason") },
                )
            }
            initError = when {
                factory == null -> "libVLC 加载失败：${dir.absolutePath}"
                target == null -> "播放器初始化失败：${dir.absolutePath}"
                else -> null
            }
            if (initError != null) Log.error(initError) else Log.info("libVLC 已加载：${dir.absolutePath}")
        }
    }

    val canPlay: Boolean get() = target != null

    /**
     * 时长探测并发数（播放器池大小与目录扫描的信号量都用它）。
     *
     * 为什么不是常数 4：探测一次要真起一个 demux（打开容器、读头部、报长度），
     * 单文件实测约 100 ms，瓶颈在磁盘寻道而不在 CPU。本机 32 核上从 4 提到 8，
     * 500 部片的探测阶段从 48 s 降到 24 s（读数见 `docs/Windows使用说明.md` 的门禁表）。
     * 上限压到 8：再多，机械盘的随机读先打满，收益归零，而每个播放器都要多占一份
     * native 资源（句柄、线程）。下限 2：单核机器上也要有一点并行，否则一个坏文件
     * 就把整轮扫描堵住。
     */
    val probeParallelism: Int
        get() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)

    val durationProbe: (String) -> Long = { path -> prober?.durationMs(path) ?: 0L }

    fun open(path: String, fileName: String, sizeBytes: Long): Boolean {
        val target = target ?: return false
        Log.info("选片：$path")
        target.setMediaItem(MediaRef(uri = path, displayName = fileName, sizeBytes = sizeBytes))
        return true
    }

    fun close() {
        runCatching { prober?.close() }
        target?.close()
        runCatching { factory?.release() }
    }
}
