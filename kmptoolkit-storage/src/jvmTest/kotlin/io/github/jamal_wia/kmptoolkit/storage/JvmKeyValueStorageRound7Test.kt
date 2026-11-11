package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Round 7: the desktop store with names near the file-name limit, very large values and keys, and a killed writer's leftovers. */
class JvmKeyValueStorageRound7Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-storage-r7").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store(name: String): KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))

    /**
     * The temporary sibling of a write is the file name plus digits plus ".tmp". A name whose own file
     * fits the limit but whose temporary sibling does not would make the store readable and never
     * writable: a put must either succeed and read back, or fail and read nothing.
     */
    @Test
    fun `a name whose file fits but whose temporary sibling does not still behaves consistently`() = runBlocking {
        withTimeout(30_000) {
            // The file name is the name plus 30 characters; the temporary name adds up to about 23 more.
            for (length in listOf(150, 195, 200, 210, 215, 219, 220, 225, 232, 400)) {
                val storage: KeyValueStorage = store("n".repeat(length))
                val put: StorageResult<Unit> = storage.put("k", "v")
                val get: StorageResult<String?> = storage.get("k")
                if (put is StorageResult.Success) {
                    assertEquals("v", get.getOrNull(), "length $length: a put that succeeded must read back")
                } else {
                    assertTrue(get.getOrNull() != "v", "length $length: a put that failed must not read back")
                }
                // A name too long for the volume reads as an empty store and fails every put with a typed
                // error (it never throws); clear() on it must not throw either.
                storage.clear()
            }
        }
    }

    @Test
    fun `a sixteen megabyte value and a one megabyte key round-trip`() = runBlocking {
        withTimeout(120_000) {
            val storage: KeyValueStorage = store("huge")
            val value: String = buildString {
                repeat(16 * 1024 * 1024 / 8) { append("ab\n=:\\é") }
            }
            val key: String = "k".repeat(1024 * 1024)
            assertTrue(storage.put(key, value) is StorageResult.Success)
            assertEquals(value, storage.get(key).getOrNull())
            assertTrue(storage.put("small", "x") is StorageResult.Success)
            assertEquals(value, store("huge").get(key).getOrNull(), "a second instance reads the same bytes")
            assertTrue(storage.clear() is StorageResult.Success)
            assertEquals(null, storage.get(key).getOrNull())
        }
    }

    /** A writer killed after creating its temporary file leaves it behind: a later write must not read it as data. */
    @Test
    fun `a leftover temporary file of a killed writer is never read as the store and is removed by clear`() = runBlocking {
        withTimeout(30_000) {
            val storage: KeyValueStorage = store("killed")
            assertTrue(storage.put("k", "v") is StorageResult.Success)
            val dataFile: File = directory.listFiles { file -> file.name.endsWith(".properties") }!!.single()
            val leftover = File(directory, dataFile.name + "1234567890.tmp")
            leftover.writeText("k=from the killed writer\n")

            assertEquals("v", storage.get("k").getOrNull())
            assertTrue(storage.put("k2", "v2") is StorageResult.Success)
            assertEquals("v", storage.get("k").getOrNull())
            assertTrue(storage.clear() is StorageResult.Success)
            assertTrue(!leftover.exists(), "clear() leaves the killed writer's temporary file")
            assertEquals(null, storage.get("k").getOrNull())
        }
    }

    /** Names that differ in separators the config forbids are rejected; names with other punctuation get separate files. */
    @Test
    fun `punctuation in names gives separate stores`() = runBlocking {
        withTimeout(30_000) {
            val names: List<String> = listOf("a", "a.", ".a", "..a", "a..", "a-", "a_", "a~", "a#", "a=", "a:", "a*", "a?", "a\n")
            val stores: List<KeyValueStorage> = names.map { store(it) }
            stores.forEachIndexed { index: Int, storage: KeyValueStorage ->
                assertTrue(storage.put("k", "v$index") is StorageResult.Success, "put to '${names[index]}'")
            }
            stores.forEachIndexed { index: Int, storage: KeyValueStorage ->
                assertEquals("v$index", storage.get("k").getOrNull(), "store '${names[index]}' lost its value")
            }
        }
    }
}
