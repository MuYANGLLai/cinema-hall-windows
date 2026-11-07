package com.cinemahall.desktop.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 影片库的「分类 / 改名 / 批量移动 / 删除」（2026-11-07 复刻安卓端能力）。
 *
 * 重点不是 CRUD 本身，而是**扫描不能把用户数据冲掉**：`scan()` 会用磁盘上的文件
 * 重建整个列表，如果它不带回 `categoryId` / `titleOverride`，用户每次点「开始扫描」
 * 分类和改名就全丢 —— 这类「保存了但一刷新就没」的缺陷在界面上极难看出来。
 */
class DesktopMovieCatalogOrganizeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(parent: File, name: String, bytes: Int = 16): File =
        File(parent, name).apply {
            parent.mkdirs()
            writeBytes(ByteArray(bytes) { 7 })
        }

    private fun catalog(store: File, durationMs: Long = 95L * 60 * 1000) =
        DesktopMovieCatalog(store, probe = { durationMs })

    @Test
    fun `新建分类会落盘_重开仍在`() {
        val store = File(temp.root, "library.json")
        val first = catalog(store)
        val created = first.addCategory("纪录片")
        assertNotNull(created)
        assertEquals(listOf("电影", "纪录片"), first.categories.value.map { it.name })

        val reopened = catalog(store)
        assertEquals(listOf("电影", "纪录片"), reopened.categories.value.map { it.name })
    }

    @Test
    fun `同名分类不会建出第二个`() {
        val catalog = catalog(File(temp.root, "library.json"))
        val a = catalog.addCategory("纪录片")
        val b = catalog.addCategory("  纪录片  ")
        assertEquals(a?.id, b?.id)
        assertEquals(2, catalog.categories.value.size)
    }

    @Test
    fun `默认分类不能改名也删不掉`() {
        val catalog = catalog(File(temp.root, "library.json"))
        catalog.renameCategory(DesktopCategory.DEFAULT_CATEGORY_ID, "别的名字")
        catalog.deleteCategory(DesktopCategory.DEFAULT_CATEGORY_ID)
        assertEquals(
            listOf("电影"),
            catalog.categories.value.map { it.name },
        )
    }

    @Test
    fun `改名只影响显示名_恢复默认回到文件名`() {
        val root = temp.newFolder("Movies")
        file(root, "Interstellar.2014.1080p.mkv")
        val catalog = catalog(File(temp.root, "library.json"))
        catalog.addRoot(root)
        runBlocking { catalog.scan() }

        val path = catalog.movies.value.single().path
        catalog.renameMovie(path, "星际穿越")
        assertEquals("星际穿越", catalog.movies.value.single().title)

        // 填回默认名 = 恢复默认（落盘写 null），这样改了文件名界面会跟着变。
        catalog.renameMovie(path, catalog.movies.value.single().defaultTitle)
        assertNull(catalog.movies.value.single().titleOverride)
        assertEquals("Interstellar.2014.1080p", catalog.movies.value.single().title)
    }

    @Test
    fun `批量移动把选中的片放进目标分类`() {
        val root = temp.newFolder("Movies")
        file(root, "a.mkv")
        file(root, "b.mkv")
        file(root, "c.mkv")
        val catalog = catalog(File(temp.root, "library.json"))
        catalog.addRoot(root)
        runBlocking { catalog.scan() }
        val target = catalog.addCategory("剧集")!!

        val paths = catalog.movies.value.take(2).map { it.path }
        catalog.moveMovies(paths, target.id)

        val moved = catalog.movies.value.filter { it.categoryId == target.id }.map { it.fileName }
        assertEquals(listOf("a.mkv", "b.mkv"), moved.sorted())
        assertEquals(1, catalog.movies.value.count { it.categoryId == DesktopCategory.DEFAULT_CATEGORY_ID })
    }

    @Test
    fun `删除分类时影片退回未分类_影片本身不删`() {
        val root = temp.newFolder("Movies")
        file(root, "a.mkv")
        val catalog = catalog(File(temp.root, "library.json"))
        catalog.addRoot(root)
        runBlocking { catalog.scan() }
        val cat = catalog.addCategory("临时")!!
        catalog.moveMovies(listOf(catalog.movies.value.single().path), cat.id)
        assertEquals(cat.id, catalog.movies.value.single().categoryId)

        catalog.deleteCategory(cat.id)

        assertEquals(1, catalog.movies.value.size)
        assertEquals(
            DesktopCategory.DEFAULT_CATEGORY_ID,
            catalog.movies.value.single().categoryId,
        )
        assertEquals(listOf("电影"), catalog.categories.value.map { it.name })
    }

    /**
     * 回归：扫描是「以磁盘为准」的重建，但**用户数据要按路径继承回来**。
     * 这条用例红了 = 用户点一次「开始扫描」，分类和改名全没了。
     */
    @Test
    fun `重新扫描不会冲掉分类与显示名`() {
        val root = temp.newFolder("Movies")
        file(root, "a.mkv")
        file(root, "b.mkv")
        val store = File(temp.root, "library.json")
        val catalog = catalog(store)
        catalog.addRoot(root)
        runBlocking { catalog.scan() }

        val cat = catalog.addCategory("收藏")!!
        val pathA = catalog.movies.value.first { it.fileName == "a.mkv" }.path
        catalog.moveMovies(listOf(pathA), cat.id)
        catalog.renameMovie(pathA, "重命名过的片")

        // 第二次扫描（用户又点了一次「开始扫描」）。
        runBlocking { catalog.scan() }

        val a = catalog.movies.value.first { it.fileName == "a.mkv" }
        assertEquals(cat.id, a.categoryId)
        assertEquals("重命名过的片", a.title)
        // 没动过的那部仍然在默认分类、没有改名。
        val b = catalog.movies.value.first { it.fileName == "b.mkv" }
        assertEquals(DesktopCategory.DEFAULT_CATEGORY_ID, b.categoryId)
        assertNull(b.titleOverride)
    }

    @Test
    fun `移除影片只删记录_磁盘文件还在`() {
        val root = temp.newFolder("Movies")
        val target = file(root, "a.mkv")
        val catalog = catalog(File(temp.root, "library.json"))
        catalog.addRoot(root)
        runBlocking { catalog.scan() }

        catalog.removeMovies(listOf(catalog.movies.value.single().path))

        assertTrue("记录应该没了", catalog.movies.value.isEmpty())
        assertTrue("磁盘文件不能被动", target.isFile)
    }

    @Test
    fun `分类被手改坏时影片退回未分类而不是变成孤儿`() {
        val store = File(temp.root, "library.json")
        store.writeText(
            """
            {
                "version": 2,
                "roots": [],
                "movies": [
                    {"path":"D:\\Movies\\x.mkv","fileName":"x.mkv","sizeBytes":10,"modifiedAtMs":1,"categoryId":"GONE"}
                ],
                "categories": []
            }
            """.trimIndent(),
        )

        val reopened = catalog(store)
        assertEquals(
            DesktopCategory.DEFAULT_CATEGORY_ID,
            reopened.movies.value.single().categoryId,
        )
    }
}
