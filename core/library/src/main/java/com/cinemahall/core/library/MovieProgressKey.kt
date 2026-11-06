package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import com.cinemahall.core.media.MediaSignatureComparator
import java.security.MessageDigest

/**
 * 观看进度的键：由片源摘要（§7 的 `{fileName,size,blocks[]}`）算出来的一串十六进制（§16.6）。
 *
 * ── 为什么不拿 URI 当键 ─────────────────────────────────────────────────────
 * `content://` URI 是**本机**的授权标识（形如
 * `content://com.android.providers.media.documents/document/video%3A1234`），
 * 同一个文件在两台设备上必然是两串不同的 URI。拿它当键 ⇒「同一部片两台各记一份、
 * 永远对不上」，而用户要的恰恰是「上次在影厅看到哪」。
 *
 * 摘要里的文件名 + 大小 + 块哈希在两端**相同** —— 这正是 §7 的比对能通过的前提。
 * 所以「§7 判定一致」与「进度键相同」是同一件事，不会出现「能对上进度却拦在开播」。
 *
 * ── 三个口径必须与 §7 完全一致 ──────────────────────────────────────────────
 *
 *  1. 文件名归一化**调用** [MediaSignatureComparator.normalizeFileName]（NFC），
 *     不另写一份。两端文件名一个 NFC 一个 NFD 时，自己写的归一化迟早会与 §7 分岔，
 *     症状就是「同一部片两台各记一份进度」。
 *  2. **大小写保持敏感，刻意不做小写化**。§7 的文件名比对就是大小写敏感的；
 *     若这里小写化，进度键会比开播判据更宽松，于是出现「进度对得上、开播却被拦」
 *     这种自相矛盾的状态 —— 用户看着一条进度，点开播报文件名不一致。
 *  3. 各段之间用 `\u0000` 分隔。直接把几段拼起来会让 `("movie1", 23)` 与
 *     `("movie12", 3)` 撞成同一个键（都是 `"movie123"`），
 *     键相同就意味着**两部不同的片共用一条进度**。
 *
 * ── 为什么只取前 32 个十六进制字符 ──────────────────────────────────────────
 * 完整 SHA-256 是 64 个字符，出现在界面、日志、诊断里都太长；16 字节（128 位）对
 * 「区分一部影片」这个用途绰绰有余，而且仍是定长、仍是小写十六进制（好比较、好截图）。
 */
object MovieProgressKey {

    /** 键的十六进制字符数（= 16 字节）。 */
    private const val KEY_CHARS = 32

    /** 参与哈希的字节数。 */
    private const val KEY_BYTES = KEY_CHARS / 2

    /**
     * 分段分隔符。用 `\u0000` 而不是 `,` 或 `|`：文件名里可以出现任何可打印字符，
     * 而 `\u0000` 不可能出现在文件名里（文件系统层面就非法），所以它不会与内容混淆。
     */
    private const val SEPARATOR = "\u0000"

    /**
     * 十六进制查表。用它而不是 `String.format("%02x", b)`：后者每个字节都要建一个
     * `Formatter`（进度写入是每 15 秒一次的热路径），而且 `%02x` 直接吃负的 `Byte`
     * 会输出 `ffffff80` 这种八位结果 —— 必须先 `and 0xff` 才对，是这里最容易踩的坑。
     */
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun of(signature: MediaSignature): String =
        of(signature.fileName, signature.sizeBytes, signature.blocks)

    /**
     * 待哈希的原文是 `归一化文件名 + \u0000 + sizeBytes + \u0000 + blocks 用逗号拼起来`。
     *
     * 返回**小写十六进制**、定长 [KEY_CHARS] 个字符。不抛异常：`MessageDigest.getInstance`
     * 取的是 JDK 保证存在的 `SHA-256`。
     */
    fun of(fileName: String, sizeBytes: Long, blocks: List<String>): String {
        val normalized = MediaSignatureComparator.normalizeFileName(fileName)
        val request = normalized + SEPARATOR + sizeBytes + SEPARATOR + blocks.joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(request.toByteArray(Charsets.UTF_8))

        return buildString(KEY_CHARS) {
            for (index in 0 until KEY_BYTES) {
                val value = digest[index].toInt() and 0xff
                append(HEX_DIGITS[value shr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }
}
