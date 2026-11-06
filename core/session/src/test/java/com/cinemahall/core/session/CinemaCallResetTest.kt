package com.cinemahall.core.session

import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.UserId
import com.cinemahall.core.rtc.CallAudio
import com.cinemahall.core.rtc.CallAudioState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换一场会话时，**通话意图必须复位**（阶段一 · 2026-10-12 真机缺陷的修复）。
 *
 * ── 用户报的现象 ─────────────────────────────────────────────────────────────
 * 「刚进入房间，未点击开启通话的按钮，但是却开启通话了」——麦克风绿点亮着。
 *
 * ── 根因：`callRequested` 活过了整场会话 ───────────────────────────────────────
 * 通话意图（`WebRtcPeerLink.callRequested`）是**进程级**字段，而且**刻意**跨链路
 * 重建存活 —— 那是「网络断一下，通话不该静默消失」要的（§9.4 的宽限期）。
 * 代价是它也可能活过整场会话：只要任何一条退房来路漏掉复位，下一次进房建链时
 * `openPeerConnection()` 就会按它把麦克风接回来 —— 而用户没按过任何开关。
 *
 * ── 修法为什么挂在「房号变了」上，而不是在退房路径上各补一次 ─────────────────────
 * 退房来路不止一条（用户按按钮、服务端解散 `sys.room_closed`、换服务器、
 * `resume` 失败），挂在它们上就是「依赖某一条一定跑到」；挂在「新房号」上则是
 * 结构性的：**一场新会话的第一帧里，通话意图必然是关的**，无论上一场是怎么结束的。
 *
 * 判据取「房号变了」而不是「进了房间」也是刻意的：`Signaler.room` 在**成员上下线**
 * 时也会发一个新值（`markMemberOnline` 每次都 `copy` 出一个新 `Room`），按「进了房间」
 * 判会在对端掉线的那一刻把正在进行的通话关掉 —— 而那一刻恰恰是两人最不希望掉麦的时候。
 *
 * ── 2026-10-12 追加：光有「房号变了」还不够 ────────────────────────────────────
 * 装了 0.1.1(2) 之后用户回报「进入房间仍自动开启通话」，说明上面那条判据漏了一条路：
 * 它的来路是 `Signaler.room` 这个 **StateFlow**，而 StateFlow 会**按相等去重**
 * （收集方来不及跑时还会合并）。「退房之后很快重进同一个房号」那一轮
 * （`null` → 同一个 `Room`）可能一次都不送到 `CinemaHandler.onRoomUpdated` ——
 * 于是 `lastRoomId` 停在旧值、通话意图停在上一场的 true，下一次建链时
 * `openPeerConnection()` 就把麦克风接回来了。
 *
 * 所以复位改挂**进房事件**（`CinemaHandler.onRoomEntered`，由 `WsSignaler` 在
 * 建房 / 加入的成功应答路径上直接派发）：**事件去重不了，状态流会**。
 * 本文件末尾那几条用例钉的就是这条路。
 *
 * ── 2026-10-13 追加：0.1.3(4) 上仍能复现，而上面三条路**结构上都够不着** ────────────
 * 用户回报「一进房间还是默认开启通话」，并要求「进房间只建立通话链路，麦克风输入默认
 * 禁用」。这一轮钉出来的根因不再是「某条信令路漏了复位」，而是**复位全挂在信令上，
 * 而「进影厅」可以一条信令都不产生**：
 *
 *  返回键从影厅退回大厅时**不调** `Signaler.leave()`（大厅那张卡片仍然写着「已在房间里」
 *  —— 房间、链路、通话意图一样都没变），而大厅里的「进入影厅」按钮只做
 *  `homePage = HOME_HALL`。于是「影厅 → 大厅 → 影厅」这一轮里 `Room` 一次都没变、
 *  没有进房应答、`rtc.close()` 也不会被调 —— 麦克风一直是开着的，绿点从大厅亮回影厅。
 *
 * 所以第四条复位路挂在**页面**上（`CinemaHandler.onHallEntered`，由 `MainActivity`
 * 的导航层在 `homePage` 变成影厅页时调）。它与其余三条并存、互为兜底。
 */
class CinemaCallResetTest {

    @Test
    fun 换到新房号时通话意图必须复位() {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)
        assertEquals("第一次进房先归零，随后用户按了开关", listOf(false, true), audio.calls)

        // 刻意**不**走「Room 变 null」那条漏斗 —— 这里模拟的就是用户报的那种
        // 「某条退房来路漏掉了复位」。房号一变，上一场的意图就不许跟过来。
        handler.onRoomUpdated(room("9WQ2TX"), ME)

        assertEquals(listOf(false, true, false), audio.calls)
        assertEquals("复位之后的状态就是「没开」", false, audio.calls.last())
    }

    @Test
    fun 同一房号里成员上下线不会关掉通话() {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)

        // `markMemberOnline` 的做法：房号不变，members 换一个新的列表。
        handler.onRoomUpdated(room("4F7K2Q", members = listOf(Member(UserId("peer"), "对方", online = false))), ME)

        assertEquals("房号没变就不是新会话，通话不许被掐", listOf(false, true), audio.calls)
    }

    @Test
    fun 同一房号重新进房也不会关掉通话() {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)

        // 拔网重连成功后服务端回 `sys.resume` + 房间快照，房号与断线前是同一个：
        // 宽限期（§9.4）里通话要照旧保留，这正是「刻意跨链路存活」那一条要的效果。
        handler.onRoomUpdated(room("4F7K2Q"), ME)

        assertEquals(listOf(false, true), audio.calls)
    }

    @Test
    fun 退房再进新房号也不会把上一场的通话带过来() {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)

        // 正常退房：`Signaler.leave()` 把 Room 置空，会话收尾关一次通话。
        handler.onRoomUpdated(null, ME)
        assertTrue("退房收尾必须关掉通话（1.7 的既有约定）", audio.calls.last() == false)

        // 再进一场全新的会话。哪怕上面那次收尾因为任何原因没跑到，房号这一道也会兜住。
        handler.onRoomUpdated(room("9WQ2TX"), ME)
        assertEquals("新会话开局必须是「没开通话」", false, audio.calls.last())
    }

    @Test
    fun 没进过房间时不会碰通话() {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        // App 刚起来时 `Room` 本来就是 null（还没进过任何房间），那不是一次退房。
        handler.onRoomUpdated(null, ME)

        assertTrue("没进过房就不该动通话开关", audio.calls.isEmpty())
    }

    // ── 进房**事件**这条路（2026-10-12：0.1.1(2) 上仍能复现的那个缺陷）────────────

    @Test
    fun 进房事件必须复位通话_不依赖状态流送出变化() = runBlocking {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)
        assertEquals(listOf(false, true), audio.calls)

        // 用户报的那条路：退房之后**很快**重进同一个房号。`Room` 是 StateFlow，
        // 这一轮 `null` → 同一个 `Room` 可能被去重成「没有变化」，于是
        // `onRoomUpdated` 一次都不会被调到 —— 复位只能靠这条**事件**。
        handler.onRoomEntered(room("4F7K2Q"))

        assertEquals("进房事件必须把上一场的通话关掉", listOf(false, true, false), audio.calls)
    }

    @Test
    fun 进房事件也记下房号_让随后的退房收尾不会被吞掉() = runBlocking {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        // 只走事件（模拟状态流被去重：进房那一次没送到）。
        handler.onRoomEntered(room("4F7K2Q"))
        handler.setCallEnabled(true)

        // 退房这一轮一定送得到（`null` 与上一次发出的值不同），所以收尾必须真的跑。
        // 若事件没把 `lastRoomId` 记上，这里会早退 —— 通话留在 true 上，
        // 下一次进房就自己复活了。
        handler.onRoomUpdated(null, ME)

        assertEquals("退房必须真的收尾（通话关掉）", false, audio.calls.last())
    }

    @Test
    fun 断线恢复不清通话() = runBlocking {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)

        // `sys.resume` 成功 = 回到**原来那个**房间、原来那场会话（§9.4 的宽限期）。
        // 断线前开着通话的人回来时通话必须还在 —— 这与「进新房要清掉」是两件事，
        // 所以底座把两条路分开派发（`sessionRestored` / `roomEntered`）。
        handler.onSessionRestored(room("4F7K2Q"))

        assertEquals(listOf(false, true), audio.calls)
    }

    @Test
    fun 收尾时通话复位不被幂等守卫挡住() {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)

        // 第一条收尾来路（前台服务 / 界面那条漏斗）。
        handler.onSessionEnded()
        assertEquals(listOf(false, true, false), audio.calls)

        // 守卫此刻认为「这一场已经收过尾了」。用户在又一场里开了通话，而那一场的
        // 进房事件因为任何原因没把标记归位（1.9 的真机现象就是这一类）——
        // 这时收尾**仍然**必须关掉通话：守卫的语义是「事件行里的『会话结束』别写两遍」，
        // 它不该管通话关不关。放在守卫之后，通话就再也关不掉了。
        handler.setCallEnabled(true)
        handler.onSessionEnded()

        assertEquals("通话复位不受幂等守卫管辖", false, audio.calls.last())
    }

    // ── 页面这条路（2026-10-13：0.1.3(4) 上仍能复现的那个缺陷）────────────────────

    @Test
    fun 进影厅页必须把麦克风意图归零() {
        val audio = RecordingCallAudio()
        val handler = CinemaHandler(Dispatchers.Unconfined).apply { bindCallAudio(audio) }

        handler.onRoomUpdated(room("4F7K2Q"), ME)
        handler.setCallEnabled(true)
        assertEquals(listOf(false, true), audio.calls)

        // 用户报的那条路，**一步信令都没有**：
        //   影厅 →（返回键，不调 `Signaler.leave()`）→ 大厅 →（「进入影厅」）→ 影厅
        // 这一轮里 `Room` 一次都没变、没有进房应答、链路也没被收 —— 上面那三条复位路
        // （`onRoomUpdated` 的房号判据 / `onRoomEntered` / `rtc.close()`）一条都到不了。
        // 复位只能挂在「进了影厅页」这件事本身。
        handler.onHallEntered()

        assertEquals("进影厅页必须把麦克风关掉", listOf(false, true, false), audio.calls)
        assertEquals("复位之后的状态就是「没开」", false, audio.calls.last())
    }

    private fun room(
        roomId: String,
        host: UserId = ME,
        members: List<Member> = emptyList(),
    ) = Room(
        roomId = roomId,
        hostUserId = host,
        mode = "cinema",
        members = members,
        modeState = null,
    )
    private companion object {
        val ME = UserId("me")
    }

    @Test
    fun 没绑链路时也能调通话音量_并且连通后把这个值下发() {
        // 用户口径（2026-11-06）：通话没连通时也要能调「接收音量」。
        // 旧实现里未绑链路 ⇒ 订阅的是 idle 状态流、`setCallVolume` 是空操作，
        // 滑块一松手就弹回默认值 —— 看着像控件坏了。
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.setCallVolume(0.3f)
        assertEquals(
            "没链路时状态流里也要是用户调的那个值",
            0.3f,
            handler.callAudio.value.volume,
        )

        val audio = RecordingCallAudio()
        handler.bindCallAudio(audio)
        assertEquals(
            "链路一建好就要把用户之前调好的音量下发过去，否则会被打回 100%",
            listOf(0.3f),
            audio.volumes,
        )
    }
}

/**
 * 记下每一次 `setCallEnabled` / `setCallVolume` 的假音频命令面。
 *
 * 真的 `WebRtcPeerLink.setCallEnabled` 是**幂等**的（与当前值相同就是空操作），
 * 这里刻意不幂等：用例要看的恰恰是「`CinemaHandler` 有没有**发起**这次复位」，
 * 而不是真实链路最后把它落成了什么 —— 后者已经被 `CallAudioPlanTest` 钉住了。
 */
private class RecordingCallAudio : CallAudio {

    private val state = MutableStateFlow(CallAudioState())

    val calls = mutableListOf<Boolean>()

    /** 收到过的音量下发（按顺序）。 */
    val volumes = mutableListOf<Float>()

    override val callAudio: StateFlow<CallAudioState> get() = state

    override fun setCallEnabled(enabled: Boolean) {
        calls += enabled
    }

    override fun setCallVolume(volume: Float) {
        volumes += volume
    }

    override fun setMovieVolumeLimit(enabled: Boolean) = Unit
}