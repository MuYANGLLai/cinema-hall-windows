package com.cinemahall.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7 的三步比对 —— **1.5 的核心验收项**。
 *
 * 任务书写的验收是「相同文件通过；改名、改大小、改中间字节三种情况分别被拦截
 * 并给出正确原因」。前四种正好一一对应下面四个用例；后面几个钉的是「顺序」与
 * 「误报」，因为一个会把同一文件判成不同的实现，同样是不可用的。
 */
class MediaSignatureCompareTest {

    private val head = "a".repeat(64)
    private val middle = "b".repeat(64)
    private val tail = "c".repeat(64)

    /** 4 MiB 的三段摘要（正常形态）。 */
    private fun threeBlock(
        fileName: String = "movie.mkv",
        sizeBytes: Long = 4L * 1024 * 1024,
        blocks: List<String> = listOf(head, middle, tail),
    ) = MediaSignature(fileName = fileName, sizeBytes = sizeBytes, blocks = blocks)

    /** 小于 3 MiB 的整文件摘要。 */
    private fun wholeFile(
        fileName: String = "clip.mkv",
        sizeBytes: Long = 1024,
        digest: String = head,
    ) = MediaSignature(fileName = fileName, sizeBytes = sizeBytes, blocks = listOf(digest))

    // ── 验收四项 ───────────────────────────────────────────────────────────

    @Test
    fun `相同文件通过`() {
        assertEquals(
            MediaCheck.Match,
            MediaSignatureComparator.compare(threeBlock(), threeBlock()),
        )
    }

    @Test
    fun `改名被拦截且原因是文件名`() {
        val result = MediaSignatureComparator.compare(
            threeBlock(fileName = "movie.mkv"),
            threeBlock(fileName = "movie (1).mkv"),
        )

        val reason = (result as MediaCheck.Mismatch).reason
        assertTrue("实得 $reason", reason is MismatchReason.FileName)
        assertEquals("movie.mkv", (reason as MismatchReason.FileName).local)
        assertEquals("movie (1).mkv", reason.remote)
    }

    @Test
    fun `改大小被拦截且原因是大小`() {
        val result = MediaSignatureComparator.compare(
            threeBlock(sizeBytes = 4L * 1024 * 1024),
            threeBlock(sizeBytes = 4L * 1024 * 1024 + 1),
        )

        val reason = (result as MediaCheck.Mismatch).reason
        assertTrue("实得 $reason", reason is MismatchReason.Size)
        assertEquals(4L * 1024 * 1024 + 1, (reason as MismatchReason.Size).remoteBytes)
    }

    @Test
    fun `改中间字节被拦截且原因是中段块`() {
        val result = MediaSignatureComparator.compare(
            threeBlock(),
            threeBlock(blocks = listOf(head, "d".repeat(64), tail)),
        )

        val reason = (result as MediaCheck.Mismatch).reason
        assertTrue("实得 $reason", reason is MismatchReason.Block)
        reason as MismatchReason.Block
        assertEquals(1, reason.index)
        assertEquals("中段", reason.label)
        assertEquals("b".repeat(64), reason.localHash)
    }

    // ── 顺序 ───────────────────────────────────────────────────────────────

    @Test
    fun `文件名比大小先判`() {
        // 同时改名与改大小 → 必须报文件名。§7 的「依次」说的就是这个。
        val result = MediaSignatureComparator.compare(
            threeBlock(fileName = "a.mkv"),
            threeBlock(fileName = "b.mkv", sizeBytes = 999),
        )

        assertTrue(
            "实得 $result",
            (result as MediaCheck.Mismatch).reason is MismatchReason.FileName,
        )
    }

    @Test
    fun `大小比块先判`() {
        // 截断文件会同时改变大小与尾段哈希。若先比块，用户会看到
        // 「尾段哈希不一致」—— 那是现象，不是原因。
        val result = MediaSignatureComparator.compare(
            threeBlock(sizeBytes = 4L * 1024 * 1024),
            threeBlock(sizeBytes = 3L * 1024 * 1024, blocks = listOf(head, middle, "e".repeat(64))),
        )

        assertTrue(
            "实得 $result",
            (result as MediaCheck.Mismatch).reason is MismatchReason.Size,
        )
    }

    // ── 不得误报 ───────────────────────────────────────────────────────────

    @Test
    fun `NFC 与 NFD 形式的同一文件名通过`() {
        // 同一个文件名从不同来源到达手机时 Unicode 形式可能不同，显示却完全一样。
        // 不归一化就会出现「看起来一样却说文件不一致」这种无法解释的误报。
        val nfc = "caf\u00E9.mkv"
        val nfd = "cafe\u0301.mkv"

        assertTrue(nfc != nfd)

        assertEquals(
            MediaCheck.Match,
            MediaSignatureComparator.compare(
                threeBlock(fileName = nfc),
                threeBlock(fileName = nfd),
            ),
        )
    }

    @Test
    fun `大小写不同视为不同文件`() {
        // §7：按精确比较 —— 目的是确保是同一个文件，而不是同一个片源的不同压制。
        val result = MediaSignatureComparator.compare(
            threeBlock(fileName = "Test.mkv"),
            threeBlock(fileName = "test.mkv"),
        )

        assertTrue(
            "实得 $result",
            (result as MediaCheck.Mismatch).reason is MismatchReason.FileName,
        )
    }

    // ── 整文件模式与防御分支 ───────────────────────────────────────────────

    @Test
    fun `整文件模式的块标签是整文件而不是首段`() {
        val result = MediaSignatureComparator.compare(
            wholeFile(digest = head),
            wholeFile(digest = "f".repeat(64)),
        )

        val reason = (result as MediaCheck.Mismatch).reason as MismatchReason.Block
        assertEquals(0, reason.index)
        // 叫「首段」会让人以为只校验了开头 1 MiB，而实际上校验的是全部字节。
        assertEquals("整文件", reason.label)
    }

    @Test
    fun `块数不同报结构不一致`() {
        val result = MediaSignatureComparator.compare(
            threeBlock(),
            // 文件名与大小都对齐，只让块数不同 —— 否则会先撞上文件名判定。
            wholeFile(fileName = "movie.mkv", sizeBytes = 4L * 1024 * 1024, digest = head),
        )

        val reason = (result as MediaCheck.Mismatch).reason
        assertTrue("实得 $reason", reason is MismatchReason.BlockCount)
        assertEquals(3, (reason as MismatchReason.BlockCount).localCount)
        assertEquals(1, reason.remoteCount)
    }

    // ── 文案 ───────────────────────────────────────────────────────────────

    @Test
    fun `提示文案点明是哪一项不一致`() {
        val cases = listOf(
            MediaSignatureComparator.compare(threeBlock(fileName = "a.mkv"), threeBlock(fileName = "b.mkv")),
            MediaSignatureComparator.compare(threeBlock(sizeBytes = 10), threeBlock(sizeBytes = 20)),
            MediaSignatureComparator.compare(threeBlock(), threeBlock(blocks = listOf(head, "d".repeat(64), tail))),
        ).map { (it as MediaCheck.Mismatch).reason.describe() }

        assertTrue(cases[0], cases[0].contains("文件名不一致"))
        assertTrue(cases[1], cases[1].contains("文件大小不一致"))
        assertTrue(cases[2], cases[2].contains("中段"))
        // 第 2 块是人类口径（index 1），不能直接暴露 index。
        assertTrue(cases[2], cases[2].contains("第 2 块"))
    }
}