package com.cinemahall.core.library

import kotlinx.serialization.json.Json

/**
 * 影片库与进度的 JSON 编解码（§16.4 的落盘部分）。
 *
 * ── 为什么 decode 要区分「没有数据」与「有数据但解不开」──────────────────────
 * 两者都返回 null，但调用方（`MovieLibraryStore`）对它们的处理**必须不同**：
 * 「没有数据」是第一次打开 App，正常；「有数据但解不开」（版本不兼容、写盘写坏）
 * **不能静默当成空库** —— 那是用户一条条建起来的分类和标记。直接返回空对象等于把
 * 它们悄悄抹掉，用户只会看到「我的分类全没了」且没有任何线索。所以 store 会把解不开的
 * 原文另存到 `library.v1.corrupt` 之后再以空库继续。
 *
 * ── 为什么 encode 不做 try/catch ────────────────────────────────────────────
 * 写不出去（序列化器不匹配、内存不够）是编程错误，不是运行期状况：它应该在编译期或
 * 单测里就暴露。运行时吞掉只会让「界面说保存了、其实没保存」更难查。
 *
 * `encodeDefaults = true`：默认值也写出去，落盘文件因此是**自解释**的 ——
 * 看 JSON 就知道每个字段当前是什么值，不必回代码里对默认值。代价是每条几十字节，
 * 对几百条的影片库无所谓。
 */
object MovieLibraryCodec {

    /**
     * `ignoreUnknownKeys`：更高版本的本机（或将来从别处导入的数据）多写了字段时，
     * 要能读得出来。它与「所有字段都有默认值」合起来，构成 §16.4 那条
     * 「旧读新、新读旧都不炸」的全部依据。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeLibrary(snapshot: MovieLibrarySnapshot): String =
        json.encodeToString(MovieLibrarySnapshot.serializer(), snapshot)

    /**
     * [raw] 为 null / 全空白 / 解析失败（含字面量 `null`、非对象 JSON）一律返回 `null`，
     * **不抛异常**。
     *
     * 返回 null 只说明「这次没读出库」，调用方必须自己判断是「没有数据」还是
     * 「有数据但解不开」—— 判据是原文是否非空白（见类注释）。
     */
    fun decodeLibrary(raw: String?): MovieLibrarySnapshot? {
        if (raw.isNullOrBlank()) return null

        return try {
            json.decodeFromString(MovieLibrarySnapshot.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeProgress(book: MovieProgressBook): String =
        json.encodeToString(MovieProgressBook.serializer(), book)

    /** 同 [decodeLibrary]：解不开返回 null，由调用方决定怎么处置原文。 */
    fun decodeProgress(raw: String?): MovieProgressBook? {
        if (raw.isNullOrBlank()) return null

        return try {
            json.decodeFromString(MovieProgressBook.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }
}
