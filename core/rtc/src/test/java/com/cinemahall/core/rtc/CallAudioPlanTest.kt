package com.cinemahall.core.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [planCallAudio] 的逐格单测（阶段一 1.7）。
 *
 * ── 为什么这条逻辑值得单独测 ──────────────────────────────────────────────────
 * 它是 1.7 里唯一一条**有判断、会出错、而错了只会静默**的逻辑。真机上极难观察到：
 *
 *  - 「麦克风在采集、但采出来的音频没有任何去处」不报错、不崩溃、界面也看不出，
 *    只有耗电和一个一直亮着的麦克风指示器；
 *  - 「对端在说话、本机却不出声」听起来像对端没开，而真相是本机的收听被开关关掉了
 *    （2026-10-11 改之前就是这个错：`playRemote` 也要求 `requested`，真机上的表现是
 *    「A 开通话、B 不开，B 就听不到 A」）；
 *  - 「链路根本没跑，却把整机的外放标志位与通信设备留在我们设的值上」要等退出影厅之后
 *    才会以「别的 App 的声音走错路」的形式暴露出来。
 *
 * 抽成纯函数就是为了让它能在 JVM 上逐格钉住 —— 与 §6 的同步算法同一个理由。
 * 这里**不需要 native 库**：`planCallAudio` 是纯 Kotlin，`CallAudioState` 是纯数据类。
 */
class CallAudioPlanTest {

    // ── 用户没按开关：本机不采集，但收听照旧 ────────────────────────────────

    @Test
    fun `没开通话时本机不采集_但对端的声音照样放出来`() {
        val plan = planCallAudio(
            requested = false,
            hasAudioTransceiver = true,
            hasRemoteTrack = true,
        )

        assertFalse("不建音轨：没按开关就不该碰麦克风", plan.createLocalTrack)
        assertFalse("不挂发送端：挂了就等于把麦克风接进链路", plan.attachLocalTrack)
        assertTrue(
            "路由要切 —— 对端的声音得有地方出来。不切的话它会落到系统的「默认通信设备」上" +
                "（有听筒的机型就是听筒），两台设备摆在一起等于没声音",
            plan.enterRouting,
        )
        assertTrue(
            "对端的声音**与开关无关**：开关只管本机麦克风接不接进链路。" +
                "旧口径在这里要求 `requested`，真机上的表现就是「A 开通话、B 不开，B 就听不到 A」",
            plan.playRemote,
        )
    }

    // ── 按了开关，但链路还没准备好 ──────────────────────────────────────────

    @Test
    fun `按了开关但没有音频发送端时不建音轨_麦克风不空转`() {
        val plan = planCallAudio(
            requested = true,
            hasAudioTransceiver = false,
            hasRemoteTrack = false,
        )

        assertFalse(
            "answer 方在对端 offer 到达之前没有发送端；此刻建音轨会开始采集，" +
                "而采出来的音频没有任何去处 —— 对端听不到、界面看不出、也没有报错",
            plan.createLocalTrack,
        )
        assertFalse(plan.attachLocalTrack)
        assertFalse(plan.enterRouting)
        assertFalse(plan.playRemote)
    }

    @Test
    fun `只收听不发言_没有发送端但有远端音轨时仍要出声`() {
        // answer 方按下开关、对端 offer 还没到的那个窗口：本机收得到对端的声音，
        // 却还发不出去。这一格保证「能听」不被「还不能说」连累。
        val plan = planCallAudio(
            requested = true,
            hasAudioTransceiver = false,
            hasRemoteTrack = true,
        )

        assertFalse(plan.createLocalTrack)
        assertTrue("对端声音与「本端有没有发送端」无关，该放就得放", plan.playRemote)
        assertTrue("收听同样要切路由 —— 路由跟着「有没有通话音频」走，不跟着开关走", plan.enterRouting)
    }

    @Test
    fun `按了开关且有发送端但没有远端音轨_本机开始发送`() {
        val plan = planCallAudio(
            requested = true,
            hasAudioTransceiver = true,
            hasRemoteTrack = false,
        )

        assertTrue(plan.createLocalTrack)
        assertTrue(plan.attachLocalTrack)
        assertTrue(plan.enterRouting)
        assertFalse("还没收到对端音轨，没什么可放的", plan.playRemote)
    }

    // ── 完整的通话态 ────────────────────────────────────────────────────────

    @Test
    fun `完整通话态_四格全 true`() {
        val plan = planCallAudio(
            requested = true,
            hasAudioTransceiver = true,
            hasRemoteTrack = true,
        )

        assertTrue(plan.createLocalTrack)
        assertTrue(plan.attachLocalTrack)
        assertTrue(plan.enterRouting)
        assertTrue(plan.playRemote)
    }

    // ── 三格的独立性（防止有人把某个条件简化掉）─────────────────────────────

    @Test
    fun `建音轨与挂发送端必须同进同出`() {
        // 只建不挂 = 麦克风在采集而音频没有去处（静默故障，见类注释）；
        // 只挂不建 = setTrack 拿到 null，链路上一片安静而状态显示「已发送」。
        // 所以这两格在所有输入组合下都必须一致。
        for (requested in listOf(false, true)) {
            for (transceiver in listOf(false, true)) {
                for (remote in listOf(false, true)) {
                    val plan = planCallAudio(requested, transceiver, remote)
                    assertEquals(
                        "requested=$requested transceiver=$transceiver remote=$remote",
                        plan.createLocalTrack,
                        plan.attachLocalTrack,
                    )
                }
            }
        }
    }

    @Test
    fun `切路由的条件是「本机在发或对端在放」`() {
        // 链路没跑却切了路由 → 整机的外放标志位与通信设备被留在我们设的值上，
        // 而本机根本没有通话音频（症状：退出影厅后别的 App 的声音走错路）。
        // 链路跑了却没切路由 → 声音走听筒或系统默认输出，两台设备摆在一起等于没声音。
        // `playRemote` 一并在这张表里钉住：它只认「对端音轨在不在」，与开关无关。
        for (requested in listOf(false, true)) {
            for (transceiver in listOf(false, true)) {
                for (remote in listOf(false, true)) {
                    val plan = planCallAudio(requested, transceiver, remote)
                    val tag = "requested=$requested transceiver=$transceiver remote=$remote"
                    assertEquals(
                        tag,
                        plan.createLocalTrack || remote,
                        plan.enterRouting,
                    )
                    assertEquals(tag, remote, plan.playRemote)
                }
            }
        }
    }

    // ── 状态数据类本身的两条默认值 ──────────────────────────────────────────

    @Test
    fun `默认状态是未开启且原音量`() {
        val state = CallAudioState()

        assertFalse("刚进页面时通话必须是关的", state.requested)
        assertFalse(state.sending)
        assertFalse(state.receiving)
        assertEquals(
            "默认必须是原音量 1f，不能是 0f —— 后者会让「忘了实现」变成一件" +
                "听不见任何声音的事故",
            1f,
            CallAudioState.DEFAULT_VOLUME,
            0f,
        )
        assertEquals(CallAudioState.DEFAULT_VOLUME, state.volume, 0f)
        assertEquals("-", state.outputDevice)
        assertEquals(null, state.error)
    }
}
