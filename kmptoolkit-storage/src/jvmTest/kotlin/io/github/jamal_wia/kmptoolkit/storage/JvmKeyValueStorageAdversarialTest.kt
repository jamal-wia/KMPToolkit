package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The desktop store reached through different spellings of one directory, and created, written and
 * dropped over and over: no key may be lost because two instances disagree on which file they share,
 * and no temporary file may outlive its write.
 */
class JvmKeyValueStorageAdversarialTest {

    private fun newDirectory(): File = Files.createTempDirectory("kmptoolkit-adversarial").toFile()

    @Test
    fun `instances opened through the plain path a dot path and a symlink share one file and lose no key`() {
        val directory: File = newDirectory()
        val link: Path = Files.createTempDirectory("kmptoolkit-link").resolve("link")
        try {
            Files.createSymbolicLink(link, directory.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            return // a platform without symbolic links has nothing to spell differently
        }
        val spellings: List<File> = listOf(
            directory,
            File(directory, "."),
            File(File(directory, "sub").also { it.mkdirs() }, ".."),
            link.toFile(),
        )
        val config = StorageConfig("spellings")

        val writers: List<Thread> = (0 until 8).map { writer: Int ->
            thread(name = "spelling-writer-$writer") {
                val storage: KeyValueStorage = createKeyValueStorage(spellings[writer % spellings.size], config)
                runBlocking { repeat(25) { index: Int -> storage.put("w$writer-k$index", "v$writer-$index") } }
            }
        }
        writers.forEach { it.join(30_000) }
        assertTrue(writers.none { it.isAlive }, "a writer is still blocked after 30 s")

        for (spelling: File in spellings) {
            val reader: KeyValueStorage = createKeyValueStorage(spelling, config)
            runBlocking {
                for (writer: Int in 0 until 8) {
                    for (index: Int in 0 until 25) {
                        assertEquals(
                            "v$writer-$index",
                            reader.get("w$writer-k$index").getOrNull(),
                            "key w$writer-k$index through $spelling",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `1000 stores created written and dropped leave no temporary file behind`() {
        val directory: File = newDirectory()
        val config = StorageConfig("churn")

        runBlocking {
            repeat(1000) { round: Int ->
                val storage: KeyValueStorage = createKeyValueStorage(directory, config)
                assertTrue(storage.put("round", round.toString()).getOrNull() != null, "round $round")
            }
        }

        val leftovers: List<String> = directory.list()?.toList().orEmpty().sorted()
        assertEquals(
            listOf("churn.kmptoolkit.storage.properties"),
            leftovers,
            "only the store's own file may remain, found $leftovers",
        )
        assertEquals("999", runBlocking { createKeyValueStorage(directory, config).get("round").getOrNull() })
    }

    @Test
    fun `a put cancelled right after it was launched leaves the file readable and the store usable for 200 rounds`() {
        val directory: File = newDirectory()
        val config = StorageConfig("cancel")
        val storage: KeyValueStorage = createKeyValueStorage(directory, config)
        val whole: String = "y".repeat(32 * 1024)

        runBlocking {
            repeat(200) { round: Int ->
                val writer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { storage.put("k$round", whole) }
                writer.cancel()
                writer.join()
                val stored: String? = withTimeout(5_000) { storage.get("k$round").getOrNull() }
                assertTrue(stored == null || stored == whole, "round $round: torn value of ${stored?.length} chars")
            }
            assertEquals(Unit, storage.put("after", "ok").getOrNull())
        }
        assertEquals(listOf("cancel.kmptoolkit.storage.properties"), directory.list()!!.toList().sorted())
    }

    @Test
    fun `clear racing writers on two instances leaves no temporary file and a usable store`() {
        val directory: File = newDirectory()
        val config = StorageConfig("clearrace")
        val writers: List<Thread> = (0 until 6).map { writer: Int ->
            thread(name = "jvm-clear-$writer") {
                val storage: KeyValueStorage = createKeyValueStorage(directory, config)
                runBlocking {
                    repeat(60) { index: Int ->
                        storage.put("k$writer", "v$index")
                        if (writer == 0 && index % 6 == 0) storage.clear()
                    }
                }
            }
        }
        writers.forEach { it.join(30_000) }
        assertTrue(writers.none { it.isAlive }, "a writer is blocked")

        val after: List<String> = directory.list()!!.toList().sorted()
        assertTrue(after.none { it.endsWith(".tmp") }, "temporary files left: $after")
        runBlocking {
            val storage: KeyValueStorage = createKeyValueStorage(directory, config)
            storage.put("final", "ok")
            assertEquals("ok", storage.get("final").getOrNull())
        }
    }
}
