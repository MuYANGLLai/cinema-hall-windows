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

    /**
     * 媒体**已经装载**（`prepare()` 成功），但还不一定起播过。
     *
     * 与 [mediaPrepared] 的区别就是「装载」与「真的播起来」：有了它，[play] 才能在
     * 「选了片、还没播」这个窗口里走 `controls().play()` 而不是重开。
     */
    @Volatile
    private var mediaLoaded = false

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

    /** 当前在用的字幕轨 id（`-1` = 关）。[currentSubtitleIndex] 由它换算。 */
    @Volatile
    private var currentSubtitleId: Int = -1

    @Volatile
    private var currentVolume: Float = PlaybackTarget.DEFAULT_VOLUME

    private val closed = AtomicBoolean(false)

    /**
     * 会**阻塞**的播放器动作（跳转 / 变速 / 重开影片）统一在这条线程上执行。
     *
     * ── 为什么非要一条独立线程（2026-11-06 真机「windows 卡死」）────────────────
     * `libvlc_media_player_set_time` 是同步的：请求交给输入线程后**等它做完**才返回。
     * 大文件（5GB x265 MKV）刚打开、索引还没建好时，这一次等待可以长达几十秒。而同步
     * 引擎的 tick 跑在 Swing 的 EDT 上（引擎要求所有状态改动在同一线程），于是界面、
     * 托盘、日志一起冻住 —— 现场日志正是 `[link]` 与 `[sync]` 在同一毫秒全停。
     *
     * 单线程而不是线程池：顺序有意义（先 seek 后 play 不能颠倒），且不需要并发。
     */
    private val control: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vlc-control").apply { isDaemon = true }
        }

    /** 最近一次要跳到的位置；[NO_SEEK] = 没有待处理的跳转。 */
    private val seekTarget = java.util.concurrent.atomic.AtomicLong(NO_SEEK)

    /** 是否已经有一个「排空 seek 目标」的任务挂在 [control] 上（合并用，见 [seekTo]）。 */
    private val seekQueued = AtomicBoolean(false)

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

    /**
     * 「媒体装载好了没有」。
     *
     * ── 为什么不能只看 [mediaPrepared]（2026-11-06）──────────────────────────────
     * 那个标记只在 `mediaPlayerReady` / `playing` 事件里才置位，而 `prepare()`（选片但
     * 不起播）**不一定**触发它。于是一台「已经选好片、停在首帧」的从端会被引擎判成
     * 「没就绪」——同步校准永远跳过，房主的暂停位置永远对不上。
     *
     * 判据改成「有时长、且不在 OPENING」：`length()` 只有在媒体解析出来之后才有值，
     * 它同时排除了「刚 setMediaItem、还在打开」的那几秒 —— 而那几秒正是同步 seek
     * 会把输入线程卡死、进而冻住 EDT 的窗口（见 [control]）。
     */
    override val isReady: Boolean
        get() {
            if (mediaPrepared) return true
            val status = runCatching { player.status() }.getOrNull() ?: return false
            return runCatching { status.length() > 0 && status.state() != State.OPENING }
                .getOrDefault(false)
        }

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
        mediaLoaded = ok
        log("[vlc] prepare(${ref.uri}) -> $ok")
    }

    override fun play() {
        requestedPlaying = true
        // ① 已经起播过：续播。
        if (mediaPrepared) {
            runCatching { player.controls().setPause(false) }
                .recoverCatching { player.controls().play() }
            return
        }
        // ② 还没起播，但媒体已经装载（界面 `prepare()` 过）：直接起播，**不重开**。
        //    旧写法一律走 `media().play(uri)`，那会把位置打回 0、并在大文件上重新解析
        //    索引；它如果落在 EDT 上，整界面就冻住（2026-11-06 真机「卡死」的一半原因）。
        if (mediaLoaded) {
            val ok = runCatching { player.controls().play() }.isSuccess
            log("[vlc] play(before ready) → controls().play()（不重开，位置保留） ok=$ok")
            if (ok) return
        }
        // ③ 连媒体都没有：只能整条重开，且必须在控制线程上跑（见 [control]）。
        val uri = currentUri ?: return
        control.execute {
            if (closed.get()) return@execute
            val started = runCatching { player.media().play(uri) }.isSuccess
            log("[vlc] play(before ready) → media().play()（control 线程） ok=$started")
        }
    }

    override fun pause() {
        requestedPlaying = false
        // `setPause` 是轻量调用（不触发索引重建），留在调用线程上：暂停的语义要求
        // 「这一瞬间就停」，排到后台会让用户觉得按键有延迟。
        runCatching { player.controls().setPause(true) }
    }

    /**
     * 跳转。**永远不在调用线程上执行**（见 [control]）。
     *
     * ── 为什么必须挪走（2026-11-06 真机卡死）──────────────────────────────────
     * `setTime()` 是同步调用：它把 seek 请求丢给输入线程并**等它完成**。刚打开的
     * 5GB x265 MKV 还没建好索引，这一等可以长达几十秒甚至更久。而引擎的 tick 跑在
     * Swing 的 EDT 上（引擎所有状态改动必须同一线程），于是整个界面连同托盘一起冻住，
     * 日志也在同一瞬间全停 —— 现场就是这样：`[link]`/`[sync]` 同时断在 21:33:04.402。
     *
     * ── 为什么要合并 ───────────────────────────────────────────────────────────
     * 引擎在校准档位里可能每 500ms 要求一次硬 seek。排队而不合并的话，控制线程会被
     * 越积越多的过期目标占满，真实的「最新位置」反而要等很久才生效。
     * 这里只保留**最后一次**目标位置：语义上 seek 本来就是「最终去哪」，中间过程没有意义。
     */
    override fun seekTo(positionMs: Long) {
        seekTarget.set(positionMs)
        if (!seekQueued.compareAndSet(false, true)) return
        control.execute {
            try {
                while (!closed.get()) {
                    val target = seekTarget.getAndSet(NO_SEEK)
                    if (target == NO_SEEK) break
                    if (!trySeek(target)) pending.requestSeek(target)
                }
            } finally {
                seekQueued.set(false)
            }
        }
    }

    /**
     * 「回到页面时画面丢了」的兜底：把播放器重新叫起来。
     *
     * 切页会摘掉视频输出（现在已改成不摘，但历史上摘过；用户机器上也可能因为
     * 显卡驱动重排 vout 而丢）。判据在调用方：`isPlayRequested && !isPlaying`。
     * 这里只做一件最轻的事：**续播**（`controls().play()`），不重开、不 seek ——
     * 位置由同步引擎在下一拍校准，重开反而会把位置打回 0。
     */
    fun resumeIfOutputLost() {
        control.execute {
            if (closed.get()) return@execute
            val state = runCatching { player.status().state() }.getOrNull()
            log("[vlc] 画面恢复：state=$state，尝试续播")
            if (mediaPrepared || mediaLoaded) {
                runCatching { player.controls().setPause(false) }
                    .recoverCatching { player.controls().play() }
            } else {
                currentUri?.let { uri -> runCatching { player.media().play(uri) } }
            }
        }
    }

    override fun setSpeed(speed: Float) {
        // 变速同样会重建视频输出（部分编码下等于一次重新起播），一并挪到控制线程。
        control.execute {
            if (closed.get()) return@execute
            if (!tryRate(speed)) pending.requestRate(speed)
        }
    }

    /**
     * 选字幕。
     *
     * ── 为什么要读回校验 + 重试（2026-11-06 用户报「只能解析出有字幕，选不中」）──────
     * `libvlc_video_set_spu` 在**轨道表还没稳定**时返回 -1 或返回一个旧 id，而调用本身
     * 不抛异常 —— 旧实现只要返回值 ≥ 0 就当成成功，于是界面点了菜单、日志里什么都没有、
     * 画面上字幕不变。这里改成：
     *   1. 调用后读 `subpictures().track()`，**读回来等于目标 id 才算生效**；
     *   2. 不相等就刷新轨道表重试（起播后的头一两秒轨道表才齐），最多 4 次；
     *   3. 每次尝试都写一行日志 —— 「点不动」必须能在 app.log 里看到原因。
     */
    override fun selectSubtitleTrack(trackIndex: Int) {
        control.execute {
            if (closed.get()) return@execute
            val id = subtitleIds.getOrNull(trackIndex)
            if (id == null) {
                log("[vlc] 字幕选择失败：index=$trackIndex 不在当前 ${subtitleIds.size} 条轨道里")
                return@execute
            }
            applySubtitle(id, trackIndex)
        }
    }

    /**
     * 关掉字幕。**桌面端口的扩展**（通用 `PlaybackTarget` 只认「选第几条」，`-1` 的语义
     * 各平台不一致，所以不往共享端口上加）。
     */
    fun disableSubtitles() {
        control.execute { if (!closed.get()) applySubtitle(-1, -1) }
    }

    /** 当前选中的字幕下标；`-1` = 关。界面用它把「正在用哪条」画出来。 */
    val currentSubtitleIndex: Int
        get() = subtitleIds.indexOf(currentSubtitleId)

    private fun applySubtitle(id: Int, index: Int) {
        var applied = -1
        repeat(4) { attempt ->
            applied = runCatching { player.subpictures().setTrack(id) }.getOrDefault(-1)
            val current = runCatching { player.subpictures().track() }.getOrDefault(-2)
            log(
                "[vlc] 字幕 setTrack(index=$index, id=$id) → 返回 $applied，" +
                    "当前 $current（第 ${attempt + 1} 次）",
            )
            if (current == id) {
                currentSubtitleId = id
                refreshTracks()
                return
            }
            // 轨道表可能还没稳定：重新拉一次再试。
            refreshTracks()
            runCatching { Thread.sleep(150L * (attempt + 1)) }
        }
        if (index >= 0) pending.requestSubtitle(index)
        log("[vlc] 字幕 id=$id 没生效（返回 $applied），已排队等下一次事件重放")
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
        //
        // ── 光等 `isShowing` 不够（2026-11-06 实测）────────────────────────────
        // `SwingPanel` 把 panel 放进 Compose 的容器时有那么几十毫秒：组件已显示、
        // 尺寸却还是 `0x0`（现场日志：`surface attach ... ok size=0x0`）。以这个尺寸
        // 挂上去，libVLC 的 vout 按零尺寸初始化，之后窗口变大也不会补画 ——
        // 症状就是「按了播放没反应 / 黑屏」。所以判据必须是「显示 **且** 有尺寸」。
        Thread({
            val deadline = System.nanoTime() + 8_000_000_000L
            var waited = 0L
            while (System.nanoTime() < deadline && !closed.get()) {
                if (awt.canvas.isShowing && awt.canvas.width > 0 && awt.canvas.height > 0) {
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
        // 控制线程可能正卡在一次 seek 里；先中断排队，再停播放器。
        runCatching { control.shutdownNow() }
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
        // 当前在用的那条：界面要能把它标出来（不然「选没选中」只能靠猜）。
        currentSubtitleId = runCatching { player.subpictures().track() }.getOrDefault(-1)
        val names = real.map { description ->
            // VLC 的 description 形如 "Track 1 - [Chinese]"；取方括号里的语言名，
            // 没有方括号就留空串（契约允许“该轨没标语言”）。
            Regex("\\[([^]]+)]").find(description.description())?.groupValues?.get(1).orEmpty()
        }
        if (names != subtitleNames) {
            // 原始串一并记下来：字幕语言名曾经出现「涓€构」这类乱码（libVLC 交 UTF-8、
            // JNA 按平台字符集 GBK 解码），有这一行就能当场判断修没修好。
            log("[vlc] 字幕轨 raw=${descriptions.map { it.description() }} → $names")
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

    private companion object {
        /** [seekTarget] 的哨兵值：没有待处理的跳转。用 `Long.MIN_VALUE` 而不是 0 —— 0 是合法的跳转目标。 */
        const val NO_SEEK = Long.MIN_VALUE
    }
}
