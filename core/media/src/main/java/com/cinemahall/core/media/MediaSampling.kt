package com.cinemahall.core.media

/** 一次抽样读的定位：[offset] 起读 [length] 字节。 */
data class SampledBlock(
    val index: Int,
    val offset: Long,
    val length: Int,
)

/**
 * §7 的分块抽样规则。
 *
 * 单独抽成对象而不是写在 [MediaFingerprinter] 里，是因为它是**协议级常量**：
 * 只要两端用不同的规则算摘要，比对结果就毫无意义，所以这几个数字必须在
 * 一处定义、两处（本机与对端）共用同一条代码路径。
 */
object MediaSampling {

    const val BLOCK_BYTES: Int = 1024 * 1024

    const val BLOCK_BYTES_LONG: Long = BLOCK_BYTES.toLong()

    /** 小于此大小就退化为「整文件一次 SHA-256」（§7）。 */
    const val WHOLE_FILE_BELOW_BYTES: Long = 3L * BLOCK_BYTES_LONG

    fun isWholeFile(sizeBytes: Long): Boolean = sizeBytes < WHOLE_FILE_BELOW_BYTES

    /**
     * 返回每块的偏移与长度。整文件模式下只有一块（`offset = 0`、`length = size`）。
     *
     * 正常模式的三个偏移是 `0`、`size / 2`、`size − 1 MiB` —— 注意当 `size` 接近
     * 3 MiB 时这三段**会重叠**。这是刻意照抄 §7 的公式：重叠只是多算一点重复字节，
     * 而为了「不重叠」去改动偏移，就等于单方面改了协议，两端摘要会算不到一起。
     */
    fun blocks(sizeBytes: Long): List<SampledBlock> {
        require(sizeBytes >= 0) { "文件大小不可能为负：$sizeBytes" }

        if (isWholeFile(sizeBytes)) {
            // 已确认 size < 3 MiB，toInt() 不会截断。
            return listOf(SampledBlock(index = 0, offset = 0L, length = sizeBytes.toInt()))
        }

        return listOf(
            SampledBlock(index = 0, offset = 0L, length = BLOCK_BYTES),
            SampledBlock(index = 1, offset = sizeBytes / 2, length = BLOCK_BYTES),
            SampledBlock(index = 2, offset = sizeBytes - BLOCK_BYTES_LONG, length = BLOCK_BYTES),
        )
    }
}