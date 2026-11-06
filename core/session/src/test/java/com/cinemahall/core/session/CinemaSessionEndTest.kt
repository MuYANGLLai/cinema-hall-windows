package com.cinemahall.core.session

import com.cinemahall.core.media.MediaRef
import com.cinemahall.core.media.PlaybackTarget
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.UserId
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CinemaHandler.onSessionEnded()` 的**幂等性**（阶段一 1.8 补）。
 *
 * ── 为什么 1.8 才需要这一组 ──────────────────────────────────────────────────
 * 1.7 时这个入口只有一条来路（`Room` 变 null 那条漏斗），「别重复调」靠的是
 * `lastRoomId` 那个判据。1.8 的前台服务也要走同一条收尾 —— 划掉最近任务、
 * 以及「房间变 null 而界面已经不在了」两条路 —— 而它与那条漏斗之间**没有共享的
 * 「收过没有」**：`Signaler.leave()` 一执行 `Room` 就变 null，界面那侧的
 * `LaunchedEffect` 会再调一次。
 *
 * 重复调用的代价不是崩溃，而是**诊断页上多一条「会话结束」**（`CinemaSyncEngine.endSession()`
 * 每次都写），而验收要读的恰好是事件行的末条 —— 看起来像出过两次事。
 *
 * 这一组钉住的就是「守卫真的在 `CinemaHandler` 里，而不是散在三个调用方各写一遍」。
 */
class CinemaSessionEndTest {

    @Test
    fun 会话收尾只做一次() {
        val player = CountingPlayer()
        val handler = CinemaHandler(Dispatchers.Unconfined)
        val engine = handler.attachPlayback(player)

        handler.onRoomUpdated(room(), UserId("me"))
        player.play()

        // 基线取在**进房之后**：`onRoomUpdated` 会经 `applyHost` → `setRole` 清一次同步
        // 状态（换角色的时间轴立刻是错的），那一次 `setSpeed` 不属于收尾。
        val base = player.speedWrites

        handler.onSessionEnded()

        assertFalse("收尾必须真的暂停播放器", player.isPlaying)
        assertEquals("收尾要清一次同步状态（resetSyncState 会把速率写回 1.0）", base + 1, player.speedWrites)
        assertTrue(engine.status.value.lastEvent.contains("会话结束"))

        // 第二条来路（前台服务 / 界面漏斗）紧接着也会调一次。
        handler.onSessionEnded()
        assertEquals("第二次收尾不该再碰播放器", base + 1, player.speedWrites)
    }

    @Test
    fun 重新进房之后收尾会重新算数() {
        val player = CountingPlayer()
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.attachPlayback(player)

        handler.onRoomUpdated(room(), UserId("me"))
        val first = player.speedWrites
        handler.onSessionEnded()
        assertEquals(first + 1, player.speedWrites)

        // 同一台设备再进一次房 = **新的一场会话**，收尾不能因为「上一场收过了」被吞掉。
        handler.onRoomUpdated(room(), UserId("me"))
        val second = player.speedWrites
        handler.onSessionEnded()
        assertEquals(second + 1, player.speedWrites)
    }

    @Test
    fun 没进过房间时收尾什么都不做() {
        val player = CountingPlayer()
        val handler = CinemaHandler(Dispatchers.Unconfined)
        handler.attachPlayback(player)

        // App 刚起来、或前台服务刚起来时房间本来就是 null。那时不该写「会话结束」——
        // 它会让第一次看日志的人以为出过事（`lastRoomId` 那段注释说的就是这件事，
        // 守卫把这个判断从「调用方自己判」搬进了方法内部）。
        handler.onSessionEnded()
        assertEquals(0, player.speedWrites)
    }

    private fun room() = Room(
        roomId = "4F7K2Q",
        hostUserId = UserId("me"),
        mode = "cinema",
        members = emptyList(),
        modeState = null,
    )
}

/**
 * 只数「引擎有没有碰播放器」的假播放器。
 *
 * `setSpeed` 是这里唯一有用的计数点：`CinemaSyncEngine.resetSyncState()` 每次都
 * **无条件**调一次 `player.setSpeed(1f)`（那是 2026-10-12 那条「速率必须真的写回
 * 播放器」的修复），所以「收尾到底跑了几次」在这台假播放器上就等于
 * 「`setSpeed` 被写了几次」。用 `pause()` 数不出来 —— 第一次收尾之后播放器已经是
 * 停的，第二次再调也是空操作，两种情况读数一样。
 */
private class CountingPlayer : PlaybackTarget {

    var speedWrites = 0
        private set

    private var playing = false

    override val currentPositionMs: Long get() = 0L

    override val isPlaying: Boolean get() = playing

    override val isPlayRequested: Boolean get() = playing

    override val isBuffering: Boolean get() = false

    override val isReady: Boolean get() = true

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }


    // 2026-10-26 端口新增：选片。假播放器不真加载文件。
    override fun setMediaItem(ref: MediaRef) = Unit
    override fun seekTo(positionMs: Long) = Unit

    override fun setSpeed(speed: Float) {
        speedWrites += 1
    }
}
