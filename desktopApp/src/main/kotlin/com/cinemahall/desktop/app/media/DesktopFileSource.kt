package com.cinemahall.desktop.app.media

import com.cinemahall.core.media.MediaFingerprinter
import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.RandomAccessSource
import java.io.File
import java.io.RandomAccessFile

/**
 * 桌面端的 [RandomAccessSource]：`RandomAccessFile` 直接读。
 *
 * ── 为什么不复用 Android 那份 ────────────────────────────────────────────────
 * `core:media-android` 的 `ContentResolverMediaSource` 走的是 `ContentResolver` +
 * `ParcelFileDescriptor`，桌面上没有这两个东西。`RandomAccessSource` 这个接口存在
 * 的全部意义就在于此：抽样与哈希的规则（[MediaFingerprinter]）两端共用一份，
 * 「字节从哪来」各写各的。
 *
 * ⚠️ `RandomAccessFile` **不是线程安全的**，而 seek + read 是两步操作：并发调用会
 * 互相把游标挪走，读出来的段是错的（且不报错，只是摘要对不上）。所以整段加锁 ——
 * 摘要计算本来就在单线程里跑，这把锁只防御「将来有人并发调」。
 */
class DesktopFileSource(file: File) : RandomAccessSource {

    private val handle = RandomAccessFile(file, "r")
    private val lock = Any()

    override val sizeBytes: Long = handle.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset >= sizeBytes) return ByteArray(0)
        val wanted = minOf(length.toLong(), sizeBytes - offset).toInt()
        val buffer = ByteArray(wanted)
        synchronized(lock) {
            handle.seek(offset)
            var read = 0
            while (read < wanted) {
                val count = handle.read(buffer, read, wanted - read)
                if (count <= 0) break
                read += count
            }
            return if (read == wanted) buffer else buffer.copyOf(read)
        }
    }

    override fun close() {
        runCatching { handle.close() }
    }

    companion object {

        /**
         * 算一份摘要（§7 的首/中/尾三段抽样）。
         *
         * 失败返回 null 而不是抛：调用点在界面层，一次读不到不该把整个影厅页打崩 ——
         * 界面拿到 null 就继续显示「本机片源未上报」，用户换一个文件即可。
         * **耗时与文件大小无关**（固定 3 MiB 读），但仍是磁盘 IO，调用方要放到 IO 线程。
         */
        fun fingerprint(file: File): MediaSignature? = runCatching {
            if (!file.isFile) return@runCatching null
            DesktopFileSource(file).use { source ->
                MediaFingerprinter.fingerprint(file.name, source)
            }
        }.getOrNull()
    }
}
