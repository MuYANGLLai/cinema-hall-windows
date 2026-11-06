package com.cinemahall.core.session

import com.cinemahall.core.media.MediaCheck
import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.MediaSignatureComparator

/**
 * 「两端片源是否一致」这件事的全部状态（§7）。
 *
 * ── [verdict] 为什么是算出来的，而不是存下来的 ──────────────────────────────
 * 存下来就要在「本机换了文件」「对端重发了 hello」「断线重连后 hello 又来一次」
 * 这三条路径上各记得更新一遍，漏一条就会出现「结果和摘要对不上」的界面。
 * 做成 `val` 派生量之后，这种不一致在结构上不可能发生。
 *
 * ── 为什么两边的摘要都要留 ──────────────────────────────────────────────────
 * §7 要求「明确提示哪一项不一致，**并展示双方摘要**」。只留一个布尔值，
 * 用户就无从判断是选错文件还是对方片源不同 —— 而这两件事的处理方式完全不同。
 */
data class CinemaMediaCheck(
    val local: MediaSignature? = null,
    val remote: MediaSignature? = null,
    val remoteNickname: String? = null,
) {

    /** 两端摘要都到齐时的结论；还缺一边时为 null（界面应显示「等待」而不是「不一致」）。 */
    val verdict: MediaCheck?
        get() = if (local != null && remote != null) {
            MediaSignatureComparator.compare(local, remote)
        } else {
            null
        }

    val bothKnown: Boolean get() = local != null && remote != null

    /** 可以开播：两端摘要都在，且三项校验全过（§7：不一致就阻止开播，没有「强制继续」）。 */
    val canStartPlayback: Boolean get() = verdict == MediaCheck.Match

    fun withLocal(signature: MediaSignature) = copy(local = signature)

    fun withRemote(hello: CinemaHello) =
        copy(remote = hello.mediaSignature, remoteNickname = hello.nickname)

    /** 只清掉本机一侧：对端的摘要仍然有效，用户换一个本地文件时不必等对端重发。 */
    fun withoutLocal() = copy(local = null)

    /**
     * 只清掉对端一侧（退房时用）。
     *
     * 那一份摘要属于**上一场会话**：留着的话，下一次进房时在对方的 `cinema.hello`
     * 到达之前，界面会先显示一次上一场的「已匹配」—— 而对面可能已经换人、换片。
     * 昵称一起清：它同样只对那一场会话有效。
     *
     * 本机那一份**留着**：文件还在本机，用户重新进房时不必重选、也不必重算摘要。
     */
    fun withoutRemote() = copy(remote = null, remoteNickname = null)
}