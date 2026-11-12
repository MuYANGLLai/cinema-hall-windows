package com.cinemahall.desktop.app

import com.cinemahall.desktop.rtc.AudioInputs
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
 *
 * ── 采集设备（2026-11-12 用户口径「下拉选麦克风输入源」）───────────────────────
 * 父进程把 `Mixer.Info.name` 通过 `--audio-input=<name>` 传进来；没传 / 名字已失效
 * （拔了 USB 麦）就回退系统默认，只在 stderr 留一行 —— **不能因为选错设备就完全没声音**。
 */
fun runAudioHelper(args: List<String>): Nothing {
    val rate = 48_000
    val preferred = args.firstOrNull { it.startsWith("--audio-input=") }
        ?.removePrefix("--audio-input=")
        ?.takeIf { it.isNotBlank() }

    val line = AudioInputs.open(preferred)
    if (line == null) {
        System.err.println("[helper] 没有可用的采集线（format=${AudioInputs.FORMAT}）")
        kotlin.system.exitProcess(2)
    }

    runCatching { line.open(AudioInputs.FORMAT, (rate / 100) * 2 * 4) }
        .onFailure {
            System.err.println("[helper] 打开采集线失败：${it.message}")
            kotlin.system.exitProcess(2)
        }
    line.start()
    System.err.println(
        "[helper] capturing ${rate}Hz mono 16-bit pid=${ProcessHandle.current().pid()} " +
            "device=${preferred ?: "系统默认"}",
    )

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
