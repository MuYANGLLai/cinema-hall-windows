package com.cinemahall.core.library.sync

import com.cinemahall.core.library.MovieLibraryStore
import com.cinemahall.core.library.MovieProgressKey
import com.cinemahall.core.media.MediaSignature
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 片单同步的**编排层**（1b）：把「本地账本」「合并内核」「HTTP」三者串起来。
 *
 * ── 一轮同步的顺序：先推后拉，且这个顺序是硬要求 ──────────────────────────────
 * ```
 * push(本地增量)  →  pull(since=cursor, 直到 hasMore = false)
 * ```
 * 反过来（先拉后推）会在最常见的一种冲突上出错：两台设备都改了同一部片的「看完」标记，
 * 拉取时本机的改动还没上云，两边都不知道谁更新，客户端只能拿本地时钟去猜；
 * 先推的话，服务端在**同一张表**上按 `updated_at` 做一次权威比较，客户端只需要
 * 老实接受「服务端比基线新的行」。
 *
 * ── 读游标只由拉取推进（本类最关键的一条）──────────────────────────────────────
 * `POST /api/library/changes` 的回执里也有一个 `cursor`，那是**写完之后**账号级的
 * `sync_seq`。看着很像「我已经同步到这里了」，但它可能包含**别的设备刚刚写入、
 * 本机还没读到的行**。拿它当读游标 = 静默跳过那段增量，而且永远补不回来
 * （那些行的 rev 已经小于游标）。所以 [pushPending] 完全不碰 `state.cursor`。
 *
 * ── 失败就停，不在这里重试 ────────────────────────────────────────────────────
 * 本类不做退避重试：调用方（启动流程 / 进片单页 / 播放钩子）知道「现在该不该再来一次」，
 * 而这里只会把 [LibrarySyncException.retryable] 交给它。唯一的例外是「分批」：
 * 一批失败就整轮失败，已推进的分批保留在服务端（幂等），下一轮从同一个游标读数。
 */
class LibrarySyncClient(
    private val api: LibrarySyncApi,
    private val store: MovieLibraryStore,
    private val states: SyncStateStore,
    /** 取 token。返回 null = 还没激活，调用方不该发请求。 */
    private val token: suspend () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val config: SyncConfig = SyncConfig(),
    /**
     * 后台推送用的作用域。进度上报是**用户不等待**的动作（播放中每 5 分钟一次），
     * 让它把界面线程挂在那里等一次 HTTP 往返是不合适的。
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** 一轮同步的结果，给界面/诊断显示。 */
    data class Report(
        /** 真的推上去（或服务端按 LWW 跳过）的条数。 */
        val pushed: Int,
        /** 应用了（LWW 赢下）的远端行数。 */
        val pulled: Int,
        /** 同步后账本里的读游标。 */
        val cursor: Long,
        /** 同步后还剩多少待推送（正常应为 0）。 */
        val pending: Int,
    )

    /**
     * 上次成功发出去的位置与时刻，按进度键。**只在内存里**：
     * 进程被杀之后第一次会上报一次（哪怕没到 5 分钟），代价是一条多余的进度，
     * 收益是重启后进度立刻可见 —— 比持久化一张「上次发送时刻」的表划算。
     */
    private data class SentProgress(val positionMs: Long, val atMs: Long)

    private val lastSent = ConcurrentHashMap<String, SentProgress>()

    /** 一轮完整同步：推完再拉到尽头。 */
    suspend fun sync(): Report {
        val pushed = pushPending()
        val pulled = pullAll()
        val state = states.load()
        val pending = LibrarySyncMerge.planPush(
            state = state,
            snapshot = store.snapshot.value,
            progress = store.progress.value,
            nowMs = nowMs(),
        ).batch.size
        return Report(pushed = pushed, pulled = pulled, cursor = state.cursor, pending = pending)
    }

    /**
     * 只拉。用户什么都没改时（打开片单页）用它：不产生任何上行流量。
     *
     * @return 应用了几行。
     */
    suspend fun pullAll(): Int {
        val token = token() ?: throw LibrarySyncException(
            LibrarySyncErrors.NOT_CONNECTED,
            "还没有激活，先输入入场码",
        )
        var applied = 0
        var rounds = 0
        while (true) {
            val state = states.load()
            val page = api.pull(token, since = state.cursor, limit = config.pageSize)
            val plan = LibrarySyncMerge.planApply(state, page)

            // 变换里已经按 LWW 挑出了要改的部分；null = 这一页没有需要改的本地行。
            plan.libraryTransform?.let { store.applySyncedLibrary(it) }
            plan.progressTransform?.let { store.applySyncedProgress(it) }
            states.save(plan.next)

            applied += plan.applied
            rounds += 1
            if (!page.hasMore) return applied
            if (rounds >= config.maxPages) return applied // 防呆：剩下的下次再来，不会丢
        }
    }

    /**
     * 只推。返回推上去（含被服务端 LWW 跳过）的条数。
     *
     * 分批推送：[ChangesBatch] 按 [SyncConfig.maxBatch] 切块，**每块一个事务**。
     * 全部块成功才把基线整份换成 `plan.next` —— 中途失败时基线保持旧值，
     * 下一轮重推一遍（服务端对同 `updated_at` 的行是 skip，幂等）。
     */
    suspend fun pushPending(): Int {
        val token = token() ?: throw LibrarySyncException(
            LibrarySyncErrors.NOT_CONNECTED,
            "还没有激活，先输入入场码",
        )
        var pushed = 0
        var rounds = 0
        while (rounds < config.maxPushRounds) {
            rounds += 1
            val state = states.load()
            val plan = LibrarySyncMerge.planPush(
                state = state,
                snapshot = store.snapshot.value,
                progress = store.progress.value,
                nowMs = nowMs(),
            )
            if (plan.batch.isEmpty) return pushed

            for (chunk in plan.batch.chunked(config.maxBatch)) {
                api.push(token, chunk)
                pushed += chunk.size
            }
            // 整批都成功了才认账：见类注释「分批」。
            states.save(plan.next)
        }
        return pushed
    }

    /**
     * 播放进度上报（**唯一的进度入口**）。
     *
     * 三件事，顺序不能换：
     *  1. **先写本地**：用户看到的进度条必须立刻动，不能等网络；
     *  2. 再按 [ProgressThrottle] 决定要不要发（播放中 5 分钟且变化 > 30 秒；暂停/退房立即）；
     *  3. 发的时候**只推进度那一批**（不拉）：拉取是进片单页/启动时的事，
     *     为了报一条进度去做一次全量增量拉取是浪费，而且会把界面上的列表在播放中改写。
     *
     * 失败**静默**：进度不是用户此刻在等的操作，抛到播放器上只会变成一次闪退或一条
     * 看不懂的提示；下一次触发（5 分钟后 / 暂停 / 退房）自然会重试。
     */
    fun reportProgress(
        signature: MediaSignature,
        positionMs: Long,
        durationMs: Long,
        trigger: ProgressTrigger,
    ) {
        store.rememberProgress(signature, positionMs, durationMs, nowMs())
        val key = MovieProgressKey.of(signature)
        val now = nowMs()
        val previous = lastSent[key]
        if (!ProgressThrottle.shouldSend(
                trigger = trigger,
                positionMs = positionMs,
                nowMs = now,
                lastSentPositionMs = previous?.positionMs,
                lastSentAtMs = previous?.atMs ?: 0L,
            )
        ) {
            return
        }
        lastSent[key] = SentProgress(positionMs, now)
        scope.launch { runCatching { pushPending() } }
    }

    /**
     * 还有多少条待推送（界面上的「待同步」计数 / 诊断用）。
     *
     * 它是**算出来的**，不是一份待办队列：见 `LibrarySyncMerge.planPush`。
     */
    fun pendingCount(): Int = LibrarySyncMerge.planPush(
        state = states.load(),
        snapshot = store.snapshot.value,
        progress = store.progress.value,
        nowMs = nowMs(),
    ).batch.size

    /** 换账号时清掉「上次发送」的记忆 —— 否则新账号会继承旧账号的节流状态。 */
    fun resetThrottle() {
        lastSent.clear()
    }
}

/**
 * 同步的时序参数。默认值都是**服务端上限的保守分数**：
 *
 *  - 服务端一页最多 500、一批最多 500（`LIBRARY_MAX_BATCH`），这里都取 200 ——
 *    一批 200 条影片的 JSON 大约 40 KB，离 1 MiB 的上限很远，网络差时也容易一次成；
 *  - [maxPages] 是「一次调用最多拉几页」的防呆：本机刚换账号时可能一次要回放几千行，
 *    全在这一次调用里也可能拖很久，分几次反而更稳；游标已经落盘，下次接着拉。
 */
data class SyncConfig(
    val pageSize: Int = 200,
    val maxBatch: Int = 200,
    val maxPages: Int = 50,
    val maxPushRounds: Int = 20,
)

/** 把一个批次按「总量不超过 [size]」切成若干批（分类 → 影片 → 进度 的顺序填）。 */
internal fun ChangesBatch.chunked(size: Int): List<ChangesBatch> {
    require(size > 0) { "分批大小必须为正数" }
    if (this.size <= size) return listOf(this)

    val chunks = ArrayList<ChangesBatch>()
    var categories = ArrayList<CategoryChange>()
    var movies = ArrayList<MovieChange>()
    var progress = ArrayList<ProgressChange>()
    var count = 0

    fun flush() {
        if (count == 0) return
        chunks += ChangesBatch(categories = categories, movies = movies, progress = progress)
        categories = ArrayList()
        movies = ArrayList()
        progress = ArrayList()
        count = 0
    }

    for (item in this.categories) {
        if (count == size) flush()
        categories += item
        count += 1
    }
    for (item in this.movies) {
        if (count == size) flush()
        movies += item
        count += 1
    }
    for (item in this.progress) {
        if (count == size) flush()
        progress += item
        count += 1
    }
    flush()
    return chunks
}
