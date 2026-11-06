package com.cinemahall.core.media

/**
 * 内存里的 [RandomAccessSource]，专供单测。
 *
 * [maxChunk] 用来**故意制造短读**：真实的 `FileChannel` 与 `InputStream` 都会短读，
 * 而「短读被补齐」这条正是最容易被漏掉、又最难在真机上定位的路径之一。
 */
class ByteArraySource(
    private val bytes: ByteArray,
    private val maxChunk: Int = Int.MAX_VALUE,
) : RandomAccessSource {

    override val sizeBytes: Long = bytes.size.toLong()

    /** 被读了几次。用来证明「大文件只抽样读了三段」，而不是偷偷读了整文件。 */
    var readCalls: Int = 0
        private set

    /** 累计读出的字节数。 */
    var bytesRead: Long = 0L
        private set

    override fun readAt(offset: Long, length: Int): ByteArray {
        readCalls += 1
        if (offset < 0 || offset >= bytes.size) return ByteArray(0)

        val from = offset.toInt()
        val available = bytes.size - from
        val count = minOf(length, maxChunk, available)
        if (count <= 0) return ByteArray(0)

        bytesRead += count
        return bytes.copyOfRange(from, from + count)
    }
}