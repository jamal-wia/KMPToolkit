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
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 8 of the breaker for unit B, Q1 and Q2 on the desktop store: a process death at each boundary of a write, and
 * a file laid out the way 4.0.0 wrote it. Contract (docs/kmptoolkit-storage/05-platform-notes.md "Desktop", the KDoc of
 * `createKeyValueStorage`): a write replaces the file atomically, so a death leaves the previous contents, never a
 * truncated file; a temporary sibling left by a killed writer is never read as data and is removed by `clear()`.
 */
class JvmKeyValueStorageCrashRound8Test {
    private val directory: File = Files.createTempDirectory("kmptoolkit-storage-r8").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private class ProcessDeath : RuntimeException("process death")

    private enum class Death { BeforeStage, StagedHalf, AfterRename }

    /** The file layer of a process that dies inside the [at] boundary of its first write; later calls are real. */
    private class Dying(private val real: FileAccess, private val at: Death) : FileAccess by real {
        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
            val target = File(path)
            when (at) {
                Death.BeforeStage -> Unit
                Death.StagedHalf -> File(target.parentFile, "${options.tempPrefix}4242424242.tmp").writeBytes(bytes.copyOf(bytes.size / 2))
                Death.AfterRename -> real.writeAtomically(path, bytes, options)
            }
            throw ProcessDeath()
        }
    }

    private fun file(): File = File(directory, "settings.kmptoolkit.storage.properties")

    private fun store(files: FileAccess = createFileAccess()): KeyValueStorage = JvmKeyValueStorage(file(), Dispatchers.IO, files)

    private fun names(): List<String> = directory.list().orEmpty().sorted()

    @Test
    fun `a death at each boundary of a write leaves the old or the new contents and the next process works`() = runBlocking<Unit> {
        withTimeout(60_000) {
            for (at: Death in Death.entries) {
                directory.deleteRecursively()
                directory.mkdirs()
                store().put("keep", "old")
                // A neighbour store of another name, with a temporary file of its own: nothing here may touch them.
                val neighbour = File(directory, "settings.kmptoolkit.storage.properties.bak")
                neighbour.writeText("bak")
                val neighbourTemp = File(directory, "other.kmptoolkit.storage.properties5.tmp")
                neighbourTemp.writeText("not ours")

                runCatching { store(Dying(createFileAccess(), at)).put("keep", "new") }

                val expected: String = if (at == Death.AfterRename) "new" else "old"
                assertEquals(expected, store().get("keep").getOrNull(), "$at: the read after the death")
                assertTrue(store().put("other", "x") is StorageResult.Success, "$at: a put after the death")
                assertEquals(expected, store().get("keep").getOrNull(), "$at: a put after the death changed the value")
                store().clear()
                assertEquals(listOf(neighbour.name, neighbourTemp.name).sorted(), names(), "$at: clear() must remove the store file and its own leftovers and nothing else")
            }
        }
    }

    /**
     * The bytes 4.0.0 wrote: `Properties.store(Writer)` of UTF-8, a date comment line, escaped separators and spaces in
     * keys, raw non-ASCII characters (a Writer does not escape them). 5.0.0 reads them, rewrites the file and the other
     * keys survive; a leftover of 4.0.0 in its `createTempFile` shape (digits up to 20) is removed by `clear()`.
     */
    @Test
    fun `a file and a leftover laid out by 4_0_0 are read and cleaned by this version`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val legacy: String = "#Fri Nov 08 10:00:00 UTC 2026\nname=café 日本語\nspaced\\ key=a\\=b\\:c\nempty=\nmultiline=one\\ntwo\n"
            file().writeText(legacy, Charsets.UTF_8)
            val storage: KeyValueStorage = store()
            assertEquals("café 日本語", storage.get("name").getOrNull())
            assertEquals("a=b:c", storage.get("spaced key").getOrNull())
            assertEquals("", storage.get("empty").getOrNull())
            assertEquals("one\ntwo", storage.get("multiline").getOrNull())
            assertTrue(storage.put("added", "1") is StorageResult.Success)
            assertEquals("café 日本語", storage.get("name").getOrNull(), "a key of the 4.0.0 file was lost by the first rewrite")
            // 4.0.0 temp leftovers: createTempFile puts an unsigned 64-bit number (1..20 digits) between name and ".tmp".
            val twentyDigits = File(directory, "${file().name}18446744073709551615.tmp")
            twentyDigits.writeText("half")
            val oneDigit = File(directory, "${file().name}7.tmp")
            oneDigit.writeText("half")
            assertEquals("café 日本語", store().get("name").getOrNull(), "a 4.0.0 leftover was read as data")
            store().clear()
            assertEquals(emptyList(), names(), "clear() left 4.0.0 files behind")
        }
    }

    /** The refusal of a rename the file system cannot do atomically arrives from the file layer as a failure result. */
    private class Refusing(private val real: FileAccess) : FileAccess by real {
        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
            FileResult.Failure(FileError.Io(path, null, java.io.IOException("atomic rename not supported")))
    }

    @Test
    fun `a write the file layer refuses is OperationFailed and the old contents stay`() = runBlocking<Unit> {
        withTimeout(60_000) {
            store().put("keep", "old")
            val put: StorageResult<Unit> = store(Refusing(createFileAccess())).put("keep", "new")
            assertTrue(put is StorageResult.Failure && put.error is StorageError.OperationFailed, "$put")
            assertEquals("old", store().get("keep").getOrNull())
            assertEquals(listOf(file().name), names(), "a refused write left a temporary file")
        }
    }

    /**
     * 05-platform-notes "Desktop": a store file name longer than 232 UTF-8 bytes uses, as the prefix of its temporary
     * files, the name cut to 215 bytes plus `~` plus 16 hex characters of its SHA-256; `clear()` removes only that
     * store's own leftovers, never a neighbour's whose file name shares the first 215 bytes.
     */
    @Test
    fun `a store name over the limit has a hashed temporary prefix and clear removes only its own leftover`() = runBlocking<Unit> {
        withTimeout(60_000) {
            fun prefixOf(fileName: String): String {
                val hash: String = java.security.MessageDigest.getInstance("SHA-256").digest(fileName.encodeToByteArray())
                    .take(8).joinToString("") { "%02x".format(it) }
                return fileName.take(215) + "~" + hash
            }
            val own = "n".repeat(222)
            val neighbourName = "n".repeat(221) + "m"
            val ownFile = "$own.kmptoolkit.storage.properties"
            val neighbourFile = "$neighbourName.kmptoolkit.storage.properties"
            assertTrue(ownFile.length > 232 && ownFile.length <= 255)
            val storage: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(own))
            assertTrue(storage.put("k", "v") is StorageResult.Success)
            assertEquals("v", storage.get("k").getOrNull())
            assertEquals(listOf(ownFile), names(), "a successful write left a temporary file")
            val ownLeftover = File(directory, prefixOf(ownFile) + "77.tmp")
            val neighbourLeftover = File(directory, prefixOf(neighbourFile) + "77.tmp")
            ownLeftover.writeText("half")
            neighbourLeftover.writeText("half")
            storage.clear()
            assertEquals(listOf(neighbourLeftover.name), names(), "clear() must remove its own leftover and keep the neighbour's")
        }
    }
}
