package com.cinemahall.desktop.app

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * 播放时阻止系统休眠（W3 要求）。
 *
 * ── 为什么不用 `java.awt.Robot` / 定时移动鼠标这类偏方 ──────────────────────
 * 那是“假装有人在用”，会让用户的屏保/锁屏策略失效，副作用比收益大。
 * `SetThreadExecutionState` 是 Windows 给影音类应用的**正式开关**：只声明本线程
 * 需要系统/显示保持唤醒，松开即恢复原策略。
 *
 * ── 为什么要 `ES_CONTINUOUS` ────────────────────────────────────────────────
 * 不带它的话，标志位只对“下一次睡眠判断”生效一次；带上它，状态一直保持到我们
 * 主动清掉。代价是**必须保证退出时清掉** —— 所以 [disable] 幂等且挂在窗口关闭路径上。
 */
object KeepAwake {

    private const val ES_CONTINUOUS = -0x80000000 // 0x80000000
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    private interface Kernel32 : Library {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private val kernel32: Kernel32? = runCatching {
        Native.load("kernel32", Kernel32::class.java)
    }.getOrNull()

    @Volatile
    private var enabled = false

    val available: Boolean get() = kernel32 != null

    val active: Boolean get() = enabled

    fun enable(): Boolean {
        val api = kernel32 ?: return false
        if (enabled) return true
        val result = runCatching {
            api.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
        }.getOrNull()
        enabled = (result != null && result != 0)
        Log.info("防休眠 ${if (enabled) "已开启" else "开启失败"}")
        return enabled
    }

    fun disable() {
        val api = kernel32 ?: return
        if (!enabled) return
        runCatching { api.SetThreadExecutionState(ES_CONTINUOUS) }
        enabled = false
        Log.info("防休眠已关闭")
    }
}
