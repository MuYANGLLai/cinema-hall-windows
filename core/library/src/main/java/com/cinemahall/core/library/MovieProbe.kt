package com.cinemahall.core.library

/**
 * 一次「这个 URI 还打不开吗」的探测结论（§16.5 的失效三态）。
 *
 * Phase 1 从 Android 的 `MovieAvailability` 抽到 `core:library`：它是纯数据，
 * `MovieLibraryStore` 的纯逻辑要用它；真正的 `probe(context, uri)` 留在
 * `core:library-android`。
 */
sealed interface MovieProbe {

    /**
     * 查得到。[fileName] / [sizeBytes] 取不到时为 null —— 提供方没报这一列。
     *
     * 注意「取不到」与「是 0」是两回事：`sizeBytes = null` 表示不知道大小，
     * 不能被当成一个 0 字节的文件（§7 的大小比对要靠它）。
     */
    data class Present(val fileName: String?, val sizeBytes: Long?) : MovieProbe

    /** **确定**文档不存在 ⇒ 调用方可以删标记（这就是用户要的「文件删了标记也自动删」）。 */
    data object Missing : MovieProbe

    /** 打不开，但原因不是「不存在」⇒ **保留**标记，只让界面显示一行原因。 */
    data class Unreachable(val reason: String) : MovieProbe
}
