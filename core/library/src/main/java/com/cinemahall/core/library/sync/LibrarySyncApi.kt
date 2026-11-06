package com.cinemahall.core.library.sync

/**
 * 片单同步的两个 HTTP 动作（契约 §6.4）。
 *
 * 单独一个接口而不是直接吃 `HttpLibrarySyncApi`：同步内核有一大半逻辑是
 * 「拉回来之后怎么合并」「推之前怎么算增量」，那些必须在 JVM 单测里用假服务端
 * 反复跑（尤其是失败与半成功），而不是每次起一台 Node。
 *
 * 两个方法都是 `suspend` 且都**只要 token**：账号 id 不从请求体里来 ——
 * 它由服务端从 token 解出来（`requireAuth`）。客户端传 `accountId` 是没用的，
 * 那反而会让人以为「换个 id 就能读别人的库」。
 */
interface LibrarySyncApi {

    /**
     * 增量拉取。[since] 是本机账本里的读游标，[limit] 是单页上限（服务端夹到 1..500）。
     *
     * 返回的 [RemotePage.hasMore] 为 true 时要**继续拉**，直到 false —— 服务端一页
     * 最多 500 行，而三张表加起来可能远超这个数（5000 条影片 + 进度）。
     */
    suspend fun pull(token: String, since: Long, limit: Int): RemotePage

    /**
     * 批量上报。服务端在**一个事务**里应用整批：要么全进，要么全不进（配额超限时
     * 抛 `LIBRARY_FULL` 并回滚）。所以客户端可以放心地「成功后整批写进基线」。
     */
    suspend fun push(token: String, batch: ChangesBatch): ChangesResult
}
