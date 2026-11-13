package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * B round 9, Q1 on the desktop store: a full disk at every write, and the directory removed under a running store.
 * Contract (KeyValueStorage KDoc, 05-platform-notes "Desktop"): nothing throws, a write the file system refuses is
 * [StorageError.OperationFailed] and the previous contents stay readable, and the next write after the disk recovers works.
 */
class JvmKeyValueStorageEnvironmentRound9Test {
    private val directory: File = Files.createTempDirectory("kmptoolkit-storage-r9").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** A file layer whose disk is full for every atomic write while [full] is set; reads and everything else are real. */
    private class Disk(private val real: FileAccess) : FileAccess by real {
        @Volatile
        var full: Boolean = false
        var refused: Int = 0

        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
            if (!full) return real.writeAtomically(path, bytes, options)
            refused++
            return FileResult.Failure(FileError.NoSpace(path))
        }
    }

    private fun file(): File = File(directory, "settings.kmptoolkit.storage.properties")

    private fun storage(files: FileAccess): KeyValueStorage = JvmKeyValueStorage(file(), Dispatchers.IO, files)

    private fun litter(): List<String> = directory.list().orEmpty().filter { it != file().name }

    @Test
    fun `a full disk fails put remove and keeps the old contents and the next write after recovery works`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val disk = Disk(createFileAccess())
            val store: KeyValueStorage = storage(disk)
            assertTrue(store.put("a", "1") is StorageResult.Success)
            assertTrue(store.put("b", "2") is StorageResult.Success)
            val bytesBefore: ByteArray = file().readBytes()
            disk.full = true
            val put: StorageResult<Unit> = store.put("a", "CHANGED")
            val newKey: StorageResult<Unit> = store.put("c", "3")
            val remove: StorageResult<Unit> = store.remove("b")
            for (result: StorageResult<Unit> in listOf(put, newKey, remove)) {
                val failure: StorageResult.Failure = assertIs<StorageResult.Failure>(result)
                assertIs<StorageError.OperationFailed>(failure.error)
            }
            assertEquals(3, disk.refused)
            assertEquals(StorageResult.Success("1"), store.get("a"))
            assertEquals(StorageResult.Success("2"), store.get("b"))
            assertEquals(StorageResult.Success<String?>(null), store.get("c"))
            assertTrue(bytesBefore.contentEquals(file().readBytes()), "the file changed although every write failed")
            assertEquals(emptyList(), litter())
            disk.full = false
            assertTrue(store.put("c", "3") is StorageResult.Success)
            assertEquals(StorageResult.Success("3"), store.get("c"))
            assertEquals(StorageResult.Success("1"), store.get("a"))
            assertEquals(emptyList(), litter())
        }
    }

    @Test
    fun `the directory removed under a running store reads empty and the next put recreates it`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val nested: File = File(directory, "inner")
            val target = File(nested, "s.properties")
            val store: KeyValueStorage = JvmKeyValueStorage(target, Dispatchers.IO, createFileAccess())
            assertTrue(store.put("k", "v") is StorageResult.Success)
            nested.deleteRecursively()
            assertEquals(StorageResult.Success<String?>(null), store.get("k"))
            assertTrue(store.remove("k") is StorageResult.Success || store.remove("k") is StorageResult.Failure)
            assertTrue(store.clear() is StorageResult.Success)
            assertTrue(store.put("k2", "v2") is StorageResult.Success)
            assertEquals(StorageResult.Success("v2"), store.get("k2"))
        }
    }
}
