package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import kotlinx.serialization.Serializable

/**
 * 一个用户自建分类（《技术方案评审》§16.3）。
 *
 * [id] 与 [name] 分开存：分类可以改名，而影片条目里的 `categoryId` 是个**指针** ——
 * 改名只动一个字符串；若拿名字当键，改一次名就要重写该分类下每一条影片。
 *
 * [createdAtMs] 是**墙上时钟**毫秒，不是 `SystemClock` 的单调时钟：这个时刻要跨重启读，
 * 而单调时钟的起点每次开机都不同，存下来没有意义。
 */
@Serializable
data class MovieCategory(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    /**
     * 扫描的目的地标记（§16.15）：`"movie"` / `"series"`，`null` = 用户自建。
     *
     * ── 为什么不能按名字认这个分类 ────────────────────────────────────────────
     * [name] 是用户可改的（[MovieLibraryStore.renameCategory]）。扫描若按名字找
     * 「电影」分类，用户把它改名成「影片」之后下一次扫描就找不到它，于是**再建一个
     * 「电影」** —— 用户会看到两个分类，而其中一个是他刚改过名的那个。
     *
     * 这个字段只说明「这个分类是扫描建的」，不限制用户对它做任何事：改名、往里面
     * 手动加片、删掉它（下次扫描会用默认名重建）都可以。
     */
    val kind: String? = null,
)

/**
 * 一条「这部片属于这个分类」的标记（§16.3）。注意是**标记**而不是拷贝：
 *
 *  - [uri] 是 SAF 给的 `content://` 文档 URI，文件本身还在用户原来的目录里；
 *  - [signature] 是登记那一刻算出来的片源摘要快照（§7 的 `{fileName,size,blocks[]}`），
 *    它同时是三件事的输入：失效检测要复核的东西、进度键的输入、以及影厅选片时
 *    不必重算 3 MiB 的那份现成摘要；
 *  - [titleOverride] 是用户起的显示名，**它绝不能写进 [signature] 的 fileName**，理由见下。
 *
 * ── 为什么显示名要单独一个字段（§16.2）──────────────────────────────────────
 * §7 的三步比对第一判据就是文件名
 * （[com.cinemahall.core.media.MediaSignatureComparator.compare]，NFC 归一化后逐字比、
 * 大小写敏感），而 §7 的规则是「任一不一致则**阻止开播**，不提供强制继续」。
 *
 * 于是若把用户起的名字写进 `signature.fileName`：「本机改了名、对端没改」会变成
 * `MismatchReason.FileName`，直接拦住开播 —— 而用户改的只是软件里的显示名、
 * 根本没动过文件，这条红字他既理解不了，也没有任何操作能让它消失。
 *
 * 所以 `signature.fileName` 永远保存**登记时的真实文件名快照**，显示名走
 * [titleOverride]；两者在存储、序列化、比对里都互不干扰，影厅交给 §7 的始终是
 * [signature]。这也意味着重命名**不需要两端同步**（各记各的、允许不同）。
 *
 * ── 为什么每个字段都有默认值 ────────────────────────────────────────────────
 * 这张结构要落盘、要跨版本读写（§16.4）。所有字段都有默认值 + 解码侧
 * `ignoreUnknownKeys` 合起来，才是「新版本加字段后旧版本读到不炸、旧版本的数据
 * 新版本也解得出」的全部依据 —— 缺的键取默认值，多的键被忽略。
 *
 * [missing] 是「上次探测的结论」，不是实时状态（§16.3）：它表示条目还在，
 * 但最近一次探测打不开、且原因不是「文件不存在」。它**不是**删除标记 ——
 * 确定不存在的条目会被直接删掉（见 `MovieLibraryStore.applyProbe`）。
 */
@Serializable
data class MovieEntry(
    val id: String,
    val categoryId: String,
    val uri: String,
    val signature: MediaSignature,
    val titleOverride: String? = null,
    val addedAtMs: Long = 0L,
    val lastCheckedAtMs: Long = 0L,
    val missing: Boolean = false,
    /**
     * 「已看完」的手动覆盖（§16.17）。三态，缺一不可：
     *
     *  - `null`（默认）⇒ **按进度自动判定**：进度 ≥ 85% 就归进「已看完」；
     *  - `true` ⇒ 手动算看完（本轮界面还没有入口，但数据形态先留着，
     *    否则将来加「标为已看完」要再改一次落盘格式）；
     *  - `false` ⇒ **手动挪出，而且粘住**：这一条从此不再因为进度高而被自动归入。
     *
     * ── 为什么必须是三态而不是一个 `finished: Boolean` ─────────────────────────
     * 一个布尔只能表达「看完 / 没看完」，表达不了「这是自动算的还是用户说的」。
     * 而「手动挪出来」如果只是写一个 `false`、判定时又被进度覆盖回去，那颗按钮
     * 就等于没有 —— 用户看到的下一帧它又回到了「已看完」里。
     *
     * 用 `null` 表示「没表过态」是这套三态的基础：它让「自动」成为一个**可以被恢复**
     * 的状态（界面上的「恢复自动判定」就是把它置回 `null`）。
     */
    val finishedOverride: Boolean? = null,
    /**
     * 同步键（服务端的 `sigKey`）。**空串表示「从 [signature] 现算」**。
     *
     * ── 为什么必须能显式存下来（1b 片单同步）──────────────────────────────────
     * 本机登记的影片，键由 [MovieProgressKey.of] 从摘要算出；但**另一台设备推来的条目
     * 只有键本身**：`sigKey` 是 SHA-256 的前 32 位十六进制，反推不回 `MediaSignature`
     * （那是不可逆的），而那台设备的 `content://` URI 在本机根本没有意义。
     *
     * 所以远端条目落到本机时是**没有摘要**的：它带着服务端给的键、显示名与进度，
     * 但本机打不开它。若这里不留字段，这类条目每次读出来都会得到一个「用合成摘要算的
     * 另一个键」，于是它的进度永远对不上、同步时会变成两条。
     *
     * 默认空串让既有数据与既有单测一行不改：老条目的键仍旧现算，且与 1a 之前完全一致。
     */
    val syncKey: String = "",
    /**
     * 「这条影片只在账号里，本机的文件不在这儿」（1b）。
     *
     * 与 [missing] 的区别是**语义**而不是程度：[missing] 是「本机有这条、上次探测打不开」
     * （云盘掉线、授权被撤），重试就有可能变回可用；[remoteOnly] 是「它从来就没在本机
     * 出现过」—— 文件在另一台设备上。所以：
     *
     *  - 它**不参与**本机探测与清理入口（否则「清理不可用条目」会把账号里别人的片删掉，
     *    而那条删除还会顺着同步推到所有设备）；
     *  - 界面应当显示「另一台设备」而不是「文件不可用」，并且不给播放入口。
     */
    val remoteOnly: Boolean = false,
) {

    /**
     * 界面显示用：有自定义名就用它，否则回落到真实文件名（§16.3 的
     * `titleOverride ?: signature.fileName`）。
     *
     * `null`、空串、纯空白都算「没有自定义名」：用户把输入框清空再保存，
     * 语义就是「回到按文件名显示」，与 `MovieLibraryStore.renameMovie` 把
     * [titleOverride] 置回 null 那条路径是同一条。
     *
     * **只用于展示**：它不参与任何一致性判据、也不上链路（§16.2）。
     */
    val displayName: String
        get() = titleOverride?.takeIf { it.isNotBlank() } ?: signature.fileName

    /**
     * 这条影片的**同步键与进度键**：优先用 [syncKey]（远端条目），否则现算。
     *
     * 必须是唯一入口 —— 库里凡是「按摘要找进度」的地方都要走它。任何一处直接写
     * `MovieProgressKey.of(entry.signature)`，远端条目就会算出一个不同的键：
     * 症状是「B 拉到这部片了，但进度栏永远是空的」，而且不会报任何错。
     */
    val syncKeyOrDerived: String
        get() = syncKey.ifEmpty { MovieProgressKey.of(signature) }
}

/**
 * 整库的一份不可变快照（§16.3）。
 *
 * 为什么要「快照」而不是让界面读一个可变集合：界面要的只是「有变化就重绘」，
 * 拿一份不可变值就不存在「读到一半被改掉」的中间态。`MovieLibraryStore` 每次写入
 * 都造一份新快照并整份落盘 —— 影片库是几百字节到几十 KB 的量级，整份比增量简单得多，
 * 也不会出现「增量写了一半、进程被杀」的半损坏状态。
 */
@Serializable
data class MovieLibrarySnapshot(
    val categories: List<MovieCategory> = emptyList(),
    val movies: List<MovieEntry> = emptyList(),
) {

    /** 按 id 找分类；找不到返回 null（调用方据此判「这个分类已经不在库里了」）。 */
    fun category(id: String): MovieCategory? = categories.firstOrNull { it.id == id }

    /**
     * 该分类下的影片，按 [MovieEntry.addedAtMs] **升序**。
     *
     * 用加入时间而不是显示名排序：显示名随时可改，排序跳来跳去很难用；加入时间是
     * 固定的，新加的在最下面，与用户「我刚加的应该在最下面」的记忆一致。
     * 同一毫秒加入的两条保持库里的先后顺序（Kotlin 的 `sortedBy` 是稳定排序）。
     */
    fun moviesIn(categoryId: String): List<MovieEntry> =
        movies.filter { it.categoryId == categoryId }.sortedBy { it.addedAtMs }
}

/**
 * 一条「准备登记进库」的影片（§16.15 的扫描、以及将来任何批量录入）。
 *
 * 只有两个字段：URI 是这一条的身份，[signature] 是登记时就该存下来的摘要（§16.3）。
 * **不含分类** —— 批量录入的入口是 `addMovies(categoryId, items)`，分类由调用方
 * 一次定好，不该混进每一行里（那样同一批里混进两个分类就成了合法输入，而它没有意义）。
 */
data class NewMovie(
    val uri: String,
    val signature: MediaSignature,
)
