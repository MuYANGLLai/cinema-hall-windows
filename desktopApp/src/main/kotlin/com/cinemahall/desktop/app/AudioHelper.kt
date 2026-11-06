package com.cinemahall.desktop.app

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * W7 的音频 helper 分支（**本轮只预留，不产品化**）。
 *
 * 启动方式：`cinema-hall.exe --audio-helper`。父进程通过 `ProcessBuilder` 拉起它，
 * 从 stdout 读 48k/16-bit/单声道/小端、每块 960 字节（10 ms）的裸 PCM，推进
 * `CustomAudioSource`；关麦 = kill 本进程（Phase 0 实测：注册表 6–139 ms 内释放）。
 *
 * ── 为什么现在就写 ──────────────────────────────────────────────────────────
 * W5 的打包脚本要为它预留分支，而打包验证「同一 exe 的 `--audio-helper` 自举」必须先
 * 有这条分支存在。真正的 `desktop:rtc` 接线在 W7，本轮不碰 RTC。
 *
 * ── 三条硬约定（设计文档 §3）───────────────────────────────────────────────
 *  - **stdout 只放 PCM**，一个字节的文本都不能混进去；
 *  - 日志一律走 stderr；
 *  - 父进程死后 stdin 会 EOF —— 必须自杀，否则会留下一个占着麦克风的孤儿。
 */
fun runAudioHelper(): Nothing {
    val rate = 48_000
    val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
    val info = DataLine.Info(TargetDataLine::class.java, format)
    if (!AudioSystem.isLineSupported(info)) {
        System.err.println("[helper] format unsupported: $format")
        kotlin.system.exitProcess(2)
    }

    val line = AudioSystem.getLine(info) as TargetDataLine
    line.open(format, (rate / 100) * 2 * 4)
    line.start()
    System.err.println("[helper] capturing ${rate}Hz mono 16-bit pid=${ProcessHandle.current().pid()}")

    Thread({
        val stdin = System.`in`
        val sink = ByteArray(64)
        while (true) {
            val n = runCatching { stdin.read(sink) }.getOrElse { -1 }
            if (n < 0) {
                System.err.println("[helper] stdin EOF, parent gone -> exit")
                kotlin.system.exitProcess(0)
            }
        }
    }, "helper-stdin-watch").apply { isDaemon = true }.start()

    val out = System.out
    val buffer = ByteArray(960)
    try {
        while (true) {
            val n = line.read(buffer, 0, buffer.size)
            if (n <= 0) break
            out.write(buffer, 0, n)
            out.flush()
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } finally {
        runCatching { line.stop() }
        runCatching { line.close() }
    }
    kotlin.system.exitProcess(0)
}
