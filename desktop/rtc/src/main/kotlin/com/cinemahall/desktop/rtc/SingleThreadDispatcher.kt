package com.cinemahall.desktop.rtc

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * 生产用的单线程执行器。
 *
 * ── 为什么是 daemon 线程 ────────────────────────────────────────────────────
 * RTC 这一路不该把进程活着这件事扛住：窗口关了就该退出。helper 子进程与 native 库的
 * 生命周期由 [DesktopPeerLink.close] 与 shutdown hook 负责，不靠「线程还在跑」兜底。
 *
 * ── 为什么异常不往上抛 ──────────────────────────────────────────────────────
 * 这是 native 回调的收口点（libwebrtc 的观察者线程也会往这里 post）。异常若逃出去，
 * 单线程执行器会换一条新线程继续跑，而状态机里的「一轮 offer 在飞」还停在旧值，
 * 之后所有协商都会被 `offerInFlight` 挡住 —— 表现为「链路卡在协商中，怎么都不动」。
 * 所以这里**就地打印并继续**，把失败留给 `onFailure` 那条路径表达。
 */
class SingleThreadDispatcher(
    name: String = "desktop-rtc",
) : LinkDispatcher, AutoCloseable {

    private val threadName = name

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, name).apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    System.err.println("[rtc] 执行器里的未捕获异常：${error.message}")
                    error.printStackTrace()
                }
            }
        },
    )

    override fun execute(block: () -> Unit) {
        executor.execute {
            runCatching(block).onFailure {
                System.err.println("[rtc] 状态机动作失败：${it.message}")
                it.printStackTrace()
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /**
     * 同步执行：等这一块在 dispatcher 线程上跑完再返回。
     *
     * 只给**收尾**用。原因：`close()` 走的是 `shutdownNow()`，**会丢弃排队任务**，
     * 所以「先 `execute { link.close() }`、再 `close()`」等于什么都没做 —— 实测
     * `PeerConnection` 与 native 工厂都没释放，进程带着活的 native 线程退出就是
     * 0xC0000005（访问违规）。收尾必须就地跑完再停执行器。
     *
     * 已经在 dispatcher 线程上时直接就地执行，避免自己等自己。
     */
    fun runBlocking(block: () -> Unit) {
        if (Thread.currentThread().name == threadName) {
            runCatching(block)
            return
        }
        runCatching { executor.submit(Runnable { block() }).get(5, TimeUnit.SECONDS) }
            .onFailure {
                System.err.println("[rtc] 同步收尾失败：${it.message}")
                it.printStackTrace()
            }
    }

    override fun close() {
        shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }
}
