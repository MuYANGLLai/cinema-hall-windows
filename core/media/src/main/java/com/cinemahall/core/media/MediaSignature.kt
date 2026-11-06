package com.cinemahall.core.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一份媒体文件的摘要（《技术方案评审》§7，源自任务书 §5.2）。
 *
 * 字段名与线上 JSON 键名**一字不差**：`{fileName, size, blocks[]}`（§5.3 的
 * `cinema.hello.mediaSignature`）。这里用 [SerialName] 把 Kotlin 侧更明确的
 * `sizeBytes` 映射到线名 `size`，而不是把属性直接命名成 `size` —— 后者在阅读时
 * 太容易与「[blocks] 的个数」混淆，而这两个数字含义完全不同。
 *
 * ── 三个字段各自在解决什么 ──────────────────────────────────────────────────
 *
 *  - [fileName] **原样保存调用方给的字符串**，写入时不做 Unicode 归一化。
 *    归一化放到比对时（见 [MediaSignatureComparator.normalizeFileName]）才做，
 *    这样界面上显示的仍是文件系统里的真实名字。反过来若在写入时就归一化，
 *    用户会看到与自己文件管理器里不一样的名字，而「为什么名字被改了」极难解释。
 *  - [sizeBytes] 用 `Long` 而不是 `Int`：影片动辄几十 GB，`Int` 在 2 GiB 处溢出。
 *  - [blocks] 是**分块抽样哈希**，长度要么是 3（正常），要么是 1（`size < 3 MiB`
 *    时按 §7 退化成整文件一次 SHA-256）。用 `List` 而不是三个固定字段，
 *    正是为了让这两种形态共用一种表示、共用一套比对与编解码。
 */
@Serializable
data class MediaSignature(
    val fileName: String,
    @SerialName("size") val sizeBytes: Long,
    val blocks: List<String>,
) {

    /**
     * 展示用的块名。**整文件模式（只有一块）不能叫「首段」** ——
     * 那会让人以为只校验了开头 1 MiB，而实际上校验的是全部字节。
     */
    fun blockLabel(index: Int): String = if (blocks.size == 1) {
        "整文件"
    } else {
        when (index) {
            0 -> "首段"
            1 -> "中段"
            else -> "尾段"
        }
    }
}