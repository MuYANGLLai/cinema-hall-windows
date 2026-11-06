package com.cinemahall.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.3 的三档漂移校准。阈值边界逐毫秒钉住 —— 真机重估参数（§6.8）时，
 * 改动的正是这些数字，而边界错 1ms 的症状是「偶尔硬跳一下」，极难观察。
 */
class DriftCorrectorTest {

    private val corrector = DriftCorrector(CinemaSyncConfig.DEFAULT)

    private val soft = CinemaSyncConfig.DEFAULT.softDriftMs   // 80
    private val hard = CinemaSyncConfig.DEFAULT.hardDriftMs   // 300

    @Test
    fun 低于软阈值什么都不做_且边界是严格小于() {
        assertEquals(DriftAction.None, corrector.decide(0, isPlaying = true, targetPositionMs = 1_000))
        assertEquals(DriftAction.None, corrector.decide(79, isPlaying = true, targetPositionMs = 1_000))
        assertEquals(DriftAction.None, corrector.decide(-79, isPlaying = true, targetPositionMs = 1_000))
        assertEquals(DriftAction.None, corrector.decide(soft - 1, isPlaying = true, targetPositionMs = 1_000))
    }

    @Test
    fun 达到软阈值进入变速追赶() {
        val action = corrector.decide(soft, isPlaying = true, targetPositionMs = 1_000)
        assertTrue("80ms 应落在第 2 档，实际 $action", action is DriftAction.AdjustSpeed)
    }

    @Test
    fun 本机跑在前面要放慢本机落后要加快() {
        val ahead = corrector.decide(150, isPlaying = true, targetPositionMs = 1_000) as DriftAction.AdjustSpeed
        assertTrue("drift 为正说明本机超前，必须放慢，实际因子=${ahead.speedFactor}", ahead.speedFactor < 1f)

        val behind = corrector.decide(-150, isPlaying = true, targetPositionMs = 1_000) as DriftAction.AdjustSpeed
        assertTrue("drift 为负说明本机落后，必须加快，实际因子=${behind.speedFactor}", behind.speedFactor > 1f)
    }

    @Test
    fun 除软阈值附近的_20ms_之外本档增益恒被上限夹住() {
        // §6.3 给的是 clamp(|drift|/2000, 0.02, 0.05)。本档（80~300ms）代入得
        // 0.04~0.15。所以 100ms 起就被上限夹到 0.05，只有 80~99ms 这 20ms 宽
        // 的窗口里露出未经夹取的原始值（0.04~0.0495）—— 这是照实实现的结果，
        // 不是简化：`/2000` 只在真机重估参数（§6.8）把阈值或上限改动后才起作用。
        listOf(100L, 150L, 299L).forEach { d ->
            val action = corrector.decide(d, isPlaying = true, targetPositionMs = 1_000) as DriftAction.AdjustSpeed
            assertEquals("drift=$d", 0.05, action.gain, 1e-9)
            assertEquals("drift=$d", 0.95f, action.speedFactor, 1e-6f)
        }

        // 刚达到软阈值：80/2000 = 0.04，尚未触到上限。若这里也写 0.05，
        // 就再也测不到「分母真的参与了计算」这件事。
        val justOver = corrector.decide(soft, isPlaying = true, targetPositionMs = 1_000) as DriftAction.AdjustSpeed
        assertEquals(0.04, justOver.gain, 1e-9)
        assertEquals(0.96f, justOver.speedFactor, 1e-6f)

        val negative = corrector.decide(-150, isPlaying = true, targetPositionMs = 1_000) as DriftAction.AdjustSpeed
        assertEquals(1.05f, negative.speedFactor, 1e-6f)
    }

    @Test
    fun 达到硬阈值直接_seek_到目标() {
        val action = corrector.decide(hard, isPlaying = true, targetPositionMs = 7_777)
        assertEquals(DriftAction.SeekTo(7_777), action)

        val bigger = corrector.decide(2_000, isPlaying = true, targetPositionMs = 7_777)
        assertEquals(DriftAction.SeekTo(7_777), bigger)

        val negative = corrector.decide(-2_000, isPlaying = true, targetPositionMs = 7_777)
        assertEquals(DriftAction.SeekTo(7_777), negative)
    }

    @Test
    fun 暂停状态下不做变速_超过软阈值直接_seek() {
        // 变速在暂停下数学上无意义（位置对时间的导数为 0）
        val action = corrector.decide(150, isPlaying = false, targetPositionMs = 3_000)
        assertEquals(DriftAction.SeekTo(3_000), action)

        // 但软阈值以内仍然不动 —— 否则每次暂停都会因为几十毫秒的差而跳一下
        assertEquals(DriftAction.None, corrector.decide(79, isPlaying = false, targetPositionMs = 3_000))
        assertEquals(DriftAction.SeekTo(3_000), corrector.decide(soft, isPlaying = false, targetPositionMs = 3_000))
    }

    @Test
    fun 变速幅度绝不超过上限() {
        val config = CinemaSyncConfig.DEFAULT
        (-hard until hard).forEach { d ->
            val action = corrector.decide(d, isPlaying = true, targetPositionMs = 0)
            if (action is DriftAction.AdjustSpeed) {
                val deviation = kotlin.math.abs(1.0 - action.speedFactor)
                assertTrue(
                    "drift=$d 的变速幅度 $deviation 超过上限 ${config.maxAdjustSpeed}",
                    deviation <= config.maxAdjustSpeed + 1e-6,
                )
            }
        }
    }

    @Test
    fun 自定义参数下_2000_分母才真正起作用() {
        // 把上限放开到 0.2，|drift|/2000 就不再被夹住，公式的形状得以显现
        val gentle = DriftCorrector(CinemaSyncConfig(maxAdjustSpeed = 0.2, hardDriftMs = 2_000))

        val small = gentle.decide(100, isPlaying = true, targetPositionMs = 0) as DriftAction.AdjustSpeed
        assertEquals(0.05, small.gain, 1e-9)   // 100/2000

        val large = gentle.decide(1_000, isPlaying = true, targetPositionMs = 0) as DriftAction.AdjustSpeed
        assertEquals(0.2, large.gain, 1e-9)    // 被上限夹住
    }

    @Test
    fun 带内的漂移不追_松手点就是进档点() {
        // 2026-10-12 试过「进档 80 / 出档 40」的施密特迟滞，方向是反的：出档门槛一降，
        // 修正就跑得更久，残留漂移从「贴着 80」被拉到 40，修正次数反而**变多** ——
        // 而每次修正都是一次速率变化（`CinemaSyncEngineTest` 里那条倍速用例当场失败）。
        // 真正让速度不再反复跳的是「抖动不再进入目标位置」：见 `ClockSync.slewOffset`
        // 的限速跟随。这条用例把「进档即出档」的原样钉住，免得以后又有人把它当改进加回来。
        assertTrue(corrector.decide(soft, isPlaying = true, targetPositionMs = 0) is DriftAction.AdjustSpeed)
        assertEquals(DriftAction.None, corrector.decide(79, isPlaying = true, targetPositionMs = 0))
        assertEquals(DriftAction.None, corrector.decide(40, isPlaying = true, targetPositionMs = 0))
        assertEquals(DriftAction.None, corrector.decide(0, isPlaying = true, targetPositionMs = 0))
    }
}
