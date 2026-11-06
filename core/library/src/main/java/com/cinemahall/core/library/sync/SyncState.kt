package com.cinemahall.core.library.sync

import com.cinemahall.core.library.LibraryScope
import com.cinemahall.core.library.LibraryStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 同步**账本**：读游标 + 三张表的「服务端已有什么」。
 *
 * ── 为什么需要一份账本，而不是直接对比服务端 ──────────────────────────────────
 * 增量同步只回答「rev > 游标 的行」，它**不回答**「本地有什么是服务端没有的」。
 * 而上报必须是增量的（5000 条全量推一遍会被限流、也会把别处的改动覆盖回去），
 * 于是客户端必须自己记住「上一次和服务端对齐时，三张表各是什么样」。
 * 这份记忆就是 [categories] / [movies] / [progress] 三张基线表。
 *
 * 每次上报成功后，被推上去的那些值写回基线；每次拉取应用成功后，收到的行也写回基线
 * —— 这样「本地 == 基线」就等价于「服务端已经有了」，没有任何待推送项。
 *
 * ── 为什么基线的键与本地条目的键不同 ──────────────────────────────────────────
 * 分类用 `id`（本来就是客户端生成的 UUID，两端同一个）；影片与进度用 `sigKey`
 * （摘要哈希），因为**同一部片在不同设备上的 `MovieEntry.id` 不同** —— 那是各设备
 * 自己生成的。用 id 做同步键会让同一部片在账号里变成两条。
 *
 * ── 删除（墓碑）为什么不用单独一张表 ──────────────────────────────────────────
 * 本地删除是**真的从库里删掉**（`MovieLibraryStore.removeMovie`），删完不留痕迹。
 * 但账本里还留着「服务端曾经有这条」的基线 —— 于是「基线有、本地没有」本身就是
 * 一条待推送的删除意图，上报时补成 `deletedAtMs = 现在` 的墓碑行即可。
 *
 * 刻意**不**再单独存一张 `sigKey → 删除时刻` 的表：那张表与基线是同一份信息的两个副本，
 * 一旦两者不同步（删了本地忘了记墓碑、或记了墓碑又手工恢复），就会出现
 * 「服务端有一行永远推不上去的删除」或「删了又自己回来」。让基线当唯一真相，
 * 判据只有一条，测试也只需要钉一处。
 *
 * [cursor] 是**读游标**：只由拉取推进。上报回执里那个 `cursor` **绝不**写进这里 ——
 * 它是账号级的当前值，可能包含别的设备刚写入、本地还没读到的行（服务端
 * `applyChanges` 返回的正是写完之后的值）。拿它当读游标会静默跳过那段增量。
 */
@Serializable
data class SyncState(
    val cursor: Long = 0L,
    val categories: Map<String, BaselineCategory> = emptyMap(),
    val movies: Map<String, BaselineMovie> = emptyMap(),
    val progress: Map<String, BaselineProgress> = emptyMap(),
)

@Serializable
data class BaselineCategory(
    val name: String,
    val kind: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

@Serializable
data class BaselineMovie(
    val displayName: String,
    val categoryId: String? = null,
    val titleOverride: String? = null,
    val finishedOverride: Boolean? = null,
    val addedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

@Serializable
data class BaselineProgress(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

/**
 * 账本的落盘（一个字符串键，与库/进度共用 [LibraryStorage] 接缝）。
 *
 * ── 解不开就当成空账本，但**不静默** ─────────────────────────────────────────
 * 与 `MovieLibraryStore` 对坏数据的态度一致：原文搬到 `sync.v1.<scope>.corrupt` 留底，
 * 本次以空账本继续。空账本 = `cursor = 0` + 无基线，后果是**一次全量重拉**：
 * 服务端把三张表（含墓碑）整份回放一遍，本地据此重建 —— 这是可以自愈的，
 * 比「每次启动都崩在同一个坏字符串上」好得多。
 *
 * `cursor = 0` 那次重拉会顺带把**本地独有的条目**当新条目再推一遍（基线是空的）。
 * 服务端按 `sigKey` 幂等（`updated_at` 相同时 skip），所以只是多一个来回，不会重复入库。
 */
class SyncStateStore(
    private val storage: LibraryStorage,
    scope: String,
) {

    private val key: String = LibraryScope.syncKey(scope)
    private val corruptKey: String = key + ".corrupt"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun load(): SyncState {
        val raw = storage.read(key) ?: return SyncState()
        return try {
            json.decodeFromString(SyncState.serializer(), raw)
        } catch (e: Exception) {
            storage.write(corruptKey, raw)
            storage.write(key, null)
            SyncState()
        }
    }

    fun save(state: SyncState) {
        storage.write(key, json.encodeToString(SyncState.serializer(), state))
    }
}
