package com.cinemahall.desktop.app

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 最小日志（W3 要求「日志」）。
 *
 * ── 为什么不用 slf4j/logback ────────────────────────────────────────────────
 * 桌面端要的是**用户能发给我们的一段文本**：时间戳 + 一行原因。引入 slf4j 之后
 * 真正的输出位置取决于绑定实现与配置文件，而“日志在哪”恰恰是用户第一个问题。
 * 这里直接写 `data/logs/app.log`，并在诊断页给出绝对路径与最近 200 行。
 *
 * ── 为什么要保留内存环形缓冲 ────────────────────────────────────────────────
 * 诊断页要边跑边看；每次都读文件的尾部会与写入线程抢句柄（Windows 上尤其明显）。
 */
object Log {

    private const val MAX_MEMORY_LINES = 400

    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    private val memory = ArrayDeque<String>(MAX_MEMORY_LINES)

    @Volatile
    private var file: File? = null

    fun init(target: File) {
        target.parentFile?.mkdirs()
        file = target
        info("日志启动 → ${target.absolutePath}")
    }

    fun info(message: String) = write("INFO ", message)

    fun warn(message: String) = write("WARN ", message)

    fun error(message: String, throwable: Throwable? = null) {
        write("ERROR", message + (throwable?.let { " :: ${it::class.java.simpleName}: ${it.message}" } ?: ""))
        throwable?.stackTraceToString()?.lineSequence()?.take(12)?.forEach { write("     ", it) }
    }

    private fun write(level: String, message: String) {
        val line = "${LocalDateTime.now().format(formatter)} $level $message"
        synchronized(memory) {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY_LINES) memory.removeFirst()
        }
        println(line)
        val target = file ?: return
        runCatching { target.appendText(line + System.lineSeparator()) }
    }

    fun tail(maxLines: Int = 200): List<String> = synchronized(memory) {
        memory.takeLast(maxLines)
    }

    fun filePath(): String = file?.absolutePath ?: "(未初始化)"
}
