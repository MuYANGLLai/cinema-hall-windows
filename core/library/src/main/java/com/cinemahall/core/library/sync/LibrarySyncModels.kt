package com.cinemahall.core.library.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 片单同步的**线上形状**（契约 §6.4 / 服务端 `server/src/library.mjs`）。
 *
 * ── 为什么这些 DTO 要单独一套，而不是直接复用 `MovieEntry` ────────────────────
 * 两边模型回答的问题不同：
 *
 *  - 本地条目（[com.cinemahall.core.library.MovieEntry]）是**一台设备上的东西**：
 *    它带 `content://` URI、带 3 MiB 采样出来的摘要，因为本机要能打开它、要能校验它；
 *  - 线上的条目是**一个账号上的东西**：只有 `sigKey`（摘要的哈希）、显示名、分类指针、
 *    看完覆盖。**URI 与摘要都不上云** —— 另一台设备拿到 URI 也没有意义（那是本机的
 *    文档授权），而完整摘要（文件名 + 大小 + 三段哈希）也没必要为了「另一台设备知道
 *    有这部片」而传上去。
 *
 * 所以 `sigKey` 是**不可逆**的：`MovieProgressKey.of(signature)` 是 SHA-256 取前 32 位
 * 十六进制，收到 `sigKey` 反推不出 `MediaSignature`。这就是为什么
 * [com.cinemahall.core.library.MovieEntry.syncKey] 必须显式存下来：
 * 远端来的条目在本地根本算不出同一个键。
 *
 * ── 空值语义（服务端实现里反复强调的那条）────────────────────────────────────
 * `deletedAtMs = null` 与「字段缺失」都表示**不是墓碑**（复活一条被删记录时传 null）。
 * 因此这些 DTO 的 `deletedAtMs` 都是 `Long? = null`，并且序列化侧 `explicitNulls = false`
 * —— 两者对上，请求体里不会出现一堆 `null`。
 */

// ── 下行（GET /api/library）──────────────────────────────────────────────────

/**
 * 一行分类。[rev] 是**账号级**单调游标（`accounts.sync_seq`），不是数组下标：
 * 服务端每写一行取一次号，两张表混在一页里也靠它排序（P1）。
 */
@Serializable
data class RemoteCategory(
    val id: String,
    val name: String,
    val kind: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val deletedAtMs: Long? = null,
    val rev: Long = 0L,
)

@Serializable
data class RemoteMovie(
    val sigKey: String,
    val categoryId: String? = null,
    val displayName: String = "",
    val titleOverride: String? = null,
    val finishedOverride: Boolean? = null,
    val addedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val deletedAtMs: Long? = null,
    val rev: Long = 0L,
)

@Serializable
data class RemoteProgress(
    val sigKey: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val deviceId: String? = null,
    val deletedAtMs: Long? = null,
    val rev: Long = 0L,
)

/**
 * 一页增量。[cursor] 是**本页最后一行**的 rev（空页时等于请求里的 `since`），
 * 下次拉取用它；[hasMore] 由服务端「多取一行」算出来，客户端只管照它继续。
 */
@Serializable
data class RemotePage(
    val cursor: Long = 0L,
    val hasMore: Boolean = false,
    val categories: List<RemoteCategory> = emptyList(),
    val movies: List<RemoteMovie> = emptyList(),
    val progress: List<RemoteProgress> = emptyList(),
)

// ── 上行（POST /api/library/changes）─────────────────────────────────────────

@Serializable
data class CategoryChange(
    val id: String,
    val name: String,
    val kind: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
)

/**
 * 一条影片变更。
 *
 * [displayName] 是**必填**的（服务端 `requireString(raw.displayName, …, 512)`）——
 * 墓碑也一样：另一台设备收到墓碑时要用它渲染「已删除」的日志/回溯，
 * 而它自己的库里可能从来没有过这条的显示名。
 *
 * [finishedOverride] 三态：`null` = 自动按进度判定。<u>它与 `deletedAtMs` 一样，
 * 传 null 表示「清空这个覆盖」</u>，不是「不改」——服务端见 null 就写 null。
 */
@Serializable
data class MovieChange(
    val sigKey: String,
    val displayName: String,
    val categoryId: String? = null,
    val titleOverride: String? = null,
    val finishedOverride: Boolean? = null,
    val addedAtMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
)

@Serializable
data class ProgressChange(
    val sigKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
)

/** 一批变更。服务端按 `categories → movies → progress` 的顺序应用（与这里字段顺序无关）。 */
@Serializable
data class ChangesBatch(
    val categories: List<CategoryChange> = emptyList(),
    val movies: List<MovieChange> = emptyList(),
    val progress: List<ProgressChange> = emptyList(),
) {
    val size: Int get() = categories.size + movies.size + progress.size
    val isEmpty: Boolean get() = size == 0
}

/** 上报回执。[cursor] 是**账号级**游标，见 [com.cinemahall.core.library.sync.LibrarySyncClient] 里「为什么不拿它当读游标」。 */
@Serializable
data class ChangesResult(
    val applied: Int = 0,
    val skipped: Int = 0,
    val tombstoned: Int = 0,
    val cursor: Long = 0L,
)
