package com.cinemahall.core.protocol

import com.cinemahall.core.model.UserId
import kotlinx.serialization.json.JsonObject

/**
 * **接缝 2 的反向**：玩法把一条 `cinema.*` 消息交给底座，由底座送进对端的 DataChannel。
 *
 * [PlayModeHandler] 管的是「底座 → 玩法」（收），本接口管「玩法 → 底座」（发）。
 * 没有它，玩法就只能自己去碰 `core:rtc` 的 `PeerLink`，于是「谁是底座、谁是玩法」
 * 立刻糊掉 —— 而 §4.2 划这条线，正是为了将来加第二个玩法时不用动底座。
 *
 * ── 为什么只给 `type` 与 `payload` ──────────────────────────────────────────
 * [Envelope] 里另外四个字段全是**底座才知道的事**：`seq` 是连接会话内的单调序号、
 * `from` 是本机 userId、`roomId` 是当前房间、`sentAtMs` 是单调时钟。让玩法自己填，
 * 就要把 userId 塞进玩法、把单调时钟的取法复制一份，还要处理「重连后序号归零」——
 * 这些都与 `Signaler.send(type, payload)` 的分工完全一致，只是换了一条通道。
 */
interface PeerMessenger {

    /**
     * 尽力而为地发一条：链路没通时返回 false，**不排队、不抛异常、不假装成功**。
     *
     * 不排队的理由与 `PeerLink.send` 相同：消息的时效性由玩法负责（播放同步靠
     * 持续心跳自愈，而不是靠补发一条 3 秒前的 `cinema.play`）。替它排队会让
     * 「现在发不出去」这个事实被藏起来，而藏起来之后，一个卡住的链路看起来
     * 就和「对端不动」一模一样。
     *
     * ── [to]：定向还是广播（契约 §4 / §7，阶段二 v2）──────────────────────────
     * `cinema.*` 里既有定向也有广播，判断权在**玩法**（它才知道这条消息是
     * 「只回给向我探测的那个人」还是「放给所有人」），底座只负责如实填进信封：
     *  - [to] 非 null ⇒ 只发给这一个座位，信封带 `to`；
     *  - [to] 为 null（缺省）⇒ 广播给房间里**所有**已建链的对端，信封 `to` 缺省。
     *
     * 所以缺省值 null 的语义是「广播」而**不是**「发给唯一那个对端」——
     * 两人间里两者等价，多人间里差别就是「只回给 A」还是「A、B、C 都收到」。
     * 这个默认值选 broadcast 是有依据的：§4 把广播类消息（`play` / `pause` / `seek` /
     * `speed` / `subtitle` / `heartbeat` / `control_policy`）列为 `to` 可空，
     * 而定向量只有三条（`hello` / `state_snapshot` / `media_status`）。
     */
    fun send(type: String, payload: JsonObject? = null, to: UserId? = null): Boolean
}