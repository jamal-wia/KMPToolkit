package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Two stores in one directory whose names are prefixes of each other's file names: clearing one must
 * never reach into the other's in-flight temporary file.
 */
class JvmKeyValueStorageNeighbourChaosTest {

    @Test
    fun `clearing a store never breaks a neighbour whose file name starts with its own`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-neighbour").toFile()
        val clearing: KeyValueStorage = createKeyValueStorage(directory, StorageConfig("app"))
        val neighbour: KeyValueStorage =
            createKeyValueStorage(directory, StorageConfig("app.kmptoolkit.storage.properties.x"))
        val failures = AtomicInteger()
        val clearer = thread(name = "neighbour-clearer") {
            runBlocking { repeat(400) { clearing.clear() } }
        }
        val writer = thread(name = "neighbour-writer") {
            runBlocking {
                repeat(400) { index: Int ->
                    if (neighbour.put("k", "v$index") is StorageResult.Failure) failures.incrementAndGet()
                }
            }
        }
        clearer.join(60_000)
        writer.join(60_000)
        assertTrue(!clearer.isAlive && !writer.isAlive, "a thread is still blocked after 60 s")
        assertEquals(0, failures.get(), "puts of the neighbouring store failed while another store was cleared")
        assertEquals("v399", runBlocking { neighbour.get("k").getOrNull() })
        directory.deleteRecursively()
    }

    @Test
    fun `clear still removes this store's own leftover temporary files and only those`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-leftover").toFile()
        try {
            val store: KeyValueStorage = createKeyValueStorage(directory, StorageConfig("app"))
            val own = File(directory, "app.kmptoolkit.storage.properties123456789.tmp").apply { writeText("x") }
            val neighbours: List<File> = listOf(
                "app.kmptoolkit.storage.properties.x123.tmp",
                "app.kmptoolkit.storage.properties.tmp",
                "app.kmptoolkit.storage.properties123abc.tmp",
                "app.kmptoolkit.storage.properties123",
            ).map { name: String -> File(directory, name).apply { writeText("y") } }

            runBlocking { store.clear() }

            assertFalse(own.exists(), "this store's leftover was not removed")
            neighbours.forEach { file: File -> assertTrue(file.exists(), "${file.name} was removed") }
        } finally {
            directory.deleteRecursively()
        }
    }
}
