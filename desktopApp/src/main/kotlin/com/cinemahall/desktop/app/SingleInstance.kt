package com.cinemahall.desktop.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * 单实例（W3 要求）。
 *
 * ── 为什么用文件锁而不是“找个端口/查进程名” ────────────────────────────────
 * 文件锁是 OS 级独占：进程被强杀时锁自动释放（不会留下一个“以为还在运行”的假状态），
 * 而端口方案在崩溃后要靠超时判断，进程名方案会被同名的开发版误伤。
 *
 * ── 为什么要带 profile ──────────────────────────────────────────────────────
 * 门禁要求「两个 Windows 实例能本地播放」（双机对测时同一台机器要跑房主与从端）。
 * 默认 `default` 保证普通用户双击第二下只弹一个已有窗口；测试时用
 * `--profile=b` 起第二实例，两者互不干扰（连 data 目录都能分开）。
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {

        fun acquire(file: File): SingleInstance? {
            file.parentFile?.mkdirs()
            val channel = runCatching { RandomAccessFile(file, "rw").channel }.getOrNull() ?: return null
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: Throwable) {
                null
            }
            if (lock == null) {
                runCatching { channel.close() }
                return null
            }
            return SingleInstance(channel, lock)
        }
    }
}
