package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影片库的账号分键（方案 §8.4）。
 *
 * 这一组用例守的是**数据**，不是代码：键名错了、认领逻辑覆盖了账号已有数据、
 * 或老键没有迁移，用户看到的就是「影片全没了」。而它不会抛任何异常。
 */
class LibraryScopeTest {

    // ── 键名 ───────────────────────────────────────────────────────────────

    @Test
    fun `未激活落在 local 作用域`() {
        assertEquals(LibraryScope.LOCAL, LibraryScope.scopeFor(null))
        assertEquals(LibraryScope.LOCAL, LibraryScope.scopeFor("  "))
    }

    @Test
    fun `账号 id 直接当作用域`() {
        assertEquals("ac_1", LibraryScope.scopeFor(" ac_1 "))
    }

    @Test
    fun `键名带作用域后缀`() {
        assertEquals("library.v1.ac_1", LibraryScope.libraryKey("ac_1"))
        assertEquals("progress.v1.ac_1", LibraryScope.progressKey("ac_1"))
        assertEquals("library.v1.ac_1.corrupt", LibraryScope.libraryCorruptKey("ac_1"))
    }

    // ── 旧键迁移 ────────────────────────────────────────────────────────────

    @Test
    fun `旧的无后缀键会迁移到 local 且旧键保留`() {
        val storage = FakeStorage()
        storage.write("library.v1", """{"movies":[]}""")
        storage.write("progress.v1", """{"byKey":{}}""")

        assertTrue(LibraryScope.migrateLegacyLocal(storage))

        assertEquals("""{"movies":[]}""", storage.read("library.v1.local"))
        assertEquals("""{"byKey":{}}""", storage.read("progress.v1.local"))
        assertNotNull("旧键是回滚保险，不许删", storage.read("library.v1"))
    }

    @Test
    fun `local 已有数据时旧键不覆盖它`() {
        val storage = FakeStorage()
        storage.write("library.v1", "old")
        storage.write("library.v1.local", "new")

        LibraryScope.migrateLegacyLocal(storage)

        assertEquals("new", storage.read("library.v1.local"))
    }

    @Test
    fun `没有旧键时迁移是空操作`() {
        val storage = FakeStorage()
        assertEquals(false, LibraryScope.migrateLegacyLocal(storage))
        assertTrue(storage.values.isEmpty())
    }

    // ── 首次激活认领 ────────────────────────────────────────────────────────

    @Test
    fun `首次激活把 local 复制到账号并保留 local`() {
        val storage = FakeStorage()
        storage.write("library.v1.local", "L")
        storage.write("progress.v1.local", "P")

        assertEquals(ClaimResult.Copied, LibraryScope.claimLocal(storage, "ac_1"))

        assertEquals("L", storage.read("library.v1.ac_1"))
        assertEquals("P", storage.read("progress.v1.ac_1"))
        assertEquals("local 必须保留", "L", storage.read("library.v1.local"))
    }

    @Test
    fun `账号键已有数据时不被 local 覆盖`() {
        val storage = FakeStorage()
        storage.write("library.v1.local", "L")
        storage.write("library.v1.ac_1", "A")

        assertEquals(ClaimResult.AlreadyClaimed, LibraryScope.claimLocal(storage, "ac_1"))
        assertEquals("A", storage.read("library.v1.ac_1"))
    }

    @Test
    fun `local 没有数据时什么也不做`() {
        val storage = FakeStorage()

        assertEquals(ClaimResult.NoLocalData, LibraryScope.claimLocal(storage, "ac_1"))
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun `只存在旧键时先迁移再认领`() {
        val storage = FakeStorage()
        storage.write("library.v1", "OLD")

        assertEquals(ClaimResult.Copied, LibraryScope.claimLocal(storage, "ac_1"))
        assertEquals("OLD", storage.read("library.v1.ac_1"))
        assertEquals("OLD", storage.read("library.v1.local"))
    }

    @Test
    fun `对 local 自己认领是空操作`() {
        val storage = FakeStorage()
        storage.write("library.v1.local", "L")
        assertEquals(ClaimResult.NoLocalData, LibraryScope.claimLocal(storage, "local"))
    }

    // ── store 按作用域读 ────────────────────────────────────────────────────

    @Test
    fun `账号作用域读不到 local 的数据`() {
        val storage = FakeStorage()
        storage.write("library.v1.local", """{"movies":[]}""")

        val accountStore = MovieLibraryStore(storage) { "ac_1" }

        assertEquals(0, accountStore.snapshot.value.categories.size + accountStore.snapshot.value.movies.size)
    }

    @Test
    fun `local 作用域能读到旧键并就地迁移`() {
        val storage = FakeStorage()
        val seed = MovieLibraryStore(storage) { LibraryScope.LOCAL }
        seed.createCategory("老分类", nowMs = 1)

        // 把数据搬回「旧键」形态，模拟 Phase 1 之前的老用户
        storage.write("library.v1", storage.read("library.v1.local")!!)
        storage.write("library.v1.local", null)

        val reopened = MovieLibraryStore(storage) { LibraryScope.LOCAL }

        assertEquals(listOf("老分类"), reopened.snapshot.value.categories.map { it.name })
        assertNotNull("读到旧键后应就地写一份到新键", storage.read("library.v1.local"))
    }

    @Test
    fun `分键之后两个账号的数据互不影响`() {
        val storage = FakeStorage()
        val a = MovieLibraryStore(storage) { "ac_a" }
        val b = MovieLibraryStore(storage) { "ac_b" }

        a.createCategory("A 的分类", nowMs = 1)
        b.createCategory("B 的分类", nowMs = 2)

        assertEquals(listOf("A 的分类"), MovieLibraryStore(storage) { "ac_a" }
            .snapshot.value.categories.map { it.name })
        assertEquals(listOf("B 的分类"), MovieLibraryStore(storage) { "ac_b" }
            .snapshot.value.categories.map { it.name })
    }

    @Test
    fun `进度也按账号分开`() {
        val storage = FakeStorage()
        val signature = MediaSignature(
            fileName = "movie.mkv",
            sizeBytes = 1,
            blocks = listOf("block-1"),
        )
        val a = MovieLibraryStore(storage) { "ac_a" }
        val b = MovieLibraryStore(storage) { "ac_b" }

        a.rememberProgress(signature, positionMs = 500, durationMs = 1_000, nowMs = 1)

        assertNotNull(a.progressOf(signature))
        assertNull(b.progressOf(signature))
    }

    private class FakeStorage : LibraryStorage {
        val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
