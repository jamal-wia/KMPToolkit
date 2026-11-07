package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * On a volume that folds case, two spellings of one store name are one file. Instances built before
 * the file exists must still share one lock, or their read-modify-write cycles interleave and lose keys.
 */
class JvmKeyValueStorageCaseRound3Test {

    private fun caseInsensitive(directory: File): Boolean {
        File(directory, "ProbeFile").createNewFile()
        return File(directory, "probefile").exists()
    }

    @Test
    fun `instances whose names differ only in case share one lock on a volume that folds case`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-case").toFile()
        if (!caseInsensitive(directory)) return // a case-sensitive volume has two files, nothing to share

        repeat(20) { round: Int ->
            val dir = File(directory, "round$round").also { it.mkdirs() }
            // Both instances exist before either file does.
            val upper: KeyValueStorage = createKeyValueStorage(dir, StorageConfig("Settings"))
            val lower: KeyValueStorage = createKeyValueStorage(dir, StorageConfig("settings"))
            val writers: List<Thread> = listOf(upper to "u", lower to "l").map { (storage: KeyValueStorage, tag: String) ->
                thread(name = "case-writer-$tag") {
                    runBlocking { repeat(40) { index: Int -> storage.put("$tag$index", "v") } }
                }
            }
            writers.forEach { it.join(30_000) }
            assertTrue(writers.none { it.isAlive }, "round $round: a writer is blocked")
            val reader: KeyValueStorage = createKeyValueStorage(dir, StorageConfig("settings"))
            val missing: List<String> = runBlocking {
                (listOf("u", "l")).flatMap { tag: String ->
                    (0 until 40).map { "$tag$it" }.filter { reader.get(it).getOrNull() == null }
                }
            }
            assertEquals(emptyList(), missing.take(5), "round $round: ${missing.size} of 80 keys were lost")
        }
    }

    @Test
    fun `the lock key folds letter case and Unicode form for files that do not exist`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-lockkey").toFile()
        assertEquals(
            jvmStorageLockKey(File(directory, "Settings.p")),
            jvmStorageLockKey(File(directory, "settings.p")),
        )
        // "\u00e9" precomposed against "e" followed by a combining acute accent.
        assertEquals(
            jvmStorageLockKey(File(directory, "caf\u00e9.p")),
            jvmStorageLockKey(File(directory, "cafe\u0301.p")),
        )
        assertTrue(jvmStorageLockKey(File(directory, "a.p")) != jvmStorageLockKey(File(directory, "b.p")))
    }
}
