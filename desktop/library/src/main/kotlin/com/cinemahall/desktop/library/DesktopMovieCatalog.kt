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

/** 一条扫描结果（W2）。[durationMs] 为 0 = 还没解析出来，不是“零长度”。 */
data class DesktopMovie(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val durationMs: Long = 0L,
) {
    val title: String get() = DesktopScanRules.displayName(fileName)

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

        val withCache = fast.movies.map { movie ->
            val cached = synchronized(durationCache) { durationCache[movie.path] }
            if (cached != null && cached.sizeBytes == movie.sizeBytes && cached.modifiedAtMs == movie.modifiedAtMs) {
                movie.copy(durationMs = cached.durationMs)
            } else {
                movie
            }
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
    @Serializable
    private data class StoredMovie(
        val path: String,
        val fileName: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val durationMs: Long = 0,
    )

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val roots: List<String> = emptyList(),
        val durations: Map<String, CachedDuration> = emptyMap(),
        val movies: List<StoredMovie> = emptyList(),
    )

    private fun load() {
        if (!storeFile.isFile) return
        val stored = json.decodeFromString<Stored>(storeFile.readText())
        _roots.value = stored.roots
        synchronized(durationCache) {
            durationCache.clear()
            durationCache.putAll(stored.durations)
        }
        _movies.value = stored.movies
            .map { DesktopMovie(it.path, it.fileName, it.sizeBytes, it.modifiedAtMs, it.durationMs) }
            .sortedWith(movieOrder)
    }

    private fun persist() {
        runCatching {
            storeFile.parentFile?.mkdirs()
            val stored = Stored(
                roots = _roots.value,
                durations = synchronized(durationCache) { HashMap(durationCache) },
                movies = _movies.value.map {
                    StoredMovie(it.path, it.fileName, it.sizeBytes, it.modifiedAtMs, it.durationMs)
                },
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
