package com.cinemahall.core.rtc

import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * 通话音频的对外状态（阶段一 1.7，《技术方案评审》§9.1 / §9.4 / §14.4）。
 *
 * ── 公开签名里**没有**任何 `org.webrtc` 类型 ────────────────────────────────
 * 与 [PeerDiagnostics] 同一条原则（§4.2 接缝 1）：音量是 `Float`、设备名是 `String`、
 * 开关是 `Boolean`。`core:rtc` 对 WebRTC 的依赖因此可以是 `implementation`。
 * （本文件里唯一一处 `org.webrtc` 导入在文件末尾的 [remoteSendingFrom] 上 ——
 * 它是 `internal`，不出现在模块对外面上。）
 *
 * ── [requested] 与 [sending] 为什么要分开 ────────────────────────────────────
 * 「用户按了开启通话」与「本机音轨真的挂上了发送端」不是同一件事，中间隔着一段
 * 链路还没建好的时间（answer 方要等对端 offer 里的音频 m 行落地才拿得到发送端）。
 * 合成一个布尔量的话，界面在那一小段时间里只能显示「没开」—— 而用户明明按过了，
 * 于是会再按一次、再按一次。分开之后界面能如实说「已开启，等音频通道建立」。
 */
data class CallAudioState(
    /** 用户是否按下了「开启通话」。这是**意图**，不随链路生灭而复位。 */
    val requested: Boolean = false,

    /** 本机音轨是否真的在采集、且已经挂在音频发送端上（= 对端能听到本机）。 */
    val sending: Boolean = false,

    /** 是否已经收到对端音轨（= 本机能听到对端）。 */
    val receiving: Boolean = false,

    /**
     * 音频 m 行**协商出来的方向**里，对端在不在往本端发。
     *
     * `null` = 还没有音频收发端（音频 m 行没协商出来，或对端跑的是 1.7 之前的版本）；
     * `true` = 协商成了双向，对端**有**发送能力；
     * `false` = 协商成了「只收不发」—— 对端那条 m 行是 `recvonly`。
     *
     * ── 为什么要有这一格 ───────────────────────────────────────────────────────
     * 「听不见对端」在真机上只有这一种表现，而成因有好几种。这一格把其中一类**直接
     * 指出来**：`false` 意味着对端那条 m 行根本没协商出发送能力（`recvonly` 的 m 行
     * 连 SSRC 都没有），再怎么等也不会出声，该去对端那台机器上找原因 —— 而不是
     * 在这台机器上反复开关通话。判据取自 `RtpTransceiver.currentDirection`，
     * 见 [remoteSendingFrom]。
     */
    val remoteSending: Boolean? = null,

    /**
     * 本端音频包的收发计数（`getStats()` 里 `kind == "audio"` 的 `outbound-rtp` /
     * `inbound-rtp`，多个同向条目求和）。
     *
     * ── 为什么要加这两格（2026-10-11）────────────────────────────────────────────
     * [sending] / [receiving] 判的都是「音轨**对象**在不在」，而对象在 ≠ 有字节到达：
     * 真机（小米平板 7S Pro / Android 17）上 `可听可说` 曾经与「一个字都听不见」
     * 同时成立 —— `receiving` 只证明「有一个对端音轨对象」，`sending` 只证明
     * 「发送端上挂着一条本机音轨」，两者都不证明 RTP 包里真的走了音频。
     * 有了这两个计数，「可听可说」才第一次有证据：数字在涨就是在通。
     *
     * `null` = 还没读到（还没采集过 stats，或这条链路上没有音频 RTP 流）。
     */
    val audioPacketsSent: Long? = null,
    val audioPacketsReceived: Long? = null,

    /**
     * 通话音量（对端声音）0f..1f，1f = 原始音量。
     *
     * **与视频音量是两条独立的通路**，这是 1.7 的硬要求：
     *  - 这一条落到 WebRTC 远端 `AudioTrack` 的增益上，走系统的**通话**流
     *    （`USAGE_VOICE_COMMUNICATION`，见 `RtcEngine.createAudioDeviceModule`）；
     *  - 视频音量落到 ExoPlayer 的输出上，走系统的**媒体**流（见 `core:media` 的
     *    `PlaybackTarget.volume`）。
     * 两条流在系统层就是分开的，所以「谁嫌片子吵就调片子、谁嫌对方声音小就调通话」
     * 不会互相拖动 —— 这比在同一个流上做两次衰减要干净得多。
     */
    val volume: Float = DEFAULT_VOLUME,

    /** 当前音频输出设备（人类可读，如「本机扬声器」）。未通话时为 [UNKNOWN]。 */
    val outputDevice: String = UNKNOWN,

    /**
     * 当前音频**输入**设备（人类可读，如「本机麦克风（系统路由）」）。未通话时为 [UNKNOWN]。
     *
     * §14.4 的最后一条要求「在真机上确认 WebRTC 实际选中的输入设备」—— 这一格就是那句话
     * 的落点。**请求与真值是分开印的**，别混（2026-10-11 加输入侧干预之后）：
     *  - `…（系统路由）` = 从系统正在进行的录音配置里读到的**真值**；
     *  - `…（系统路由；已按在本机麦克风）` = 真值就是本机麦克风，干预**生效了**；
     *  - `…（系统路由；已请求本机麦克风，未生效）` = 请求发出去了、系统没用它 —— **要排查**；
     *  - `…（已请求；系统路由尚未读到）` = 请求已发出，但真值还没登记，只能说请求；
     *  - `…（预计…）` = 音轨刚建起来、系统还没登记录音配置时的**枚举推断**。
     */
    val inputDevice: String = UNKNOWN,

    /**
     * **系统**此刻是否在为本应用采集麦克风 —— 与状态栏那个麦克风指示灯**同一事实**。
     *
     * ── 为什么要单独有这一格（2026-10-14「真停采集」）────────────────────────────
     * 上面几个读数讲的都是**我们自己的模型**：[sending] 读「音轨挂没挂在发送端上」、
     * [inputDevice] 按「我们以为在不在采集」门控。真机实测证明这两件事会脱节：关掉开关后
     * 对端确实听不到了、面板也写着「未开启（只是本机不采集）」，而**系统麦克风一直在采**
     * （绿点常亮，还能挺过一次 `pc.close()` + 新建 PC）。那一刻面板每一格都是 `-`，
     * 恰好把唯一能看见的证据（绿点）留在了屏幕之外 —— 用户报的正是这一条。
     *
     * 所以这一格直接读 `AudioManager.getActiveRecordingConfigurations()`（系统从 API 24 起
     * 只返回**本应用自己**的采集配置），并且**刻意不跟开关门控**：它要能在「我们的模型说
     * 没在采、系统其实在采」时把这句话说出来。
     *
     * ⚠️ 采集的**停止是异步的**（协商落地 → 销毁发送流 → `ADM.StopRecording()`），所以关麦
     * 那一瞬它可能仍是 `true`、几百毫秒后才变 `false`。链路拆掉与关麦之后由
     * `WebRtcPeerLink` 的收尾探针再读几次 —— 别把「正在停」当成「没停」。
     */
    val systemRecording: Boolean = false,

    /**
     * 系统音量索引（媒体 / 通话），形如 `媒体 12/15 ／ 通话 5/10`。未通话时为 [UNKNOWN]。
     *
     * ── 为什么它与「影片音量」「通话音量」两根滑块不是一回事（2026-10-11）─────────
     * 那两根滑块是我们**自己的增益**（ExoPlayer 的 `player.volume` / 远端 `AudioTrack` 的
     * `setVolume`），而这一行是**系统那两条流**的音量索引 —— 音量键改的正是它。两者是
     * 乘起来的，缺了后者就分不开两种「声音小」：
     *  - 滑块 100% + 媒体索引满格 + 声音仍小 ⇒ 衰减在**我们之外**（路由 / 蓝牙绝对音量 /
     *    系统 ducking），该去别处找；
     *  - 媒体索引本身很低 ⇒ 音量就是低的，先按音量键。
     *
     * 真机报「接了蓝牙耳机之后影片声音变小」时，缺这一格就判不了。
     */
    val systemVolumes: String = UNKNOWN,

    /**
     * 通话中限制影片音量（§471 的「通话中限制最大外放音量」兜底开关）。
     *
     * 这是**意图**，与 [requested] / [volume] 一样不随链路生灭复位。它本身不改任何音量 ——
     * 真正把音量压下去的是界面（见 [movieVolumeCeiling]），因为影片音量的唯一真值在
     * 播放器上（`PlaybackTarget.volume`），这里只表达「用户要不要这个兜底」。
     *
     * ── 2026-10-11 起默认**开**（原来默认关）──────────────────────────────────────
     * 原来默认关，理由是「别替用户做决定」。真机反馈证明那个理由站不住：用户开着外放、
     * 开着麦、放着片，回声当场就发生 —— 而**没有人会想到去一个默认关着的开关上找原因**。
     * 兜底开关的全部价值就在「它自己会生效」上；默认开、另给一个关掉的入口，才是 §471
     * 要的形态。
     */
    val limitMovieVolume: Boolean = true,

    /**
     * 影片此刻是不是在从**本机扬声器**外放（§471 兜底开关的第三个条件）。
     *
     * ── 为什么不复用 [outputDevice] 那一行 ──────────────────────────────────────
     * 那一行说的是**通话**（`USAGE_VOICE_COMMUNICATION`）落在哪，这一格说的是**影片**
     * （`USAGE_MEDIA`）落在哪。两条流在系统层是两条独立策略（`STRATEGY_PHONE` /
     * `STRATEGY_MEDIA`），落点可以不同 —— 而回声是**影片**这一路被麦克风收回去造成的，
     * 所以判据必须取影片那一路。接了耳机时媒体会去耳机（声音进不了麦克风），
     * 此时压影片音量纯属白扣 —— 这一格就是用来免掉那次白扣的。
     *
     * 判据是「一台用户真的接上的输出设备都没有」（`isLoudspeakerMediaRoute`）。
     * 链路没跑起来时它是 `false` —— 与其余几个设备读数同口径。
     */
    val movieOnLoudspeaker: Boolean = false,

    /**
     * 回声抑制量（ERLE，dB）= `getStats()` 里 `media-source`（audio）的
     * `echoReturnLossEnhancement`。
     *
     * ── 为什么非要有这一格 ──────────────────────────────────────────────────────
     * 它是**唯一**能回答「AEC 到底在不在干活」的读数：0 dB 上下 = 一点都没消；
     * 15 dB 以上 = 在正常工作。没有它，「对端说听到回声」的两种成因（AEC 根本没生效 /
     * 扬声器声压太大把线性回声消除压垮）在屏幕上**完全同形**，只能靠猜 ——
     * 而猜错的方向是「去改一套本来没坏的配置」。
     *
     * `null` = 还没读到（没通话、stats 还没回来，或这一版库里没有这个字段）。
     * ⚠️ **对端没出声时它也会接近 0** —— 没有回声可消，不等于 AEC 坏了。
     */
    val echoReturnLossEnhancementDb: Double? = null,

    /** 最近一次音频相关失败。null = 没有。成功切换通话开关时会被清掉。 */
    val error: String? = null,
) {
    /**
     * 影片音量此刻允许的最大值（§471 的兜底开关落到界面上的那一格）。
     *
     * ── 为什么兜的是**影片**音量，不是通话音量 ──────────────────────────────────
     * 回声的来源是「本机扬声器放出来的声音被本机麦克风收回去」。WebRTC 的 AEC 只消得掉
     * **它自己渲染的那一路**（对端语音，参考信号来自它自己的渲染缓冲），而影片是 ExoPlayer
     * 播的、走另一条输出，AEC 拿不到它的参考信号 —— 这一路**任何软件都消不掉**，
     * 只能把外放声压降下来（评审 §3.6 第 4 条原话：「实际决定 AEC 效果的是外放音量」）。
     * 压通话音量只会变成「听不清对方」，与回声无关，所以不压它。
     *
     * ── 三个条件缺一不可（2026-10-11 改）────────────────────────────────────────
     *  1. [sending] —— **本机真的在发**。只收不发时本机没有任何采集，回声无从产生，
     *     此时压音量纯属白扣。原来用的是 `requested`，它不够精确：开关按下了、但音频 m 行
     *     还没协商出来时麦克风根本没开。
     *  2. [limitMovieVolume] —— 兜底开关（默认开，见那一格）。
     *  3. [movieOnLoudspeaker] —— 影片**真的在从扬声器外放**。接了耳机时声音进不了麦克风，
     *     压它同样是白扣。
     * 三条不齐时是 1f，也就是「什么也不做」—— 这个函数只做单向压制，从不主动把音量调大。
     *
     * 放在这里而不是界面里，是为了能在 JVM 单测里逐格钉住（见 `CallAudioLimitTest`）——
     * 与 `planCallAudio` / `remoteSendingFrom` 同一个理由：这一格错了只会表现为「声音莫名
     * 变小」，而真机上没人会怀疑到它头上。
     */
    fun movieVolumeCeiling(): Float =
        if (sending && limitMovieVolume && movieOnLoudspeaker) MOVIE_VOLUME_LIMIT else 1f

    /**
     * `音频收发` 那一行的文案（诊断页）。
     *
     * 先「收到」后「发出」是刻意的：1.7 真机上要回答的问题就是这个顺序 ——
     * 「我这边听不见」先看收到多少，而不是先看自己发了多少。
     *
     * 计数为 0 时**只在对应方向确实应该有声时**才加注：没在发送时「发出 0 包」是正常的
     * （麦克风没开），给它加注只会制造噪声。而加了注的那两句话都只是**事实**，
     * 不是诊断 —— 比如「对端一个音频包都还没到」既可能是对端没开通话，也可能是
     * 对端那条 m 行是 `recvonly`（后者由 [remoteSending] 那一格点名）。
     */
    fun audioPacketsText(): String {
        if (audioPacketsSent == null && audioPacketsReceived == null) return UNKNOWN
        val counts = "收到 ${audioPacketsReceived ?: UNKNOWN} 包 / 发出 ${audioPacketsSent ?: UNKNOWN} 包"
        val notes = buildList {
            if (receiving && audioPacketsReceived == 0L) add("对端一个音频包都还没到")
            if (sending && audioPacketsSent == 0L) add("本机一个音频包都没发出去")
        }
        return if (notes.isEmpty()) counts else "$counts（${notes.joinToString("；")}）"
    }

    /**
     * `音频处理` 那一行末尾的回声抑制读数（§471 的「回声到底消掉了多少」）。
     *
     * 与 [audioPacketsText] 同一个取向：`-` 与 `0.0 dB` **严格分开** —— 前者是「还没读到」，
     * 后者是「读到了、AEC 一点都没消」。把两者印成同一句话，会让排查走到反方向：
     * 一个是再等等，另一个是该去查 AEC 为什么没生效。
     *
     * 格式固定一位小数、`Locale.ROOT`（与 `formatSpeed` 同一条理由）：真机上的读数会被
     * 逐字抄进验收记录，逗号小数点会让「12,4 dB」与「12.4 dB」看起来像两个不同的读数。
     */
    fun echoSuppressionText(): String {
        val value = echoReturnLossEnhancementDb
        return if (value == null) {
            "回声抑制 $UNKNOWN"
        } else {
            "回声抑制 ${String.format(Locale.ROOT, "%.1f", value)} dB"
        }
    }

    companion object {
        const val UNKNOWN = "-"

        /**
         * 1f = 原始音量。默认**不放大**：远端语音在 1f 时已经是满量程，
         * 再往上加只会削顶。真要更响，用系统音量键调通话流（它与这一条是乘起来的）。
         */
        const val DEFAULT_VOLUME = 1f

        /**
         * 开了 [limitMovieVolume] 时，影片音量被压到的上限。
         *
         * ── 为什么是 40% ──────────────────────────────────────────────────────────
         * §471 记的是「实际决定 AEC 效果的是外放音量」，而平板是 4 麦克风阵列 + 4~6 扬声器
         * （含独立低音单元），声压比手机大。40% 是「台词还听得清、但明显压低声压」的折中，
         * 不是一个测出来的最优值 —— **真机按 §471 用高音量场景实测后可以调**，
         * 调的时候只改这一个常量（界面与单测都读它，不各写一份）。
         */
        const val MOVIE_VOLUME_LIMIT = 0.4f
    }
}

/**
 * 通话音频的命令面。
 *
 * ── 为什么要从 [PeerLink] 里抽出来 ───────────────────────────────────────────
 * `app` 要把「通话」转交给玩法（`CinemaHandler`），而玩法**不该看见整条 [PeerLink]** ——
 * 它不需要 `send` / `onSignal` / `close`，那些是底座的事。抽成这个窄接口之后，
 * 玩法拿到的能力就只有「读状态、开关、调音量」三件。
 *
 * 与 [PeerMessenger] 的形态一致：端口在底座侧声明，实现在装配点接上。
 */
interface CallAudio {

    /** 通话音频状态。粗粒度、电平型，供 UI 直接绑。 */
    val callAudio: StateFlow<CallAudioState>

    /**
     * 开 / 关**本机麦克风接进链路**。
     *
     * `true`：建本机音轨（麦克风开始采集）并挂到音频发送端；
     * `false`：把发送端上的音轨摘掉、释放音轨（采集停止）。
     *
     * ⚠️ `false` **不等于「退出通话」**（2026-10-11 改）：链路与收听都是常驻的，关掉开关
     * 只是本机不再发送 —— 对端的声音照样能听到（见 [CallAudioPlan]）。
     *
     * **调用方必须先拿到 `RECORD_AUDIO`** —— 这里不申请权限（§9.2 把它划给界面）。
     * 顺序上仍然是「配置先于采集」：路由与音频环境在音轨建立之前切好，
     * 与 §9.1「先 `startForeground` 再建 `PeerConnection`」是同一个取向。
     *
     * 幂等：与当前值相同就是空操作。
     */
    fun setCallEnabled(enabled: Boolean)

    /** 设置通话音量 0f..1f（超出会被夹回）。与视频音量互不影响。 */
    fun setCallVolume(volume: Float)

    /**
     * 通话中要不要限制影片音量（§471 的兜底开关）。**默认是开的**（2026-10-11 改），
     * 这个入口是用来**关掉**它的。
     *
     * 幂等：与当前值相同就是空操作。**它不改音量本身** —— 界面按
     * [CallAudioState.movieVolumeCeiling] 把「影片音量」的上限压下来，并在那一刻把已经
     * 高于上限的音量写低一次。通话结束后**不会自动弹回**（突然变响比一直偏小更难接受，
     * 用户自己拖回来）。
     */
    fun setMovieVolumeLimit(enabled: Boolean)
}

/**
 * 「通话开关的意图 + 链路此刻具备的条件」→「这一次同步该做什么」。
 *
 * ── 为什么把它抽成一个纯函数 ────────────────────────────────────────────────
 * 这是 1.7 里唯一一条**有判断、会出错、而错了只会静默**的逻辑：真机上很难观察到
 * 「麦克风在采集但音频没有去处」（耗电与隐私问题都不会报错），也很难观察到
 * 「路由切了但没切回来」（症状是整机音量键去调通话流）。抽成纯函数之后它能在
 * JVM 单测里逐格钉住（见 `CallAudioPlanTest`），与 §6 的算法同一个理由。
 */
data class CallAudioPlan(
    /** 建本机音轨（= 开始采集）。 */
    val createLocalTrack: Boolean,
    /** 把本机音轨挂到音频发送端。 */
    val attachLocalTrack: Boolean,
    /** 切入通话音频路由（把通话音频的落点摆到扬声器 / 耳机上，见 `CallAudioRouting.enter`）。 */
    val enterRouting: Boolean,
    /** 让对端声音出声（音量取用户设定值；否则为 0）。 */
    val playRemote: Boolean,
)

/**
 * @param requested 用户按下的通话开关 —— 语义是**本机麦克风要不要接进链路**，不是「要不要通话」。
 * @param hasAudioTransceiver 本端已经拿到音频的 `RtpTransceiver`（音频 m 行已协商）。
 * @param hasRemoteTrack 已经收到对端音轨。
 *
 * ── 开关的语义（2026-10-11 改）───────────────────────────────────────────────
 * 真机报过「A 开通话、B 不开，B 就听不到 A」。那是旧口径的错：`playRemote` 也要求
 * `requested`，于是 B 一关开关就把**收听**也关掉了。现在开关只管**本机麦克风要不要接进
 * 链路**：链路（音频 m 行）本来就是常驻的，谁开着开关谁就在发，而**谁都能听见对方**。
 * 这也正是界面按钮旁那句注解一直承诺的口径：「关掉只是本机不采集，对端的声音照样能听到。」
 *
 * ⚠️ `hasAudioTransceiver` 这一格是**必须**的，不能简化成 `requested`：
 * answer 方在对端 offer 到达之前没有音频发送端，此刻若照 `requested` 去建音轨，
 * 麦克风就开始采集了 —— 而采出来的音频**没有任何去处**，对端听不到、界面也看不出，
 * 只有耗电和那个一直亮着的麦克风指示器。所以「没有发送端就什么都不做」。
 */
fun planCallAudio(
    requested: Boolean,
    hasAudioTransceiver: Boolean,
    hasRemoteTrack: Boolean,
): CallAudioPlan {
    // 本机这一侧：开关按下了、发送端也在，才开始采集。
    val live = requested && hasAudioTransceiver
    return CallAudioPlan(
        createLocalTrack = live,
        attachLocalTrack = live,
        // 路由：本机在发**或**对端在放，都要切。只收不发时同样得切 —— 否则对端的声音会落到
        // 系统的「默认通信设备」上（有听筒的机型就是听筒），两台设备摆在一起等于没声音。
        enterRouting = live || hasRemoteTrack,
        // 对端声音与开关无关：只收听不发言也该听得见（见上面的语义说明）。
        playRemote = hasRemoteTrack,
    )
}

/**
 * 链路此刻的**观测读数**（都来自 `getStats()`，都不是意图）。
 *
 * 三个量的 `null` 都表示「还没读到」，与 `0` / `0.0` 严格分开 —— 见
 * [CallAudioState.audioPacketsText] 与 [CallAudioState.echoSuppressionText] 的说明。
 */
data class AudioReadouts(
    val packetsSent: Long? = null,
    val packetsReceived: Long? = null,
    val echoReturnLossEnhancementDb: Double? = null,
)

/**
 * 按 [plan] 决定这几个读数**此刻该不该显示**。
 *
 * ── 为什么需要这个函数（2026-10-13 第八轮真机缺陷）────────────────────────────
 * 原来这三个读数共用一把锁：`enterRouting`（= 本机在发**或**对端在放）。可其中两个
 * （`发出 N 包`、`回声抑制`）讲的是**本机采集**这一路 —— 开关关着、本机根本没在采集时，
 * 它们照样把上一次通话留下的数字显示出来。真机症状是同一屏里自相矛盾：
 *
 * ```
 * 通话状态  未开启（只是本机不采集；对端的声音照样能听到）
 * 音频输入  -
 * 音频收发  收到 760 包 / 发出 773 包      ← 本机没在采集，却写着「发出 773 个包」
 * ```
 *
 * 用户据此判定「没点开启通话，通话却自动开启」—— 面板撒的谎恰好指向一个不存在的故障，
 * 而真相只是「关掉开关那一刻起，这一格就该空了」。修法是把锁按**每一格自己回答的问题**
 * 分开：
 *  - `发出` / `回声抑制`：问的是本机采集 ⇒ 门控在 [CallAudioPlan.createLocalTrack]；
 *  - `收到`：问的是对端在不在发 ⇒ 门控在 [CallAudioPlan.enterRouting]（链路在跑就成立，
 *    只收听不发言时同样要能看见）。
 *
 * ⚠️ 这里只做**显示**判断，**不**在关掉开关时把累计值清零：它们是**这一条链路**的累计
 * 计数（见 `WebRtcPeerLink.audioPacketsSent` 的 KDoc），关开关不等于拆链路，清掉会丢掉
 * 「这一场里到底发过多少」这个真实信息 —— 而那个数正是判断「麦克风是不是被偷偷开过」
 * 的第一手证据。
 */
fun audioReadoutsForDisplay(plan: CallAudioPlan, raw: AudioReadouts): AudioReadouts =
    AudioReadouts(
        packetsSent = if (plan.createLocalTrack) raw.packetsSent else null,
        packetsReceived = if (plan.enterRouting) raw.packetsReceived else null,
        echoReturnLossEnhancementDb =
        if (plan.createLocalTrack) raw.echoReturnLossEnhancementDb else null,
    )

/**
 * 把音频收发端的「当前方向」（**本端视角**）翻成「对端在不在往本端发」。
 *
 * ── 为什么要单独抽出来 ──────────────────────────────────────────────────────
 * 这是 1.7 里唯一一条**能看出「对端到底发没发」**的信号，而它错了只会表现为
 * 「一直听不见」—— 真机上没有别的观测点。抽成纯函数之后能在 JVM 单测里逐格钉住
 * （见 `CallAudioDirectionTest`），与 [planCallAudio] 同一个理由。
 *
 * ── 方向是怎么算出来的 ──────────────────────────────────────────────────────
 * libwebrtc 生成 answer 时，每一条 m 行的方向是
 * `intersection(本端 transceiver 的 direction, reverse(对端 offer 的方向))`
 * （上游 `pc/sdp_offer_answer.cc` 里那条 `RtpTransceiverDirectionIntersection` 判据）。
 * 于是「本端视角的 `currentDirection` 含 `RECV`」==「对端在发」：
 *  - `SEND_RECV` / `RECV_ONLY` ⇒ 对端在发；
 *  - `SEND_ONLY` / `INACTIVE` / `STOPPED` ⇒ 对端没在发。其中 `SEND_ONLY` 正是
 *    「对端那条 m 行协商成了 `recvonly`」的样子（也是 1.7 首版在 answer 方的样子，
 *    成因见 `WebRtcPeerLink.applyAudioDirection`）。
 *
 * 与 [localSendingFrom] 是一对镜像：那一格问**本端**发不发（判「关麦真的停了吗」），
 * 这一格问**对端**发不发（判「为什么听不见」）。两格读的是同一个 `currentDirection`，
 * 但**同一个值在两格不互补**（`SEND_RECV` 两边都是 true、`INACTIVE` 两边都是 false）——
 * 「对端视角」是本端方向的 reverse。改一张表就要对着另一张看一遍
 * （`CallAudioNegotiationTest` 里有一条专门对这两张表）。
 */
fun remoteSendingFrom(currentDirection: AudioDirection?): Boolean? =
    when (currentDirection) {
        null -> null
        AudioDirection.SEND_RECV,
        AudioDirection.RECV_ONLY -> true
        AudioDirection.SEND_ONLY,
        AudioDirection.INACTIVE -> false
    }

/**
 * 音频 m 行该用的方向：开关开着 = `SEND_RECV`，关着 = **`RECV_ONLY`**。
 *
 * ── 为什么关麦要动方向，而不是只摘音轨（2026-10-14，用户拍板方案 B）────────────
 * 摘音轨（`RtpSender.setTrack(null)`）只走到 `ClearAudioSend()` → 通道层
 * `SetAudioSend(ssrc, false, …)` → **`MuteStream`（静音）**：对端听不到了，但
 * `AudioSendStream` 还活着、`AudioState::sending_streams_` 里那一条还在，
 * `ADM.StopRecording()` 永远不被调用 —— 绿点因此常亮。真正会销毁发送流的是**协商结果**：
 * 本地描述里那条音频 m 行一旦不含我们的 SSRC，`channel.cc` 的 `UpdateLocalStreams_w`
 * 就 `RemoveSendStream(ssrc)` → `AudioSendStream::Stop()` → `RemoveSendingStream()` →
 * 删空则 `StopRecording()`（上游源码逐段见《技术方案评审》§5.2）。
 *
 * 所以：关麦 = 方向改成 `recvonly` + 把这次变化**协商出去**；开麦 = 改回 `sendrecv` 再来一轮。
 *
 * ⚠️ 关麦用 `RECV_ONLY` 而**不是** `INACTIVE`：链路常驻、收听常驻是已定案的语义 ——
 * 只收不发时对端的声音照样要能听到（见 [planCallAudio] 的 `playRemote`）。
 */
fun audioDirectionFor(sendEnabled: Boolean): AudioDirection =
    if (sendEnabled) AudioDirection.SEND_RECV else AudioDirection.RECV_ONLY

/**
 * 协商出来的方向里**本端在不在发**。`null` = 还没有协商结果（音频 m 行还没协商出来）。
 *
 * ── 为什么看协商结果，而不是看音轨挂没挂 ────────────────────────────────────
 * 「关麦」这件事的完成标志是**发送流被销毁**，而它由协商结果驱动（见 [audioDirectionFor]）：
 * 音轨挂没挂只说明我们**请求**了什么，`currentDirection` 才说明协商**成了**什么。
 * 两者不一致时（真机上出现过：音轨摘了、采集还在跑）要按这一格去补一轮协商。
 */
fun localSendingFrom(currentDirection: AudioDirection?): Boolean? =
    when (currentDirection) {
        null -> null
        AudioDirection.SEND_RECV,
        AudioDirection.SEND_ONLY -> true
        AudioDirection.RECV_ONLY,
        AudioDirection.INACTIVE -> false
    }

/**
 * 「此刻要不要为音频方向再发起一轮协商」。
 *
 * 三个条件缺一不可：
 *  ① **有协商结果** —— `null` 表示首轮还在飞；那一轮自己会带上当前意图（offer 按
 *     [audioDirectionFor] 建、answer 在回之前现设方向），此刻再发起只会多一轮；
 *  ② **结果与意图不一致** —— 比的是「本端在不在发」（[localSendingFrom]）而不是
 *     方向是否逐字相等：对端关着麦时本端协商成 `SEND_ONLY` 是**正常**的，
 *     那不是要修的偏差（照字面比会对着一个修不好的状态来回刷协商）；
 *  ③ **这个结果还没为它发起过**（[initiatedFor]）—— 同一个结果只反应一次，
 *     对端不配合时不会来回刷。
 */
fun audioRenegotiationDue(
    wantSend: Boolean,
    negotiated: AudioDirection?,
    initiatedFor: AudioDirection?,
): Boolean {
    val sending = localSendingFrom(negotiated) ?: return false
    return wantSend != sending && initiatedFor != negotiated
}
