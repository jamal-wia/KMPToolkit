package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The capital sharp s (U+1E9E) folds to `ss` like the sharp s does (APFS and NTFS treat `ẞ`, `ß` and `ss` as one file),
 * but upper-then-lower case mapping leaves it as `ß` where `ß` and `ss` become `ss`. See the downloader's
 * UnitFileLayoutFoldRound6Test for the search that found it the only such character.
 */
class JvmKeyValueStorageFoldRound6Test {

    @Test
    fun `the capital sharp s sharp s and ss get one lock key`() {
        val dir: File = Files.createTempDirectory("kmptoolkit-fold6").toFile()
        try {
            val ss: String = jvmStorageLockKey(File(dir, "ss.xml"))
            assertEquals(ss, jvmStorageLockKey(File(dir, "ß.xml")))
            assertEquals(ss, jvmStorageLockKey(File(dir, "ẞ.xml")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `two instances over the capital sharp s and ss spellings of one file lose no key`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-fold6v").toFile()
        try {
            File(directory, "ẞ.probe").createNewFile()
            if (!File(directory, "ss.probe").exists()) return // two files on this volume, nothing to share
            var lost = 0
            repeat(15) { round: Int ->
                val dir = File(directory, "round$round").also { it.mkdirs() }
                val one: KeyValueStorage = createKeyValueStorage(dir, StorageConfig("ẞ"))
                val two: KeyValueStorage = createKeyValueStorage(dir, StorageConfig("ss"))
                val writers: List<Thread> = listOf(one to "u", two to "l").map { (storage: KeyValueStorage, tag: String) ->
                    thread(name = "fold6-writer-$tag") {
                        runBlocking { repeat(40) { index: Int -> storage.put("$tag$index", "v") } }
                    }
                }
                writers.forEach { it.join(30_000) }
                assertTrue(writers.none { it.isAlive }, "round $round: a writer is blocked")
                val reader: KeyValueStorage = createKeyValueStorage(dir, StorageConfig("ss"))
                lost += runBlocking {
                    listOf("u", "l").flatMap { tag: String -> (0 until 40).map { "$tag$it" } }
                        .count { reader.get(it).getOrNull() == null }
                }
            }
            assertEquals(0, lost, "$lost keys were lost across 15 rounds of 80")
        } finally {
            directory.deleteRecursively()
        }
    }
}
