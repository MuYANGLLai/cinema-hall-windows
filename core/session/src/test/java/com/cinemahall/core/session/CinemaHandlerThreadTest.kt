package com.cinemahall.core.session

import com.cinemahall.core.media.MediaRef
import com.cinemahall.core.media.PlaybackTarget
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.UserId
import com.cinemahall.core.protocol.Envelope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * 底座回调**必须落在引擎线程上**（阶段一 · 2026-10-12 真机缺陷的修复）。
 *
 * ── 这一组要钉住的那个闪退 ───────────────────────────────────────────────────
 * 用户报的现象：「两端通话打开的情况下，一端退出房间，另一端**直接闪退**」。
 * 根因不在通话里，在**线程**上：
 *
 * `sys.peer_left` 走的是 WebSocket 那条路 —— `WsSignaler.receivePath`
 * （`Dispatchers.IO.limitedParallelism(1)`），于是
 * `CinemaHandler.onPeerLeft()` → `CinemaSyncEngine.onPeerUnavailable()` 整个跑在
 * IO 线程上，而它的第一句就是 `player.isPlayRequested`。Media3 的
 * `ExoPlayerImpl.verifyApplicationThread()` 会当场抛
 * `IllegalStateException("Player is accessed on the wrong thread")`；
 * 这条协程没有 `try/catch`，异常一路冒到
 * `Thread.getDefaultUncaughtExceptionHandler()` —— 进程被杀。**是崩溃，不是 ANR。**
 *
 * 同一根因还有两条来路（都在 IO 线程上）：`onMessage` 的 `cinema.*` 播放动作、
 * 以及 `onSessionRestored`（`sys.resume` 的应答，它经 `applyHost` → `setRole` →
 * `resetSyncState()` 写 `player.setSpeed(1f)`）。
 *
 * ── 为什么用「播放器被碰时在哪条线程」当判据 ────────────────────────────────────
 * 假播放器把**每一次**成员访问（含只读属性）都记下线程名，而不只是记
 * `play()` / `pause()`：崩的那一句恰恰是一个**读**（`isPlayRequested`）。
 * 只记写入的话，「读在 IO 线程、写在主线程」这种真实故障会漏过去 —— 而它正是
 * 用户遇到的那一种。
 *
 * ── 为什么不用默认的 `Dispatchers.Main.immediate` ──────────────────────────────
 * JVM 单测里没有 Android 主线程（`kotlinx-coroutines-android` 不在测试类路径上），
 * 用它只会得到一句「Module with the Main dispatcher is missing」。所以每个用例都
 * 显式注入一条**有名字的单线程**调度器，判据就是那条线程的名字。
 */
class CinemaHandlerThreadTest {

    @Test
    fun 对端掉线回调落在引擎线程上() = withEngineThread { dispatcher ->
        val handler = CinemaHandler(dispatcher)
        val player = TouchRecordingPlayer()
        handler.attachPlayback(player)
        handler.onRoomUpdated(room(), ME)
        player.play()

        player.forgetTouch()
        // 这条来路在真机上是 WsSignaler 的 receivePath（IO 线程）。
        runBlocking { handler.onPeerLeft(UserId("peer")) }

        assertTouchedOnEngineThread(player)
        assertFalse("对端走了要暂停本机（§9.4）", player.isPlaying)
    }

    @Test
    fun 播放动作回调落在引擎线程上() = withEngineThread { dispatcher ->
        val handler = CinemaHandler(dispatcher)
        val player = TouchRecordingPlayer()
        handler.attachPlayback(player)
        // 阶段二 P4：从端只接受**房主**的控制（契约 §7）。本用例验的是「回调落在哪条
        // 线程上」，所以把对端设成房主、本机当从端 —— 那样这条 pause 才会被真正执行。
        handler.onRoomUpdated(room(host = PEER), ME)
        player.play()

        player.forgetTouch()
        runBlocking { handler.onMessage(CinemaPlayback.ACTION_PAUSE, pauseEnvelope()) }

        assertEquals(listOf(CinemaPlayback.ACTION_PAUSE), handler.receivedActions.value)
        assertTouchedOnEngineThread(player)
        assertFalse("收到 pause 要暂停本机", player.isPlaying)
    }

    @Test
    fun 会话恢复回调落在引擎线程上() = withEngineThread { dispatcher ->
        val handler = CinemaHandler(dispatcher)
        val player = TouchRecordingPlayer()
        handler.attachPlayback(player)

        // 先在「别人是房主」的房间里把本端定成从端，恢复时才会有一次真的换角色 ——
        // `setRole` 对「角色没变」是空操作，而那一次空操作不碰播放器。
        handler.onRoomUpdated(room(roomId = "4F7K2Q", host = UserId("peer")), ME)

        player.forgetTouch()
        // sys.resume 的应答：applyHost → setRole → resetSyncState → player.setSpeed(1f)。
        runBlocking { handler.onSessionRestored(room(roomId = "9WQ2TX", host = ME)) }

        assertTouchedOnEngineThread(player)
        assertTrue("速率必须真的写回播放器（resetSyncState 的既有约定）", player.speedWrites > 0)
    }

    @Test
    fun 引擎还没接上时回调不崩() = withEngineThread { dispatcher ->
        // 前台服务与「界面还没起来」这两个时机都可能先收到底座回调。
        // 那时 engine 是 null，回调必须只是空操作，而不是把协程崩掉。
        val handler = CinemaHandler(dispatcher)

        runBlocking {
            handler.onPeerLeft(UserId("peer"))
            handler.onMessage(CinemaPlayback.ACTION_PAUSE, pauseEnvelope())
            handler.onSessionRestored(room())
        }

        assertNull("没有引擎时不该凭空造出一个", handler.syncEngine)
    }

    @Test
    fun 引擎比房间晚建时仍然认得房主() = withEngineThread { dispatcher ->
        // 真机形状（2026-11-06）：「自动进房」跑在影厅页之外，房间先到、引擎后建。
        // 那时 `applyHost()` 已经跑过一遍，但 `engine` 还是 null —— 什么都灌不进去。
        // 之后建引擎时若只补 `setRole`，`hostUserId` 就永远是 null，于是房主发来的
        // `heartbeat` / `state_snapshot` 全被 `mayApply()` 按「从端只接受房主的控制
        // （房主=未定）」丢掉：从端 `anchored=false` 一动不动，**且没有任何报错**。
        // 这是纯时序竞态 —— 引擎先建就正常，后建就永久失效。
        val handler = CinemaHandler(dispatcher)

        // ① 房间先到：本机是从端，房主是 PEER。
        handler.onRoomUpdated(room(host = PEER), ME)

        // ② 引擎后建（影厅页首次组合才 attach）。
        val player = TouchRecordingPlayer()
        val engine = handler.attachPlayback(player)

        // ③ 房主的心跳到：必须被接受并用来锚定。
        runBlocking {
            handler.onMessage(
                CinemaPlayback.ACTION_HEARTBEAT,
                Envelope(
                    type = CinemaPlayback.TYPE_HEARTBEAT,
                    seq = 1L,
                    roomId = "4F7K2Q",
                    from = PEER.value,
                    sentAtMs = 0L,
                    payload = CinemaPlaybackCodec.encodeHeartbeat(
                        CinemaHeartbeat(
                            isPlaying = true,
                            positionMs = 42_000L,
                            senderClockMs = 0L,
                        ),
                    ),
                ),
            )
        }

        // 等引擎线程把这条消息跑完（onMessage 是 suspend，Unconfined 之外要等一下）。
        eventually { engine.status.value.isAnchored }
        assertTrue(
            "从端必须认得房主：心跳要能建立时间轴，实际 lastEvent=${engine.status.value.lastEvent}",
            engine.status.value.isAnchored,
        )
        assertEquals(
            "锚点位置要等于心跳里的位置（本机时钟为 0 时无补偿）",
            42_000L,
            engine.status.value.anchoredMediaMs,
        )
    }

    private fun pauseEnvelope() = Envelope(
        type = CinemaPlayback.TYPE_PAUSE,
        seq = 1L,
        roomId = "4F7K2Q",
        from = "peer",
        sentAtMs = 0L,
        payload = CinemaPlaybackCodec.encodePosition(CinemaPosition(positionMs = 0L, senderClockMs = 0L)),
    )

    private fun room(roomId: String = "4F7K2Q", host: UserId = ME) = Room(
        roomId = roomId,
        hostUserId = host,
        mode = "cinema",
        members = emptyList(),
        modeState = null,
    )

    /**
     * 判据取**前缀**而不是全等：协程跑在这条线程上时，线程名会被加上一个
     * ` @coroutine#N` 后缀（协程的调试信息），全等断言会假失败。
     * 测试线程叫 `Test worker`，与引擎线程名不会撞。
     */
    private fun assertTouchedOnEngineThread(player: TouchRecordingPlayer) {
        val actual = player.lastTouchThread
        assertTrue(
            "引擎碰播放器必须发生在引擎线程（$ENGINE_THREAD）上，实际在：$actual",
            actual != null && actual.startsWith(ENGINE_THREAD),
        )
    }

    private fun withEngineThread(block: (ExecutorCoroutineDispatcher) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, ENGINE_THREAD).apply { isDaemon = true }
        }.asCoroutineDispatcher()
        try {
            block(dispatcher)
        } finally {
            dispatcher.close()
        }
    }

    /**
     * 等引擎线程把一件事跑完（最多 2 秒）。
     *
     * `onMessage` 会被 `onEngineThread` 丢到引擎线程上执行，而断言在测试线程上跑 ——
     * 直接断言会偶发假失败（本机够快时通常已经跑完，CI 上就未必）。
     */
    private fun eventually(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private companion object {
        const val ENGINE_THREAD = "cinema-engine-thread"
        val ME = UserId("me")

        /** 对端。信封里的 `from` 就是它 —— 阶段二起「它是不是房主」决定消息能不能执行。 */
        val PEER = UserId("peer")
    }
}

/**
 * 把**每一次**访问都记下线程名的假播放器。
 *
 * 只读属性也记 —— 那才是崩的那一句（`player.isPlayRequested`）。
 */
private class TouchRecordingPlayer : PlaybackTarget {

    private var playing = false

    /** 最近一次被访问时所在线程的名字；`null` = 自上次 [forgetTouch] 以来没被碰过。 */
    var lastTouchThread: String? = null
        private set

    var speedWrites = 0
        private set

    fun forgetTouch() {
        lastTouchThread = null
    }

    override val currentPositionMs: Long get() = touchAnd(0L)

    override val isPlaying: Boolean get() = touchAnd(playing)

    override val isPlayRequested: Boolean get() = touchAnd(playing)

    override val isBuffering: Boolean get() = touchAnd(false)

    override val isReady: Boolean get() = touchAnd(true)

    override fun play() {
        touch()
        playing = true
    }

    override fun pause() {
        touch()
        playing = false
    }


    // 2026-10-26 端口新增：选片。假播放器不真加载文件。
    override fun setMediaItem(ref: MediaRef) = Unit
    override fun seekTo(positionMs: Long) = touch()

    override fun setSpeed(speed: Float) {
        touch()
        speedWrites += 1
    }

    private fun touch() {
        lastTouchThread = Thread.currentThread().name
    }

    private fun <T> touchAnd(value: T): T {
        touch()
        return value
    }
}
