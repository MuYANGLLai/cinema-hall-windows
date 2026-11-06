package com.cinemahall.core.session

import com.cinemahall.core.media.MediaSignature
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 播放同步的八个载荷（《技术方案评审》§5.3 的消息表）。
 *
 * 字段名照 §5.3 **原样**，一个不改、一个不多（唯一的一处加工见文件末尾
 * 「§6.1 的 t1/t2 落在哪两个字段上」）。`speed` 是后加的第 8 条，
 * 加它的理由见 [CinemaSpeed]。
 */
object CinemaPlayback {

    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_SEEK = "seek"
    const val ACTION_SPEED = "speed"
    const val ACTION_HEARTBEAT = "heartbeat"
    const val ACTION_SYNC_ACK = "sync_ack"
    const val ACTION_STATE_SNAPSHOT = "state_snapshot"
    const val ACTION_SUBTITLE = "subtitle"
    const val ACTION_CONTROL_POLICY = "control_policy"
    const val ACTION_MEDIA_STATUS = "media_status"

    const val TYPE_PLAY = "cinema.play"
    const val TYPE_PAUSE = "cinema.pause"
    const val TYPE_SEEK = "cinema.seek"
    const val TYPE_SPEED = "cinema.speed"
    const val TYPE_HEARTBEAT = "cinema.heartbeat"
    const val TYPE_SYNC_ACK = "cinema.sync_ack"
    const val TYPE_STATE_SNAPSHOT = "cinema.state_snapshot"
    const val TYPE_SUBTITLE = "cinema.subtitle"
    const val TYPE_CONTROL_POLICY = "cinema.control_policy"
    const val TYPE_MEDIA_STATUS = "cinema.media_status"

    /** 动作名 → 完整 type。日志与诊断页用它，避免各处手写字符串。 */
    fun typeOf(action: String): String = "cinema.$action"

    /**
     * §5.3 里属于播放同步的**八个**动作。
     *
     * 给 `CinemaHandler.onMessage` 用来按动作分流：玩法域里除了这八个还有 1.5 的 `hello`，
     * 早先的写法是在每个 `when` 分支里手抄一遍常量列表 —— 那样漏一个的症状是
     * 「某一类消息静默地不生效」，而它看起来完全像对端没发。
     *
     * 把 `speed` 加进这个集合也顺带给了**跨版本兼容**：旧版 APK 的这个集合里没有它，
     * 收到就落到 `when` 之外、静默忽略，于是「一端旧一端新」表现为「倍速没跟过来」，
     * 而不是一次解不出来的载荷。
     */
    val ACTIONS: Set<String> = setOf(
        ACTION_PLAY,
        ACTION_PAUSE,
        ACTION_SEEK,
        ACTION_SPEED,
        ACTION_HEARTBEAT,
        ACTION_SYNC_ACK,
        ACTION_STATE_SNAPSHOT,
        ACTION_SUBTITLE,
        // 阶段二新增的两条（契约 §7）。放进同一个集合，分流仍然只有一处：
        // `CinemaHandler.onMessage` 按它把八个播放动作交给引擎，`hello` 自己单独处理。
        ACTION_CONTROL_POLICY,
        ACTION_MEDIA_STATUS,
    )
}

/**
 * `cinema.play` / `cinema.pause` / `cinema.seek` 的共同载荷：`{positionMs, senderClockMs}`。
 *
 * 三条消息的形状在 §5.3 里**完全相同**，所以共用一个数据类而不是写三个 ——
 * 写三个的话，任何一处改动（比如将来加一个 `isPlaying`）都要改三遍，
 * 而漏掉一处的症状是「三种操作里有一种不同步」，最难查的那种。
 */
@Serializable
data class CinemaPosition(
    val positionMs: Long,
    val senderClockMs: Long,
)

/**
 * `cinema.speed`：`{speed}`。**整场倍速**，仍是房主主控（§6.5）。
 *
 * ── 为什么倍速必须是一条广播消息，而不能只改本机 ────────────────────────────
 * §6.2 的目标位置是「锚点 + 本地经过时间 × 速率」外推出来的。若只把本机播放器
 * 设成 2x、时间轴仍按 1x 外推，从端每秒会算出一个「本机超前 1000ms」的**假漂移**，
 * 直接吃满硬阈值 → 每几百毫秒硬 seek 一次，画面碎成幻灯片。所以倍速一变，
 * 接收方的 `MasterTimeline` 也必须跟着改斜率 —— 这条消息就是那个「跟着」。
 *
 * ── 为什么单独一条，而不是塞进 heartbeat ────────────────────────────────────
 * 心跳 2.5s 一条，塞进去意味着「改倍速后最多 2.5s 从端还在按旧速率外推」，
 * 这段时间足够攒出一次硬 seek。单独一条能立刻生效（心跳那条路只负责持续刷新
 * 位置与播放态）。
 *
 * ── 载荷为什么只有 speed，没有 senderClockMs ────────────────────────────────
 * 倍速的生效时刻由**接收方自己的时钟**决定：它收到就改斜率，并把锚点重设到
 * 「按旧斜率外推出来的当前位置」，所以不需要发送方的时刻。加一个用不上的
 * 时钟字段，只会让人以为它参与了某种补偿计算。
 */
@Serializable
data class CinemaSpeed(
    val speed: Float,
)

/** `cinema.heartbeat`：`{isPlaying, positionMs, senderClockMs, probeId?}`。 */
@Serializable
data class CinemaHeartbeat(
    val isPlaying: Boolean,
    val positionMs: Long,
    val senderClockMs: Long,
    /**
     * 定向探测的回执编号（契约 §7，阶段二）。null = 定时广播的那条。
     *
     * ── 为什么必须有它（多人房才暴露的问题）────────────────────────────────────
     * 从端每 2.5 秒广播一次心跳、每收到一条 `sync_ack` 再回一条心跳。两人房里这两条
     * 心跳是一一对应的；六人房里房主会**同时**回五条，而从端只记着「我发过一次探测」——
     * 于是「哪条心跳是我那次探测的应答」全靠运气，RTT 量出来的是一堆混合样本，
     * 时钟估计直接变成噪声。带上同一个 `probeId` 之后，从端只把**配对的那条**
     * 用于时钟采样，其余心跳照常锚定位置（锚定与采样是两件事，见 §6.1）。
     */
    val probeId: Long? = null,
)

/**
 * `cinema.sync_ack`：`{ackSeq, clientPositionMs, driftMs, rttMs}`。
 *
 * 它是 §5.3 里**唯一**由从端主动发出的同步消息，身兼两职（同一个方向、同一个节拍，
 * 拆成两条只会让两端的状态更难对齐）：
 *  - **漂移上报**：§9.3 的诊断页要的 `driftMs` 历史来自它（原文点名 `cinema.sync_ack`）；
 *  - **时钟探测的发起**：这条消息发出的一刻就是从端记录的 `t1`（见文件末尾说明）。
 */
@Serializable
data class CinemaSyncAck(
    val ackSeq: Long,
    val clientPositionMs: Long,
    val driftMs: Long,
    val rttMs: Long,
    /** 本次探测的自增编号（契约 §7）。房主必须把它原样带回那条定向心跳。 */
    val probeId: Long? = null,
)

/**
 * `cinema.state_snapshot`：`{isPlaying, positionMs, mediaSignature, seq, sessionEpoch, playbackSpeed}`。
 *
 * ── `playbackSpeed` 为什么必须有默认值 ──────────────────────────────────────
 * 快照的用途是「对端中途重装 App / 链路断过一次之后的全量恢复」，而倍速是
 * **没法从位置推出来**的状态：位置只能看出「现在在哪」，看不出「以什么速率在走」。
 * 不带它，从端就会用一个错的斜率外推，症状是「重连后十几秒内画面反复硬跳」。
 *
 * 默认值 `1.0f` 是给**旧版对端**留的：老版本的载荷里没有这个字段，
 * 解码时有默认值才能读到一条能用的快照（而不是整条被当成坏消息丢掉）。
 * 反过来，新版载荷多一个字段对老版本无害 —— 编解码统一开着
 * `ignoreUnknownKeys`。
 */
@Serializable
data class CinemaStateSnapshot(
    val isPlaying: Boolean,
    val positionMs: Long,
    val mediaSignature: MediaSignature,
    val seq: Long,
    val sessionEpoch: Long,
    val playbackSpeed: Float = 1.0f,
    /**
     * 当前控制策略（契约 §7：快照要带 `controlPolicy`，让迟到者一进来就知道权限）。
     *
     * 默认 null 是给旧包的：没有它时按「房主独占」解释（`defaultAllow = false`），
     * 那正好是旧包唯一可能的行为（旧版本里从端根本没有控制权）。
     */
    val controlPolicy: CinemaControlPolicy? = null,
)

/**
 * `cinema.control_policy`（契约 §7）：`{defaultAllow, overrides: {userId: Boolean}}`。
 *
 * `canControl(user) = user == 房主 || (在看 && (overrides[user] ?: defaultAllow))`。
 * 真值表见契约 §7；实现与用例在 `ControlPolicyTable`。
 *
 * `overrides` 用 `Map<String, Boolean>` 而不是 `Map<UserId, Boolean>`：`UserId` 是
 * inline value class，序列化器要额外声明，而这条载荷的键本来就必须是字符串。
 */
@Serializable
data class CinemaControlPolicy(
    val defaultAllow: Boolean = false,
    val overrides: Map<String, Boolean> = emptyMap(),
)

/**
 * `cinema.media_status`（契约 §7）：房主 → **某个成员**的定向结论。
 *
 * - `matched=false` ⇒ 成员停播、显示旁听横幅；
 * - `matched=true, watching=false` ⇒ 等待房主开播；
 * - `matched=true, watching=true` ⇒ 进入同步组。
 *
 * [reason] 是给人看的一句话（哪一项不一致），不参与判据。
 */
@Serializable
data class CinemaMediaStatus(
    val matched: Boolean,
    val watching: Boolean,
    val reason: String? = null,
)

/** `cinema.subtitle`：`{trackIndex}`。 */
@Serializable
data class CinemaSubtitle(
    val trackIndex: Int,
)

object CinemaPlaybackCodec {

    /** 与 `CinemaHelloCodec` / `MediaSignatureCodec` 同一取向：未知字段忽略，坏消息不抛。 */
    private val json = Json { ignoreUnknownKeys = true }

    fun encodePosition(value: CinemaPosition): JsonObject =
        encode(CinemaPosition.serializer(), value)

    fun decodePosition(payload: JsonObject?): CinemaPosition? =
        decode(CinemaPosition.serializer(), payload)

    fun encodeSpeed(value: CinemaSpeed): JsonObject =
        encode(CinemaSpeed.serializer(), value)

    fun decodeSpeed(payload: JsonObject?): CinemaSpeed? =
        decode(CinemaSpeed.serializer(), payload)

    fun encodeHeartbeat(value: CinemaHeartbeat): JsonObject =
        encode(CinemaHeartbeat.serializer(), value)

    fun decodeHeartbeat(payload: JsonObject?): CinemaHeartbeat? =
        decode(CinemaHeartbeat.serializer(), payload)

    fun encodeSyncAck(value: CinemaSyncAck): JsonObject =
        encode(CinemaSyncAck.serializer(), value)

    fun decodeSyncAck(payload: JsonObject?): CinemaSyncAck? =
        decode(CinemaSyncAck.serializer(), payload)

    fun encodeStateSnapshot(value: CinemaStateSnapshot): JsonObject =
        encode(CinemaStateSnapshot.serializer(), value)

    fun decodeStateSnapshot(payload: JsonObject?): CinemaStateSnapshot? =
        decode(CinemaStateSnapshot.serializer(), payload)

    fun encodeSubtitle(value: CinemaSubtitle): JsonObject =
        encode(CinemaSubtitle.serializer(), value)

    fun decodeSubtitle(payload: JsonObject?): CinemaSubtitle? =
        decode(CinemaSubtitle.serializer(), payload)

    fun encodeControlPolicy(value: CinemaControlPolicy): JsonObject =
        encode(CinemaControlPolicy.serializer(), value)

    fun decodeControlPolicy(payload: JsonObject?): CinemaControlPolicy? =
        decode(CinemaControlPolicy.serializer(), payload)

    fun encodeMediaStatus(value: CinemaMediaStatus): JsonObject =
        encode(CinemaMediaStatus.serializer(), value)

    fun decodeMediaStatus(payload: JsonObject?): CinemaMediaStatus? =
        decode(CinemaMediaStatus.serializer(), payload)

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        json.encodeToJsonElement(serializer, value).jsonObject

    /** 解析失败返回 null —— 对端发来一条坏消息不该打断本机的接收链路（§4.2 的同一条原则）。 */
    private fun <T> decode(serializer: KSerializer<T>, payload: JsonObject?): T? {
        if (payload == null) return null
        return try {
            json.decodeFromJsonElement(serializer, payload)
        } catch (_: Exception) {
            null
        }
    }
}

/*
 * ── §6.1 的 t1/t2 落在哪两个字段上（这里曾经看起来像是规格缺了一条消息）────
 *
 * §6.1 要求「发起方记录 t1，发出带 t1 的探测；对端回 SYNC_ACK，携带 t1 与 t2」，
 * 但 §5.3 的消息表里**没有**探测消息。查过之后确认：不需要新增，因为 §6.1 的
 * 三个时刻在两个方向的既有消息上都能取到 ——
 *
 *   t1 = 从端发出 `cinema.sync_ack` 的那一刻。它是**从端自己的**单调时钟读数，
 *        所以根本不需要对端回传（信封的 `sentAtMs` 走的是同一个时基，§5.1）。
 *        对端把 t1 回传的唯一用途是让发起方知道自己的 t1，而发起方本来就记着。
 *   t2 = 房主那条 `cinema.heartbeat` 的 `senderClockMs`。
 *   t4 = 从端收到那条 heartbeat 的时刻。
 *
 * 于是 §6.4 里 heartbeat 那句「重设锚点（**这同时也在持续刷新 rttHalf 与时钟 offset**）」
 * 就是字面成立的：刷新发生在 heartbeat 的入站处理里。
 *
 * 为什么让房主「收到 sync_ack 就立刻回一条 heartbeat」，而不是等它自己的定时器：
 * 等定时器的话，量出来的 RTT 里绝大部分是「定时器还差多久到点」，与路径延迟无关，
 * 时钟估计会变成一串噪声。代价是会多出一倍心跳消息（2.5s 内 2 条），可以接受。
 */
