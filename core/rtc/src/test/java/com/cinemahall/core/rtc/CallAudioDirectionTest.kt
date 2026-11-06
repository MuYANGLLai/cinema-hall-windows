package com.cinemahall.core.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [remoteSendingFrom] 的逐格单测（阶段一 1.7，2026-10-11 补）。
 *
 * ── 为什么这一格值得单独测 ──────────────────────────────────────────────────
 * 它是 1.7 里唯一一条**能看出「对端到底发没发」**的信号，而它错了只会表现为
 * 「一直听不见对端」—— 真机上没有第二个观测点，只能靠反复开关通话去猜。
 * 尤其是 `SEND_ONLY` 那一格：它正是「对端那条音频 m 行协商成了 `recvonly`」的样子
 * （1.7 首版 answer 方就是这样，音频一个字节都发不出去），
 * 界面上要据此把排查方向指到对端那台机器上。判反了就会让人一直在这台机器上折腾。
 *
 * ── 为什么这里不需要 native 库 ──────────────────────────────────────────────
 * `remoteSendingFrom` 是纯 Kotlin 函数，`RtpTransceiverDirection` 是 AAR 里一个
 * 普通 Java 枚举（`javap` 确认 `values()` / `fromNativeIndex(int)` 都不是 native，
 * 静态初始化块不碰 JNI），所以这几格能在 JVM 上跑。
 */
class CallAudioDirectionTest {

    @Test
    fun `没有收发端时是未知_而不是「对端没在发」`() {
        // 这一格必须与 false 分开：音频 m 行还没协商出来时，界面该说「等音频通道建立」，
        // 而不是指着一个还不存在的通道说「对端只收不发」。
        assertNull(remoteSendingFrom(null))
    }

    @Test
    fun `协商成双向时对端在发`() {
        assertEquals(true, remoteSendingFrom(AudioDirection.SEND_RECV))
    }

    @Test
    fun `本端只收时对端在发`() {
        // 对端 offer sendonly ⇒ 本端 answer 只能是 recvonly ⇒ 本端视角就是 RECV_ONLY。
        assertEquals(true, remoteSendingFrom(AudioDirection.RECV_ONLY))
    }

    @Test
    fun `协商成只发时对端没在发`() {
        // 这一格就是 1.7 首版 answer 方的样子：对端那条 m 行是 recvonly，
        // 本端只能协商成 SEND_ONLY —— 等多久都不会有对端声音。
        assertEquals(false, remoteSendingFrom(AudioDirection.SEND_ONLY))
    }

    @Test
    fun `停用算对端没在发`() {
        assertEquals(false, remoteSendingFrom(AudioDirection.INACTIVE))
    }

    @Test
    fun `空闲状态默认是未知`() {
        // `CinemaHandler` 在还没接上 RTC 时发的就是这个默认值。它必须是 null ——
        // 否则影厅页在进房之前就会显示「对端只收不发」，而那时连链路都还没有。
        val idle = CallAudioState()
        assertNull(idle.remoteSending)
        assertEquals(false, idle.requested)
        assertEquals(false, idle.sending)
        assertEquals(false, idle.receiving)
    }
}
