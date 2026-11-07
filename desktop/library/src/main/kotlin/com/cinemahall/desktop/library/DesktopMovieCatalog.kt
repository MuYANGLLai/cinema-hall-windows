package com.cinemahall.desktop.library

import com.cinemahall.core.library.MovieScanRules
import com.cinemahall.core.library.ScanBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 一个分类（与安卓端 `MovieCategory` 对齐的最小集）。
 *
 * 桌面端没有「ID 由服务端发」这件事，所以 `id` 自带生成；[DEFAULT_CATEGORY_ID] 是
 * 「未分类」这个保留分类 —— 它的名字不可改、不可删，删掉别的分类时影片往它这里退。
 */
data class DesktopCategory(
    val id: String,
    val name: String,
    val createdAtMs: Long = 0L,
) {
    val isDefault: Boolean get() = id == DEFAULT_CATEGORY_ID

    companion object {
        const val DEFAULT_CATEGORY_ID = "default"

        val Default = DesktopCategory(DEFAULT_CATEGORY_ID, "电影", 0L)
    }
}

/** 一条扫描结果（W2）。[durationMs] 为 0 = 还没解析出来，不是“零长度”。 */
data class DesktopMovie(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val durationMs: Long = 0L,
    /** 所属分类；老库读进来时没有这个字段 ⇒ 落回「未分类」。 */
    val categoryId: String = DesktopCategory.DEFAULT_CATEGORY_ID,
    /** 用户改过的显示名；null = 用文件名推出来的那个（[DesktopScanRules.displayName]）。 */
    val titleOverride: String? = null,
) {
    /** 界面上显示、以及交给房间的片名。 */
    val title: String
        get() = titleOverride?.takeIf { it.isNotBlank() } ?: DesktopScanRules.displayName(fileName)

    /** 文件名推出来的那个名字（改名对话框拿它当输入框的初始值 / 「恢复默认」的目标）。 */
    val defaultTitle: String get() = DesktopScanRules.displayName(fileName)

    /** 时长已知时才归档；未知（0）返回 null —— 与 `MovieScanRules.classify(0)=SKIP` 同义。 */
    val bucket: ScanBucket? get() = if (durationMs > 0) MovieScanRules.classify(durationMs) else null
}

enum class ScanPhase { IDLE, FAST, PROBING, DONE, CANCELLED }

/** 两段式扫描的进度快照（界面与门禁读数都用它）。 */
data class ScanStats(
    val phase: ScanPhase = ScanPhase.IDLE,
    val roots: Int = 0,
    val visitedDirs: Int = 0,
    val found: Int = 0,
    val skippedExtension: Int = 0,
    val skippedHidden: Int = 0,
    val skippedSystemDirs: Int = 0,
    val duplicates: Int = 0,
    val probed: Int = 0,
    val probeFailed: Int = 0,
    val fastElapsedMs: Long = 0,
    val probeElapsedMs: Long = 0,
) {
    val probedFraction: Float
        get() = if (found <= 0) 0f else (probed.toFloat() / found.toFloat()).coerceIn(0f, 1f)
}

/**
 * W2 的影片目录：根目录管理 + 两段式扫描 + 时长缓存。
 *
 * ── 为什么“两段式” ──────────────────────────────────────────────────────────
 * 500 部片的目录树，第一段只做 `stat`（文件名/大小/修改时间），在 SSD 上是数百毫秒级；
 * 时长解析要真的跑 demux，单片 50–300 ms，500 部就是分钟级。如果合成一段，界面在
 * 这段时间里是空的 —— 用户会以为“没扫到”，然后反复点扫描。
 *
 * 所以：**快扫先把片名列出来（可点、可播），时长在后台补**。补到之后 `bucket` 才有值，
 * 界面上的「电影 / 电视剧」分组随之出现。
 *
 * ── 时长缓存为什么按 `(size, mtime)` 失效 ───────────────────────────────────
 * 文件名会改、目录会搬，但“同一个文件”的判据是大小 + 修改时间。只按路径缓存的话，
 * 用户把 4 GB 的片子换成另一部同名的，界面会继续显示旧时长（并且分类是错的）。
 */
class DesktopMovieCatalog(
    private val storeFile: File,
    private val probe: (String) -> Long,
    private val probeConcurrency: Int = 4,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _roots = MutableStateFlow<List<String>>(emptyList())
    val roots: StateFlow<List<String>> = _roots.asStateFlow()

    private val _movies = MutableStateFlow<List<DesktopMovie>>(emptyList())
    val movies: StateFlow<List<DesktopMovie>> = _movies.asStateFlow()

    /**
     * 分类表。**永远**以「未分类」打头 —— 它是保留分类，删不掉也改不了名，
     * 别的分类被删掉时影片往它这里退（和安卓端 `MovieLibrarySnapshot` 的默认桶一致）。
     *
     * ⚠️ 声明位置必须在 `init { load() }` **之前**：`load()` 会写它，晚于 `init` 声明
     * 就还是 null —— 与下面 `movieOrder` 同一条坑（那次是被 `runCatching` 静默吞掉的）。
     */
    private val _categories = MutableStateFlow<List<DesktopCategory>>(listOf(DesktopCategory.Default))
    val categories: StateFlow<List<DesktopCategory>> = _categories.asStateFlow()

    private val _stats = MutableStateFlow(ScanStats())
    val stats: StateFlow<ScanStats> = _stats.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    /** `path -> 缓存`；读写只在扫描线程/初始化线程，所以用普通 map + 同步块。 */
    private val durationCache = HashMap<String, CachedDuration>()

    /**
     * 影片顺序：先按所在目录，再按文件名（大小写不敏感）—— 载入与扫描必须用同一个。
     *
     * ⚠️ 声明位置是**有讲究**的：必须排在 `init` 之前。Kotlin 的实例初始化按声明顺序执行，
     * 放到 `init` 之后就等于「`load()` 里用到它时它还是 null」—— 而 `load()` 被
     * `runCatching` 包着，那个 NPE 会被静默吞掉，症状是「根目录还在、影片列表整个空掉」，
     * 看起来像落盘没写成功（2026-11-06 亲手撞过）。
     */
    private val movieOrder = compareBy<DesktopMovie>(
        { it.path.substringBeforeLast(File.separatorChar) },
        { it.fileName.lowercase() },
    )

    init {
        runCatching { load() }
    }

    // ── 根目录 ──────────────────────────────────────────────────────────────

    sealed interface AddRootResult {
        data class Added(val path: String) : AddRootResult
        data object AlreadyPresent : AddRootResult
        data class Rejected(val reason: String) : AddRootResult
    }

    fun addRoot(directory: File): AddRootResult {
        if (!directory.isDirectory) return AddRootResult.Rejected("不是目录：${directory.absolutePath}")
        val path = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
        if (_roots.value.any { it.equals(path, ignoreCase = true) }) return AddRootResult.AlreadyPresent
        _roots.value = _roots.value + path
        persist()
        return AddRootResult.Added(path)
    }

    fun removeRoot(path: String) {
        val before = _roots.value
        _roots.value = before.filterNot { it.equals(path, ignoreCase = true) }
        if (_roots.value.size != before.size) {
            _movies.value = _movies.value.filterNot { movie ->
                _roots.value.none { root -> movie.path.startsWith(root, ignoreCase = true) }
            }
            persist()
        }
    }

    // ── 分类与影片编辑（复刻安卓端影片库的「分类 / 改名 / 批量移动 / 删除」）──

    /** 新建分类。重名（忽略大小写）直接返回已有的那个，不制造两个同名条目。 */
    fun addCategory(name: String): DesktopCategory? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        _categories.value.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
        val created = DesktopCategory(
            id = "c" + System.currentTimeMillis().toString(36) + "-" + _categories.value.size,
            name = trimmed,
            createdAtMs = System.currentTimeMillis(),
        )
        _categories.value = _categories.value + created
        persist()
        return created
    }

    fun renameCategory(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val target = _categories.value.firstOrNull { it.id == id } ?: return
        if (target.isDefault) return
        if (_categories.value.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) return
        _categories.value = _categories.value.map { if (it.id == id) it.copy(name = trimmed) else it }
        persist()
    }

    /** 删分类。里面的影片退到「未分类」，**不删影片**（删片是另一个动作）。 */
    fun deleteCategory(id: String) {
        if (id == DesktopCategory.DEFAULT_CATEGORY_ID) return
        if (_categories.value.none { it.id == id }) return
        _categories.value = _categories.value.filterNot { it.id == id }
        _movies.value = _movies.value.map {
            if (it.categoryId == id) it.copy(categoryId = DesktopCategory.DEFAULT_CATEGORY_ID) else it
        }
        persist()
    }

    /**
     * 改显示名。[title] 为空（或与默认名相同）= 恢复默认 —— 落盘时写 null，
     * 这样改了文件名之后界面会跟着变（存死字符串就永远停在旧名字上）。
     */
    fun renameMovie(path: String, title: String?) {
        val next = title?.trim()?.takeIf { it.isNotEmpty() }
        _movies.value = _movies.value.map { movie ->
            if (!movie.path.equals(path, ignoreCase = true)) {
                movie
            } else {
                movie.copy(titleOverride = next?.takeIf { it != movie.defaultTitle })
            }
        }
        persist()
    }

    /** 批量移动。分类不存在时不动（宁可什么都不做，也别把影片丢进一个看不见的桶）。 */
    fun moveMovies(paths: Collection<String>, categoryId: String) {
        if (_categories.value.none { it.id == categoryId }) return
        val wanted = paths.map { it.lowercase() }.toSet()
        if (wanted.isEmpty()) return
        _movies.value = _movies.value.map {
            if (it.path.lowercase() in wanted) it.copy(categoryId = categoryId) else it
        }
        persist()
    }

    /**
     * 从库里移除（**只移除记录，不删磁盘文件**）。
     *
     * 下一次扫描会把它重新扫回来 —— 这是有意的：移除是「这次不想看到」，
     * 而「永久排除」需要黑名单，那是另一个特性（安卓端也没有）。
     */
    fun removeMovies(paths: Collection<String>) {
        val wanted = paths.map { it.lowercase() }.toSet()
        if (wanted.isEmpty()) return
        _movies.value = _movies.value.filterNot { it.path.lowercase() in wanted }
        persist()
    }

    fun category(id: String): DesktopCategory =
        _categories.value.firstOrNull { it.id == id } ?: DesktopCategory.Default

    /**
     * 手动把一个/多个文件加进某个分类（分类卡上那颗「+」）。
     *
     * 与 [scan] 的区别只有一处：它不要求文件在某个根目录下面，也不碰别的影片。
     * 重复加入按路径去重；已存在的那条只改分类，不重建（保住改名与时长）。
     *
     * @return 实际加进来（或改了分类）的条数
     */
    fun addFiles(files: List<File>, categoryId: String = DesktopCategory.DEFAULT_CATEGORY_ID): Int {
        val target = _categories.value.firstOrNull { it.id == categoryId } ?: DesktopCategory.Default
        val added = ArrayList<DesktopMovie>()
        _movies.value = _movies.value.map { existing ->
            val hit = files.firstOrNull { file ->
                runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                    .equals(existing.path, ignoreCase = true)
            }
            if (hit != null && existing.categoryId != target.id) existing.copy(categoryId = target.id) else existing
        }
        val known = _movies.value.map { it.path.lowercase() }.toSet()
        files.forEach { file ->
            val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (path.lowercase() in known) return@forEach
            if (!DesktopScanRules.isVideo(file.name)) return@forEach
            added += DesktopMovie(
                path = path,
                fileName = file.name,
                sizeBytes = file.length(),
                modifiedAtMs = file.lastModified(),
                categoryId = target.id,
            )
        }
        if (added.isNotEmpty()) {
            _movies.value = (_movies.value + added).sortedWith(movieOrder)
        }
        if (added.isNotEmpty()) persist()
        return added.size
    }

    // ── 扫描 ────────────────────────────────────────────────────────────────

    /**
     * 两段式扫描。**同一个实例同时只应有一个扫描在跑**（界面用按钮禁用保证）。
     *
     * 快扫在主线程之外完成；解析阶段每完成 8 部更新一次 `movies`，界面是渐进长出来的。
     */
    suspend fun scan() {
        cancelled.set(false)
        val rootsSnapshot = _roots.value
        if (rootsSnapshot.isEmpty()) {
            _stats.value = ScanStats(phase = ScanPhase.DONE)
            return
        }

        _stats.value = ScanStats(phase = ScanPhase.FAST, roots = rootsSnapshot.size)
        val fastStarted = System.nanoTime()
        val fast = withContext(Dispatchers.IO) { fastScan(rootsSnapshot) }
        val fastElapsed = (System.nanoTime() - fastStarted) / 1_000_000

        if (cancelled.get()) {
            _stats.value = _stats.value.copy(phase = ScanPhase.CANCELLED, fastElapsedMs = fastElapsed)
            return
        }

        // 分类与显示名是**用户数据**，扫描不能把它们冲掉：扫描只负责「有哪些文件」，
        // 这两项按路径从上一次的列表里继承下来（文件被删了自然就丢了）。
        val known = _movies.value.associateBy { it.path.lowercase() }
        val withCache = fast.movies.map { movie ->
            val cached = synchronized(durationCache) { durationCache[movie.path] }
            val keep = known[movie.path.lowercase()]
            movie.copy(
                durationMs = if (cached != null && cached.sizeBytes == movie.sizeBytes && cached.modifiedAtMs == movie.modifiedAtMs) {
                    cached.durationMs
                } else {
                    movie.durationMs
                },
                categoryId = keep?.categoryId ?: DesktopCategory.DEFAULT_CATEGORY_ID,
                titleOverride = keep?.titleOverride,
            )
        }
        _movies.value = withCache
        _stats.value = _stats.value.copy(
            phase = ScanPhase.PROBING,
            visitedDirs = fast.visitedDirs,
            found = withCache.size,
            skippedExtension = fast.skippedExtension,
            skippedHidden = fast.skippedHidden,
            skippedSystemDirs = fast.skippedSystemDirs,
            duplicates = fast.duplicates,
            probed = withCache.count { it.durationMs > 0 },
            fastElapsedMs = fastElapsed,
        )

        val pending = withCache.filter { it.durationMs <= 0 }
        if (pending.isEmpty()) {
            _stats.value = _stats.value.copy(phase = ScanPhase.DONE, probeElapsedMs = 0)
            persist()
            return
        }

        val probeStarted = System.nanoTime()
        val probed = AtomicInteger(withCache.size - pending.size)
        val failed = AtomicInteger(0)
        val updated = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val semaphore = Semaphore(probeConcurrency.coerceAtLeast(1))

        coroutineScope {
            pending.forEach { movie ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (cancelled.get()) return@withPermit
                        val duration = runCatching { probe(movie.path) }.getOrDefault(0L)
                        if (duration > 0) {
                            updated[movie.path] = duration
                            synchronized(durationCache) {
                                durationCache[movie.path] = CachedDuration(
                                    sizeBytes = movie.sizeBytes,
                                    modifiedAtMs = movie.modifiedAtMs,
                                    durationMs = duration,
                                )
                            }
                        } else {
                            failed.incrementAndGet()
                        }
                        val done = probed.incrementAndGet()
                        if (done % 8 == 0 || done == _stats.value.found) {
                            _movies.value = _movies.value.map { current ->
                                updated[current.path]?.let { current.copy(durationMs = it) } ?: current
                            }
                            _stats.value = _stats.value.copy(
                                probed = done,
                                probeFailed = failed.get(),
                                probeElapsedMs = (System.nanoTime() - probeStarted) / 1_000_000,
                            )
                        }
                    }
                }
            }
        }

        _movies.value = _movies.value
            .map { current -> updated[current.path]?.let { current.copy(durationMs = it) } ?: current }
            .sortedWith(movieOrder)

        _stats.value = _stats.value.copy(
            phase = if (cancelled.get()) ScanPhase.CANCELLED else ScanPhase.DONE,
            probed = probed.get(),
            probeFailed = failed.get(),
            probeElapsedMs = (System.nanoTime() - probeStarted) / 1_000_000,
        )
        persist()
    }

    fun cancel() {
        cancelled.set(true)
    }

    // ── 快扫 ────────────────────────────────────────────────────────────────

    private class FastResult(
        val movies: List<DesktopMovie>,
        val visitedDirs: Int,
        val skippedExtension: Int,
        val skippedHidden: Int,
        val skippedSystemDirs: Int,
        val duplicates: Int,
    )

    private fun fastScan(roots: List<String>): FastResult {
        val movies = ArrayList<DesktopMovie>(512)
        val visited = LinkedHashSet<String>(1024)
        var dirs = 0
        var skippedExtension = 0
        var skippedHidden = 0
        var skippedSystem = 0
        var duplicates = 0
        var duplicateCount = 0

        for (root in roots) {
            if (cancelled.get()) break
            val stack = ArrayDeque<File>()
            stack.addLast(File(root))
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                if (cancelled.get()) break
                if (DesktopScanRules.isSystemDirectory(dir.name)) {
                    skippedSystem++
                    continue
                }
                if (dir != File(root) && DesktopScanRules.isHiddenFile(dir)) {
                    skippedHidden++
                    continue
                }
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                dirs++
                for (child in children) {
                    if (child.isDirectory) {
                        stack.addLast(child)
                        continue
                    }
                    if (!child.isFile) continue
                    if (DesktopScanRules.isHiddenFile(child)) {
                        skippedHidden++
                        continue
                    }
                    if (!DesktopScanRules.isVideo(child.name)) {
                        skippedExtension++
                        continue
                    }
                    val path = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                    if (!visited.add(path.lowercase())) {
                        duplicateCount++
                        continue
                    }
                    movies += DesktopMovie(
                        path = path,
                        fileName = child.name,
                        sizeBytes = child.length(),
                        modifiedAtMs = child.lastModified(),
                    )
                }
            }
        }
        duplicates = duplicateCount
        return FastResult(movies, dirs, skippedExtension, skippedHidden, skippedSystem, duplicates)
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    @Serializable
    private data class CachedDuration(
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val durationMs: Long,
    )

    /**
     * 落盘的一条影片。
     *
     * ── 为什么影片列表也要存（2026-11-06 补）───────────────────────────────────
     * 早先只存「根目录 + 时长缓存」，影片列表靠每次启动重扫才出现。症状是：用户上次
     * 已经扫过 30 部，今天打开影片库却是「0 部 / 还没有影片」—— 看起来像库丢了。
     * 安卓端那一侧是持久化的（`MovieLibraryStore`），两端行为不一致本身就是缺陷。
     *
     * 存列表**不改变扫描的地位**：重扫仍然覆盖它（新增、删除、改名的文件都以扫描为准），
     * 时长缓存继续按 `(size, mtime)` 失效重算。
     */
    /** 落盘的一个分类。`version 1` 的库里没有这一段，读进来就是「只有未分类」。 */
    @Serializable
    private data class StoredCategory(
        val id: String,
        val name: String,
        val createdAtMs: Long = 0,
    )

    @Serializable
    private data class StoredMovie(
        val path: String,
        val fileName: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val durationMs: Long = 0,
        val categoryId: String = DesktopCategory.DEFAULT_CATEGORY_ID,
        val titleOverride: String? = null,
    )

    @Serializable
    private data class Stored(
        val version: Int = 2,
        val roots: List<String> = emptyList(),
        val durations: Map<String, CachedDuration> = emptyMap(),
        val movies: List<StoredMovie> = emptyList(),
        val categories: List<StoredCategory> = emptyList(),
    )

    private fun load() {
        if (!storeFile.isFile) return
        val stored = json.decodeFromString<Stored>(storeFile.readText())
        _roots.value = stored.roots
        synchronized(durationCache) {
            durationCache.clear()
            durationCache.putAll(stored.durations)
        }
        // 分类表：读进来的顺序即展示顺序，「未分类」永远排在第一条。
        val restoredCategories = buildList {
            add(DesktopCategory.Default)
            stored.categories.forEach { saved ->
                if (saved.id != DesktopCategory.DEFAULT_CATEGORY_ID) {
                    add(DesktopCategory(saved.id, saved.name, saved.createdAtMs))
                }
            }
        }.distinctBy { it.id }
        // ── 撞名合并（2026-11-07）───────────────────────────────────────────────
        // 默认分类正式改名叫「电影」（对齐安卓端的初始分类）。老库里可能已经有一个
        // 用户自己建的「电影」—— 不合并的话，影片库里会出现两张同名卡（实测截图）。
        // 这里把它整个并进默认分类：影片跟着搬，分类本身删掉。
        val defaultName = DesktopCategory.Default.name
        val duplicates = restoredCategories.filter { !it.isDefault && it.name == defaultName }
        _categories.value = if (duplicates.isEmpty()) {
            restoredCategories
        } else {
            restoredCategories.filterNot { candidate -> duplicates.any { it.id == candidate.id } }
        }
        val mergedIds = duplicates.map { it.id }.toSet()
        val knownCategories = _categories.value.map { it.id }.toSet()

        // ── 老库的迁移（2026-11-06）─────────────────────────────────────────────
        // 0.1.6 及以前的 `library.json` 只有「根目录 + 时长缓存」，没有 `movies`。
        // 直接按新格式读就会得到「0 部」，用户升级后第一眼看到的是「我的库空了」——
        // 而时长缓存里的每一项都够还原一条影片（路径、大小、修改时间、时长），
        // 文件名取路径末段即可。所以 `movies` 为空时用它兜底重建；不为空就以 `movies` 为准。
        val restored = if (stored.movies.isNotEmpty()) {
            stored.movies.map {
                DesktopMovie(
                    path = it.path,
                    fileName = it.fileName,
                    sizeBytes = it.sizeBytes,
                    modifiedAtMs = it.modifiedAtMs,
                    durationMs = it.durationMs,
                    // 分类被删过（或库是手改的）时退回「未分类」，别让影片变成孤儿；
                    // 撞名合并掉的那些分类，影片一并搬到默认分类。
                    categoryId = when {
                        it.categoryId in mergedIds -> DesktopCategory.DEFAULT_CATEGORY_ID
                        it.categoryId in knownCategories -> it.categoryId
                        else -> DesktopCategory.DEFAULT_CATEGORY_ID
                    },
                    titleOverride = it.titleOverride,
                )
            }
        } else {
            stored.durations.map { (path, cached) ->
                DesktopMovie(
                    path = path,
                    fileName = File(path).name,
                    sizeBytes = cached.sizeBytes,
                    modifiedAtMs = cached.modifiedAtMs,
                    durationMs = cached.durationMs,
                )
            }
        }
        _movies.value = restored.sortedWith(movieOrder)
    }

    private fun persist() {
        runCatching {
            storeFile.parentFile?.mkdirs()
            val stored = Stored(
                roots = _roots.value,
                durations = synchronized(durationCache) { HashMap(durationCache) },
                movies = _movies.value.map {
                    StoredMovie(
                        path = it.path,
                        fileName = it.fileName,
                        sizeBytes = it.sizeBytes,
                        modifiedAtMs = it.modifiedAtMs,
                        durationMs = it.durationMs,
                        categoryId = it.categoryId,
                        titleOverride = it.titleOverride,
                    )
                },
                categories = _categories.value.filterNot { it.isDefault }
                    .map { StoredCategory(it.id, it.name, it.createdAtMs) },
            )
            val temp = File(storeFile.parentFile, storeFile.name + ".tmp")
            temp.writeText(json.encodeToString(stored))
            if (!temp.renameTo(storeFile)) {
                storeFile.writeText(temp.readText())
                temp.delete()
            }
        }
    }
}
