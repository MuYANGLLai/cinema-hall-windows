package com.cinemahall.desktop.media

import com.cinemahall.core.media.MediaRef
import com.cinemahall.core.media.PlaybackSurface
import com.cinemahall.core.media.PlaybackTarget
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.base.TrackDescription
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * W1：`PlaybackTarget` 的 libVLC 实现。
 *
 * ── 与 Android 的 `VlcPlaybackTarget` 是什么关系 ─────────────────────────────
 * **没有关系**（桌面的 libVLC 是 C 的 JNI 绑定，Android 的是 AAR 里的 JNI，两者 API
 * 形状不同）。共享的是 `core:media` 的端口与 §6 的同步算法，不是这份实现。
 *
 * ── 为什么所有 setter 都可能“没反应” ────────────────────────────────────────
 * libVLC 的 `setTime` 在输入未打开时静默无效；`setRate` 返回 false；字幕轨在轨道表
 * 解析前设置无效。同步引擎的动作顺序偏偏是「先摆位置/倍速，再起播」，所以失败的请求
 * 要落进 [pending] 等事件重放（见该类的说明）。
 *
 * ── 线程 ────────────────────────────────────────────────────────────────────
 * 端口约定「调用方在同一线程上调」，但 vlcj 的事件回调在 VLC 自己的线程上。这里的
 * 做法是：**读的状态用 `@Volatile` 快照，事件回调只更新快照 + 通知 listener**，
 * 不在回调里反过来调用播放器（避免 VLC 线程重入）。
 */
class VlcPlaybackTarget(
    private val factory: MediaPlayerFactory,
    /**
     * 诊断输出通道。默认打 stdout，但**打包后 stdout 是黑洞**：
     * jpackage 出来的 exe 是 GUI 子系统，没有控制台。所以 `desktopApp` 会把这里
     * 接到自己的日志文件上（`Log.info`）—— 否则「第二个实例点开片子停在 0:00」这种
     * 现场，日志里一条 vlc 事件都没有，只能靠猜。
     */
    private val log: (String) -> Unit = { println(it) },
) : PlaybackTarget {

    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val pending = PendingPlayback()
    private val listeners = CopyOnWriteArrayList<PlaybackTarget.Listener>()

    @Volatile
    private var mediaPrepared = false

    @Volatile
    private var currentUri: String? = null

    @Volatile
    private var requestedPlaying = false

    @Volatile
    private var errorText: String? = null

    @Volatile
    private var aspectRatio: Float? = null

    @Volatile
    private var subtitleIds: List<Int> = emptyList()

    @Volatile
    private var subtitleNames: List<String> = emptyList()

    @Volatile
    private var currentVolume: Float = PlaybackTarget.DEFAULT_VOLUME

    private val closed = AtomicBoolean(false)

    init {
        player.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
                    mediaPrepared = true
                    errorText = null
                    log("[vlc] event mediaPlayerReady length=${player.status().length()}")
                    flushPending()
                    refreshTracks()
                }

                override fun playing(mediaPlayer: MediaPlayer) {
                    mediaPrepared = true
                    log("[vlc] event playing")
                    flushPending()
                    refreshTracks()
                }

                override fun paused(mediaPlayer: MediaPlayer) = refreshTracks()

                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                    // 起播那一瞬 setTime 可能仍被拒；每一次时间推进都是一次重试机会。
                    flushPending()
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    log("[vlc] event lengthChanged=$newLength")
                    flushPending()
                    refreshTracks()
                }

                override fun stopped(mediaPlayer: MediaPlayer) {
                    log("[vlc] event stopped")
                    mediaPrepared = false
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    // 片尾不改变“意图”（端口注释：片尾不该被当成一次暂停广播）。
                    mediaPrepared = true
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    log("[vlc] event error")
                    errorText = "播放器报错（文件损坏、编码不支持或路径不可读）"
                    requestedPlaying = false
                    listeners.forEach { it.onPlaybackError(errorText) }
                }

                override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
                    currentVolume = (volume / 100f).coerceIn(0f, 1f)
                    listeners.forEach { it.onVolumeChanged(currentVolume) }
                }

                override fun videoOutput(mediaPlayer: MediaPlayer, width: Int) {
                    updateAspect()
                }

                override fun elementaryStreamAdded(
                    mediaPlayer: MediaPlayer,
                    type: uk.co.caprica.vlcj.media.TrackType,
                    id: Int,
                ) {
                    if (type == uk.co.caprica.vlcj.media.TrackType.TEXT) refreshTracks()
                }
            },
        )
        // 初始音量按端口默认值（1f = 原音量）落到播放器，避免“界面 100% 实际 80%”。
        runCatching { player.audio().setVolume((currentVolume * 100).toInt()) }
    }

    // ── 状态 ────────────────────────────────────────────────────────────────

    override val currentPositionMs: Long
        get() = runCatching { player.status().time() }.getOrDefault(0L).coerceAtLeast(0L)

    override val isPlaying: Boolean
        get() = runCatching { player.status().isPlaying() }.getOrDefault(false)

    override val isPlayRequested: Boolean
        get() = requestedPlaying

    override val isBuffering: Boolean
        get() = runCatching {
            when (player.status().state()) {
                State.OPENING, State.BUFFERING -> true
                else -> false
            }
        }.getOrDefault(false)

    override val isReady: Boolean
        get() = mediaPrepared

    override val durationMs: Long
        get() = runCatching { player.status().length() }.getOrDefault(0L).coerceAtLeast(0L)

    override val videoAspectRatio: Float?
        get() = aspectRatio

    override val subtitleLanguages: List<String>
        get() = subtitleNames

    override val playbackError: String?
        get() = errorText

    override val isSeekable: Boolean
        get() = runCatching { player.status().isSeekable() }.getOrDefault(false)

    /** libVLC 没有 Media3 那套解码器计数 —— 诚实返回 false，而不是给一串 0 假装有。 */
    override val supportsDecoderStats: Boolean
        get() = false

    override val volume: Float
        get() = currentVolume

    // ── 动作 ────────────────────────────────────────────────────────────────

    override fun setMediaItem(ref: MediaRef) {
        errorText = null
        mediaPrepared = false
        currentUri = ref.uri
        aspectRatio = null
        subtitleIds = emptyList()
        subtitleNames = emptyList()
        pending.clearSeek()
        pending.clearSubtitle()
        // prepare 而不是 play：起播由引擎的 play() 触发；这里先让 VLC 解析轨道表，
        // 界面才能在“已选片、未开播”的窗口里显示时长与字幕轨。
        val ok = runCatching { player.media().prepare(ref.uri) }
            .onFailure {
                errorText = "打不开片源：${ref.displayName}"
                listeners.forEach { l -> l.onPlaybackError(errorText) }
            }
            .getOrDefault(false)
        log("[vlc] prepare(${ref.uri}) -> $ok")
    }

    override fun play() {
        requestedPlaying = true
        // 未就绪时 setPause 无效（没有输入）；这种情况下走 media().play(uri)，
        // 它会在同一次调用里完成「打开 + 起播」。这是 Phase 0 里最容易踩空的一步。
        if (!mediaPrepared) {
            val uri = currentUri
            if (uri != null) {
                val started = runCatching { player.media().play(uri) }.getOrDefault(false)
                log("[vlc] play(before ready) -> $started")
                return
            }
        }
        runCatching { player.controls().setPause(false) }
            .recoverCatching { player.controls().play() }
    }

    override fun pause() {
        requestedPlaying = false
        runCatching { player.controls().setPause(true) }
    }

    override fun seekTo(positionMs: Long) {
        if (trySeek(positionMs)) return
        pending.requestSeek(positionMs)
    }

    override fun setSpeed(speed: Float) {
        if (tryRate(speed)) return
        pending.requestRate(speed)
    }

    override fun selectSubtitleTrack(trackIndex: Int) {
        if (trySubtitle(trackIndex)) return
        pending.requestSubtitle(trackIndex)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        currentVolume = clamped
        runCatching { player.audio().setVolume((clamped * 100).toInt()) }
        listeners.forEach { it.onVolumeChanged(clamped) }
    }

    override fun addListener(listener: PlaybackTarget.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackTarget.Listener) {
        listeners -= listener
    }

    // ── 画面 ────────────────────────────────────────────────────────────────

    override fun createSurface(): PlaybackSurface = AwtPlaybackSurface()

    override fun attachSurface(surface: PlaybackSurface) {
        val awt = surface as? AwtPlaybackSurface ?: return
        // AWT 的 heavyweight Canvas 要等 isShowing 才拿得到稳定的 HWND；Compose 的组合
        // 时机早于它。Phase 0 §2.1 的做法是轮询最多 5 秒（不是 sleep 一次就挂）。
        Thread({
            val deadline = System.nanoTime() + 8_000_000_000L
            var waited = 0L
            while (System.nanoTime() < deadline && !closed.get()) {
                if (awt.canvas.isShowing) {
                    val result = runCatching {
                        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(awt.canvas))
                        player.videoSurface().attachVideoSurface()
                    }
                    log(
                        "[vlc] surface attach after ${waited}ms: " +
                            (result.exceptionOrNull()?.let { "FAILED ${it.message}" } ?: "ok") +
                            " size=${awt.canvas.width}x${awt.canvas.height}",
                    )
                    updateAspect()
                    return@Thread
                }
                Thread.sleep(50)
                waited += 50
            }
            if (!closed.get()) {
                log("[vlc] surface attach timed out (canvas never showing)")
                errorText = "画面没能挂上（Canvas 未显示）"
                listeners.forEach { it.onPlaybackError(errorText) }
            }
        }, "vlc-surface-attach").apply { isDaemon = true }.start()
    }

    override fun detachSurface(surface: PlaybackSurface) {
        runCatching { player.videoSurface().set(null) }
        aspectRatio = null
    }

    /** 不是端口方法：进程退出/切片时释放 native 资源。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { player.controls().stop() }
        runCatching { player.release() }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun trySeek(positionMs: Long): Boolean {
        val ready = runCatching { player.status().length() > 0 }.getOrDefault(false)
        if (!ready) return false
        runCatching { player.controls().setTime(positionMs) }
        return runCatching { player.status().length() > 0 }.getOrDefault(false)
    }

    private fun tryRate(speed: Float): Boolean =
        runCatching { player.controls().setRate(speed) }.getOrDefault(false)

    private fun trySubtitle(index: Int): Boolean {
        val ids = subtitleIds
        if (index !in ids.indices) return false
        return runCatching { player.subpictures().setTrack(ids[index]) >= 0 }.getOrDefault(false)
    }

    private fun flushPending() {
        val cleared = pending.apply(
            applyRate = { tryRate(it) },
            applySeek = { trySeek(it) },
            applySubtitle = { trySubtitle(it) },
        )
        if (cleared > 0) {
            // 没有专门的事件通道；这里只更新一次轨道快照（字幕可能刚被选中）。
            refreshTracks()
        }
    }

    private fun refreshTracks() {
        val descriptions: List<TrackDescription> = runCatching {
            player.subpictures().trackDescriptions()
        }.getOrDefault(emptyList())

        // trackDescriptions 的第一条通常是 Disable（id=-1）；界面只列真实字幕轨。
        val real = descriptions.filter { it.id() >= 0 }
        subtitleIds = real.map { it.id() }
        val names = real.map { description ->
            // VLC 的 description 形如 "Track 1 - [Chinese]"；取方括号里的语言名，
            // 没有方括号就留空串（契约允许“该轨没标语言”）。
            Regex("\\[([^]]+)]").find(description.description())?.groupValues?.get(1).orEmpty()
        }
        if (names != subtitleNames) {
            subtitleNames = names
            listeners.forEach { it.onSubtitleTracks(names) }
        }
        updateAspect()
    }

    private fun updateAspect() {
        val dimension = runCatching { player.video().videoDimension() }.getOrNull()
        val ratio = if (dimension != null && dimension.width > 0 && dimension.height > 0) {
            dimension.width.toFloat() / dimension.height.toFloat()
        } else {
            null
        }
        if (ratio != aspectRatio) {
            aspectRatio = ratio
            listeners.forEach { it.onVideoAspectRatio(ratio) }
        }
    }
}
