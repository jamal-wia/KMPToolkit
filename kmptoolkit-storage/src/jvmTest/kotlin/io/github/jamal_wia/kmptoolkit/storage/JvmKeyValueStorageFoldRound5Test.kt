package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A volume that folds case folds more than `lowercase()` does: APFS (macOS) and NTFS (Windows) treat
 * the German sharp s and "ss", and the three Greek sigma spellings, as one file name. Two instances
 * over two spellings of one store name are then two locks over one file, and their read-modify-write
 * cycles lose keys (the JvmKeyValueStorage KDoc: "Shared by every instance over the same canonical path").
 */
class JvmKeyValueStorageFoldRound5Test {

    private fun foldsTogether(directory: File, first: String, second: String): Boolean {
        File(directory, "$first.probe").createNewFile()
        return File(directory, "$second.probe").exists()
    }

    private fun lostKeys(first: String, second: String): Int? {
        val directory: File = Files.createTempDirectory("kmptoolkit-fold").toFile()
        try {
            if (!foldsTogether(directory, first, second)) return null // two files on this volume, nothing to share
            var lost = 0
            repeat(15) { round: Int ->
                val dir = File(directory, "round$round").also { it.mkdirs() }
                val one: KeyValueStorage = createKeyValueStorage(dir, StorageConfig(first))
                val two: KeyValueStorage = createKeyValueStorage(dir, StorageConfig(second))
                val writers: List<Thread> = listOf(one to "u", two to "l").map { (storage: KeyValueStorage, tag: String) ->
                    thread(name = "fold-writer-$tag") {
                        runBlocking { repeat(40) { index: Int -> storage.put("$tag$index", "v") } }
                    }
                }
                writers.forEach { it.join(30_000) }
                assertTrue(writers.none { it.isAlive }, "round $round: a writer is blocked")
                val reader: KeyValueStorage = createKeyValueStorage(dir, StorageConfig(second))
                lost += runBlocking {
                    listOf("u", "l").flatMap { tag: String -> (0 until 40).map { "$tag$it" } }
                        .count { reader.get(it).getOrNull() == null }
                }
            }
            return lost
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sharp s and ss share one lock when the volume folds them together`() {
        val lost: Int = lostKeys("ß", "ss") ?: return
        assertEquals(0, lost, "$lost keys were lost across 15 rounds of 80")
    }

    @Test
    fun `the three Greek sigma spellings share one lock when the volume folds them together`() {
        // lowercase() turns the capital sigma at the end of a word into the final form, never the plain one.
        val lost: Int = lostKeys("ασ", "ας") ?: return
        assertEquals(0, lost, "$lost keys were lost across 15 rounds of 80")
    }

    private fun key(name: String): String = jvmStorageLockKey(File("/kmptoolkit-lockkey-dir", name))

    @Test
    fun `the lock key is one for every spelling a folding volume treats as one file`() {
        assertEquals(key("\u00df.p"), key("ss.p"))
        assertEquals(key("ss.p"), key("SS.p"))
        assertEquals(key("\u03b1\u03c3.p"), key("\u03b1\u03c2.p"))
        assertEquals(key("\u03b1\u03c3.p"), key("\u0391\u03a3.p"))
        assertEquals(key("\u017f.p"), key("s.p"))
        assertEquals(key("\u212a.p"), key("k.p"))
        assertEquals(key("caf\u00e9.p"), key("cafe\u0301.p"))
    }

    @Test
    fun `the lock key still tells different names apart`() {
        assertNotEquals(key("a.p"), key("b.p"))
        assertNotEquals(key("ss.p"), key("s.p"))
    }
}
