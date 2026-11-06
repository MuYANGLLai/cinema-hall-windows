package com.cinemahall.core.library

/**
 * 落盘接缝：只有「读一个字符串」「写一个字符串」两个方法（§16.7）。
 *
 * ── 为什么这里要多一个接缝，而仓库里既有的 5 个偏好类都直接吃 Context ─────────
 * `SignalPreferences` / `ModePreference` / `ThemePreference` / `KeepAlivePreference` /
 * `UserIdProvider` 都是「一个设置项一次读写」的薄封装，里面没有逻辑可测 ——
 * 给它们包一层接缝只是噪音。
 *
 * 本模块不一样：[MovieLibraryStore] 的核心是「库怎么变」—— 增删分类、标记影片、
 * 失效三态判定、进度合并，以及坏数据要留原文不能静默丢。这些逻辑**必须在 JVM 单测里真跑**
 * （§16.12 的本机可验证清单就是它们），而 `SharedPreferences` 在 JVM 上不存在
 * （本地单测拿到的是 android.jar 里会抛异常的桩）。把「存储」压成两个方法之后，
 * 全部逻辑留在 store 里，测试塞一份内存版实现即可。
 *
 * Phase 1：接口本身是纯 JVM；`SharedPreferences` 实现搬 `core:library-android`。
 */
interface LibraryStorage {

    /** 没存过（或被删掉了）返回 null。 */
    fun read(key: String): String?

    /** [value] 为 null ⇒ 删除该键。 */
    fun write(key: String, value: String?)
}
