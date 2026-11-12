package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * Round 10 (compatibility with 4.0.0, iOS): 4.0.0 named a journal entry after its item id as it was (`dir/<id>`), always
 * unstamped. Whatever such a file a process left behind is read, replayed by the sweep and removed by this version,
 * for every shape of id the default generator or a consumer produces. Entries are written in the 4.0.0 layout, with
 * bytes from the codec the two versions share (their codec source is identical; see the round 10 report).
 */
@OptIn(ExperimentalForeignApi::class)
class Journal400CompatIosRound10AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r10c-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"
    private val now = 1_700_000_000_000L

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    /** Ids a 4.0.0 app had on disk, one per case-fold class (the volume of the simulator folds case). */
    private val ids400: List<String> = listOf(
        "item-1", "Photo-AbC", "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF", "7", "Z", ".hidden", "a_b-c.d", "UPPER", "x".repeat(200),
        "F47AC10B-58CC-4372-A567-0E02B2C3D479", "mixed.CASE_id-9", "0",
    )

    private fun responseFor(id: String): UploadResponse =
        UploadResponse(200 + id.length % 300, mapOf("x-id" to id.take(30)), id.encodeToByteArray(), false)

    private fun writeRaw(name: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val data: NSData = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath("$dir/$name", data, attributes = null), "could not create $name")
    }

    private fun onDisk(): List<String> =
        (NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null).orEmpty().filterIsInstance<String>()).sorted()

    private fun seed400() {
        for (id in ids400) writeRaw(id, OutcomeCodec.encode(responseFor(id), now, null))
    }

    @Test
    fun `every entry 4_0_0 left under the id's own name is read by inspect with its answer and no stamp`() {
        seed400()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        val missing: MutableList<String> = mutableListOf()
        for (id in ids400) {
            val entry: JournalEntry = journal.inspect(id)
            if (entry !is JournalEntry.Valid) {
                missing += "$id -> $entry"
                continue
            }
            assertEquals(responseFor(id), entry.response, "answer of $id")
            assertNull(entry.attemptStamp, "a 4.0.0 entry has no stamp ($id)")
        }
        assertTrue(missing.isEmpty(), "4.0.0 entries not found by their id: $missing")
    }

    @Test
    fun `the sweep replays every 4_0_0 entry once under its id and leaves no file behind`() = runBlocking {
        seed400()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        val handed: MutableMap<String, UploadResponse> = linkedMapOf()
        val calls: MutableList<String> = mutableListOf()
        journal.sweep { id, response, stamp, onSettling ->
            calls += id
            assertNull(stamp)
            onSettling()
            handed[id] = response
            JournalSettleOutcome.LANDED
        }
        assertEquals(ids400.sorted(), calls.sorted(), "ids the sweep handed over (each exactly once)")
        for (id in ids400) assertEquals(responseFor(id), handed[id], "answer handed for $id")
        assertEquals(emptyList(), onDisk(), "files left in the journal directory after the sweep")
    }

    @Test
    fun `an unsettled 4_0_0 entry survives a failed commit and a new write for the same id replaces it instead of adding a second one`() = runBlocking {
        seed400()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        val failing: OutcomeSettle = { _, _, _, onSettling ->
            onSettling()
            JournalSettleOutcome.NOT_LANDED
        }
        journal.sweep(failing)
        assertEquals(ids400.size, IosOutcomeStorage(dir).itemIds().size, "entries kept after a commit that did not land")
        for (id in ids400) {
            val replacement = UploadResponse(500, emptyMap(), byteArrayOf(7), false)
            assertTrue(journal.record(id, replacement, attemptStamp = 3), "write of $id over its 4.0.0 entry")
            val entry: JournalEntry = journal.inspect(id)
            assertTrue(entry is JournalEntry.Valid, "entry of $id is $entry")
            assertContentEquals(byteArrayOf(7), entry.response.body(), "$id still reads the 4.0.0 body")
            assertEquals(3, entry.attemptStamp)
        }
        assertEquals(ids400.size, IosOutcomeStorage(dir).itemIds().size, "one entry per id after the rewrite, ids: ${IosOutcomeStorage(dir).itemIds()}")
        val handed: MutableList<String> = mutableListOf()
        journal.sweep { id, response, stamp, _ ->
            handed += id
            assertEquals(500, response.statusCode, "$id: the sweep handed the 4.0.0 answer again")
            assertEquals(3, stamp)
            JournalSettleOutcome.LANDED
        }
        assertEquals(ids400.sorted(), handed.sorted())
        assertEquals(emptyList(), onDisk())
    }

    @Test
    fun `delete of an id removes its 4_0_0 entry and nothing else`() {
        seed400()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        for (id in ids400) {
            journal.delete(id)
            assertEquals(JournalEntry.Absent, journal.inspect(id), "$id after delete")
            assertEquals(ids400.size - (ids400.indexOf(id) + 1), IosOutcomeStorage(dir).itemIds().size, "deleting $id touched another entry")
        }
    }
}
