package com.cinemahall.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 统一信封（《技术方案评审》§5.1）。
 *
 * `type` 的格式是 `<域>.<动作>`，例如 `cinema.play` / `rtc.ice` / `sys.ping`。
 * 字段名照任务书 §5.2 原样，不做改名。
 *
 * [seq] 语义：发送方在**一次连接会话内**单调递增；重连后 `sessionEpoch` 变化，
 * 接收方以此判断是否重置期望序号。DataChannel 是可靠有序模式，不会乱序，
 * 所以它主要用于**丢弃重连后到达的过期消息**与诊断。
 *
 * [sentAtMs] 用发送端单调时钟（`SystemClock.elapsedRealtime()`），不是墙上时钟 ——
 * 两端的墙钟不可信，同步算法只认单调时间差（§6.1）。
 *
 * ── 阶段二 v2：多了 [to]（契约 §4）────────────────────────────────────────────
 * `rtc.*` **必填**（服务端只单播给那一个座位，缺 ⇒ `BAD_PAYLOAD`）；`cinema.*` 里
 * `hello` / `state_snapshot` / `media_status` 带它，广播类（`play` / `pause` / `seek` /
 * `speed` / `subtitle` / `heartbeat` / `control_policy`）可空。
 *
 * 接收方只有一条判据：**`to != null && to != 自己` ⇒ 丢弃，不记错**（契约 §4）。
 * 默认 null 让 v1 服务端的报文（根本没有这个键）照常解析 —— 这就是
 * `PROTOCOL_V2=0` 双栈兼容的物理基础，不需要第二个信封类型。
 */
@Serializable
data class Envelope(
    val type: String,                 // "<域>.<动作>"，例：cinema.play / rtc.ice / sys.ping
    val seq: Long,                    // 发送方单调递增序号
    // 给了默认值：信封里的 roomId 语义上就是可空的，给了默认值之后
    // 「缺少该键」与「该键为 null」都会解成 null，不会抛 MissingFieldException。
    val roomId: String? = null,
    val from: String,                 // userId
    /** 目标 userId。null = 广播（§4）。缺省让 v1 报文照常解析。 */
    val to: String? = null,
    val sentAtMs: Long,               // 发送端单调时钟（elapsedRealtime）
    val payload: JsonObject? = null,
)
