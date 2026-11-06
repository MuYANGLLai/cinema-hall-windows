package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignatureComparator

/** 扫描到的一条候选（§16.15）。还没算摘要、也还没决定归到哪个分类。 */
data class ScannedMovie(
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
)

/** 时长落在哪一档（§16.15 的四条边界）。 */
enum class ScanBucket {

    /** ≥ 80 分钟 ⇒ 进「电影」分类。 */
    MOVIE,

    /** > 30 分钟 且 < 80 分钟 ⇒ 进「电视剧」分类。 */
    SERIES,

    /** ≤ 30 分钟 ⇒ 不收录。 */
    SKIP,
}

/**
 * 扫描的判据：**只有纯函数**（§16.15）。
 *
 * ── 为什么把它从 `MovieScanner` 里拆出来 ────────────────────────────────────
 * `MediaStore` 在 JVM 单测里不存在（本地单测拿到的是 android.jar 里会抛异常的桩），
 * 所以「怎么查」那一层只能是编译期保证。而「怎么判」这四条边界 —— 它们都只差 1 毫秒、
 * 而且都不会在真机上给出任何错误提示（只是归错分类）—— 必须能逐条钉死。
 * 与 §16.5 把 `MovieAvailability` 的判据写进注释、把三态判定留在可测的那一层同一种切法。
 *
 * ── 阈值为什么逐字照用户口径，而不是「四舍五入到整分钟」 ──────────────────────
 * 用户说的是「大于或等于 80 分钟」「大于 30 分钟小于 80 分钟」「小于等于 30 分钟不收录」。
 * 三句话里 80 这个点**是闭的**（≥）、30 这个点**是开的**（>）—— 恰好一个片长 80:00 的
 * 片子要进电影、片子 30:00 要被排除。写成 `> 80` 或 `>= 30` 都会让边界上的那一类
 * 静默归错，而用户没有任何办法看出来是判据错了还是片子本身有问题。
 */
object MovieScanRules {

    /** 电影的下界（含）。80 分钟。 */
    const val MOVIE_MIN_MS: Long = 80L * 60L * 1000L

    /** 电视剧的下界（**不含**）。30 分钟。 */
    const val SERIES_MIN_MS: Long = 30L * 60L * 1000L

    /**
     * 按片长归档。
     *
     * `durationMs <= 0` 落到 [ScanBucket.SKIP]：0 表示「不知道时长」（`MediaStore` 里
     * 元数据没解出来的片子就是 0），而「不知道」不能被当成「不超过 30 分钟」以外的
     * 任何一种猜测 —— 收进来的话它会立刻落进「电视剧」，因为 0 比 30 分钟小。
     */
    fun classify(durationMs: Long): ScanBucket = when {
        durationMs >= MOVIE_MIN_MS -> ScanBucket.MOVIE
        durationMs > SERIES_MIN_MS -> ScanBucket.SERIES
        else -> ScanBucket.SKIP
    }

    /** 该档对应的分类 `kind`（§16.3 的 `MovieCategory.kind`）。[ScanBucket.SKIP] 没有目的地。 */
    fun kindOf(bucket: ScanBucket): String? = when (bucket) {
        ScanBucket.MOVIE -> MovieCategoryKind.MOVIE
        ScanBucket.SERIES -> MovieCategoryKind.SERIES
        ScanBucket.SKIP -> null
    }

    /**
     * 从候选里挑出**库里还没有的**，保持入参顺序。
     *
     * ── 判据为什么是「文件名 + 大小」，而且是全库范围 ──────────────────────────
     *  1. **不按 URI**：同一部片可能已经从 SAF 手动加过（那个 URI 是
     *     `content://.../document/video%3A1234`），而扫描给的是
     *     `content://media/external/video/media/1234` —— 两个不同的字符串指的是同一个文件。
     *     按 URI 判重会让它在库里出现两次，一次在用户手放的分类、一次在扫描分类。
     *  2. **全库而不是目标分类**：扫描是「补全」，不是「往某个分类里塞」。一部片已经在
     *     「纪录片」里了，扫描不该再把它抄一份进「电影」。
     *  3. **文件名 + 大小正是 §7 的前两条判据**（`MediaSignatureComparator`），与「这两条
     *     是不是同一个片源」同一个口径 —— 不会出现「界面说重复、开播却说不是同一部」。
     *     文件名走 `normalizeFileName`（NFC）且**大小写敏感**，理由与 §16.6 的进度键相同。
     *
     * 不算块哈希：那要真读 3 MiB。扫描几十部片时，为了判重把每一部都读一遍是没必要的 ——
     * 文件名与大小已经能区分「同一个文件」与「不同文件」，而误判的代价只是「多一条标记，
     * 用户能删」。
     */
    fun selectNew(
        scanned: List<ScannedMovie>,
        existing: List<MovieEntry>,
    ): List<ScannedMovie> {
        if (scanned.isEmpty()) return scanned

        val seen = existing.mapTo(HashSet(existing.size)) { entry ->
            keyOf(entry.signature.fileName, entry.signature.sizeBytes)
        }

        return scanned.filter { movie ->
            // 同一批里也可能出现两条同名同大小的（同一部片在 SD 卡与内置存储各一份）。
            // 一起去重：加进去之后 `addMovie` 的「同一分类内同一个 uri 只留一条」拦不住
            // 这种情况（uri 不同），而界面上它们会是两个一模一样的格子。
            seen.add(keyOf(movie.fileName, movie.sizeBytes))
        }
    }

    private fun keyOf(fileName: String, sizeBytes: Long): String =
        MediaSignatureComparator.normalizeFileName(fileName) + '\u0000' + sizeBytes
}

/**
 * `MovieCategory.kind` 的两个取值。
 *
 * 写成常量而不是字符串字面量：「电影」分类在扫描（`ensureScanCategories`）、
 * 界面分组（`CategoryCard`）两处都要认它，两处各写一遍 `"movie"` 是那种
 * **拼错也不报错、只是永远找不到** 的东西。
 */
object MovieCategoryKind {

    const val MOVIE: String = "movie"

    const val SERIES: String = "series"

    /** 建分类时用的默认名。用户改名之后不会回到这里（`kind` 才是身份）。 */
    const val MOVIE_NAME: String = "电影"

    const val SERIES_NAME: String = "电视剧"
}
