package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 5 of the breaker for unit G, Q2 (a real consumer): the uploader's real default outcome storage (its journal's
 * file layer) over the real kmptoolkit-files engine. Docs (uploader 05-platform-notes, AndroidOutcomeStorage KDoc): a
 * write is atomic and synced, the temporary file is `.kmptoolkit-<digits>.tmp` in the partial directory, leftovers older
 * than an hour (by the injected clock) are purged and younger ones kept, a reader sees the previous entry or the whole new
 * one, an entry over the encoded limit is not read at all (an empty array), and a failed write reports `false` and
 * changes nothing.
 */
class OutcomeStorageOverRealFilesR5Test {
    private val base: File = Files.createTempDirectory("kmptoolkit-outcome-r5").toRealPath().toFile()
    private val dir = File(base, "outcomes")
    private val partial = File(base, "outcomes-partial")
    private var clock: Long = 1_700_000_000_000L
    private val storage = AndroidOutcomeStorage(dir, partial, now = { clock })

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun `a full cycle writes and replaces and lists and deletes and leaves no temporary file`() {
        assertTrue(storage.write("item-1", "one".encodeToByteArray()))
        assertTrue(storage.write("item-2", ByteArray(100_000) { 7 }))
        assertTrue(storage.write("item-1", "uno".encodeToByteArray()), "a second write replaces the first whole")
        assertContentEquals("uno".encodeToByteArray(), storage.read("item-1"))
        assertEquals(listOf("item-1", "item-2"), storage.itemIds().sorted())
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "the temporary files are consumed by the renames")
        storage.delete("item-1")
        storage.delete("item-1")
        assertNull(storage.read("item-1"))
        assertEquals(listOf("item-2"), storage.itemIds())
        assertNull(storage.read("missing"))
        // A directory named like an entry is no entry.
        File(dir, "a-dir").mkdirs()
        assertNull(storage.read("a-dir"))
    }

    @Test
    fun `an entry over the encoded limit is read as empty and leftovers are purged by the injected clock`() {
        assertTrue(storage.write("big", ByteArray(MAX_ENCODED_ENTRY_BYTES.toInt() + 1)))
        assertEquals(0, storage.read("big")?.size, "over the limit: not read, an empty array (docs)")
        partial.mkdirs()
        val leftover = File(partial, ".kmptoolkit-123.tmp").also { it.writeText("half"); assertTrue(it.setLastModified(clock)) }
        storage.purgeLeftovers()
        assertTrue(leftover.exists(), "a leftover younger than an hour is kept: it may belong to a write in flight")
        clock += 2 * 60 * 60 * 1000L
        storage.purgeLeftovers()
        assertFalse(leftover.exists(), "an old leftover is purged")
        assertTrue(File(dir, "big").exists(), "purging touches only the partial directory")
    }

    @Test
    fun `a failed write reports false and leaves the previous entry and no temporary file`() {
        assertTrue(storage.write("item", "old".encodeToByteArray()))
        // The partial directory is a file: no temporary file can be made.
        assertTrue(partial.deleteRecursively())
        assertTrue(partial.createNewFile())
        assertFalse(storage.write("item", "new".encodeToByteArray()), "an impossible write reports false")
        assertContentEquals("old".encodeToByteArray(), storage.read("item"), "the previous entry survives")
        assertTrue(partial.delete())
        // An id longer than the file system allows.
        assertFalse(storage.write("x".repeat(300), "n".encodeToByteArray()))
        assertEquals(listOf("item"), storage.itemIds())
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "a failed write left a temporary file")
        assertTrue(storage.write("item", "again".encodeToByteArray()), "the storage works again once the obstacle is gone")
    }
}
