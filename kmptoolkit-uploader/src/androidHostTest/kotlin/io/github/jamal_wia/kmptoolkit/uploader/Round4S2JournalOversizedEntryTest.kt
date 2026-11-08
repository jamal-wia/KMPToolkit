package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * An entry file larger than anything the journal writes is damaged: it is deleted without being read, and nothing
 * throws out of the journal for it (the class promises that for a storage problem).
 */
class Round4S2JournalOversizedEntryTest {

    private val cleanup = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        cleanup.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("uploader-r4-").toFile().also { cleanup += it }

    private fun storageOn(root: File): AndroidOutcomeStorage = AndroidOutcomeStorage(File(root, "outcomes"), File(root, "partial"))

    private fun inspectDiscarding(root: File, length: Long, fill: Boolean) {
        val storage: AndroidOutcomeStorage = storageOn(root)
        File(root, "outcomes").mkdirs()
        val file = File(root, "outcomes/item-1")
        RandomAccessFile(file, "rw").use { raf ->
            if (fill) {
                val chunk = ByteArray(1024 * 1024) { 7 }
                var left: Long = length
                while (left > 0) {
                    val n: Int = minOf(left, chunk.size.toLong()).toInt()
                    raf.write(chunk, 0, n)
                    left -= n
                }
            } else {
                raf.setLength(length)
            }
        }
        val seen: JournalEntry = try {
            OutcomeJournal(storage).inspect("item-1")
        } catch (e: Throwable) {
            fail("the journal's contract is that nothing here throws for a storage problem, but inspect threw $e")
        }
        assertIs<JournalEntry.Discarded>(seen)
        assertFalse(file.exists(), "the poison file must be deleted, or every attempt of the item meets it again")
    }

    @Test
    fun `an entry file too large to read is damaged, not an Error out of the journal`() {
        // A sparse three-gigabyte file under an item's name: a corrupted volume, a stray file of the wrong app.
        inspectDiscarding(tempDir(), 3L * 1024 * 1024 * 1024, fill = false)
    }

    @Test
    fun `an entry just over the bound is discarded without being read`() {
        val root: File = tempDir()
        inspectDiscarding(root, MAX_ENCODED_ENTRY_BYTES + 1, fill = true)
    }

    @Test
    fun `the storage answers an oversized entry with an empty array instead of its bytes`() {
        val root: File = tempDir()
        val storage: AndroidOutcomeStorage = storageOn(root)
        File(root, "outcomes").mkdirs()
        RandomAccessFile(File(root, "outcomes/item-2"), "rw").use { it.setLength(MAX_ENCODED_ENTRY_BYTES + 1) }

        val bytes: ByteArray = assertNotNull(storage.read("item-2"))

        assertEquals(0, bytes.size, "a file over the bound must not be read")
    }

    /** Guard, no red-before state: an entry at the bound is still read in full. */
    @Test
    fun `an entry exactly at the bound is still read`() {
        val root: File = tempDir()
        val storage: AndroidOutcomeStorage = storageOn(root)
        File(root, "outcomes").mkdirs()
        RandomAccessFile(File(root, "outcomes/item-3"), "rw").use { it.setLength(MAX_ENCODED_ENTRY_BYTES) }

        assertEquals(MAX_ENCODED_ENTRY_BYTES, assertNotNull(storage.read("item-3")).size.toLong())
    }
}
