package com.cinemahall.core.media

/**
 * 播放器端口（《技术方案评审》§6.3 —— 同步引擎眼中的「播放器」）。
 *
 * ── 为什么要有这层接口，而不是让玩法直接用 ExoPlayer ────────────────────────
 * §6 的整套算法（时钟估计、虚拟主时间轴、三档漂移校准）**本身与播放器无关** ——
 * 它只用到「当前位置 / 是否在播 / 是否在缓冲 / play / pause / seekTo / setSpeed」
 * 这几个动作。抽出来之后，§6.3 的每一档、§6.2 的外推、§6.1 的滤波都能在纯 JVM
 * 单测里精确钉住（假时钟 + 假播放器，可逐毫秒断言），而不必起 Android 运行时、
 * 更不必真的解码一段视频 —— 真机上最难观察的恰恰是「刚过阈值的那 1ms」。
 *
 * ── 为什么端口放在 `core:media`，而不是 `feature:cinema` ────────────────────
 * §4.1 把「ExoPlayer 封装、SeekParameters、PlaybackParameters」划给 `core:media`，
 * 而端口的实现（[Media3PlaybackTarget]）必须在**能看见 Media3 的模块**里，也就是说
 * 端口与实现必须同模块。若把端口放进 `feature:cinema`，实现就只能掉到 `app`，
 * 于是 `core:media` 变成一个只装摘要工具的模块，§4.1 那一行就名不副实了。
 *
 * 反方向的约束不变：`core:*` 不得依赖 `feature:*`（构建期断言守着）。所以这里是
 * **端口 + 适配器都在 core:media**，玩法只依赖接口。
 *
 * ── 线程约定 ────────────────────────────────────────────────────────────────
 * 调用方保证所有方法都在同一线程上调用（与 `PeerCoordinator` 同一条约定：
 * 状态改动都发生在 RTC 那个 `Dispatchers.Main.immediate` 作用域里）。
 */
interface PlaybackTarget {

    /** 当前位置（毫秒）。播放器未就绪时返回 0。 */
    val currentPositionMs: Long

    val isPlaying: Boolean

    /**
     * 本机**被请求**的播放态（Media3 的 `playWhenReady`）。
     *
     * ── 为什么需要一个与 [isPlaying] 不同的量 ──────────────────────────────────
     * 双向控制之后，「本机播放态被引擎之外的东西改了」这件事必须能被发现并广播
     * 出去，否则症状是「我拔了耳机按下暂停，2.5 秒后心跳又把它播起来」。
     * 触发这种变化的有：`setHandleAudioBecomingNoisy(true)` 带来的拔耳机自动暂停
     * （见 [Media3PlaybackTarget]）、系统音频焦点丢失、播放到片尾。
     *
     * 用 [isPlaying] 做这个判断是**错的**：Media3 的 `isPlaying` 在缓冲期间会翻成
     * false，于是每一次网络抖动都会被误判成「用户暂停了」并广播一条 pause ——
     * 两端会被一条条缓冲期的假暂停搅得反复停播。`playWhenReady` 表达的是
     * 「意图」，缓冲与片尾都不改变它，所以它才是判断外部改动的正确信号。
     *
     * 播放结束那一瞬它仍然是 true，这是刻意的：片尾不该被当成一次暂停广播出去。
     */
    val isPlayRequested: Boolean

    /**
     * 正在缓冲。
     *
     * §6.3 要求缓冲期间**跳过漂移校准**：缓冲时 `currentPosition` 会停住不动，
     * 而目标位置仍在按 1.0 外推，两者之差在几百毫秒内就会越过硬阈值 →
     * 一次网络抖动被误判成「严重不同步」→ 硬 seek → 又要重新缓冲，越纠越糟。
     */
    val isBuffering: Boolean

    /** 已经有可播放的媒体（还没选片时为 false）。 */
    val isReady: Boolean

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /**
     * 选定片源并开始 prepare（异步：时长与轨道表随后才到）。
     *
     * 这条与 [durationMs] 原来只长在 `Media3PlaybackTarget` 上，界面因此必须认识
     * 具体类 —— 2026-10-26 为换内核把它们搬进端口。
     */
    fun setMediaItem(ref: MediaRef)

    /** 时长（毫秒）。未知时给 0 —— 界面不认 `C.TIME_UNSET` 这种哨兵值。 */
    val durationMs: Long get() = 0L

    // ── 播放内核解耦（2026-10-26，为「换 libVLC」做的接缝）────────────────────────
    //
    // 在这之前，界面对 ExoPlayer 的**具体类型**是硬依赖：`PlayerView` / `Tracks` /
    // `VideoSize` / `PlaybackException`。换内核时那些调用点全都要重写，所以先把它们
    // 收进端口 —— 界面对播放器的认知只剩下面这几个成员。
    //
    // 每一项都给了默认实现：只关心「播放/暂停/位置」的实现（以及单测里的假播放器）
    // 不必为它们写空方法。

    /**
     * 造一个承载画面的 View（ExoPlayer 是 `PlayerView`，libVLC 是 `VLCVideoLayout`）。
     *
     * 返回 `null` 表示这个实现不提供内嵌画面。**调用方必须处理 null**（不要 crash）。
     */
    fun createSurface(): PlaybackSurface? = null

    /**
     * 同上，但带上**宿主句柄**（2026-11-05 加）。
     *
     * ── 为什么需要这个参数（真机缺陷的直接原因）────────────────────────────────
     * libVLC 的画面 View 必须用**宿主的 Activity 上下文**来造。它的
     * `VideoHelper.updateVideoSurfaces()` 第一步是 `AndroidUtil.resolveActivity(context)`，
     * 拿不到 Activity 就 `return` —— 于是 `setWindowSize()` 一次都不会被调用，
     * native 侧只能按**片子的原始分辨率**（1920×1080 这种）去画，而不是按容器大小。
     * 真机上的症状就是「整幅画按 1:1 原始像素画在画面区角落，周围一圈黑」。
     *
     * 本工程的播放器是进程级单例，构造时只有 `applicationContext`；
     * 而「哪个 Activity 在显示它」只有组合期才知道 —— 所以只能由调用方在
     * [createSurface] 时传进来。
     *
     * ⚠️ 参数类型是 `Any?` 而不是 `Context`：`core:media` 是纯 JVM 模块，
     * 依赖里没有任何 Android 类型（阶段一拆模块的硬约束）。习惯用法是按平台强转：
     * Android 实现转成 `Context`，JVM 实现直接忽略。默认实现退回无参版本。
     */
    fun createSurface(host: Any?): PlaybackSurface? = createSurface()

    /** 把播放器的输出挂到这个平台句柄上。 */
    fun attachSurface(surface: PlaybackSurface) = Unit

    /** 摘掉输出（离开组合时调；不做事会让解码器一直占着 Surface）。 */
    fun detachSurface(surface: PlaybackSurface) = Unit

    /**
     * 播放器侧的变化通知。**刻意不用协程 / Compose 类型** —— `core:media` 的依赖里
     * 没有 kotlinx-coroutines，为一条读数引一个流不值得；界面用 `DisposableEffect`
     * 自己转成 Compose 状态即可（见 `CinemaPlaybackSection` 的 `remember…` 系列）。
     */
    interface Listener {
        fun onVideoAspectRatio(ratio: Float?) = Unit
        fun onSubtitleTracks(languages: List<String>) = Unit
        fun onAudioNotice(notice: String?) = Unit
        fun onPlaybackError(text: String?) = Unit

        /** 影片音量被改了（包括本 App 自己写的那一次 —— 单一真值是播放器）。 */
        fun onVolumeChanged(volume: Float) = Unit
    }

    fun addListener(listener: Listener) = Unit

    fun removeListener(listener: Listener) = Unit

    /** 画面宽高比（宽/高）的**当前值**。`null` = 还没拿到 → 调用方用 16:9 兜底。 */
    val videoAspectRatio: Float? get() = null

    /**
     * 内封字幕轨的**语言代码**（顺序就是选择用的序号，空串 = 该轨没标语言）。
     *
     * 只给语言、不给成品文案：显示格式（字幕 N（chi））留在界面里，这样换内核
     * 不会顺带改屏幕上的字 —— 文案是验收口径。
     */
    val subtitleLanguages: List<String> get() = emptyList()

    /**
     * 片源层面「本机一条音轨都解不了」的提示；`null` = 没问题，或这个引擎判断不了。
     *
     * 判断本身在 [AudioDiagnosis]（纯逻辑、有单测）。换内核后如果拿不到等价的轨道
     * 信息，返回 `null` 就是**诚实的缺失** —— 比显示一句猜的话好。
     */
    val audioNotice: String? get() = null

    /** 最近一次播放错误（已翻成人话）；`null` = 没出错，或已被清掉。 */
    val playbackError: String? get() = null

    /** 这个实现能否提供解码器计数（`⑤` 的 `解码器丢帧` / `解码器输出` 两行）。 */
    val supportsDecoderStats: Boolean get() = true

    /**
     * 这个媒体项**能不能跳转**（播放器自己的判断，2026-10-26 新增）。
     *
     * ── 为什么端口上要有它 ──────────────────────────────────────────────────────
     * 「拖进度条没反应、画面从开头继续」这个症状有两种完全不同的成因：我们的 seek
     * 逻辑错了，或者**这部片在播放器眼里根本不可跳**（索引缺失 / 索引不被识别）。
     * 两者在界面上长得一模一样，而在真机上又没法看日志 —— 所以把播放器的判断
     * 直接搬到诊断面板上，一眼就能分开。
     *
     * 默认 `true`：不关心这件事的实现（以及单测里的假播放器）不必改。
     */
    val isSeekable: Boolean get() = true

    /**
     * 变速播放，**不变调**（§6.3：pitch 固定 1.0，Media3 走 Sonic 做时间伸缩）。
     *
     * 故意不提供 `setPitch` / `setPlaybackParameters(speed, pitch)` 这样的重载：
     * 一旦签名里能传 pitch，就迟早会有人顺手传个非 1.0 的值去「对齐」，
     * 而那是以牺牲音高换取同步，属于听得出且不可接受的做法。
     */
    fun setSpeed(speed: Float)

    /**
     * 切换内封字幕轨（`cinema.subtitle`，§5.3 依据 Q8）。
     *
     * 给了默认空实现：字幕跟随不是 §6 的同步算法的一部分，而是「房主主控」的一个
     * 顺带应用（§6.5）。默认空实现让不关心字幕的播放器实现（以及单测里的假播放器）
     * 不必为它写空方法。
     */
    fun selectSubtitleTrack(trackIndex: Int) = Unit

    /**
     * 影片的播放音量，`1f` = 原音量，取值 `0f..1f`。
     *
     * ── 为什么端口上要有它（1.7）───────────────────────────────────────────────
     * 语音通话走系统的**通话流**（WebRTC 的 ADM 把远端音轨渲染在
     * `USAGE_VOICE_COMMUNICATION` 上，见 `core:rtc` 的 `RtcEngine`），影片走**媒体流**
     * （ExoPlayer 自己的输出）。两条流在系统层本来就是分开的，所以「嫌片子吵就调片子、
     * 嫌对方声音小就调通话」不会互相拖动 —— 这正是 1.7 那条硬要求要的效果，
     * 而且它不需要在本 App 里做任何混音或二次衰减。
     *
     * 端口把「影片这一路」收在这里，通话那一路在 `core:rtc` 的 `CallAudio` 上，
     * 两边**互不引用**：`core:media` 不必认识通话，`core:rtc` 也不必认识播放器。
     *
     * 给了默认实现：与 [selectSubtitleTrack] 同一个理由 —— 不关心音量的实现
     * （以及单测里的假播放器）不必为它写空方法。
     */
    fun setVolume(volume: Float) = Unit

    /**
     * 当前播放音量。默认 `1f` —— 不实现 [setVolume] 的播放器就是原音量，
     * 而不是「0（静音）」：后者会让「忘了实现」变成一件听不见任何声音的事故。
     */
    val volume: Float get() = DEFAULT_VOLUME

    /**
     * 视频解码器的累计计数（§9.3 排障用）。
     *
     * ── 为什么端口上要暴露它（2026-10-12 加）───────────────────────────────────
     * 真机报过一个现象：**从端**的画面「几秒保持在同一个画面、再跳到下一个画面」，
     * 而音频与通话都正常。这个形状在 Media3 里有一条对应的机制，但它是**看不见的** ——
     * 除非把解码器的计数读出来：
     *
     *  - `MediaCodecVideoRenderer` 在某一帧晚于播放时钟 **500ms** 以上时
     *    （`MIN_EARLY_US_VERY_LATE_THRESHOLD = -500_000`），会走
     *    `maybeDropBuffersToKeyframe`：把源丢到当前位置的关键帧、**flush 并重建解码器**
     *    （`flushOrReinitializeCodec()`），同时 `videoSink.flush(resetPosition=false)`
     *    **保留上一帧画面**。于是屏幕上就是「定格几秒 → 换一张 → 再定格」。
     *  - 每走一次，`droppedToKeyframeCount` 加一。**它涨 = 机制确认**；
     *    它不涨而 `dropped` 猛涨 = 只是单纯跟不上（另一回事）。
     *
     * 没有这两个数，「画面卡」只能靠猜：`hardSeeks` 涨说明是我们的校准在跳，
     * `droppedToKeyframe` 涨说明是解码器在自我重置 —— 两者的修法完全不同。
     *
     * 默认返回空值：与 [setVolume] 同一个理由，不关心解码统计的实现（以及单测里的
     * 假播放器）不必为它写空方法。
     */
    val videoDecoderStats: VideoDecoderStats get() = VideoDecoderStats.EMPTY

    companion object {
        /** `1f` = 原音量。与 `core:rtc` 的 `CallAudioState.DEFAULT_VOLUME` 同一个取值。 */
        const val DEFAULT_VOLUME = 1f
    }
}

/**
 * 视频解码器计数快照（[PlaybackTarget.videoDecoderStats]）。
 *
 * ── 为什么给「计数器」而不是「最近一秒的丢帧数」─────────────────────────────
 * 与 §11 里那三个「本次会话累计量」同一个理由：诊断页的折线只有 20 秒，而卡顿是
 * 一阵一阵的 —— 看的人往往是卡完之后才翻到这一页。计数器只会单调增长，
 * **同一段播放里看两次之间的差值**就能判断「刚才那几秒有没有在丢帧」，
 * 不必守在页面上。
 *
 * ── 计数从什么时候开始算（不知道这条就会把 0 读成「没问题」）──────────────────
 * `MediaCodecRenderer.onEnabled()` 里是 `decoderCounters = new DecoderCounters()`，
 * 而 `ExoPlayerImpl` 是在 `onVideoEnabled(counters)` 时接住这个新对象的 ⇒
 * **每次起播（渲染器被启用）都会换成一组新的全零计数器**，seek 不会；
 * `stop()` 之后 `onVideoDisabled` 把它置 null、`getVideoDecoderCounters()` 返回 null，
 * 这里读到的就是全 0 —— 那是「读不到」，不是「没丢帧」。
 * 所以判据只能是同一段播放内两次读数之差。
 *
 * @param dropped 被丢弃的输出帧总数（含丢到关键帧时一起算进来的那些）
 * @param droppedToKeyframe 「丢到关键帧并重建解码器」发生的次数。**这是画面定格的直接证据**
 * @param skipped 有意跳过的输出帧数（seek 之后落在目标位置之前的那一段）
 * @param rendered 真正渲染出去的帧数。与 `dropped` 一起看：渲染占比过低就是跟不上
 */
data class VideoDecoderStats(
    val dropped: Int = 0,
    val droppedToKeyframe: Int = 0,
    val skipped: Int = 0,
    val rendered: Int = 0,
) {
    companion object {
        /**
         * 全部为零 —— 还没就绪、实现方不提供，或播放已 `stop()`（此时
         * `ExoPlayer.getVideoDecoderCounters()` 返回 null，与「没丢帧」同形，
         * 判读时必须先确认片子正在播）。
         */
        val EMPTY = VideoDecoderStats()
    }
}
