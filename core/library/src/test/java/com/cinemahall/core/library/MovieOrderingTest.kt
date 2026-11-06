package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 正方形网格的排列规则（[MovieOrdering]）。
 *
 * 这些用例钉的是**用户 2026-10-13 那条口径**的每一条分句：按上次观看时间倒序、时间相同
 * 或没看过则按名称、名字里含集数的相邻排序且最近看的那集在最前、其余按集数升序。
 *
 * 为什么值得逐格钉：排序错了**不会报任何错**，只会让「刚看的那部/那一集」跑到别处。
 * 而这一页是给人找片用的，顺序乱掉等于这个功能不存在 —— 但屏幕上看起来一切正常。
 */
class MovieOrderingTest {

    private fun entry(name: String): MovieEntry = MovieEntry(
        id = name,
        categoryId = "c1",
        uri = "content://demo/${name.hashCode()}",
        signature = MediaSignature(
            fileName = name,
            sizeBytes = 1024L,
            blocks = listOf("aa"),
        ),
    )

    /** 一条观看记录。时间就是排序唯一的依据，位置与时长填什么不影响它。 */
    private fun watched(vararg records: Pair<MovieEntry, Long>): MovieProgressBook = MovieProgressBook(
        byKey = records.associate { (movie, atMs) ->
            MovieProgressKey.of(movie.signature) to MovieProgress(
                positionMs = 1_000L,
                durationMs = 10_000L,
                updatedAtMs = atMs,
            )
        },
    )

    private fun names(movies: List<MovieEntry>): List<String> = movies.map { it.displayName }

    private fun arrange(movies: List<MovieEntry>, progress: MovieProgressBook = MovieProgressBook()) =
        names(MovieOrdering.arrange(movies, progress))

    // ── 名称与时间 ──────────────────────────────────────────────────────────────

    @Test
    fun `没有观看记录时按名称排序且忽略大小写`() {
        val result = arrange(listOf(entry("banana.mkv"), entry("Apple.mkv"), entry("cherry.mkv")))

        assertEquals(listOf("Apple.mkv", "banana.mkv", "cherry.mkv"), result)
    }

    @Test
    fun `看过的最新排最前`() {
        val old = entry("old.mkv")
        val fresh = entry("fresh.mkv")

        val result = arrange(listOf(old, fresh), watched(old to 100L, fresh to 300L))

        assertEquals(listOf("fresh.mkv", "old.mkv"), result)
    }

    @Test
    fun `观看时间相同则按名称`() {
        val zeta = entry("zeta.mkv")
        val alpha = entry("alpha.mkv")

        val result = arrange(listOf(zeta, alpha), watched(zeta to 500L, alpha to 500L))

        assertEquals(listOf("alpha.mkv", "zeta.mkv"), result)
    }

    @Test
    fun `有观看记录的排在没看过的前面`() {
        val never = entry("alpha.mkv")
        val seen = entry("zeta.mkv")

        val result = arrange(listOf(never, seen), watched(seen to 100L))

        assertEquals(listOf("zeta.mkv", "alpha.mkv"), result)
    }

    @Test
    fun `少于两条原样返回`() {
        val only = entry("only.mkv")

        assertEquals(listOf("only.mkv"), arrange(listOf(only)))
        assertEquals(emptyList<String>(), arrange(emptyList()))
    }

    // ── 集数相邻排序 ────────────────────────────────────────────────────────────

    @Test
    fun `第几集的相邻排在一起并按集数升序`() {
        val one = entry("某剧 第1集.mkv")
        val ten = entry("某剧 第10集.mkv")
        val two = entry("某剧 第2集.mkv")
        val other = entry("aaa 别的片.mkv")

        val result = arrange(listOf(ten, other, two, one))

        assertEquals(
            listOf("aaa 别的片.mkv", "某剧 第1集.mkv", "某剧 第2集.mkv", "某剧 第10集.mkv"),
            result,
        )
    }

    @Test
    fun `最近看的那一集排在组内最前其余按集数升序`() {
        val one = entry("剧 第01集.mkv")
        val two = entry("剧 第02集.mkv")
        val three = entry("剧 第03集.mkv")

        val result = arrange(listOf(one, two, three), watched(two to 900L, one to 100L))

        assertEquals(listOf("剧 第02集.mkv", "剧 第01集.mkv", "剧 第03集.mkv"), result)
    }

    @Test
    fun `组内时间并列时取集数最小的那一集放最前`() {
        val one = entry("剧 第01集.mkv")
        val two = entry("剧 第02集.mkv")
        val three = entry("剧 第03集.mkv")

        val result = arrange(listOf(one, two, three), watched(two to 500L, three to 500L))

        assertEquals(listOf("剧 第02集.mkv", "剧 第01集.mkv", "剧 第03集.mkv"), result)
    }

    @Test
    fun `整组的先后按组内最新那一次观看时间`() {
        val episodeOne = entry("剧 第01集.mkv")
        val episodeTwo = entry("剧 第02集.mkv")
        val movie = entry("Y.mkv")
        val never = entry("Z.mkv")

        val result = arrange(
            listOf(episodeOne, episodeTwo, movie, never),
            watched(movie to 900L, episodeTwo to 500L),
        )

        assertEquals(listOf("Y.mkv", "剧 第02集.mkv", "剧 第01集.mkv", "Z.mkv"), result)
    }

    @Test
    fun `中文数字的集数照样按数值排`() {
        val tenth = entry("剧 第十集.mkv")
        val first = entry("剧 第一集.mkv")
        val second = entry("剧 第二集.mkv")

        val result = arrange(listOf(tenth, first, second))

        assertEquals(listOf("剧 第一集.mkv", "剧 第二集.mkv", "剧 第十集.mkv"), result)
    }

    @Test
    fun `EP 写法与 S01E03 写法都算集数`() {
        val epTwo = entry("Show.EP02.mkv")
        val epOne = entry("Show.EP01.mkv")
        val seasonTen = entry("Anime.S01E10.mkv")
        val seasonTwo = entry("Anime.S01E2.mkv")

        val result = arrange(listOf(epTwo, epOne, seasonTen, seasonTwo))

        assertEquals(
            listOf("Anime.S01E2.mkv", "Anime.S01E10.mkv", "Show.EP01.mkv", "Show.EP02.mkv"),
            result,
        )
    }

    @Test
    fun `季与集同时出现时按集分组而不是按季`() {
        val third = entry("某剧 第1季 第03集.mkv")
        val first = entry("某剧 第1季 第01集.mkv")
        val secondSeason = entry("某剧 第2季 第01集.mkv")

        val result = arrange(listOf(third, first, secondSeason))

        assertEquals(
            listOf("某剧 第1季 第01集.mkv", "某剧 第1季 第03集.mkv", "某剧 第2季 第01集.mkv"),
            result,
        )
    }

    @Test
    fun `集数写在最前面也算同一系列`() {
        val one = entry("第01集 某剧.mkv")
        val two = entry("第02集 某剧.mkv")

        val result = arrange(listOf(two, one))

        assertEquals(listOf("第01集 某剧.mkv", "第02集 某剧.mkv"), result)
    }

    @Test
    fun `分隔符不同但系列名相同的算同一组`() {
        val dashed = entry("show-第01集.mkv")
        val dotted = entry("Show.第02集.mkv")

        val result = arrange(listOf(dotted, dashed))

        assertEquals(listOf("show-第01集.mkv", "Show.第02集.mkv"), result)
    }

    // ── 解析 ────────────────────────────────────────────────────────────────────

    @Test
    fun `没有集数字眼的名字不参与相邻排序`() {
        assertNull(MovieOrdering.parseEpisode("2012.mkv"))
        assertNull(MovieOrdering.parseEpisode("Interstellar.2014.1080p.mkv"))
        assertNull(MovieOrdering.parseEpisode("Season.of.Love.mkv"))
        assertNull(MovieOrdering.parseEpisode("第几集随便写.mkv"))
    }

    @Test
    fun `解析出来的集号是数值不是字符串`() {
        assertEquals(2, MovieOrdering.parseEpisode("某剧 第2集.mkv")?.episode)
        assertEquals(10, MovieOrdering.parseEpisode("某剧 第10集.mkv")?.episode)
        assertEquals(12, MovieOrdering.parseEpisode("某剧 第十二集.mkv")?.episode)
        assertEquals(3, MovieOrdering.parseEpisode("Anime.S01E03.mkv")?.episode)
    }

    @Test
    fun `中文数字两种写法都能读`() {
        assertEquals(10, MovieOrdering.chineseNumber("十"))
        assertEquals(11, MovieOrdering.chineseNumber("十一"))
        assertEquals(23, MovieOrdering.chineseNumber("二十三"))
        assertEquals(105, MovieOrdering.chineseNumber("一百零五"))
        assertEquals(123, MovieOrdering.chineseNumber("一二三"))
        assertEquals(2008, MovieOrdering.chineseNumber("二零零八"))
        assertNull(MovieOrdering.chineseNumber("集"))
        assertNull(MovieOrdering.chineseNumber(""))
    }
}
