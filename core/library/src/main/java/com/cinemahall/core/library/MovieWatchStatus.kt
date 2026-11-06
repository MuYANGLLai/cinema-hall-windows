package com.cinemahall.core.library

/**
 * 「这部片算不算看完了」（§16.17）。
 *
 * ── 判据只有一条，但它是三态 ────────────────────────────────────────────────
 * 顺序是死的：**先看用户表过态没有**（[MovieEntry.finishedOverride]），没表过态才看进度。
 * 反过来（先算进度、再被 override 覆盖）也能得出同样的结果，但会让「用户明明手动挪出来了、
 * 下一次判定却先被判成看完再改回来」这种中间态存在 —— 而它一旦泄漏到界面上，就是
 * 「点一下挪出来，画面闪了一下又回去了」。
 *
 * ── 阈值 0.85 是用户口径 ────────────────────────────────────────────────────
 * 「观看进度大于等于 85% 的会自动归入『已看完』」。用 `>=` 不是 `>`：85% 整那一下就该归入。
 *
 * ── 为什么「时长未知」不会被误判 ────────────────────────────────────────────
 * [MovieProgress.fraction] 在 `durationMs <= 0L` 时返回 `0f`（不能拿 0 当分母），
 * 所以「元数据没解出时长」这一档落在「没看完」，而不是「100%」那种反向错误。
 *
 * 放在 `core:library` 而不是界面里：它是**数据归属**的判据（哪个网格显示这一条），
 * 界面只负责把它铺成两段；写成纯函数才能在 JVM 单测里把三态逐条钉住。
 */
object MovieWatchStatus {

    /** 「已看完」的进度阈值（含）。 */
    const val FINISHED_THRESHOLD: Float = 0.85f

    fun isFinished(entry: MovieEntry, progressBook: MovieProgressBook): Boolean {
        entry.finishedOverride?.let { return it }

        val progress = progressBook.byKey[entry.syncKeyOrDerived] ?: return false
        return progress.fraction >= FINISHED_THRESHOLD
    }

    /**
     * 把一个分类下的影片分成「未看完」与「已看完」两段（§16.17），**两段都保持入参顺序**。
     *
     * 调用点通常先过 `MovieOrdering.arrange`，于是两段各自沿用同一套排序规则
     * （最近看的在前、同一部剧的相邻）—— 分组不改变组内的相对次序，只决定谁进哪一段。
     */
    fun partition(
        movies: List<MovieEntry>,
        progressBook: MovieProgressBook,
    ): Pair<List<MovieEntry>, List<MovieEntry>> {
        val unfinished = ArrayList<MovieEntry>(movies.size)
        val finished = ArrayList<MovieEntry>(movies.size)

        movies.forEach { entry ->
            if (isFinished(entry, progressBook)) finished += entry else unfinished += entry
        }
        return unfinished to finished
    }
}
