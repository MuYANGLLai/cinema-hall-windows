package com.cinemahall.core.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cinemahall.core.rtc.AudioDirection.INACTIVE
import com.cinemahall.core.rtc.AudioDirection.RECV_ONLY
import com.cinemahall.core.rtc.AudioDirection.SEND_ONLY
import com.cinemahall.core.rtc.AudioDirection.SEND_RECV

/**
 * 「关麦真的停采集」这一轮的三个纯判定（2026-10-14，阶段二「真停采集」）。
 *
 * ── 为什么这一组值得逐格钉住 ────────────────────────────────────────────────
 * 这三条判的是**同一件事的三段**：该用什么方向（[audioDirectionFor]）、协商结果算不算
 * 「本端在发」（[localSendingFrom]）、以及要不要为偏差再发起一轮（[audioRenegotiationDue]）。
 * 三段全部**只在真机上表现为「麦克风绿点还亮着」**，而那一刻面板上每一格都是 `-`：
 * 判错任何一段都不会报错，只会让采集一直跑下去 —— 隐私与耗电问题都不发出声音。
 *
 * 尤其是 [audioRenegotiationDue] 的第二条：它比的是「本端在不在发」而**不是**方向逐字
 * 相等。对端关着麦时本端协商成 `SEND_ONLY` 是正常的，照字面比会把一个**修不好**的状态
 * 当成偏差，于是每轮协商都再发起一轮 —— 真机上表现为两端来回刷 offer，而双方都以为
 * 是对方的问题。
 *
 * ── 为什么这里不需要 native 库 ──────────────────────────────────────────────
 * 三个都是纯 Kotlin 函数，`RtpTransceiverDirection` 是 AAR 里一个普通 Java 枚举
 * （与 `CallAudioDirectionTest` 同一条理由）。
 */
class CallAudioNegotiationTest {

    // ── audioDirectionFor：方向怎么跟着开关走 ────────────────────────────────

    @Test
    fun `开麦用双向_关麦用只收`() {
        assertEquals(AudioDirection.SEND_RECV, audioDirectionFor(sendEnabled = true))
        assertEquals(AudioDirection.RECV_ONLY, audioDirectionFor(sendEnabled = false))
    }

    @Test
    fun `关麦不能用停用_收听是常驻语义`() {
        // `INACTIVE` 会连**收听**一起停掉，而「链路常驻、收听常驻」是 1.7 定案的语义：
        // 关掉开关只是本机不采集，对端的声音照样要能听到。用错这一格的真机症状是
        // 「关麦之后连对方说话都听不见了」—— 而那正是用户以为「通话被整个关掉」的来源。
        assertFalse(audioDirectionFor(sendEnabled = false) == AudioDirection.INACTIVE)
        assertTrue(audioDirectionFor(sendEnabled = false) == AudioDirection.RECV_ONLY)
    }

    // ── localSendingFrom：协商结果里本端在不在发 ─────────────────────────────

    @Test
    fun `没有协商结果时是未知_而不是「没在发」`() {
        // 与 `remoteSendingFrom` 那一格同一条理由：首轮协商还在飞时，这一格必须与
        // 「协商成了不发」分开 —— 否则刚进房就会去补一轮协商，而那一轮自己会带上意图。
        assertNull(localSendingFrom(null))
    }

    @Test
    fun `含发送的两个方向都算本端在发`() {
        assertTrue(localSendingFrom(AudioDirection.SEND_RECV) == true)
        // 对端关着麦时本端就是这一格（它 offer recvonly，本端 answer sendonly）。
        // **这不是偏差**：本端确实在发，对端只是不收而已。
        assertTrue(localSendingFrom(AudioDirection.SEND_ONLY) == true)
    }

    @Test
    fun `不含发送的三个方向都算本端没在发`() {
        assertTrue(localSendingFrom(AudioDirection.RECV_ONLY) == false)
        assertTrue(localSendingFrom(AudioDirection.INACTIVE) == false)
    }

    @Test
    fun `与对端那一格是一对镜像`() {
        // 两格读的是同一个 `currentDirection`，但问的是**相反的问题**：本端视角含 `RECV`
        // ⇒ 对端在发；含 `SEND` ⇒ 本端在发。所以**同一个方向值在两格不互补**
        // （`SEND_RECV` 两边都是 true，`INACTIVE` 两边都是 false）—— 互补关系要先把方向
        // 反过来：「对端那条 m 行的方向」正是本端方向的 reverse。
        //
        // 这一条钉的是两张表不许各自漂移：它们各写一份（问的问题不同，合成一个函数反而
        // 读不清），而分岔的症状是「一端说对端没在发、另一端说自己没在发」—— 两端都在
        // 说同一件事，却互相指认对方有问题。
        for (direction in AudioDirection.values()) {
            assertEquals(
                "方向 $direction：对端视角就是本端方向的 reverse",
                remoteSendingFrom(direction),
                localSendingFrom(reverse(direction)),
            )
        }
    }

    /** 「从对端看过去」的方向：只有含 `SEND` / `RECV` 的那两个会互换。 */
    private fun reverse(direction: AudioDirection): AudioDirection =
        when (direction) {
            SEND_RECV -> SEND_RECV
            SEND_ONLY -> RECV_ONLY
            RECV_ONLY -> SEND_ONLY
            INACTIVE -> INACTIVE
        }

    // ── audioRenegotiationDue：要不要为偏差再发起一轮 ────────────────────────

    @Test
    fun `意图与结果一致时不发起`() {
        assertFalse(audioRenegotiationDue(wantSend = true, negotiated = SEND_RECV, initiatedFor = null))
        assertFalse(audioRenegotiationDue(wantSend = false, negotiated = RECV_ONLY, initiatedFor = null))
        // 两端都关着麦时协商成 `INACTIVE` —— 本端本来就不发，不该去补协商。
        assertFalse(audioRenegotiationDue(wantSend = false, negotiated = INACTIVE, initiatedFor = null))
    }

    @Test
    fun `对端关麦造成的只发不算偏差`() {
        // 这一格是**整条判据的关键**：`SEND_ONLY` 的字面方向与 `SEND_RECV` 不同，但
        // 「本端在不在发」的答案是「在」—— 与意图一致。照字面比会得出「要修」，
        // 而对端此刻根本没法收（它没开麦），于是每一轮协商都再发起一轮、永远修不好。
        assertFalse(audioRenegotiationDue(wantSend = true, negotiated = SEND_ONLY, initiatedFor = null))
    }

    @Test
    fun `关麦还没落地时要发起`() {
        // 真机上最常见的那一格：用户按了「关闭通话」，而协商结果还停在 `SEND_RECV`
        // （开关按下时正好有一条 offer 在飞，那条 offer 的方向是按下之前定的）。
        assertTrue(audioRenegotiationDue(wantSend = false, negotiated = SEND_RECV, initiatedFor = null))
    }

    @Test
    fun `开麦还没落地时也要发起`() {
        assertTrue(audioRenegotiationDue(wantSend = true, negotiated = RECV_ONLY, initiatedFor = null))
    }

    @Test
    fun `首轮协商还在飞时不发起`() {
        // `null` = 还没有协商结果。此刻发起会与那一轮撞车（连续两条 offer），
        // 而那一轮自己会带上当前意图（offer 按开关建、answer 在回之前现设方向）。
        assertFalse(audioRenegotiationDue(wantSend = true, negotiated = null, initiatedFor = null))
        assertFalse(audioRenegotiationDue(wantSend = false, negotiated = null, initiatedFor = null))
    }

    @Test
    fun `同一个结果只反应一次`() {
        // 已经为 `SEND_RECV` 这个结果发起过一轮、而对端没有配合（结果没变）：
        // 不许再发起 —— 这是「对端不配合时不来回刷协商」那条约定的落点。
        assertFalse(audioRenegotiationDue(wantSend = false, negotiated = SEND_RECV, initiatedFor = SEND_RECV))
        assertFalse(audioRenegotiationDue(wantSend = true, negotiated = RECV_ONLY, initiatedFor = RECV_ONLY))
    }

    @Test
    fun `结果是新的时还能再发起一次`() {
        // 已经为 `SEND_RECV` 反应过，但这一轮的协商结果变成了 `SEND_ONLY`（对端也动了）：
        // 那是一个**新的**结果，值得再发起一次。
        assertTrue(audioRenegotiationDue(wantSend = false, negotiated = SEND_ONLY, initiatedFor = SEND_RECV))
    }
}
