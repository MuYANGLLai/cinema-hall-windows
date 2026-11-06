package com.cinemahall.core.library

/**
 * 影片在「正方形网格」里的排列顺序（用户 2026-10-13 定的规则）。
 *
 * ── 规则原文与它落成的三段 ───────────────────────────────────────────────────
 * 用户口径：「排序规则为按上次观看时间，离上次观看时间越近则越靠前；如果时间一样或
 * 没有上次观看时间，则按名称排序。如果影片名字中明确含有第几集等字眼，要把这些影片
 * 相邻排序，把上次观看时间最近的一集放在相邻排序的最前面，其它影片按集数从小到大排序。」
 *
 * 落成三段：
 *  1. **分组**：显示名里能解析出「第几集 / EP01 / S01E03」的条目，按「去掉集数标记之后的
 *     名字」合成一组；其余条目各自成一组。**组是排序的最小单位** —— 这就是「相邻排序」
 *     的实现：同组必然连在一起，不会被别的片子插进去。
 *  2. **组的先后**：组内最近一次观看时间（`updatedAtMs` 的最大值）越近越靠前；没有观看
 *     记录的组排在**所有有记录的组之后**（它们离「上次观看」无限远），这些组之间、以及
 *     时间并列的组之间，按名称排。
 *  3. **组内**：上次观看时间**最近的那一集**排第一，其余按集数从小到大。
 *
 * ── 为什么第 3 条不是「按集数排完再把最近的挪到最前」以外的东西 ────────────────
 * 就是它。**并列的最大值只取一个**（取集数最小的那集），剩下的仍在原顺序里 —— 于是
 * 「最近的一集在最前，其余升序」在并列时也成立，而且结果唯一（不会因为集数顺序抖动）。
 *
 * ── 为什么名称比较忽略大小写 ──────────────────────────────────────────────────
 * 片名是给人看的（「The Matrix」与「the matrix」不该分成两处）。`lowercase()` 用的是
 * **Unicode 不变区域**映射（不是设备语言），所以同一份库在任何机器上排出来的顺序一样。
 * 大小写不同但忽略后相同的两条，再按原串比一次，保证顺序是确定的。
 *
 * ── 为什么这一步不碰磁盘、也不碰 Compose ───────────────────────────────────────
 * 它只吃「库快照里的影片列表」与「进度表」两份不可变值，输出一份新列表。所以它能逐格
 * 钉在 JVM 单测上（见 `MovieOrderingTest`），而界面只负责把它铺成网格。
 */
object MovieOrdering {

    /**
     * 排好序的影片列表。入参顺序即「没有任何依据时」的兜底顺序（库里按加入时间升序给）。
     *
     * 少于两条时原样返回：一条没有排序可言，而空列表更不该在这里被碰。
     */
    fun arrange(movies: List<MovieEntry>, progress: MovieProgressBook): List<MovieEntry> {
        if (movies.size < 2) return movies

        val seriesMembers = LinkedHashMap<String, MutableList<MovieEntry>>()
        val singles = mutableListOf<MovieEntry>()

        movies.forEach { entry ->
            val parsed = parseEpisode(entry.displayName)
            if (parsed == null) {
                singles += entry
            } else {
                seriesMembers.getOrPut(parsed.seriesKey) { mutableListOf() } += entry
            }
        }

        val groups = mutableListOf<Group>()

        seriesMembers.forEach { (seriesKey, members) ->
            // 集数升序；同一集号（少见，但文件名可能重复）再按显示名，保证顺序确定。
            val byEpisode = members.sortedWith(
                compareBy({ parseEpisode(it.displayName)?.episode ?: Int.MAX_VALUE }, { it.displayName }),
            )
            val latest = members.mapNotNull { watchedAtMs(it, progress) }.maxOrNull()
            val ordered = if (latest == null) {
                byEpisode
            } else {
                val headline = byEpisode.first { watchedAtMs(it, progress) == latest }
                listOf(headline) + byEpisode.filter { it !== headline }
            }
            groups += Group(sortName = seriesKey, latestMs = latest, items = ordered)
        }

        singles.forEach { entry ->
            groups += Group(
                sortName = entry.displayName,
                latestMs = watchedAtMs(entry, progress),
                items = listOf(entry),
            )
        }

        // 稳定排序 ⇒ 时间与名称都并列的两组保持上面那个顺序（有集数的组按出现先后、
        // 单条按加入时间），结果同一份库每次一致。
        return groups
            .sortedWith(
                compareByDescending<Group> { it.latestMs ?: Long.MIN_VALUE }
                    .thenBy(NAME_ORDER) { it.sortName },
            )
            .flatMap { it.items }
    }

    /** 一组相邻排序的影片。 */
    private class Group(
        val sortName: String,
        val latestMs: Long?,
        val items: List<MovieEntry>,
    )

    private fun watchedAtMs(entry: MovieEntry, progress: MovieProgressBook): Long? =
        progress.byKey[entry.syncKeyOrDerived]?.updatedAtMs

    /**
     * 忽略大小写的名称比较；忽略后相同的按原串比（结果唯一）。
     */
    private val NAME_ORDER = Comparator<String> { left, right ->
        val folded = left.lowercase().compareTo(right.lowercase())
        if (folded != 0) folded else left.compareTo(right)
    }

    // ── 集数解析 ────────────────────────────────────────────────────────────────

    /**
     * 一个「第 N 集」的解析结果。
     *
     * @param seriesKey 去掉集数标记、再把分隔符归一化之后的名字 —— 同一部剧的各集算出来的
     *   值相同，分组就靠它。
     * @param episode 集号（**数值**，所以「第10集」排在「第2集」后面）。解析不出来时是 null，
     *   那一档走「连写按位拼」的分支（见 [chineseNumber]）。
     */
    internal data class EpisodeMark(val seriesKey: String, val episode: Int?)

    /**
     * 从显示名里解析集数标记。解析不出来返回 null（= 这一条不参与「相邻排序」）。
     *
     * ── 三种写法与它们的优先级 ──────────────────────────────────────────────────
     *  1. `第N集` / `第N话` / `第N期` / `第N回` / `第N章` / `第N节` / `第N部`（N 可以是
     *     阿拉伯数字，也可以是「一二三…十百千两」）。**先试这一种**：「某剧 第1季 第03集」
     *     要按 `第03集` 分组，而不是被前面的 `第1季` 抢走。
     *  2. `S01E03`（季 + 集）。
     *  3. `EP01` / `E01`。**前面必须是行首或非字母数字**，否则「Season」「Episode」
     *     这类词里会误命中。
     */
    internal fun parseEpisode(displayName: String): EpisodeMark? {
        for (pattern in EPISODE_PATTERNS) {
            val match = pattern.find(displayName) ?: continue
            val group = match.groups[1] ?: continue
            val numberText = group.value
            val episode = numberText.toIntOrNull() ?: chineseNumber(numberText)
            val key = seriesKeyOf(displayName, match.range)
            return EpisodeMark(seriesKey = key, episode = episode)
        }
        return null
    }

    private val EPISODE_PATTERNS: List<Regex> = listOf(
        Regex("第\\s*([0-9]+|[零〇一二三四五六七八九十百千两]+)\\s*[集話话期回章节節部]"),
        Regex("(?i)(?<![A-Za-z0-9])S\\s*[0-9]+\\s*E\\s*([0-9]+)"),
        Regex("(?i)(?<![A-Za-z0-9])EP?\\s*([0-9]+)(?![0-9])"),
    )

    /**
     * 系列键 = 显示名去掉命中的那一段，再把分隔符统一成空格、折叠、去首尾、转小写。
     *
     * 分隔符这一谱系（`.`、`_`、`-`、`[]`、`()`、全角、空白…）在资源名里随手就换一种，
     * 不归一化的话「海贼王-第01集」与「海贼王.第02集」会算成两个系列 —— 那正是这一条
     * 要避免的。**归一化之后可能是空串**（名字里只有集数标记，例如 `第01集.mp4` 去掉标记
     * 只剩扩展名），空串也是一个合法的键：那些条目本来就该相邻。
     */
    private fun seriesKeyOf(displayName: String, marked: IntRange): String {
        val rest = displayName.removeRange(marked)
        return rest
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .trim()
            .replace(WHITESPACE, " ")
            .lowercase()
    }

    private val WHITESPACE = Regex("\\s+")

    /** 集号上限。超过它按上限算：既防「二零零八」这类连写溢出，又足够容纳任何剧集。 */
    private const val MAX_EPISODE = 9999

    private val CN_DIGIT = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val CN_UNIT = mapOf('十' to 10, '百' to 100, '千' to 1000)

    /**
     * 中文数字 → 整数。两种写法都要认，因为它们混在同一批资源名里：
     *
     *  - **带单位**（`十` `百` `千`）：「二十三」→ 23、「一百零五」→ 105。逐段累加，
     *    单位前没有数字时按 1 算（「十一」→ 11）。
     *  - **不带单位**：按位拼（「一二三」→ 123、「二零零八」→ 2008）。这一类在剧集名里
     *    少见，但在年份、编号里常见。
     *
     * 认不出来返回 null —— 调用方据此把集号当「未知」，而不是猜一个 0 出来（猜出来的 0
     * 会让该集永远排在最前面）。
     */
    internal fun chineseNumber(text: String): Int? {
        if (text.isEmpty()) return null

        if (text.none { it in CN_UNIT }) {
            var concatenated = 0L
            text.forEach { character ->
                val digit = CN_DIGIT[character] ?: return null
                concatenated = concatenated * 10 + digit
                if (concatenated > MAX_EPISODE) return MAX_EPISODE
            }
            return concatenated.toInt()
        }

        var total = 0L
        var pending = 0L
        text.forEach { character ->
            val unit = CN_UNIT[character]
            if (unit != null) {
                total += (if (pending == 0L) 1L else pending) * unit
                pending = 0L
            } else {
                pending = CN_DIGIT[character]?.toLong() ?: return null
            }
        }
        return (total + pending).coerceAtMost(MAX_EPISODE.toLong()).toInt()
    }
}
