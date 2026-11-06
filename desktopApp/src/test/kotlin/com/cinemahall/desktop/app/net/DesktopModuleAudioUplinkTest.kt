package com.cinemahall.desktop.app.net

import com.cinemahall.core.model.UserId
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 `0.1.2` 那条真机缺陷：**界面先摸到的那条链路也必须带音频上行**。
 *
 * ── 缺陷长什么样 ────────────────────────────────────────────────────────────
 * `CinemaScreen` 一打开影厅页就订阅 `module.link.state`，于是 [DesktopModule.link] 的
 * 空值分支先造了一条 `DesktopPeerLink(rtcBackend, linkDispatcher)`（`uplink` 默认 `null`），
 * 它被记成 `pendingLink` 并被协调者认领 —— 结果房间里的链路天生没有采集上行，
 * 点开麦克风只报「本机没有接入音频采集」、收发永远是 `-`。
 *
 * ── 为什么这条用例抓得住 ────────────────────────────────────────────────────
 * 它走的是**产品路径的入口顺序**（先读 `module.link`，再谈建链），而不是
 * `--rtc-call-test` 那种直接 `DesktopPeerLink(..., uplink = …)` 的旁路 —— `0.1.2`
 * 的语音门禁正是绕过了这里才没报红。
 *
 * 不建连、不进房：只验装配，跑起来不碰网络也不碰音频设备。
 */
class DesktopModuleAudioUplinkTest {

    @Test
    fun `界面先摸到的那条链路必须带音频上行`() {
        val module = DesktopModule(endpoint = "ws://127.0.0.1:9/ws", myUserId = UserId("dv_test"))
        try {
            val fromUi = module.link
            assertTrue(
                "界面先创建的那条链路没有接上行采集：开麦只会报「本机没有接入音频采集」",
                fromUi.hasAudioUplink,
            )
            // 同一个对象才算「界面盯着的就是协调者建的那条」。两条各建一份的症状是
            // 界面永远 `Idle`、与「压根没建链」一模一样。
            assertSame("重复读 module.link 必须返回同一个实例", fromUi, module.link)
        } finally {
            runCatching { module.close() }
        }
    }
}
