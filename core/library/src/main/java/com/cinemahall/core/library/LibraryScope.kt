package com.cinemahall.core.library

/**
 * 影片库的**账号作用域**（方案 §8.4）。
 *
 * ── 为什么要分键 ────────────────────────────────────────────────────────────
 * 一台设备上换账号（或先本地用、后激活）时，两个人的片单不能互相覆盖。
 * 键名带账号 id 之后，切换账号 = 换一组键，两份数据在同一个 SharedPreferences 里各放各的。
 *
 * ── 键的形状 ────────────────────────────────────────────────────────────────
 * ```
 * library.v1.<scope>           分类与影片（scope = accountId 或 "local"）
 * progress.v1.<scope>          观看进度
 * library.v1.<scope>.corrupt   解不开时的原文暂存
 * ```
 * `.v1` 是**格式版本**，不是数据版本 —— 它留在前面，与既有键 `library.v1` 的语义一致。
 *
 * ── 旧键怎么办（不做就会丢用户的库）──────────────────────────────────────────
 * Phase 1 之前用的是无后缀的 `library.v1` / `progress.v1`。本类把 `local` 作用域
 * 定义为「带 `.local` 后缀」，但**首次读取时**若带后缀的键不存在、无后缀的旧键存在，
 * 就把旧键的内容搬到带后缀的键（[migrateLegacyLocal]）。老用户升级后看到的还是原来的库，
 * 而新键从此成为唯一读写点。
 *
 * ── 首次激活的认领 ──────────────────────────────────────────────────────────
 * 激活成功后把 `local` 的两份数据**复制**到账号键（[claimLocal]），`local` 保留不动：
 * 它是回滚保险 —— 退出登录 / 账号被停用 / 服务端出事时，用户至少还能回到激活前的本地库。
 * 复制而不是移动，代价是几十 KB 的重复；收益是「账号体系出问题时本地数据还在」。
 */
object LibraryScope {

    const val LOCAL = "local"

    /** Phase 1 之前的无后缀键（只用于迁移）。 */
    const val LEGACY_LIBRARY_KEY = "library.v1"
    const val LEGACY_PROGRESS_KEY = "progress.v1"

    fun scopeFor(accountId: String?): String =
        accountId?.trim()?.takeIf { it.isNotEmpty() } ?: LOCAL

    fun libraryKey(scope: String): String = "library.v1.$scope"

    fun progressKey(scope: String): String = "progress.v1.$scope"

    fun libraryCorruptKey(scope: String): String = "library.v1.$scope.corrupt"

    fun progressCorruptKey(scope: String): String = "progress.v1.$scope.corrupt"

    /**
     * 同步账本（游标 + 三张表的基线 + 待推送墓碑），键名 `sync.v1.<scope>`。
     *
     * 与库/进度同作用域：换账号必须换账本，否则 A 账号的游标会把 B 账号的增量
     * 整段跳过 —— 那不是「慢一拍」，是永久丢数据。
     */
    fun syncKey(scope: String): String = "sync.v1.$scope"

    /**
     * 把旧的无后缀键搬成 `local` 后缀（一次性、幂等）。
     *
     * 只在**目标键不存在**时搬：否则每次启动都会用旧值覆盖用户后来新增的库
     * （旧键在第一次搬完之后就不该再被写，但万一有别的版本还在写它，这条守卫能兜住）。
     *
     * @return 是否真的发生了迁移。
     */
    fun migrateLegacyLocal(storage: LibraryStorage, scope: String = LOCAL): Boolean {
        if (scope != LOCAL) return false
        var migrated = false
        migrated = migrateOne(storage, LEGACY_LIBRARY_KEY, libraryKey(scope)) || migrated
        migrated = migrateOne(storage, LEGACY_PROGRESS_KEY, progressKey(scope)) || migrated
        return migrated
    }

    /**
     * 首次激活：把 `local` 作用域的数据复制到 [accountId]（**保留** `local`）。
     *
     * 三种情况：
     *  - 账号键已有数据 → 不覆盖（用户可能已经在新账号下加过影片）；
     *  - `local` 没有数据（全新安装就激活）→ 什么都不做，返回 [ClaimResult.NoLocalData]；
     *  - 复制成功 → [ClaimResult.Copied]。
     *
     * `local` 里没有但旧的无后缀键有（升级后立刻激活，还没被读过一次）—— 先迁移再复制，
     * 否则用户升级+激活两件事一起做时，老库会被判定成「没有数据」。
     */
    fun claimLocal(storage: LibraryStorage, accountId: String): ClaimResult {
        val scope = scopeFor(accountId)
        if (scope == LOCAL) return ClaimResult.NoLocalData

        migrateLegacyLocal(storage, LOCAL)

        val targetLibrary = libraryKey(scope)
        if (storage.read(targetLibrary) != null) return ClaimResult.AlreadyClaimed

        val localLibrary = storage.read(libraryKey(LOCAL))
        val localProgress = storage.read(progressKey(LOCAL))
        if (localLibrary == null && localProgress == null) return ClaimResult.NoLocalData

        if (localLibrary != null) storage.write(targetLibrary, localLibrary)
        if (localProgress != null) storage.write(progressKey(scope), localProgress)
        return ClaimResult.Copied
    }

    private fun migrateOne(storage: LibraryStorage, from: String, to: String): Boolean {
        if (storage.read(to) != null) return false
        val legacy = storage.read(from) ?: return false
        storage.write(to, legacy)
        // 旧键**不删**：与「local 保留」同一条理由 —— 回滚保险。
        // 它已经不会被本类写入（读写都走带后缀的键），留着只是一份几十 KB 的备份。
        return true
    }
}

/** [LibraryScope.claimLocal] 的结果。界面据此决定要不要提示「已导入本地影片库」。 */
enum class ClaimResult {
    /** 账号键已有数据，没动。 */
    AlreadyClaimed,

    /** 从 `local` 复制过去了。 */
    Copied,

    /** `local` 本来就没有数据（全新安装直接激活）。 */
    NoLocalData,
}
