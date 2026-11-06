package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 影片库与观看进度的唯一入口（《技术方案评审》§16）。
 *
 * ── 写入纪律：读当前内存态 → 造新对象 → 一次性落盘 → 再换 StateFlow ─────────
 * 每个写入方法都是这一个形状，没有例外。好处是三条：
 *  1. 落盘的 JSON 与界面看到的快照**永远是同一份值**，不会出现「界面显示了、盘上没有」；
 *  2. 盘上要么是旧的完整库、要么是新的完整库，不存在写了一半的半损坏状态
 *     （整份重写的代价见下）；
 *  3. 全部逻辑都是纯内存变换 + 一次 `storage.write`，所以能在 JVM 单测里真跑（§16.7）。
 *
 * 为什么整份重写而不是增量：库是分类 + 影片标记，几百条的量级、几十 KB；
 * 而进度（每 15 秒一次的热路径）**故意另存一条键**，所以「高频写」不会牵动整个库（§16.4）。
 *
 * ── 初值同步读盘，构造里不起协程 ────────────────────────────────────────────
 * 与仓库既有的 5 个偏好类一致：初值在构造时同步读出来。若改成「先给空库、再异步读」，
 * 界面第一帧就会闪一下「空空如也」，而影片管理页恰恰是用户点进来才创建的，
 * 那一下闪烁没有意义。读的是一两条 SharedPreferences 字符串，同步读的代价可以忽略。
 *
 * ── 坏数据不许静默丢（§16.4）───────────────────────────────────────────────
 * 读 `library.v1` 解不开、但原文非空白时：把原文写进 `library.v1.corrupt`、清掉
 * `library.v1`，本次以空库继续。用户一条条建起来的分类不能悄悄消失；留着原文，
 * 至少还有排查与人工恢复的余地。`progress.v1` 同理（键 `progress.v1.corrupt`）。
 *
 * ── 键带账号作用域（W3–W4，方案 §8.4）─────────────────────────────────────────
 * `library.v1.<scope>` / `progress.v1.<scope>`，`scope` 由 [scopeProvider] 给出
 * （账号 id，或未激活时的 `local`）。见 [LibraryScope]。默认 `local` 让既有单测
 * 一行不改，而生产装配点显式传入账号。**构造时求值一次**：一次运行内不换作用域 ——
 * 换账号要重建 store（`AppModule.resetLibrary`），否则界面会同时看到两份数据。
 */
class MovieLibraryStore(
    private val storage: LibraryStorage,
    /**
     * 账号作用域。构造时求值一次，之后不再变。
     *
     * 默认 `local`：既让单测的构造点保持原样，也让「没接账号」的调用方（例如
     * 桌面端早期版本）自动落在本地作用域上。
     */
    scopeProvider: () -> String = { LibraryScope.LOCAL },
) {

    private val scope: String = LibraryScope.scopeFor(scopeProvider())

    private val keyLibrary: String = LibraryScope.libraryKey(scope)
    private val keyProgress: String = LibraryScope.progressKey(scope)
    private val keyLibraryCorrupt: String = LibraryScope.libraryCorruptKey(scope)
    private val keyProgressCorrupt: String = LibraryScope.progressCorruptKey(scope)


    private val _snapshot = MutableStateFlow(loadLibrary())

    /** 分类与影片条目。界面直接订阅它重绘。 */
    val snapshot: StateFlow<MovieLibrarySnapshot> = _snapshot.asStateFlow()

    private val _progress = MutableStateFlow(loadProgress())

    /** 观看进度，按 [MovieProgressKey] 索引。 */
    val progress: StateFlow<MovieProgressBook> = _progress.asStateFlow()

    // ── 分类 ────────────────────────────────────────────────────────────────────

    /**
     * 新建分类，返回新 id；名字 trim 后为空、或与已有分类重名时返回 null（且不改任何东西）。
     *
     * 重名判据是「trim 之后相同」：两个名字 trim 后一样，在界面上就是两个一模一样的分组，
     * 没有理由允许。大小写**不折叠**（「Action」与「action」可以共存）—— 与 §7 的
     * 文件名比对同一个口径：折叠大小写会让「界面上看着是两个不同的名字」被判成重名，
     * 而用户没有任何办法绕过。
     *
     * [nowMs] 默认取墙上时钟，可注入是为了单测能给定值（`SystemClock` 那类单调时钟
     * 在 JVM 单测里是未实现的桩，而且它的起点跨重启会变，不适合存进库里）。
     */
    fun createCategory(name: String, nowMs: Long = System.currentTimeMillis()): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        val current = _snapshot.value
        if (current.categories.any { it.name.trim() == trimmed }) return null

        val id = UUID.randomUUID().toString()
        persistLibrary(
            current.copy(
                categories = current.categories + MovieCategory(
                    id = id,
                    name = trimmed,
                    createdAtMs = nowMs,
                ),
            ),
        )
        return id
    }

    /**
     * 改名。名字 trim 后为空、或 id 不存在时返回 false 且不改动。
     *
     * 这里**不做**重名检查（只有 [createCategory] 做）：改名撞上另一个分类名的时候，
     * 拒绝改名会让用户卡在原地（他想要的只是换个名字），而两个同名分类本身不会让任何
     * 逻辑出错 —— 条目永远靠 id 认分类，不靠名字。
     */
    fun renameCategory(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false

        val current = _snapshot.value
        if (current.categories.none { it.id == id }) return false

        persistLibrary(
            current.copy(
                categories = current.categories.map { category ->
                    if (category.id == id) category.copy(name = trimmed) else category
                },
            ),
        )
        return true
    }

    /**
     * 删分类，**连带删掉该分类下的全部影片条目**：条目只属于一个分类，
     * 留下没有分类的孤儿条目在界面上无处显示。
     *
     * 进度记录**不删**（§16.6）：这样「文件被删 ⇒ 标记自动消失 ⇒ 用户重新添加同一个文件」
     * 能接上原来的进度。代价是残留若干条几十字节的记录，可接受 —— 相比「重新添加后
     * 进度从零开始、用户以为进度丢了」，残留便宜得多。
     */
    fun deleteCategory(id: String) {
        val current = _snapshot.value
        if (current.categories.none { it.id == id }) return

        persistLibrary(
            MovieLibrarySnapshot(
                categories = current.categories.filterNot { it.id == id },
                movies = current.movies.filterNot { it.categoryId == id },
            ),
        )
    }

    /**
     * 保证「电影」与「电视剧」两个扫描目的地存在，返回它们的 id（§16.15）。
     *
     * ── 三条口径 ────────────────────────────────────────────────────────────────
     *  1. **按 `kind` 找，不按名字找**。用户可以把「电影」改名成「影片」，扫描照样认它
     *     （理由见 `MovieCategory.kind` 的注释）。
     *  2. **同名的手建分类直接收编**：用户自己建过一个叫「电影」的空分类时，扫描不再
     *     造第二个「电影」，而是给它打上 `kind` 之后往里放。否则界面上会出现两个一模一样的
     *     分组，而用户无从分辨哪个是「真的」。
     *  3. **两个都缺时只落一次盘**：分两次落盘会推两次快照，界面闪两下（新建的两张卡片
     *     一前一后出现）。
     *
     * 用户可以删掉这两个分类 —— 下次扫描会重建（名字回到默认）。不禁止删除的理由见 §16.15：
     * `deleteCategory` 的语义是「这个分类连同里面的标记一起消失」，为扫描破例会让删除按钮
     * 变成一个「有时生效有时不生效」的东西。
     */
    fun ensureScanCategories(nowMs: Long = System.currentTimeMillis()): ScanCategoryIds {
        val current = _snapshot.value
        val working = current.categories.toMutableList()
        var changed = false

        fun resolve(kind: String, defaultName: String): String {
            working.firstOrNull { it.kind == kind }?.let { existing -> return existing.id }

            val sameNameIndex = working.indexOfFirst {
                it.kind == null && it.name.trim() == defaultName
            }
            if (sameNameIndex >= 0) {
                val adopted = working[sameNameIndex].copy(kind = kind)
                working[sameNameIndex] = adopted
                changed = true
                return adopted.id
            }

            val id = UUID.randomUUID().toString()
            working += MovieCategory(
                id = id,
                name = defaultName,
                createdAtMs = nowMs,
                kind = kind,
            )
            changed = true
            return id
        }

        val movieId = resolve(MovieCategoryKind.MOVIE, MovieCategoryKind.MOVIE_NAME)
        val seriesId = resolve(MovieCategoryKind.SERIES, MovieCategoryKind.SERIES_NAME)

        if (changed) persistLibrary(current.copy(categories = working))
        return ScanCategoryIds(movieId = movieId, seriesId = seriesId)
    }

    // ── 影片标记 ────────────────────────────────────────────────────────────────

    /**
     * 把一部影片标记进分类，返回新标记的 id。
     *
     * 分类不存在、或**同一分类内**已经有同一个 [uri] 时返回 null。「同一分类内」这三个字
     * 是有意的：同一个文件可以同时属于「纪录片」与「给客人看」两个分类（收集癖用户的常见
     * 用法），而在一个分类里重复加同一部片只会让界面出现两行一模一样的东西。
     *
     * [signature] 由调用方（影厅选片那条路）算好传进来 —— 登记时就存下它，
     * 后面影厅选片就不必再重算 3 MiB（§16.8）。
     *
     * 新条目的 `lastCheckedAtMs` 是 0，表示**从没探测过**；界面不该把「0」显示成
     * 「1970 年检查过」。
     */
    fun addMovie(
        categoryId: String,
        uri: String,
        signature: MediaSignature,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val current = _snapshot.value
        if (current.categories.none { it.id == categoryId }) return null
        if (current.movies.any { it.categoryId == categoryId && it.uri == uri }) return null

        val id = UUID.randomUUID().toString()
        persistLibrary(
            current.copy(
                movies = current.movies + MovieEntry(
                    id = id,
                    categoryId = categoryId,
                    uri = uri,
                    signature = signature,
                    addedAtMs = nowMs,
                ),
            ),
        )
        return id
    }

    /**
     * 一次登记一批影片（扫描用，§16.15），返回**真的加进去的条数**。
     *
     * 与 [addMovie] 的差别只有两处，两处都是为了批量：
     *  - **一次算完、只落一次盘**（理由同 [removeMovies]：N 次落盘 = N 次重组）；
     *  - **批内也去重**：同一个文件在一批里出现两次（SD 卡与内置存储各一份）时只收第一条 ——
     *    `addMovie` 的「同一分类内同一个 uri 只留一条」拦不住它（两条 uri 不同）。
     *
     * 分类不存在时返回 0 且不落盘：扫描是异步的，跑完时那个分类可能刚被用户删掉。
     * 一条都没加进去（全被去重）时也不落盘 —— 空写只会白换一次快照。
     *
     * 新条目的 `lastCheckedAtMs` 是 0（从没探测过），与 [addMovie] 同口径。
     */
    fun addMovies(
        categoryId: String,
        movies: List<NewMovie>,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        if (movies.isEmpty()) return 0

        val current = _snapshot.value
        if (current.categories.none { it.id == categoryId }) return 0

        // 只在**目标分类**里比 uri：同一部片可以同时属于两个分类（§16.3 的收集癖用法），
        // 那两条的 uri 相同是合法的。跨分类的重复由扫描那一层按「文件名 + 大小」拦（§16.15）。
        val takenUris = current.movies
            .filter { it.categoryId == categoryId }
            .mapTo(HashSet()) { it.uri }

        val additions = ArrayList<MovieEntry>(movies.size)
        movies.forEach { movie ->
            if (!takenUris.add(movie.uri)) return@forEach
            additions += MovieEntry(
                id = UUID.randomUUID().toString(),
                categoryId = categoryId,
                uri = movie.uri,
                signature = movie.signature,
                addedAtMs = nowMs,
            )
        }

        if (additions.isEmpty()) return 0
        persistLibrary(current.copy(movies = current.movies + additions))
        return additions.size
    }
    /**
     * 改显示名。[title] 为 null / 空串 / 纯空白 ⇒ 把 `titleOverride` 置回 null，
     * **这是「取消重命名、回到按文件名显示」的唯一路径**（界面上把输入框清空再保存走的就是它）。
     * id 不存在时返回 false。
     *
     * 只动 `titleOverride`，**绝不碰 `signature.fileName`** —— 理由见 [MovieEntry] 的
     * 类注释（§16.2）：显示名混进摘要会让「本机改了名」变成拦住开播的文件名不一致。
     */
    fun renameMovie(id: String, title: String?): Boolean {
        val override = title?.trim()?.takeIf { it.isNotBlank() }

        val current = _snapshot.value
        if (current.movies.none { it.id == id }) return false

        persistLibrary(
            current.updateMovie(id) { movie -> movie.copy(titleOverride = override) },
        )
        return true
    }

    /** 删掉一条标记。**进度记录不删**（同 [deleteCategory] 的理由）。 */
    fun removeMovie(id: String) {
        val current = _snapshot.value
        if (current.movies.none { it.id == id }) return

        persistLibrary(current.copy(movies = current.movies.filterNot { it.id == id }))
    }

    /**
     * 一次删掉多条标记（影片管理页的批量删除），返回**真的删掉的条数**。
     *
     * ── 为什么不是循环调 [removeMovie] ──────────────────────────────────────────
     * 每一次 [removeMovie] 都是一次整份重写 + 换一次 `StateFlow`：删 20 条就是 20 次
     * 落盘与 20 次重组，而用户只按了一次按钮。这里一次算完、只落一次盘。
     *
     * [ids] 里不存在的 id 不算数（返回值只计真的删掉的）—— 界面的选中集与库之间会有
     * 时间差（失效探测刚把某条判成 Missing 删掉、用户刚删掉一个分类），静默忽略比
     * 报错合适：用户按的是「删这些」，那些本来就已经不在了。
     *
     * 一条都没删掉时**不落盘**：写一份一模一样的 JSON 只会白白换一次快照、让所有
     * 订阅者重组一遍。进度记录不删（同 [removeMovie] 的理由）。
     */
    fun removeMovies(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0

        // 选中集在生产路径上是 Set，但方法签名收 Collection —— 这里换一次哈希表，
        // 免得调用方传 List 时 `in` 变成每一条都扫一遍。
        val targets = ids.toHashSet()
        val current = _snapshot.value
        val remaining = current.movies.filterNot { it.id in targets }
        val removed = current.movies.size - remaining.size

        if (removed > 0) persistLibrary(current.copy(movies = remaining))
        return removed
    }

    /**
     * 把选中的几条一次性移到另一个分类（§16.16），返回**真的换了分类的条数**。
     *
     * 五条口径（逐条对应 §16.16 的表）：
     *  - 一次落盘；
     *  - [targetCategoryId] 不存在 ⇒ 返回 0、不落盘（扫描/界面与库之间有时间差）；
     *  - 已经在目标分类里的条目当作无变化（不计入返回值）；
     *  - 目标分类里已有同一个 [MovieEntry.uri] 时**合并**：删掉源条目、不新增，
     *    也不计入返回值 —— `addMovie` 的规则就是「同一分类内同一个文件只留一条」，
     *    移动必须服从它，否则会造出一条 `addMovie` 自己都不允许的状态；
     *  - 全都没动 ⇒ 不落盘（空写白换一次快照）。
     *
     * 进度记录不受影响：它按摘要存（§16.6），与条目挂在哪个分类无关。
     */
    fun moveMovies(ids: Collection<String>, targetCategoryId: String): Int {
        if (ids.isEmpty()) return 0

        val current = _snapshot.value
        if (current.categories.none { it.id == targetCategoryId }) return 0

        val targets = ids.toHashSet()
        // 目标分类里已有的 uri。**包含这次也在选中集里的那些**：它们自己的分支是
        // 「已经在目标分类里 ⇒ 无变化、原地留着」，所以它们并没有腾出位置；把一个
        // 同 uri 的源条目移进来只会让目标分类里出现两条一模一样的 —— 而那正是
        // `addMovie` 不允许的状态（同一分类内同一个文件只留一条）。
        val occupied = current.movies
            .filter { it.categoryId == targetCategoryId }
            .mapTo(HashSet()) { it.uri }

        var moved = 0
        var changed = false
        val next = ArrayList<MovieEntry>(current.movies.size)

        current.movies.forEach { movie ->
            if (movie.id !in targets || movie.categoryId == targetCategoryId) {
                next += movie
                return@forEach
            }

            if (!occupied.add(movie.uri)) {
                // 目标分类里已经有同一个文件 ⇒ 合并：源条目就此消失（→ `next` 里不加）。
                changed = true
                return@forEach
            }

            next += movie.copy(categoryId = targetCategoryId)
            moved++
            changed = true
        }

        if (changed) persistLibrary(current.copy(movies = next))
        return moved
    }

    /**
     * 设置「已看完」的手动覆盖（§16.17）：`false` = 手动挪出（粘住）、`true` = 手动算看完、
     * `null` = 回到按进度自动判定。
     *
     * 值没有变化时**不落盘**但返回 true：调用方问的是「这个操作成立吗」，
     * 而「设置成它已经是的值」是成立的、只是什么也不用做。
     */
    fun setFinished(id: String, finished: Boolean?): Boolean {
        val current = _snapshot.value
        val entry = current.movies.firstOrNull { it.id == id } ?: return false
        if (entry.finishedOverride == finished) return true

        persistLibrary(current.updateMovie(id) { movie -> movie.copy(finishedOverride = finished) })
        return true
    }

    /**
     * 文件内容变了（探测到大小对不上）之后重新登记摘要，返回是否命中条目。
     *
     * 刻意**不自动**重算：摘要同时是进度键的输入，自动换掉会让进度接不上（§16.5 末 /
     * §16.11），所以这条路由界面上的「重新读取摘要」显式触发。
     *
     * `missing` 一并清掉、`lastCheckedAtMs` 记为 [nowMs]：能重新读出摘要就说明这个 URI
     * 刚被成功打开过，之前那条「打不开」的结论已经过期了。
     *
     * `titleOverride` **不动** —— 用户起的名字与文件内容无关。
     */
    fun refreshSignature(
        id: String,
        signature: MediaSignature,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = _snapshot.value
        val entry = current.movies.firstOrNull { it.id == id } ?: return false
        // 远端条目的摘要本来就不在本机；给它写一份本地摘要会让它的 syncKey 与
        // 现算键分叉（它是靠 syncKey 认身份的），同步立刻多出一条重复影片。
        if (entry.remoteOnly) return false

        persistLibrary(
            current.updateMovie(id) { movie ->
                movie.copy(signature = signature, missing = false, lastCheckedAtMs = nowMs)
            },
        )
        return true
    }

    /**
     * 把一次探测结论落进库里。返回 **true 表示该条目已被删除**（界面不能再拿这个 id 去查）。
     *
     *  - [MovieProbe.Present]：清掉 `missing`、记下探测时刻。留着上次的原因没有意义 ——
     *    现在能打开了。
     *  - [MovieProbe.Missing]：**删掉条目**。这就是用户要的「本地文件被删则标记自动删除」；
     *    进度记录保留（§16.6），所以用户重新添加同一个文件能接上原来的进度。
     *  - [MovieProbe.Unreachable]：保留条目、只标 `missing = true`，界面显示原因 +
     *    手动清理入口（[removeMissing]）。「打不开但原因不明」不能当删除（§16.1 裁决②）。
     *
     * id 不存在时返回 false 且不改动任何东西：探测是异步跑的，条目可能已被用户删掉。
     */
    fun applyProbe(
        id: String,
        probe: MovieProbe,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = _snapshot.value
        val entry = current.movies.firstOrNull { it.id == id } ?: return false
        // 远端条目在本机没有 uri 可探测；把它当「打不开」判成 Missing 会**删掉账号里的片**，
        // 而那条删除还会顺同步推到所有设备（1b）。
        if (entry.remoteOnly) return false

        return when (probe) {
            is MovieProbe.Present -> {
                persistLibrary(
                    current.updateMovie(id) { movie ->
                        movie.copy(missing = false, lastCheckedAtMs = nowMs)
                    },
                )
                false
            }

            MovieProbe.Missing -> {
                persistLibrary(current.copy(movies = current.movies.filterNot { it.id == id }))
                true
            }

            is MovieProbe.Unreachable -> {
                persistLibrary(
                    current.updateMovie(id) { movie ->
                        movie.copy(missing = true, lastCheckedAtMs = nowMs)
                    },
                )
                false
            }
        }
    }

    /**
     * 手动清理入口：删掉全部 `missing == true` 的条目，返回删掉的条数。
     *
     * 为什么需要它：[MovieProbe.Unreachable] 是保守判断（可能只是云盘暂时连不上、
     * 授权方应用被卸载重装），所以界面不会自动删。攒下来的「暂时不可用」条目由用户
     * 在这里一次性清掉 —— 这是裁决②「宁可留着，也不误删」留下来的人手兜底。
     */
    fun removeMissing(): Int {
        val current = _snapshot.value
        // remoteOnly 的一律留着：它们不是「这台设备上打不开」，而是「本来就不在这台设备上」。
        val remaining = current.movies.filterNot { it.missing && !it.remoteOnly }
        val removed = current.movies.size - remaining.size

        if (removed > 0) persistLibrary(current.copy(movies = remaining))
        return removed
    }

    /** 当前全部「暂时不可用」的条目，供界面显示标记与清理入口。 */
    fun missing(): List<MovieEntry> = _snapshot.value.movies.filter { it.missing && !it.remoteOnly }

    // ── 同步写回（1b）────────────────────────────────────────────────────────────

    /**
     * 同步层专用：把一次**合并变换**落到当前的库上，返回是否真的变了。
     *
     * ── 为什么收 `(Snapshot) -> Snapshot` 而不是直接收一份新快照 ──────────────────
     * 「读到写」之间用户可能刚在界面上删了一条、加了一条。收一份现成快照等于拿**读那一刻**
     * 的旧值覆盖它（丢失更新）；收一个变换则由本类在写之前对**当前**值求值，
     * 而同步的变换是 upsert/按 key 删除这种**合并形状**，天然不会碰无关的条目。
     *
     * 变换必须只改同步管的字段：远端没有、本机才有的那些（uri / signature / missing /
     * lastCheckedAtMs / remoteOnly）由变换自己负责保持原样。
     */
    fun applySyncedLibrary(transform: (MovieLibrarySnapshot) -> MovieLibrarySnapshot): Boolean {
        val current = _snapshot.value
        val next = transform(current)
        if (next == current) return false
        persistLibrary(next)
        return true
    }

    /** 同 [applySyncedLibrary]，作用于进度那份键（进度是单独一条，避免高频写入牵动整个库）。 */
    fun applySyncedProgress(transform: (MovieProgressBook) -> MovieProgressBook): Boolean {
        val current = _progress.value
        val next = transform(current)
        if (next == current) return false
        persistProgress(next)
        return true
    }

    // ── 观看进度 ────────────────────────────────────────────────────────────────

    /**
     * 记一次播放进度（播放中每 15 秒一次、暂停时、退房收尾，§16.10）。
     *
     * 键由 [MovieProgressKey.of] 从摘要算出，**同一个键直接覆盖**：这样「同一部片看了
     * 两小时」在盘上始终只有一条记录，而不是几百条。
     *
     * [positionMs] / [durationMs] 都先夹到 >= 0：负数只说明播放器给了异常值，
     * 原样存进去会让界面算出负进度。`durationMs = 0` 表示「还不知道时长」，
     * [MovieProgress.fraction] 负责不拿它当分母。
     *
     * 进度**不检查这个摘要是否在库里**：影厅可以从文件选择那条兜底路直接开播（§16.8），
     * 那部片可能根本没被标记过，而用户下次真把它加进库时应当能接上进度。
     */
    fun rememberProgress(
        signature: MediaSignature,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = _progress.value
        val key = MovieProgressKey.of(signature)

        persistProgress(
            current.copy(
                byKey = current.byKey + (key to MovieProgress(
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    updatedAtMs = nowMs,
                )),
            ),
        )
    }

    /** 读进度。没记过返回 null（界面据此显示「没看过」，而不是一条 0% 的进度）。 */
    fun progressOf(signature: MediaSignature): MovieProgress? =
        _progress.value.byKey[MovieProgressKey.of(signature)]

    /**
     * 按条目读进度。**界面一律用这个重载**，别自己写 `progressOf(entry.signature)`。
     *
     * 远端来的条目（另一台设备上加的片）本机没有摘要，`entry.signature` 只是为了让
     * 显示名能落地的占位 —— 用它算出来的键与真正的 `sigKey` 不是一个值，
     * 于是「B 拉到这部片了，但进度栏永远空着」，而且不会报任何错。
     */
    fun progressOf(entry: MovieEntry): MovieProgress? =
        _progress.value.byKey[entry.syncKeyOrDerived]

    // ── 落盘 ────────────────────────────────────────────────────────────────────

    /** 本类里所有「改一条影片」的公共形状：命中的那条按 [transform] 换掉，其余原样。 */
    private fun MovieLibrarySnapshot.updateMovie(
        id: String,
        transform: (MovieEntry) -> MovieEntry,
    ): MovieLibrarySnapshot = copy(
        movies = movies.map { movie -> if (movie.id == id) transform(movie) else movie },
    )

    private fun persistLibrary(next: MovieLibrarySnapshot) {
        storage.write(keyLibrary, MovieLibraryCodec.encodeLibrary(next))
        _snapshot.value = next
    }

    private fun persistProgress(next: MovieProgressBook) {
        storage.write(keyProgress, MovieLibraryCodec.encodeProgress(next))
        _progress.value = next
    }

    /** 见类注释：解不开且原文非空白 ⇒ 留原文、清原键、以空库继续。 */
    private fun loadLibrary(): MovieLibrarySnapshot {
        val raw = readWithLegacyFallback(keyLibrary, LibraryScope.LEGACY_LIBRARY_KEY)
        val decoded = MovieLibraryCodec.decodeLibrary(raw)

        if (decoded == null && !raw.isNullOrBlank()) {
            storage.write(keyLibraryCorrupt, raw)
            storage.write(keyLibrary, null)
        }
        return decoded ?: MovieLibrarySnapshot()
    }

    /** 同 [loadLibrary]，坏数据的暂存键是 `progress.v1.<scope>.corrupt`。 */
    private fun loadProgress(): MovieProgressBook {
        val raw = readWithLegacyFallback(keyProgress, LibraryScope.LEGACY_PROGRESS_KEY)
        val decoded = MovieLibraryCodec.decodeProgress(raw)

        if (decoded == null && !raw.isNullOrBlank()) {
            storage.write(keyProgressCorrupt, raw)
            storage.write(keyProgress, null)
        }
        return decoded ?: MovieProgressBook()
    }

    /**
     * 读带作用域的键；**只有 local 作用域**才回落到无后缀的旧键（Phase 1 之前的格式）。
     *
     * 回落到旧键时顺手写一份到新键，于是迁移只发生一次。旧键不删（与
     * [LibraryScope.claimLocal] 保留 `local` 是同一条保险）。
     */
    private fun readWithLegacyFallback(key: String, legacyKey: String): String? {
        storage.read(key)?.let { return it }
        if (scope != LibraryScope.LOCAL) return null
        val legacy = storage.read(legacyKey) ?: return null
        storage.write(key, legacy)
        return legacy
    }

    companion object {

        /**
         * prefs 文件名。
         *
         * ⚠️ **改这里的任何一个字符串都等于丢用户的库**：键名就是数据的地址。
         * 数据键本身在 [LibraryScope] 里（带账号作用域），改格式的做法是
         * 「新键 + 读旧键迁移 + 迁移成功后删旧键」，而不是就地改字符串。
         */
        const val PREFS_NAME = "movie-library"

        // Phase 1：Android 生产入口 `from(context)` 已搬 `core:library-android`；
        // 本模块只保留 `PREFS_NAME` 与纯逻辑，构建不再依赖 android.content.Context。
    }
}

/**
 * 扫描目的地两个分类的 id（§16.15）。
 *
 * 用一对象而不是 `List<String>` 或 `Pair`：两个 id 是**有名字的**，调用点要按
 * 「电影 / 电视剧」分开用；`Pair` 的 `first` / `second` 在调用点读起来是同一种东西
 * 出现两次，传反了不会报错。
 */
data class ScanCategoryIds(
    val movieId: String,
    val seriesId: String,
)
