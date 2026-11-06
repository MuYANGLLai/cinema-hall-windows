package com.cinemahall.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.1 的时钟模型：`rtt = t4 − t1`、`offset = t2 − (t1 + rtt/2)`，
 * 以及「窗口内只采用 RTT 最小的那个样本」这条滤波规则。
 *
 * 2026-10-12 起还多了一样：[ClockSync.flightMsOf] —— §6.2 的锚点补偿要的是
 * 「**这条消息**飞了多久」，而不是「窗口里最走运的那一次的一半」。
 */
class ClockSyncTest {

    @Test
    fun 一次往返给出正确的_rtt_与_offset() {
        val sync = ClockSync(window = 8)

        // 单程 50ms、对端时钟比本机快 1000ms
        val sample = sync.onRoundTrip(t1 = 1_000, t2 = 2_050, t4 = 1_100)!!

        assertEquals(100L, sample.rttMs)
        // offset = 2050 − (1000 + 50) = 1000
        assertEquals(1_000L, sample.offsetMs)
        assertEquals(50L, sync.rttHalfMs)
        assertEquals(1_000L, sync.offsetMs)
        assertEquals(100L, sync.rttMs)
        assertEquals(1, sync.sampleCount)
        assertTrue(sync.hasEstimate)
    }

    @Test
    fun 只采用_rtt_最小的样本而不是平均值() {
        val sync = ClockSync(window = 8)

        // 三个样本：RTT 分别为 400 / 60 / 300。中间那个才是可信的。
        sync.onRoundTrip(t1 = 0, t2 = 1_200, t4 = 400)     // rtt 400, offset 1000
        sync.onRoundTrip(t1 = 0, t2 = 1_030, t4 = 60)      // rtt 60,  offset 1000
        sync.onRoundTrip(t1 = 0, t2 = 1_150, t4 = 300)     // rtt 300, offset 1000

        assertEquals(60L, sync.rttMs)
        assertEquals(30L, sync.rttHalfMs)
        assertEquals(1_000L, sync.offsetMs)
    }

    @Test
    fun 坏样本给出偏的_offset_但最小值滤波会挡住它() {
        val sync = ClockSync(window = 8)

        // 好样本：单程 20ms、offset 500
        sync.onRoundTrip(t1 = 0, t2 = 520, t4 = 40)
        assertEquals(500L, sync.offsetMs)
        assertEquals(20L, sync.rttHalfMs)

        // 坏样本：大得多的 RTT，算出来的 offset 也偏（1000 而不是 500）。
        // 它 rtt 更大，不该被采用。
        sync.onRoundTrip(t1 = 0, t2 = 1_400, t4 = 800)
        assertEquals(40L, sync.rttMs)
        assertEquals(500L, sync.offsetMs)
        assertEquals(20L, sync.rttHalfMs)
    }

    @Test
    fun 窗口滑动时淘汰掉的那个如果正是最小值则退回次小值() {
        val sync = ClockSync(window = 2)

        sync.onRoundTrip(t1 = 0, t2 = 0, t4 = 10)     // rtt 10（最小）
        sync.onRoundTrip(t1 = 0, t2 = 0, t4 = 200)    // rtt 200
        assertEquals(10L, sync.rttMs)

        // 再进一个：窗口只留最近两个，rtt=10 被挤出去，最小值应变成 90
        sync.onRoundTrip(t1 = 0, t2 = 0, t4 = 90)
        assertEquals(2, sync.sampleCount)
        assertEquals(90L, sync.rttMs)
    }

    @Test
    fun t4_早于_t1_的样本被丢弃且不影响已有估计() {
        val sync = ClockSync(window = 8)
        sync.onRoundTrip(t1 = 1_000, t2 = 2_000, t4 = 1_100)
        assertEquals(100L, sync.rttMs)

        // 负的 RTT 只可能来自「拿墙钟和单调钟混算」。丢弃，而不是取绝对值。
        assertNull(sync.onRoundTrip(t1 = 5_000, t2 = 6_000, t4 = 4_000))

        assertEquals(100L, sync.rttMs)
        assertEquals(1, sync.sampleCount)
    }

    @Test
    fun 还没量到过往返时补偿量为零而不是猜一个() {
        val sync = ClockSync(window = 8)

        assertFalse(sync.hasEstimate)
        assertEquals(0L, sync.rttHalfMs)
        assertEquals(0L, sync.rttMs)
        assertEquals(0L, sync.offsetMs)
        assertEquals(0, sync.sampleCount)
    }

    @Test
    fun 重置后回到未估计状态() {
        val sync = ClockSync(window = 8)
        sync.onRoundTrip(t1 = 0, t2 = 0, t4 = 100)

        sync.reset()

        assertFalse(sync.hasEstimate)
        assertEquals(0, sync.sampleCount)
        assertEquals(0L, sync.rttHalfMs)
    }

    // ── 飞行时间：§6.2 的锚点补偿真正要的那个量（2026-10-12 真机缺陷）────────────

    @Test
    fun 飞行时间按这条消息的发送时刻算出而不是窗口里最小的半_rtt() {
        val sync = ClockSync(window = 8)

        // 窗口里留着的是「最走运」的那次直连往返：单程 10ms、对端时钟快 1000ms。
        // 它给出的 rttHalf = 10 —— 而它在中继路径上毫无参考价值。
        sync.onRoundTrip(t1 = 0, t2 = 1_010, t4 = 20)
        assertEquals(10L, sync.rttHalfMs)
        assertEquals(1_000L, sync.offsetMs)

        // 用户切到强制中继之后，一条真的飞了 400ms 的心跳到了（本机此刻是 1000）：
        // 对端那一刻的时钟 = (1000 + 1000) − 400 = 1600。
        assertEquals(
            "必须报「这条消息真的飞了多久」，而不是 rttHalf",
            400L,
            sync.flightMsOf(senderClockMs = 1_600, nowLocalMs = 1_000),
        )

        // 同一条链路上下一条心跳只飞了 10ms，它就该报 10 —— 这个量是**逐条**算的。
        assertEquals(10L, sync.flightMsOf(senderClockMs = 1_990, nowLocalMs = 1_000))
    }

    @Test
    fun 还没量到往返时飞行时间不可用而不是猜一个() {
        val sync = ClockSync(window = 8)

        // 返回 null 而不是 0：调用方据此退回「先不补偿」。
        // 给一个编出来的数字去锚定，会让第一帧就跳一下。
        assertNull(sync.flightMsOf(senderClockMs = 1_000, nowLocalMs = 1_000))
    }

    @Test
    fun 飞行时间被夹在零与上限之间() {
        val sync = ClockSync(window = 8)
        sync.onRoundTrip(t1 = 1_000, t2 = 2_050, t4 = 1_100)   // offset 1000

        // 对端时钟比我们以为的快（它重启过、而 offset 还是旧的）⇒ 算出负的飞行时间。
        // 夹到 0，而不是拿它去把锚点往前推。
        assertEquals(0L, sync.flightMsOf(senderClockMs = 2_500, nowLocalMs = 1_100))

        // 一条积压了很久的旧消息 ⇒ 夹到上限。它描述的已经不是「这一刻」了，
        // 再往上补就是拿一个坏样本去锚定（那正是要治的症状）。
        assertEquals(ClockSync.MAX_FLIGHT_MS, sync.flightMsOf(senderClockMs = 0, nowLocalMs = 1_100))
    }

    // ── 偏差的限速跟随：2026-10-12「强制中继下从端一会卡一会不卡」的根因 ──────────
    //
    // 这一组钉的是**性质**而不是实现：单次重锚注入的漂移必须小于软阈值的一半。
    // 目标位置每平移一次，从端的漂移就跟着平移一次；只要平移量到不了 80ms，
    // 「变速追赶」与硬 seek 两档都不可能被网络抖动自己触发。

    @Test
    fun 偏差的跳变被限速跟随压成小步() {
        val sync = ClockSync(window = 8)

        // 第一个样本直接采纳：不采纳就没有任何补偿可用，首帧会稳定落后半个 RTT。
        sync.onRoundTrip(t1 = 0, t2 = 1_000, t4 = 60)     // rtt 60, offset 970
        assertEquals(970L, sync.offsetMs)
        assertEquals(970L, sync.rawOffsetMs)

        // 链路抖了一下：窗口里出现了 RTT 更小的样本，它给出的偏差是 1380 —— 一步跳 410ms。
        // 原始估计照实报（诊断页要看到抖动幅度），补偿用的那个只挪 40。
        sync.onRoundTrip(t1 = 0, t2 = 1_400, t4 = 40)     // rtt 40, offset 1380
        assertEquals(1_380L, sync.rawOffsetMs)
        assertEquals(970L + ClockSync.MAX_OFFSET_STEP_MS, sync.offsetMs)

        // 再抖一次，还是只挪一步。
        sync.onRoundTrip(t1 = 0, t2 = 1_500, t4 = 30)     // rtt 30, offset 1485
        assertEquals(1_485L, sync.rawOffsetMs)
        assertEquals(970L + 2 * ClockSync.MAX_OFFSET_STEP_MS, sync.offsetMs)
    }

    @Test
    fun 限速跟随下单次重锚注入的漂移绝不到软阈值() {
        val sync = ClockSync(window = 8)
        val soft = CinemaSyncConfig.DEFAULT.softDriftMs

        // 一串「每次都换最小值」的坏样本：偏差在 800~1900 之间来回跳，
        // 幅度远超软阈值 —— 中继 + 蜂窝下量出来的就是这种东西。
        var prev: Long? = null
        listOf(
            Triple(0L, 1_000L, 60L),
            Triple(0L, 1_600L, 40L),
            Triple(0L, 900L, 50L),
            Triple(0L, 1_700L, 30L),
            Triple(0L, 800L, 45L),
            Triple(0L, 1_900L, 35L),
        ).forEach { (t1, t2, t4) ->
            sync.onRoundTrip(t1 = t1, t2 = t2, t4 = t4)
            val step = prev?.let { kotlin.math.abs(sync.offsetMs - it) } ?: 0L
            assertTrue(
                "单次重锚挪了 $step ms；超过软阈值的一半就等于把抖动直接喂给了漂移判断",
                step <= soft / 2,
            )
            prev = sync.offsetMs
        }

        // 原始估计的跨度确实很大 —— 否则这一组什么都没测到。
        assertTrue("原始偏差只有 ${sync.rawOffsetMs} ms，没造出抖动", sync.rawOffsetMs > 1_000L)
    }

    @Test
    fun 飞行时间跟着平滑后的偏差走而不是原始估计() {
        val sync = ClockSync(window = 8)
        sync.onRoundTrip(t1 = 0, t2 = 1_000, t4 = 60)     // rtt 60,  offset 970
        sync.onRoundTrip(t1 = 0, t2 = 1_400, t4 = 40)     // rtt 40,  offset 1380 → 平滑到 1010

        assertEquals(1_380L, sync.rawOffsetMs)
        assertEquals(1_010L, sync.offsetMs)

        // 对端那一刻的时钟是 1600：按平滑后的偏差，1600 对应本机 1600 − 1010 = 590，
        // 而本机此刻是 1000 ⇒ 这条消息飞了 410ms。
        // 若拿原始估计（1380）去算，会得到 780ms —— 目标位置凭空多 370ms，
        // 正好越过硬阈值 ⇒ 每收到一条心跳就硬 seek 一次。那正是真机报的症状。
        assertEquals(410L, sync.flightMsOf(senderClockMs = 1_600, nowLocalMs = 1_000))
    }

    @Test
    fun 偏差一次跳掉一秒以上时直接采纳_那是换了一只时钟() {
        val sync = ClockSync(window = 8)
        sync.onRoundTrip(t1 = 0, t2 = 1_000, t4 = 60)     // offset 970
        assertEquals(970L, sync.offsetMs)

        // 对端重启：它的单调钟归零，新偏差是「它的开机时长」，远超抖动造得出的量级。
        // 这时必须立刻跟上 —— 慢慢挪的话，飞行时间会被夹在 0 或上限上一整段时间，
        // 目标位置反而错得更久。
        sync.onRoundTrip(t1 = 0, t2 = 40_000, t4 = 40)    // rtt 40, offset 39980
        assertEquals(39_980L, sync.rawOffsetMs)
        assertEquals(39_980L, sync.offsetMs)
    }

    @Test
    fun 重置后偏差从新的第一个样本重新起步() {
        val sync = ClockSync(window = 8)
        sync.onRoundTrip(t1 = 0, t2 = 1_000, t4 = 60)     // offset 970

        sync.reset()
        assertEquals(0L, sync.offsetMs)
        assertEquals(0L, sync.rawOffsetMs)

        // 换链路之后的第一条直接采纳：差值 510ms 本来够得着限速的步子，
        // 但换链路不是「抖动」，没有理由让新路径背着旧路径的偏差慢慢挪。
        sync.onRoundTrip(t1 = 0, t2 = 1_500, t4 = 40)     // offset 1480
        assertEquals(1_480L, sync.offsetMs)
    }
}
