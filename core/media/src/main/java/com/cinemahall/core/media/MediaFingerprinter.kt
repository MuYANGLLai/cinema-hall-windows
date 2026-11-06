package com.cinemahall.core.media

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * 按 §7 的规则算出一份 [MediaSignature]。
 *
 * ── 为什么是「抽样」而不是整文件哈希 ────────────────────────────────────────
 * 一部 20 GB 的影片整文件哈希要读满 20 GB：手机上既慢又费电，而且这一步发生在
 * **每次开播前**。首/中/尾三段 1 MiB 只要 3 MiB 磁盘读，几百毫秒量级；
 * 而「同一文件」的判定本来就不需要密码学强度 —— 首/中/尾都撞上的概率，
 * 对「两个人各自下载的同一部片」这种场景已经足够。
 *
 * 代价是明确的：**它能发现「不同」，不能证明「相同」**。文件中间被改动、
 * 而改动恰好落在三个取样窗口之外时会被漏掉。这是 §7 选择的取舍，不是缺陷。
 */
object MediaFingerprinter {

    /**
     * 算出摘要。**不负责关闭** [source] —— 它的生命周期归调用方，
     * 因为同一个来源可能还要被用来做别的事（例如交给 ExoPlayer 播放）。
     */
    fun fingerprint(fileName: String, source: RandomAccessSource): MediaSignature {
        val size = source.sizeBytes
        val blocks = MediaSampling.blocks(size).map { block ->
            sha256Hex(readFully(source, block.offset, block.length))
        }
        return MediaSignature(fileName = fileName, sizeBytes = size, blocks = blocks)
    }

    /** 循环补齐短读，直到读满 [length] 或触到文件尾。 */
    private fun readFully(source: RandomAccessSource, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)

        val out = ByteArrayOutputStream(length)
        var position = offset
        var remaining = length

        while (remaining > 0) {
            val chunk = source.readAt(position, remaining)
            // 空返回 = 已经在文件尾。**必须靠这一条退出**：来源若完全不推进，
            // 没有它就是一个死循环，而症状是界面卡死、连日志都没有。
            if (chunk.isEmpty()) break
            out.write(chunk)
            position += chunk.size
            remaining -= chunk.size
        }

        return out.toByteArray()
    }

    /**
     * 小写十六进制 SHA-256。
     *
     * 每次新建 `MessageDigest` 实例（而不是共享一个静态实例）：`MessageDigest`
     * **不是线程安全的**，共享就得自己加锁或上 `ThreadLocal`，而这里一次开播
     * 只调用三次，收益为零、出错代价却是「摘要随机对不上」这种最难查的错。
     */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val value = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[value ushr 4]
            out[i * 2 + 1] = HEX[value and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}