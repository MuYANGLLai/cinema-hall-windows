package com.cinemahall.core.library

import com.cinemahall.core.media.MediaSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只在账号里、本机没有」的条目（1b）在 store 里的边界。
 *
 * 这几条看着像细节，实际是**会丢数据**的那一类：远端条目没有本地 `uri`，
 * 一旦被当成「探测失败」或「不可用条目」处理，本机就会删掉它 —— 而删除会顺着同步
 * 推到所有设备，另一台设备上那部真片也跟着消失。所以每个入口都要显式挡住。
 */
class RemoteOnlyEntryTest {

    private class FakeStorage : LibraryStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun remoteEntry(
        syncKey: String = "e".repeat(32),
        missing: Boolean = false,
    ) = MovieEntry(
        id = "sync:$syncKey",
        categoryId = "cat-1",
        uri = "",
        signature = MediaSignature("另一台设备的片.mkv", 0L, emptyList()),
        addedAtMs = 5L,
        missing = missing,
        syncKey = syncKey,
        remoteOnly = true,
    )

    private fun storeWith(entry: MovieEntry): MovieLibraryStore {
        val store = MovieLibraryStore(FakeStorage())
        store.applySyncedLibrary { current ->
            current.copy(
                categories = listOf(MovieCategory("cat-1", "纪录片", 1L)),
                movies = listOf(entry),
            )
        }
        return store
    }

    @Test
    fun `远端条目的进度键用 syncKey 而不是合成摘要`() {
        val entry = remoteEntry()
        val store = storeWith(entry)

        store.applySyncedProgress { book ->
            book.copy(byKey = mapOf("e".repeat(32) to MovieProgress(60_000L, 600_000L, 7L)))
        }

        assertEquals(
            "远端条目没有真摘要，进度必须按 syncKey 找 —— 否则进度栏永远空着",
            60_000L,
            store.progressOf(entry)?.positionMs,
        )
        assertNull(
            "用合成摘要去算会是另一个键，那里本来就没有东西",
            store.progressOf(entry.signature),
        )
    }

    @Test
    fun `探测不会删掉远端条目`() {
        val entry = remoteEntry()
        val store = storeWith(entry)

        val deleted = store.applyProbe(entry.id, MovieProbe.Missing, nowMs = 100L)

        assertFalse("远端条目没有本地文件可探测，判成 Missing 会连带删掉别的设备上的片", deleted)
        assertEquals(1, store.snapshot.value.movies.size)
    }

    @Test
    fun `重新读摘要对远端条目无效`() {
        val entry = remoteEntry()
        val store = storeWith(entry)
        val replacement = MediaSignature("换一个.mkv", 9L, listOf("ff"))

        assertFalse(store.refreshSignature(entry.id, replacement, nowMs = 100L))
        assertEquals(entry.signature, store.snapshot.value.movies[0].signature)
    }

    @Test
    fun `清理不可用条目时保留远端条目`() {
        val local = MovieEntry(
            id = "local-1",
            categoryId = "cat-1",
            uri = "content://demo/a.mkv",
            signature = MediaSignature("a.mkv", 10L, listOf("aa")),
            missing = true,
        )
        val store = MovieLibraryStore(FakeStorage())
        store.applySyncedLibrary {
            it.copy(
                categories = listOf(MovieCategory("cat-1", "纪录片", 1L)),
                movies = listOf(local, remoteEntry(missing = true)),
            )
        }

        val removed = store.removeMissing()

        assertEquals("只清本机那条", 1, removed)
        assertEquals(1, store.snapshot.value.movies.size)
        assertTrue(store.snapshot.value.movies[0].remoteOnly)
        assertTrue("missing() 是界面上的清理入口，不该把远端条目算进去", store.missing().isEmpty())
    }

    @Test
    fun `同步写回会落盘且重启后还在`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        store.applySyncedLibrary { it.copy(movies = listOf(remoteEntry())) }
        store.applySyncedProgress { it.copy(byKey = mapOf("e".repeat(32) to MovieProgress(1L, 2L, 3L))) }

        val reopened = MovieLibraryStore(storage)

        assertEquals(1, reopened.snapshot.value.movies.size)
        assertTrue(reopened.snapshot.value.movies[0].remoteOnly)
        assertEquals("e".repeat(32), reopened.snapshot.value.movies[0].syncKey)
        assertEquals(1L, reopened.progressOf(reopened.snapshot.value.movies[0])?.positionMs)
    }

    @Test
    fun `变换没造成变化时不落盘`() {
        val storage = FakeStorage()
        val store = MovieLibraryStore(storage)
        store.applySyncedLibrary { it.copy(movies = listOf(remoteEntry())) }
        val before = storage.values.size.also { }
        val writes = storage.values.toMap()

        val changed = store.applySyncedLibrary { it }

        assertFalse(changed)
        assertEquals(writes, storage.values)
        assertTrue(before > 0)
    }
}
