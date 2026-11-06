package com.cinemahall.core.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 通话面板三个观测读数的**分格门控**单测（2026-10-13 第五轮真机缺陷①）。
 *
 * ── 为什么这一格值得单独钉住 ────────────────────────────────────────────────
 * 它错了**不会有任何症状**：功能一切正常，只是面板上多出一个数字。而真机上那个数字
 * 恰好被读成了「没开通话却在发音频」—— 用户据此判定「通话自己开了」，去查一条并不
 * 存在的故障。这类「读数撒谎」的修复只能靠单测守住：它下一次再错，屏幕上还是那副样子。
 *
 * 三格的锁**刻意不一样**，这是本测要钉住的核心：
 *  - `发出` / `回声抑制` 讲的是**本机采集**这一路 ⇒ 门控在 `createLocalTrack`；
 *  - `收到` 讲的是**对端在不在发** ⇒ 门控在 `enterRouting`（只收听不发言时也要看得见）。
 *
 * ── 为什么这里不需要 native 库 ──────────────────────────────────────────────
 * [audioReadoutsForDisplay] 与 [planCallAudio] 都是纯函数，[CallAudioState.audioPacketsText]
 * 是 `CallAudioState` 上的纯 Kotlin 方法 —— 三者都不碰 `org.webrtc` 类型。
 */
class CallAudioReadoutsTest {

    /** 一份「上一段通话留下的」读数：三格都有值，且都不是 0。 */
    private val raw = AudioReadouts(
        packetsSent = 773L,
        packetsReceived = 760L,
        echoReturnLossEnhancementDb = 42.5,
    )

    // ── 真机那一屏（本次修的那个洞）─────────────────────────────────────────

    @Test
    fun `开关关着但链路在跑时_不显示发出与回声抑制_收到照旧显示`() {
        // 真机原文：
        //   通话状态  未开启（只是本机不采集；对端的声音照样能听到）
        //   音频输入  -
        //   音频收发  收到 760 包 / 发出 773 包
        // 对端音轨在 ⇒ 链路在跑（`enterRouting`），但本机没在采集 —— 所以「发出 773 包」
        // 讲的是**上一次通话**的事，必须收起来。
        val plan = planCallAudio(requested = false, hasAudioTransceiver = true, hasRemoteTrack = true)
        val shown = audioReadoutsForDisplay(plan, raw)

        assertNull(shown.packetsSent)
        assertNull(shown.echoReturnLossEnhancementDb)
        assertEquals(760L, shown.packetsReceived)
    }

    @Test
    fun `真机那一屏渲染出来是发出未知_不是发出 0`() {
        // `null` 与 `0` 在面板上是两句不同的话：`-` 是「这个问题现在不成立」，
        // `0` 是「发过，一个包都没出去」—— 后者会把排查的人带去查网络。
        val plan = planCallAudio(requested = false, hasAudioTransceiver = true, hasRemoteTrack = true)
        val shown = audioReadoutsForDisplay(plan, raw)
        val state = CallAudioState(
            audioPacketsSent = shown.packetsSent,
            audioPacketsReceived = shown.packetsReceived,
        )

        assertEquals("收到 760 包 / 发出 - 包", state.audioPacketsText())
    }

    @Test
    fun `链路还没跑时三格全是未知_上一次通话的残影必须收起来`() {
        // 残影的另一种形态：开关关着、对端音轨也还没到（刚进房那一刻）。三格都不成立。
        val plan = planCallAudio(requested = false, hasAudioTransceiver = false, hasRemoteTrack = false)

        assertEquals(AudioReadouts(), audioReadoutsForDisplay(plan, raw))
    }

    // ── 三个格子的锁互不相同 ────────────────────────────────────────────────

    @Test
    fun `开关开着在发时三格都显示`() {
        val plan = planCallAudio(requested = true, hasAudioTransceiver = true, hasRemoteTrack = true)

        assertEquals(raw, audioReadoutsForDisplay(plan, raw))
    }

    @Test
    fun `开关关着且对端音轨也没到时_收到也不显示_那一刻还没有东西在往本机发`() {
        // 「收到」的锁是 `enterRouting`（本机在发 **或** 对端在放），两者都不成立时这一格
        // 也不成立 —— 它回答的问题是「有没有东西在往本机发」。
        val plan = planCallAudio(requested = false, hasAudioTransceiver = true, hasRemoteTrack = false)
        val shown = audioReadoutsForDisplay(plan, raw)

        assertNull(shown.packetsReceived)
        assertNull(shown.packetsSent)
    }

    @Test
    fun `本机在发时链路就算在跑_收到那格跟着显示`() {
        // 另一半：`enterRouting = 本机在发 || 对端在放`。所以本机在发时链路已经切进了通话
        // 路由，「收到」这一格成立（它有没有数字是另一件事）。这一格钉的是「收到不挂在
        // `hasRemoteTrack` 上」—— 真机上那两格正是靠这个分岔才不再自相矛盾。
        val plan = planCallAudio(requested = true, hasAudioTransceiver = true, hasRemoteTrack = false)
        val shown = audioReadoutsForDisplay(plan, raw)

        assertEquals(773L, shown.packetsSent)
        assertEquals(760L, shown.packetsReceived)
    }

    // ── 门控不是清零 ───────────────────────────────────────────────────────

    @Test
    fun `被收起来的那一格是未知而不是零_传进来的原值一个字都没被改`() {
        // 「关掉开关不清零」是刻意选择（见 `audioReadoutsForDisplay` 的 ⚠️）：累计计数是
        // **这一条链路**的真实信息，也是判断「麦克风是不是被偷偷开过」的第一手证据。
        // 所以门控只能发生在**显示**这一层 —— 传进去的那个对象不许被改。
        val plan = planCallAudio(requested = false, hasAudioTransceiver = true, hasRemoteTrack = true)
        val shown = audioReadoutsForDisplay(plan, raw)

        // ① 显示层：`null`（面板印 `-`），不是 `0L`（面板印「发出 0 包」）。
        assertNull(shown.packetsSent)
        // ② 原对象一个字都没被改。
        assertEquals(773L, raw.packetsSent)
        assertEquals(42.5, raw.echoReturnLossEnhancementDb!!, 0.0)
    }
}
