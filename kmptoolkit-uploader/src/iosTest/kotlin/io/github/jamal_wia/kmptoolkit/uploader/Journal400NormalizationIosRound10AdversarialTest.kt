package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * Round 10 (compatibility with 4.0.0, iOS), R10-S6: 4.0.0 journaled an id outside ASCII under its own spelling (`dir/<id>`),
 * and Foundation stores and lists the name with accents decomposed. Every operation that takes the id finds that entry
 * whichever way the id is spelled. (What the sweep hands over for such an entry is a separate question: the file does not
 * remember the id's spelling.)
 */
@OptIn(ExperimentalForeignApi::class)
class Journal400NormalizationIosRound10AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r10o-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"
    private val now = 1_700_000_000_000L

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    private val composed: List<String> = listOf("café", "Ångström", "über") // NFC; APFS lists them decomposed

    private fun responseFor(id: String) = UploadResponse(201, mapOf("x-id" to "v"), id.encodeToByteArray(), false)

    private fun writeRaw(name: String, bytes: ByteArray): Boolean {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val data: NSData = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        return NSFileManager.defaultManager.createFileAtPath("$dir/$name", data, attributes = null)
    }

    private fun seed(): List<String> = composed.filter { writeRaw(it, OutcomeCodec.encode(responseFor(it), now, null)) }

    private fun listing(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null).orEmpty().filterIsInstance<String>()

    @Test
    fun `inspect finds the entry 4_0_0 wrote under an id with composed accents`() {
        val seeded: List<String> = seed()
        assertEquals(composed, seeded, "the volume refused a name")
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        val notFound: List<String> = seeded.filter { journal.inspect(it) !is JournalEntry.Valid }.map { id -> "$id -> ${journal.inspect(id)}" }
        assertTrue(notFound.isEmpty(), "legacy entries not found by their id: $notFound")
        for (id in seeded) assertEquals(responseFor(id), (journal.inspect(id) as JournalEntry.Valid).response)
    }

    @Test
    fun `delete removes the 4_0_0 entry of a composed id`() {
        seed()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        for (id in composed) {
            journal.delete(id)
            assertEquals(JournalEntry.Absent, journal.inspect(id), "$id after delete")
        }
        assertEquals(emptyList(), listing())
    }

    @Test
    fun `a new write of a composed id replaces its 4_0_0 entry instead of adding a second one`() {
        seed()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        for (id in composed) {
            assertTrue(journal.record(id, UploadResponse(500, emptyMap(), byteArrayOf(7), false), attemptStamp = 2), "write of $id")
            val entry: JournalEntry = journal.inspect(id)
            assertTrue(entry is JournalEntry.Valid, "entry of $id is $entry")
            assertContentEquals(byteArrayOf(7), entry.response.body())
        }
        assertEquals(composed.size, listing().size, "one entry per id: ${listing()}")
        assertEquals(composed.size, IosOutcomeStorage(dir).itemIds().size)
    }

    @Test
    fun `a replay by id settles the 4_0_0 entry of a composed id once`() = runBlocking {
        seed()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        for (id in composed) {
            var settled = 0
            val first: ReplayOutcome = journal.replay(id) { _, _, _, onSettling ->
                settled += 1
                onSettling()
                JournalSettleOutcome.LANDED
            }
            assertEquals(ReplayOutcome.Replayed, first, "first replay of $id")
            assertEquals(ReplayOutcome.NoEntry, journal.replay(id) { _, _, _, _ -> JournalSettleOutcome.LANDED }, "second replay of $id")
            assertEquals(1, settled)
        }
        assertEquals(emptyList(), listing())
    }
}
