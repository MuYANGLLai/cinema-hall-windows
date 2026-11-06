package com.cinemahall.core.library.sync

import com.cinemahall.core.library.MovieCategory
import com.cinemahall.core.library.MovieEntry
import com.cinemahall.core.library.MovieLibrarySnapshot
import com.cinemahall.core.library.MovieProgress
import com.cinemahall.core.library.MovieProgressBook
import com.cinemahall.core.media.MediaSignature

/**
 * 同步的**合并内核**：两件事，都是纯函数，都不碰 IO。
 *
 * | 方向 | 输入 | 输出 |
 * |---|---|---|
 * | 上行 [planPush] | 本地库/进度 + 账本基线 | 一批变更 + 推完之后的新基线 |
 * | 下行 [planApply] | 一页远端行 + 账本基线 | 两个**变换**（库 / 进度）+ 新基线 |
 *
 * ── 为什么下行返回「变换」而不是「结果」─────────────────────────────────────
 * 远端行只说明「这些东西变了」，而真正的合并对象是**那一刻的本地状态**。
 * 从读到写之间，用户完全可能在界面上又加了一条片。返回 `(Snapshot) -> Snapshot`
 * 让 `MovieLibraryStore` 在自己那把锁里对**当前**状态做 upsert/删除，
 * 于是「同步刚写进来，用户刚删掉」这种交错不会互相覆盖成一条不存在的记录。
 *
 * ── LWW 的判据与服务端逐字对齐 ───────────────────────────────────────────────
 * 服务端是 `if (existing.updated_at_ms >= updatedAt) skip`，即**平局时保留既有行**。
 * 客户端必须同口径：若这里用 `>`，两端在「同一毫秒两条不同内容」上会永久分叉 ——
 * 服务端留着旧的、客户端换成新的，下一轮客户端又把它推回去、又被 skip，
 * 界面看着像「改了没生效」，而日志里什么都没有。
 */
object LibrarySyncMerge {

    /**
     * 上行计划。[next] 是「这批变更全部成功后」的账本：
     * 被推上去的值进了基线，于是下一轮不再重复上报。
     */
    data class PushPlan(
        val batch: ChangesBatch,
        val next: SyncState,
    )

    /**
     * 下行计划。[libraryTransform] / [progressTransform] 为 null 表示「这一页没有需要改的」，
     * 调用方据此跳过落盘（一次空写会白换一次 `StateFlow`、让整页重组一遍）。
     */
    data class ApplyPlan(
        val libraryTransform: ((MovieLibrarySnapshot) -> MovieLibrarySnapshot)?,
        val progressTransform: ((MovieProgressBook) -> MovieProgressBook)?,
        val next: SyncState,
        /** 真的应用了几行（LWW 输掉的不算）。 */
        val applied: Int,
    )

    // ── 上行 ────────────────────────────────────────────────────────────────

    /**
     * 本地与基线一比，得出待推送的三张表。
     *
     * 所有变更共用同一个 [nowMs] 作为 `updatedAtMs`：一次上报里的条目本来就是同一刻
     * 被观察到的，给它们各自取一次 `System.currentTimeMillis()` 只会让同批数据在服务端
     * 排出无意义的先后（而且平局判据是 `>=`，同数值反而更稳定）。
     *
     * 进度用**它自己的** `updatedAtMs`（播放那一刻），不是 [nowMs]：
     * 那是「用户真正看到这里」的时刻，冲突时该由它决定谁赢，而不是「谁先联网」。
     */
    fun planPush(
        state: SyncState,
        snapshot: MovieLibrarySnapshot,
        progress: MovieProgressBook,
        nowMs: Long,
    ): PushPlan {
        val categories = ArrayList<CategoryChange>()
        val movies = ArrayList<MovieChange>()
        val progressChanges = ArrayList<ProgressChange>()

        // ── 分类：新增 / 改名 / 改 kind / 删除 ──
        val localCategoryIds = HashSet<String>(snapshot.categories.size)
        val nextCategories = HashMap<String, BaselineCategory>(state.categories)
        for (category in snapshot.categories) {
            localCategoryIds += category.id
            val base = state.categories[category.id]
            val unchanged = base != null && base.name == category.name && base.kind == category.kind
            if (unchanged) continue
            categories += CategoryChange(
                id = category.id,
                name = category.name,
                kind = category.kind,
                createdAtMs = category.createdAtMs,
                updatedAtMs = nowMs,
            )
            nextCategories[category.id] = BaselineCategory(
                name = category.name,
                kind = category.kind,
                createdAtMs = category.createdAtMs,
                updatedAtMs = nowMs,
            )
        }
        for ((id, base) in state.categories) {
            if (id in localCategoryIds) continue
            // 基线有、本地没有 ⇒ 用户删了它。补成墓碑行，否则下一轮会被别的设备推回来。
            categories += CategoryChange(
                id = id,
                name = base.name,
                kind = base.kind,
                createdAtMs = base.createdAtMs,
                updatedAtMs = nowMs,
                deletedAtMs = nowMs,
            )
            nextCategories.remove(id)
        }

        // ── 影片：按 sigKey 比 ──
        val localMovieKeys = HashSet<String>(snapshot.movies.size)
        val nextMovies = HashMap<String, BaselineMovie>(state.movies)
        for (movie in snapshot.movies) {
            val key = movie.syncKeyOrDerived
            if (!localMovieKeys.add(key)) continue // 同键重复（理论上不该有）只推第一条
            val base = state.movies[key]
            val categoryId = movie.categoryId.ifBlank { null }
            val unchanged = base != null &&
                base.displayName == movie.displayName &&
                base.categoryId == categoryId &&
                base.titleOverride == movie.titleOverride &&
                base.finishedOverride == movie.finishedOverride &&
                base.addedAtMs == movie.addedAtMs
            if (unchanged) continue
            movies += MovieChange(
                sigKey = key,
                displayName = movie.displayName,
                categoryId = categoryId,
                titleOverride = movie.titleOverride,
                finishedOverride = movie.finishedOverride,
                addedAtMs = movie.addedAtMs,
                updatedAtMs = nowMs,
            )
            nextMovies[key] = BaselineMovie(
                displayName = movie.displayName,
                categoryId = categoryId,
                titleOverride = movie.titleOverride,
                finishedOverride = movie.finishedOverride,
                addedAtMs = movie.addedAtMs,
                updatedAtMs = nowMs,
            )
        }
        for ((key, base) in state.movies) {
            if (key in localMovieKeys) continue
            movies += MovieChange(
                sigKey = key,
                // 墓碑也要带显示名：另一台设备可能从来没收到过这条的正常版本。
                displayName = base.displayName,
                categoryId = base.categoryId,
                titleOverride = base.titleOverride,
                finishedOverride = base.finishedOverride,
                addedAtMs = base.addedAtMs,
                updatedAtMs = nowMs,
                deletedAtMs = nowMs,
            )
            nextMovies.remove(key)
        }

        // ── 进度 ──
        val nextProgress = HashMap<String, BaselineProgress>(state.progress)
        for ((key, value) in progress.byKey) {
            val base = state.progress[key]
            if (base != null && base.positionMs == value.positionMs && base.durationMs == value.durationMs) {
                continue
            }
            progressChanges += ProgressChange(
                sigKey = key,
                positionMs = value.positionMs,
                durationMs = value.durationMs,
                updatedAtMs = value.updatedAtMs,
            )
            nextProgress[key] = BaselineProgress(
                positionMs = value.positionMs,
                durationMs = value.durationMs,
                updatedAtMs = value.updatedAtMs,
            )
        }

        return PushPlan(
            batch = dedupe(
                ChangesBatch(
                    categories = categories,
                    movies = movies,
                    progress = progressChanges,
                ),
            ),
            next = state.copy(
                categories = nextCategories,
                movies = nextMovies,
                progress = nextProgress,
            ),
        )
    }

    /**
     * **批内去重**：同一个键只留 [updatedAtMs] 最大的那条。
     *
     * 严格说 [planPush] 是按「本地现状」算的，一个键天然只出现一次。这条守卫是给
     * **将来的调用方**兜底：只要有人（界面上的批量操作、重试队列、或把两次 diff
     * 拼在一起的新代码）往同一批里塞了两条键相同、内容不同的记录，服务端会按数组顺序
     * 逐条 `apply` —— 先到的先写、后到的那条再按 LWW 覆盖一次，结果是「看数组顺序」，
     * 而这个顺序谁都说不清。去重之后结果只由时间戳决定。
     */
    fun dedupe(batch: ChangesBatch): ChangesBatch {
        fun <T> lastWins(items: List<T>, key: (T) -> String, at: (T) -> Long): List<T> =
            items.groupBy(key)
                .map { (_, group) -> group.maxByOrNull(at)!! }
                // 保持首次出现的位置，让同一份输入永远产出同一份输出（好测、好对比日志）
                .sortedBy { item -> items.indexOfFirst { key(it) == key(item) } }

        return ChangesBatch(
            categories = lastWins(batch.categories, { it.id }, { it.updatedAtMs }),
            movies = lastWins(batch.movies, { it.sigKey }, { it.updatedAtMs }),
            progress = lastWins(batch.progress, { it.sigKey }, { it.updatedAtMs }),
        )
    }

    // ── 下行 ────────────────────────────────────────────────────────────────

    /**
     * 一页远端行 → 两个变换 + 新基线。
     *
     * 顺序是固定的：**分类 → 影片 → 进度**。分类先落，影片才有地方挂；而且服务端
     * 级联（影片墓碑 → 进度墓碑）在客户端要复刻一次，否则本地会留下一条「孤儿进度」——
     * 影片没了、进度还在，用户重新添加同一部片时会看到一个莫名其妙的半截进度。
     */
    fun planApply(state: SyncState, page: RemotePage): ApplyPlan {
        var next = state.copy(cursor = maxOf(state.cursor, page.cursor))
        var applied = 0

        val categoryUpserts = ArrayList<MovieCategory>()
        val categoryDeletes = HashSet<String>()
        for (row in page.categories) {
            val base = next.categories[row.id]
            if (base != null && base.updatedAtMs >= row.updatedAtMs) continue
            applied += 1
            if (row.deletedAtMs != null) {
                categoryDeletes += row.id
                next = next.copy(categories = next.categories - row.id)
            } else {
                categoryUpserts += MovieCategory(
                    id = row.id,
                    name = row.name,
                    createdAtMs = row.createdAtMs,
                    kind = row.kind,
                )
                next = next.copy(
                    categories = next.categories + (
                        row.id to BaselineCategory(
                            name = row.name,
                            kind = row.kind,
                            createdAtMs = row.createdAtMs,
                            updatedAtMs = row.updatedAtMs,
                        )
                        ),
                )
            }
        }

        val movieUpserts = ArrayList<RemoteMovie>()
        val movieDeletes = HashSet<String>()
        for (row in page.movies) {
            val base = next.movies[row.sigKey]
            if (base != null && base.updatedAtMs >= row.updatedAtMs) continue
            applied += 1
            if (row.deletedAtMs != null) {
                movieDeletes += row.sigKey
                next = next.copy(movies = next.movies - row.sigKey)
            } else {
                movieUpserts += row
                next = next.copy(
                    movies = next.movies + (
                        row.sigKey to BaselineMovie(
                            displayName = row.displayName,
                            categoryId = row.categoryId,
                            titleOverride = row.titleOverride,
                            finishedOverride = row.finishedOverride,
                            addedAtMs = row.addedAtMs,
                            updatedAtMs = row.updatedAtMs,
                        )
                        ),
                )
            }
        }

        val progressUpserts = ArrayList<RemoteProgress>()
        val progressDeletes = HashSet<String>(movieDeletes) // 服务端级联：影片墓碑连同进度
        for (row in page.progress) {
            val base = next.progress[row.sigKey]
            if (base != null && base.updatedAtMs >= row.updatedAtMs) continue
            applied += 1
            if (row.deletedAtMs != null) {
                progressDeletes += row.sigKey
                next = next.copy(progress = next.progress - row.sigKey)
            } else {
                progressUpserts += row
                next = next.copy(
                    progress = next.progress + (
                        row.sigKey to BaselineProgress(
                            positionMs = row.positionMs,
                            durationMs = row.durationMs,
                            updatedAtMs = row.updatedAtMs,
                        )
                        ),
                )
            }
        }

        // 级联出来的删除也要从**基线**里清掉：不过这一步，基线会永远留着一条
        // 「服务端有、本地没有」的进度，而 planPush 只看本地有哪些键、永远发现不了它。
        val upsertedProgressKeys = progressUpserts.mapTo(HashSet()) { it.sigKey }
        val cascadeOnly = progressDeletes - upsertedProgressKeys
        if (cascadeOnly.isNotEmpty()) {
            next = next.copy(progress = next.progress - cascadeOnly)
        }

        if (categoryUpserts.isEmpty() && categoryDeletes.isEmpty() &&
            movieUpserts.isEmpty() && movieDeletes.isEmpty()
        ) {
            // 只有进度（或什么都没）变：不碰库那份 JSON。
            return ApplyPlan(
                libraryTransform = null,
                progressTransform = progressTransform(progressUpserts, progressDeletes),
                next = next,
                applied = applied,
            )
        }

        val baseline = next
        return ApplyPlan(
            libraryTransform = { snapshot ->
                applyLibrary(snapshot, categoryUpserts, categoryDeletes, movieUpserts, movieDeletes, baseline)
            },
            progressTransform = progressTransform(progressUpserts, progressDeletes),
            next = next,
            applied = applied,
        )
    }

    private fun progressTransform(
        upserts: List<RemoteProgress>,
        deletes: Set<String>,
    ): ((MovieProgressBook) -> MovieProgressBook)? {
        if (upserts.isEmpty() && deletes.isEmpty()) return null
        return { book ->
            val keys = book.byKey.toMutableMap()
            for (key in deletes) keys.remove(key)
            for (row in upserts) {
                keys[row.sigKey] = MovieProgress(
                    positionMs = row.positionMs,
                    durationMs = row.durationMs,
                    updatedAtMs = row.updatedAtMs,
                )
            }
            book.copy(byKey = keys)
        }
    }

    private fun applyLibrary(
        snapshot: MovieLibrarySnapshot,
        categoryUpserts: List<MovieCategory>,
        categoryDeletes: Set<String>,
        movieUpserts: List<RemoteMovie>,
        movieDeletes: Set<String>,
        baseline: SyncState,
    ): MovieLibrarySnapshot {
        val categories = snapshot.categories
            .filterNot { it.id in categoryDeletes }
            .toMutableList()
        for (category in categoryUpserts) {
            val index = categories.indexOfFirst { it.id == category.id }
            if (index >= 0) categories[index] = category else categories += category
        }
        val categoryIds = categories.mapTo(HashSet()) { it.id }

        val movies = snapshot.movies
            .filterNot { it.syncKeyOrDerived in movieDeletes }
            .toMutableList()

        for (row in movieUpserts) {
            val index = movies.indexOfFirst { it.syncKeyOrDerived == row.sigKey }
            val categoryId = row.categoryId?.takeIf { it in categoryIds } ?: ""
            if (index >= 0) {
                // 本地已有的条目：只覆盖**上云的那几个字段**，绝不动 uri / signature /
                // missing / lastCheckedAtMs —— 那些是本机的事，另一台设备没有话语权。
                movies[index] = movies[index].copy(
                    categoryId = categoryId,
                    titleOverride = row.titleOverride,
                    finishedOverride = row.finishedOverride,
                    addedAtMs = row.addedAtMs,
                )
            } else {
                movies += remoteEntry(row, categoryId)
            }
        }

        // 孤儿分类兜底（P9：服务端只存不改，客户端负责把指向已删分类的影片归入未分类）。
        // 同时做一次「重连」：分类可能比影片晚到（分类是后来才建的），那时影片先落成
        // 未分类，等分类到了再按基线里的 categoryId 接回去 —— 否则它会永远停在未分类里。
        for (index in movies.indices) {
            val movie = movies[index]
            if (movie.categoryId in categoryIds) continue
            val wanted = baseline.movies[movie.syncKeyOrDerived]?.categoryId
            val fixed = if (wanted != null && wanted in categoryIds) wanted else ""
            if (fixed != movie.categoryId) movies[index] = movie.copy(categoryId = fixed)
        }

        return snapshot.copy(categories = categories, movies = movies)
    }

    /**
     * 远端来的、本机没有的条目。
     *
     * `uri` 留空、摘要用**显示名 + 0 字节 + 空块**合成一份占位：它不参与任何一致性判断
     * （这条不是本机文件），只为了让 `displayName` 在没有 titleOverride 时能显示出来。
     * 真正的身份是 [MovieEntry.syncKey]，所有按摘要找进度的地方都走 `syncKeyOrDerived`。
     */
    private fun remoteEntry(row: RemoteMovie, categoryId: String): MovieEntry = MovieEntry(
        id = "sync:" + row.sigKey,
        categoryId = categoryId,
        uri = "",
        signature = MediaSignature(
            fileName = row.titleOverride ?: row.displayName,
            sizeBytes = 0L,
            blocks = emptyList(),
        ),
        titleOverride = row.titleOverride,
        addedAtMs = row.addedAtMs,
        finishedOverride = row.finishedOverride,
        syncKey = row.sigKey,
        remoteOnly = true,
    )
}
