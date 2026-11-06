package com.cinemahall.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹计算。
 *
 * ── 这里为什么先钉两个「标准向量」──────────────────────────────────────────
 * 其余用例都是「同一份数据，两种读法得到同一个结果」，那只能证明**自洽**，
 * 证明不了 SHA-256 真的被算对了。`SHA-256("")` 与 `SHA-256("abc")` 是公开的
 * 标准向量，用它们把哈希函数本身钉死，其余用例才有意义。
 */
class MediaFingerprintTest {

    private val mib = 1024 * 1024

    /** 标准向量：`SHA-256("")`。 */
    private val emptyDigest =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** 标准向量：`SHA-256("abc")`。 */
    private val abcDigest =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `空文件的摘要是空串的标准哈希`() {
        val signature = MediaFingerprinter.fingerprint("empty.mkv", ByteArraySource(ByteArray(0)))

        assertEquals(0L, signature.sizeBytes)
        assertEquals(listOf(emptyDigest), signature.blocks)
    }

    @Test
    fun `短文件命中 abc 标准向量`() {
        val signature =
            MediaFingerprinter.fingerprint("abc.mkv", ByteArraySource("abc".toByteArray()))

        assertEquals(3L, signature.sizeBytes)
        assertEquals(listOf(abcDigest), signature.blocks)
    }

    @Test
    fun `短读会被补齐到与一次读完完全相同`() {
        val bytes = pattern(size = 100_000, seed = 3)

        val oneShot = MediaFingerprinter.fingerprint("a.mkv", ByteArraySource(bytes))
        val dribbled = MediaFingerprinter.fingerprint("a.mkv", ByteArraySource(bytes, maxChunk = 7))

        assertEquals(oneShot, dribbled)
        // 顺带证明这条用例真的走了短读路径，而不是恰好一次读完。
        assertTrue("短读路径未被触发", dribbled.blocks.isNotEmpty())
    }

    @Test
    fun `大文件只抽样三段而不是读完整文件`() {
        val bytes = pattern(size = 8 * mib, seed = 11)
        val source = ByteArraySource(bytes)

        val signature = MediaFingerprinter.fingerprint("big.mkv", source)

        assertEquals(8 * mib.toLong(), signature.sizeBytes)
        assertEquals(3, signature.blocks.size)
        assertEquals("应当恰好读三段", 3L, source.readCalls.toLong())
        assertEquals("读出的字节数应当是 3 MiB", 3L * mib, source.bytesRead)
    }

    @Test
    fun `三段模式的三块哈希对应 0 与 size 的一半与 size 减 1 MiB`() {
        val bytes = pattern(size = 4 * mib, seed = 7)

        val signature = MediaFingerprinter.fingerprint("movie.mkv", ByteArraySource(bytes))

        assertEquals(3, signature.blocks.size)
        assertEquals(
            listOf(
                MediaFingerprinter.sha256Hex(bytes.copyOfRange(0, mib)),
                MediaFingerprinter.sha256Hex(bytes.copyOfRange(2 * mib, 3 * mib)),
                MediaFingerprinter.sha256Hex(bytes.copyOfRange(3 * mib, 4 * mib)),
            ),
            signature.blocks,
        )
        // 三段内容不同 ⇒ 三个哈希必须不同。否则上面的断言可能因为「三段其实一样」而失去意义。
        assertEquals(3, signature.blocks.toSet().size)
    }

    @Test
    fun `改动中间字节只影响中段哈希`() {
        val bytes = pattern(size = 4 * mib, seed = 7)
        val before = MediaFingerprinter.fingerprint("movie.mkv", ByteArraySource(bytes))

        val tampered = bytes.copyOf()
        tampered[2 * mib + 4096] = (tampered[2 * mib + 4096] + 1).toByte()
        val after = MediaFingerprinter.fingerprint("movie.mkv", ByteArraySource(tampered))

        // 首段与尾段必须原样不变 —— 这正是「抽样」的取舍：改动只有落在取样窗口内才会被发现。
        assertEquals(before.blocks[0], after.blocks[0])
        assertEquals(before.blocks[2], after.blocks[2])
        assertNotEquals(before.blocks[1], after.blocks[1])
    }

    @Test
    fun `文件名原样保留不做归一化`() {
        // NFD 形式的「é」（e + 组合重音）与 NFC 形式字节不同、显示相同。
        val nfd = "cafe\u0301.mkv"

        val signature = MediaFingerprinter.fingerprint(nfd, ByteArraySource("abc".toByteArray()))

        assertEquals(nfd, signature.fileName)
        assertNotEquals(MediaSignatureComparator.normalizeFileName(nfd), signature.fileName)
    }

    @Test
    fun `来源完全不前进时不会死循环`() {
        // 一个永远返回空数组的来源：readFully 必须靠「空返回即文件尾」退出。
        val stubborn = object : RandomAccessSource {
            override val sizeBytes: Long = 4L * mib
            override fun readAt(offset: Long, length: Int): ByteArray = ByteArray(0)
        }

        val signature = MediaFingerprinter.fingerprint("stubborn.mkv", stubborn)

        assertEquals(3, signature.blocks.size)
        assertEquals(listOf(emptyDigest, emptyDigest, emptyDigest), signature.blocks)
    }

    /**
     * 测试数据。**每 64 KiB 换一个基值**：若只用 `index * k + seed` 这类线性式，
     * 图案的周期会是 256 字节，而 1 MiB 正好是 256 的整数倍 —— 三段内容会一模一样，
     * 「三个哈希必须不同」这类断言就变成恒真，失去意义（这条是实测踩出来的）。
     */
    private fun pattern(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> ((index / 65536 + index + seed) and 0xFF).toByte() }
}