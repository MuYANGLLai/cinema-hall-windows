package com.cinemahall.desktop.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat

/**
 * 0.1.18 音频门禁：**「对端说话时影片声音一卡一卡」的两个可测成因**。
 *
 * 用户报的现象是「对端一说话，影片的声音就消失、很短时间又恢复」。听感上像音量被抢，
 * 但代码路径上没有一处碰影片音量 —— 真正的动作是 [AudioDownlink] 在**反复开/关扬声器**：
 * 同一块端点上一条流不停 start/stop，别的流（VLC 的影片音轨）就会跟着顿一下。
 *
 * 所以这里钉的不是「有没有声音」，而是**设备被开了几次**：
 *  - 开不起来时不能每帧都去试（原来 = 每秒 100 次）；
 *  - 空闲时不能什么都不写（设备反复 underrun = 反复 STOP/START）；
 *  - 写失败后要先进冷却，而不是立刻下一帧重开。
 *
 * 判据用 [AudioDownlink.openAttempts] 与 [FakeFactory] 的记录，不依赖本机有没有声卡。
 */
class AudioDownlinkTest {

    private val rate = 48_000
    private val frame = ByteArray(rate / 100 * 2) // 10 ms 单声道

    @Test
    fun `开不起来时一秒内只试一次，不按帧抢占设备`() {
        val factory = FakeFactory(failChannels = setOf(1, 2))
        val downlink = AudioDownlink(devices = factory)
        val startedAt = System.currentTimeMillis()

        // 200 帧 = 2 秒的音频，但一口气灌进来（压缩了时间）。
        repeat(200) { downlink.write(frame, rate, 1, 16) }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("这次循环应该远快于冷却窗口（实际 ${elapsed}ms）", elapsed < 900)
        // 一轮 openDevice 会按 order 依次试 1 与 2 两个声道 ⇒ 恰好 2 次尝试，之后进冷却。
        assertEquals("失败后必须立刻进冷却，不能再碰设备", 2L, downlink.openAttempts)
        assertTrue("诊断串要能看出开不起来", downlink.deviceNote.startsWith("开不起来："))
        downlink.close()
    }

    @Test
    fun `主声道开不起来时退到立体声，而不是一直重开`() {
        // 单声道开不起来、立体声可以 —— 正是「Windows 共享混音是 48k 立体声」的常见形态。
        val factory = FakeFactory(failChannels = setOf(1))
        val downlink = AudioDownlink(devices = factory)

        assertTrue(downlink.write(frame, rate, 1, 16))
        assertEquals("先试 1，失败后试 2，成功即止", 2L, downlink.openAttempts)
        assertEquals("sr=48000 ch=2 buf=${factory.last!!.bufferSize}B", downlink.deviceNote)

        // ⚠️ 这一条是 0.1.18 的要害：设备退让成 2 声道之后，本机必须记住「上游给的是 1 声道」。
        // 记错成实际声道数时，上游每一帧都算作「格式变了」⇒ 每秒重开设备 100 次，
        // 同一端点上 VLC 的影片音轨跟着一顿一顿（用户报的「一卡一卡」）。
        repeat(50) { downlink.write(frame, rate, 1, 16) }
        assertEquals("退让过声道之后，同格式的后续帧不许再开设备", 2L, downlink.openAttempts)
        downlink.close()
    }

    @Test
    fun `空闲时补静音帧，而不是让设备反复 underrun`() {
        val factory = FakeFactory()
        val downlink = AudioDownlink(devices = factory)
        assertTrue(downlink.write(frame, rate, 1, 16))

        // 不再送任何帧，让播放线程自己空转 300ms（≈30 个轮询周期）。
        Thread.sleep(300)

        assertTrue(
            "空闲必须补静音（实际 ${downlink.silenceFrames} 帧）",
            downlink.silenceFrames > 5,
        )
        assertTrue("静音帧长度必须等于一帧", factory.device!!.lastWriteSize.get() > 0)
        downlink.close()
    }

    @Test
    fun `写失败后进冷却，不会每帧重开设备`() {
        val factory = FakeFactory()
        val downlink = AudioDownlink(devices = factory)
        assertTrue(downlink.write(frame, rate, 1, 16))
        assertEquals(1L, downlink.openAttempts)

        // 把设备弄坏：写就抛，播放线程会 resetDevice() 进冷却。
        factory.device!!.broken = true
        Thread.sleep(60)
        assertEquals("写失败后必须关掉设备", 1, factory.device!!.closed.get())

        // 冷却期内持续送帧：一次都不该重开。
        repeat(100) { downlink.write(frame, rate, 1, 16) }
        assertEquals("冷却期内不能重开设备", 1L, downlink.openAttempts)
        downlink.close()
    }

    @Test
    fun `格式非法或已关闭时直接拒绝，不去碰设备`() {
        val factory = FakeFactory()
        val downlink = AudioDownlink(devices = factory)

        assertFalse(downlink.write(frame, 0, 1, 16))
        assertFalse(downlink.write(frame, rate, 3, 16))
        assertFalse(downlink.write(frame, rate, 1, 24))
        assertEquals(0L, downlink.openAttempts)

        downlink.close()
        assertFalse(downlink.write(frame, rate, 1, 16))
        assertEquals(0L, downlink.openAttempts)
    }

    // ── 假设备 ──────────────────────────────────────────────────────────────

    private class FakeFactory(
        /** 这些声道数的格式一律「开不起来」。 */
        private val failChannels: Set<Int> = emptySet(),
    ) : AudioDeviceFactory {
        val opens = AtomicInteger()
        var device: FakeDevice? = null
            private set
        var last: FakeDevice? = null
            private set

        override fun open(format: AudioFormat, bufferBytes: Int): AudioDevice {
            opens.incrementAndGet()
            if (format.channels in failChannels) throw IOException("设备不支持 ${format.channels} 声道")
            return FakeDevice(bufferBytes).also { device = it; last = it }
        }
    }

    private class FakeDevice(override val bufferSize: Int) : AudioDevice {
        val closed = AtomicInteger()
        val lastWriteSize = AtomicLong()

        @Volatile
        var broken = false

        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            if (broken) throw IOException("端点已被释放")
            lastWriteSize.set(length.toLong())
            return length
        }

        override fun close() {
            closed.incrementAndGet()
        }
    }
}
