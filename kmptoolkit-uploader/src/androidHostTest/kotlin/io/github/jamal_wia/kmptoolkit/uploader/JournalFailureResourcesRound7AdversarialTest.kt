package io.github.jamal_wia.kmptoolkit.uploader

import com.sun.management.UnixOperatingSystemMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.test.AfterTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round 7 (resource accounting after failures): a journal write that cannot complete (the entry directory or the staging
 * directory refuses the file) reports `false` without throwing, leaves no staging file and no descriptor behind, and the
 * journal works again once the directory is writable. Skipped where the process can write into a read-only directory.
 */
class JournalFailureResourcesRound7AdversarialTest {

    private val root: File = Files.createTempDirectory("r7-fail").toFile()
    private val outcomes = File(root, "outcomes")
    private val partial = File(root, "partial")
    private val journal = OutcomeJournal(AndroidOutcomeStorage(outcomes, partial), UploaderClock.System)
    private val response = UploadResponse(200, mapOf("a" to "b"), ByteArray(2_048), false)

    @AfterTest
    fun cleanUp() {
        // path-hygiene-ignore: PH2 restores flags a permission test cleared; harmless on Windows
        outcomes.setWritable(true)
        // path-hygiene-ignore: PH2 restores flags a permission test cleared; harmless on Windows
        partial.setWritable(true)
        root.deleteRecursively()
    }

    // path-hygiene-ignore: PH2 only reached after the POSIX permission assumption
    private fun openFds(): Long = (ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean).openFileDescriptorCount

    @Test
    fun `a write the entry directory refuses leaves no staging file and no descriptor`() {
        assertTrue(journal.record("seed", response))
        // path-hygiene-ignore: PH2 permission probe guarded by an assumption; skipped where the host ignores it
        assumeTrue(outcomes.setWritable(false) && !File(outcomes, "probe").also { runCatching { it.createNewFile() } }.exists())
        val before: Long = openFds()
        repeat(300) { n -> assertFalse(journal.record("blocked-$n", response), "a refused write was reported as journaled") }
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "staging files were left behind")
        assertTrue(openFds() - before <= 4, "descriptors grew from $before to ${openFds()}")
        // path-hygiene-ignore: PH2 restores flags a permission test cleared; harmless on Windows
        outcomes.setWritable(true)
        assertTrue(journal.record("after", response), "the journal did not recover once the directory was writable")
        assertTrue(journal.inspect("after") is JournalEntry.Valid)
        assertTrue(journal.inspect("seed") is JournalEntry.Valid, "an entry written before the failures was lost")
    }

    @Test
    fun `a write the staging directory refuses leaves nothing and recovers`() {
        assertTrue(journal.record("seed", response))
        // path-hygiene-ignore: PH2 permission probe guarded by an assumption; skipped where the host ignores it
        assumeTrue(partial.setWritable(false) && !File(partial, "probe").also { runCatching { it.createNewFile() } }.exists())
        val before: Long = openFds()
        repeat(300) { n -> assertFalse(journal.record("blocked-$n", response)) }
        assertTrue(openFds() - before <= 4, "descriptors grew from $before to ${openFds()}")
        assertEquals(listOf("seed"), outcomes.list()?.toList().orEmpty().sorted(), "an entry appeared for a refused write")
        // path-hygiene-ignore: PH2 restores flags a permission test cleared; harmless on Windows
        partial.setWritable(true)
        assertTrue(journal.record("after", response))
    }
}
