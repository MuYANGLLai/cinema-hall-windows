package com.cinemahall.desktop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopScanRulesTest {

    @Test
    fun `白名单收常见的可播容器`() {
        listOf("a.mkv", "b.MP4", "c.avi", "d.m2ts", "e.webm").forEach {
            assertTrue("$it 应被收录", DesktopScanRules.isVideo(it))
        }
    }

    @Test
    fun `半成品与压缩包不收`() {
        // 这些是用户目录里最常见的“看起来像视频”的文件。
        listOf("a.part", "b.tmp", "c.iso", "d.rmvb", "e.txt", "noext").forEach {
            assertFalse("$it 不应被收录", DesktopScanRules.isVideo(it))
        }
    }

    @Test
    fun `点文件与系统目录会被跳过`() {
        assertTrue(DesktopScanRules.isHiddenName(".cache"))
        assertFalse(DesktopScanRules.isHiddenName("movies"))

        listOf("\$RECYCLE.BIN", "System Volume Information", "Windows", "AppData", "node_modules").forEach {
            assertTrue("$it 应跳过", DesktopScanRules.isSystemDirectory(it))
        }
        assertFalse(DesktopScanRules.isSystemDirectory("Movies"))
    }

    @Test
    fun `展示名去掉最后一个扩展名`() {
        assertEquals("流浪地球 2", DesktopScanRules.displayName("流浪地球 2.mkv"))
        assertEquals("noext", DesktopScanRules.displayName("noext"))
        assertEquals("a.b", DesktopScanRules.displayName("a.b.mkv"))
    }
}

class DesktopMovieCatalogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(parent: File, name: String, bytes: Int = 16): File =
        File(parent, name).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes) { 1 })
        }

    @Test
    fun `快扫按白名单收录并跳过隐藏与系统目录`() {
        val root = temp.newFolder("Movies")
        file(root, "film-a.mkv")
        file(root, "film-b.mp4")
        file(root, "notes.txt")
        file(root, "partial.part")

        val hiddenDir = File(root, ".cache").apply { mkdirs() }
        file(hiddenDir, "hidden.mkv")

        val systemDir = File(root, "\$RECYCLE.BIN").apply { mkdirs() }
        file(systemDir, "deleted.mkv")

        val nested = File(root, "2024").apply { mkdirs() }
        file(nested, "film-c.avi")

        val catalog = DesktopMovieCatalog(File(temp.root, "library.json"), probe = { 0L })
        assertEquals(DesktopMovieCatalog.AddRootResult.Added(root.canonicalPath), catalog.addRoot(root))

        kotlinx.coroutines.runBlocking { catalog.scan() }

        val names = catalog.movies.value.map { it.fileName }.sorted()
        assertEquals(listOf("film-a.mkv", "film-b.mp4", "film-c.avi"), names)

        val stats = catalog.stats.value
        assertEquals(3, stats.found)
        assertEquals(2, stats.skippedExtension) // notes.txt + partial.part
        assertEquals(ScanPhase.DONE, stats.phase)
    }

    @Test
    fun `时长解析成功后才归档分类`() {
        val root = temp.newFolder("Movies")
        file(root, "movie.mkv")
        file(root, "episode.mkv")
        file(root, "clip.mkv")

        val durations = mapOf(
            "movie.mkv" to 95L * 60 * 1000,
            "episode.mkv" to 45L * 60 * 1000,
            "clip.mkv" to 5L * 60 * 1000,
        )
        val catalog = DesktopMovieCatalog(
            storeFile = File(temp.root, "library.json"),
            probe = { path -> durations[File(path).name] ?: 0L },
        )
        catalog.addRoot(root)

        kotlinx.coroutines.runBlocking { catalog.scan() }

        val byName = catalog.movies.value.associateBy { it.fileName }
        assertEquals(com.cinemahall.core.library.ScanBucket.MOVIE, byName.getValue("movie.mkv").bucket)
        assertEquals(com.cinemahall.core.library.ScanBucket.SERIES, byName.getValue("episode.mkv").bucket)
        assertEquals(com.cinemahall.core.library.ScanBucket.SKIP, byName.getValue("clip.mkv").bucket)
    }

    @Test
    fun `未变化的文件第二次扫描不再解析时长`() {
        val root = temp.newFolder("Movies")
        file(root, "a.mkv")
        file(root, "b.mkv")

        // ⚠️ 必须用原子计数：probe 是在 `Dispatchers.IO` + 4 路信号量里并行调用的，
        // `var probes++` 会丢计数（本机实测同一份代码一次跑出 2、一次跑出 1，
        // 断言随机红）。这不是产品 bug，是测试自己的竞态。
        val probes = java.util.concurrent.atomic.AtomicInteger(0)
        val catalog = DesktopMovieCatalog(
            storeFile = File(temp.root, "library.json"),
            probe = { probes.incrementAndGet(); 90L * 60 * 1000 },
        )
        catalog.addRoot(root)

        kotlinx.coroutines.runBlocking { catalog.scan() }
        assertEquals(2, probes.get())

        kotlinx.coroutines.runBlocking { catalog.scan() }
        assertEquals("第二次扫描应命中缓存，不再调用 probe", 2, probes.get())
        assertTrue(catalog.movies.value.all { it.durationMs > 0 })
    }

    @Test
    fun `同一个文件只收录一次`() {
        val root = temp.newFolder("Movies")
        file(root, "a.mkv")
        val catalog = DesktopMovieCatalog(File(temp.root, "library.json"), probe = { 0L })
        catalog.addRoot(root)
        catalog.addRoot(root)

        kotlinx.coroutines.runBlocking { catalog.scan() }

        assertEquals(1, catalog.movies.value.size)
        assertEquals(1, catalog.roots.value.size)
    }

    @Test
    fun `重启后影片列表仍在_不必重新扫描`() {
        val root = temp.newFolder("Movies")
        file(root, "film-a.mkv")
        val nested = File(root, "2024").apply { mkdirs() }
        file(nested, "film-b.mkv")

        val store = File(temp.root, "library.json")
        val first = DesktopMovieCatalog(store, probe = { 95L * 60 * 1000 })
        first.addRoot(root)
        kotlinx.coroutines.runBlocking { first.scan() }
        assertEquals(2, first.movies.value.size)

        // 新实例 = 下一次启动：只读盘，**不扫描**，列表必须已经在那儿。
        // 早先只存根目录与时长缓存，用户上次扫过的片在下次启动时全消失（「0 部」），
        // 看起来像库被清空了 —— 安卓端是持久化的，两端行为必须一致。
        val reopened = DesktopMovieCatalog(store, probe = { 0L })
        assertEquals("film-a.mkv", reopened.movies.value.first().fileName)
        assertEquals(
            listOf("film-a.mkv", "film-b.mkv"),
            reopened.movies.value.map { it.fileName },
        )
        assertTrue("时长也要一起活下来", reopened.movies.value.all { it.durationMs == 95L * 60 * 1000 })
        assertEquals(1, reopened.roots.value.size)
    }

    @Test
    fun `扫描 500 个文件应在可接受时间内完成`() {        val root = temp.newFolder("Big")
        repeat(500) { index ->
            val bucket = File(root, "cat${index % 10}").apply { mkdirs() }
            file(bucket, "movie-$index.mkv", bytes = 8)
        }

        val catalog = DesktopMovieCatalog(File(temp.root, "library.json"), probe = { 90L * 60 * 1000 })
        catalog.addRoot(root)

        val started = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking { catalog.scan() }
        val elapsed = System.currentTimeMillis() - started

        assertEquals(500, catalog.movies.value.size)
        // 门禁是“可接受”，不是“多快”；500 个小文件的 stat 在 SSD 上远低于 5 秒。
        assertTrue("500 部片扫描耗时 ${elapsed}ms，超出 5 秒", elapsed < 5_000)
    }
}
