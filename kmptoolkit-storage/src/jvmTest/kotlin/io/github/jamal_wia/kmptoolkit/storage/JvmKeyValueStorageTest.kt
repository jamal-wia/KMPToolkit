package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The desktop store, held to the shared contract plus what is specific to a properties file. */
class JvmKeyValueStorageTest : KeyValueStorageContractTest() {

    private val directory: File = DIRECTORY

    override fun create(config: StorageConfig): KeyValueStorage = createKeyValueStorage(directory, config)

    @Test
    fun `the store is one file named after the config in the given directory`() {
        create().put("k", "v")

        assertTrue(File(directory, "${plainStoreId(NAME)}.properties").isFile)
    }

    @Test
    fun `clear deletes the file and nothing else in the directory`() {
        val neighbour = File(directory, "unrelated.txt").apply { writeText("keep") }
        val storage: KeyValueStorage = create()
        storage.put("k", "v")

        assertTrue(storage.clear().isSuccess)

        assertFalse(File(directory, "${plainStoreId(NAME)}.properties").exists())
        assertEquals("keep", neighbour.readText())
    }

    @Test
    fun `a missing directory is created on the first write`() {
        val nested = File(Files.createTempDirectory("kmptoolkit-storage").toFile(), "a/b")

        val storage: KeyValueStorage = createKeyValueStorage(nested, StorageConfig(NAME))

        assertEquals(StorageResult.Success(null), storage.get("k"))
        assertTrue(storage.put("k", "v").isSuccess)
        assertEquals("v", storage.get("k").getOrNull())
    }

    @Test
    fun `a write that cannot reach the disk is reported not thrown`() {
        // A regular file where the directory should be: the store cannot create its file there.
        val blocker: File = File.createTempFile("kmptoolkit-storage", ".blocker")

        val result: StorageResult<Unit> =
            createKeyValueStorage(File(blocker, "sub"), StorageConfig(NAME)).put("k", "v")

        val error = assertIs<StorageError.OperationFailed>(result.errorOrNull())
        assertEquals(StorageOperation.PUT, error.operation)
        assertEquals("k", error.key)
    }

    @Test
    fun `keys and values with properties-format special characters round-trip`() {
        val storage: KeyValueStorage = create()
        val key = "a=b:c #d\\e"
        val value = "line one\nline two = x : y # z \\ end"

        storage.put(key, value)

        assertEquals(value, create().get(key).getOrNull())
    }

    @Test
    fun `a config without a name is rejected`() {
        assertFailsWith<IllegalArgumentException> { createKeyValueStorage(directory, StorageConfig()) }
    }

    @Test
    fun `concurrent writers through different instances lose no key`() {
        val threads: List<Thread> = (0 until 8).map { index ->
            Thread { create().put("key-$index", "value-$index") }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val storage: KeyValueStorage = create()
        (0 until 8).forEach { index -> assertEquals("value-$index", storage.get("key-$index").getOrNull()) }
    }

    private companion object {
        val DIRECTORY: File = Files.createTempDirectory("kmptoolkit-storage").toFile()
    }
}
