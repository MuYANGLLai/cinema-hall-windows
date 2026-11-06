package com.cinemahall.core.session

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退房时「只清对端那一侧」这条语义（[CinemaMediaCheck.withoutRemote]）。
 *
 * ── 为什么它值得一条测试 ────────────────────────────────────────────────────
 * 两个方向不对称：**对端摘要属于上一场会话**，留着会让下一次进房在对方的
 * `cinema.hello` 到达之前先显示一次上一场的「已匹配」（而对面可能已经换人、换片）；
 * **本机摘要属于本机那个文件**，文件还在，用户重新进房时不必重选、也不必重算摘要。
 *
 * 写成测试是因为两边的症状完全不对等：「清多了」只是用户要重选一次文件，
 * 而「清少了」是**静默的错判** —— 界面显示「已匹配」，其实对面是另一部片子，
 * 而 §7 的整个意义就是不让这一条悄悄发生。
 */
class CinemaMediaCheckTest {

    private val local = MediaSignature(
        fileName = "movie.mkv",
        sizeBytes = 4L * 1024 * 1024,
        blocks = listOf("a".repeat(64)),
    )

    private val remote = MediaSignature(
        fileName = "movie.mkv",
        sizeBytes = 4L * 1024 * 1024,
        blocks = listOf("a".repeat(64)),
    )

    private fun bothKnown() = CinemaMediaCheck()
        .withLocal(local)
        .withRemote(CinemaHello(mediaSignature = remote, nickname = "对面那台"))

    @Test
    fun 退房只清对端摘要_本机那份留着() {
        val before = bothKnown()
        assertTrue("前置：两端摘要都在时应可开播", before.canStartPlayback)

        val after = before.withoutRemote()

        assertNull(after.remote)
        assertNull(after.remoteNickname)
        // 本机那一份必须还在：文件还在本机，用户重新进房时不必重选。
        assertEquals(local, after.local)
        // 结论跟着回到「等待」（verdict = null），而不是「不一致」——
        // 缺一边时界面该说「等对面」，不该判人家不一致。
        assertNull(after.verdict)
        assertFalse(after.canStartPlayback)
    }

    @Test
    fun 换本机文件时只清本机那份_对端摘要仍然有效() {
        val after = bothKnown().withoutLocal()

        assertNull(after.local)
        assertEquals(remote, after.remote)
        assertEquals("对面那台", after.remoteNickname)
    }
}
