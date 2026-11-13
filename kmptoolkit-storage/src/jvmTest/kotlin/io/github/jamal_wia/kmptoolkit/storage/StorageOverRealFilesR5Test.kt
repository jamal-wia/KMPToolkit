package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 5 of the breaker for unit G, Q2 (a real consumer): the desktop key-value store over the real kmptoolkit-files
 * engine, driven through its public API. The storage docs (05-platform-notes "Durability", "Failures") promise: every write
 * is `writeAtomically` with `Durability.Synced`; the temporary sibling is `<file name><digits>.tmp`; a failure arrives as
 * `OperationFailed`, never an exception, and leaves the previous contents and no temporary file.
 */
class StorageOverRealFilesR5Test {
    private val directory: File = Files.createTempDirectory("kmptoolkit-storage-r5").toRealPath().toFile()
    private val real: FileAccess = createFileAccess()
    private val writes: MutableList<Pair<String, WriteOptions>> = mutableListOf()
    private var injected: FileError? = null

    private val recording: FileAccess = object : FileAccess by real {
        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
            writes += path to options
            injected?.let { return FileResult.Failure(it) }
            return real.writeAtomically(path, bytes, options)
        }
    }

    private val file: File = File(directory, "r5.properties")
    private val store: KeyValueStorage = JvmKeyValueStorage(file, Dispatchers.IO, recording)

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    @Test
    fun `the happy path puts and gets and removes through one atomic synced write each`() {
        assertTrue(run { store.put("a", "1") }.isSuccess)
        assertTrue(run { store.put("b", "two") }.isSuccess)
        assertEquals("1", run { store.get("a") }.getOrNull())
        assertTrue(run { store.remove("a") }.isSuccess)
        assertNull(run { store.get("a") }.getOrNull())
        assertEquals("two", run { store.get("b") }.getOrNull())
        assertEquals(listOf("r5.properties"), directory.list()!!.sorted(), "only the store's own file remains")
        assertEquals(3, writes.size)
        for ((path, options) in writes) {
            assertEquals(file.absolutePath, path)
            assertEquals(Durability.Synced, options.durability, "docs: Durability.Synced")
            assertEquals(ExistingFile.Replace, options.existing)
            val prefix: String = options.tempPrefix ?: error("the store passes its own temp prefix")
            // docs: the temporary sibling is `<file name><digits>.tmp` (4.x shape): the prefix is the file name here.
            assertEquals("r5.properties", prefix)
            assertTrue(FileNames.isTempName("r5.properties123.tmp", prefix))
        }
    }

    @Test
    fun `a directory where the file should be is a typed failure and a refused write keeps the old contents`() {
        assertTrue(run { store.put("k", "old") }.isSuccess)
        injected = FileError.NoSpace(file.absolutePath)
        val full: StorageResult<Unit> = run { store.put("k", "new") }
        assertTrue(full.errorOrNull() is StorageError.OperationFailed, "$full")
        injected = null
        assertEquals("old", run { store.get("k") }.getOrNull(), "the previous contents survive a failed write")
        // The real engine: the store file replaced by a directory.
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        val blocked: StorageResult<Unit> = run { store.put("k", "x") }
        assertTrue(blocked.errorOrNull() is StorageError.OperationFailed, "$blocked")
        assertTrue(run { store.get("k") }.errorOrNull() != null || run { store.get("k") }.getOrNull() == null)
        assertEquals(listOf("r5.properties"), directory.list()!!.sorted(), "a failed write left a temporary file")
        assertTrue(file.delete())
        assertTrue(run { store.put("k", "again") }.isSuccess, "the store works again once the obstacle is gone")
        assertEquals("again", run { store.get("k") }.getOrNull())
        assertEquals(listOf("r5.properties"), directory.list()!!.sorted())
    }
}
