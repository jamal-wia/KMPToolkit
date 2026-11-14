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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * Round 10 (compatibility with 4.0.0, iOS), R10-S6: 4.0.0 journaled an id outside ASCII under its own spelling (`dir/<id>`),
 * and Foundation stores and lists the name with accents decomposed. Every operation that takes the id finds that entry
 * whichever way the id is spelled, and the sweep hands it to the item under the spelling the queue holds (ruling D-28: the
 * `knows` callback stands for the queue; the breaker's first version of the sweep test had no queue and could not pass
 * with a decomposed twin).
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

    private val decomposed: String = "cafe\u0301"

    private fun journalOver(): OutcomeJournal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })

    private suspend fun sweepHanding(journal: OutcomeJournal, knows: suspend (String) -> Boolean?): List<Pair<String, String>> {
        val handed: MutableList<Pair<String, String>> = mutableListOf()
        journal.sweep(
            { id, response, _, onSettling ->
                onSettling()
                handed += id to response.body().decodeToString()
                JournalSettleOutcome.LANDED
            },
            knows,
        )
        return handed
    }

    @Test
    fun `the sweep hands a composed id's 4_0_0 entry over under that id once and leaves no file`() = runBlocking {
        val seeded: List<String> = seed()
        val handed = sweepHanding(journalOver()) { it in seeded }
        assertEquals(seeded.sorted(), handed.map { it.first }.sorted(), "ids handed over")
        assertTrue(handed.all { it.first == it.second }, "an entry handed under another id: $handed")
        assertEquals(emptyList(), listing())
    }

    @Test
    fun `the sweep hands a decomposed id's 4_0_0 entry over under that id once`() = runBlocking {
        assertTrue(writeRaw(decomposed, OutcomeCodec.encode(responseFor(decomposed), now, null)))
        val handed = sweepHanding(journalOver()) { it == decomposed }
        assertEquals(listOf(decomposed to decomposed), handed)
        assertEquals(emptyList(), listing())
    }

    @Test
    fun `the sweep hands the entry over under the listed name when the queue holds neither spelling`() = runBlocking {
        seed()
        val listed: Set<String> = listing().toSet()
        val handed = sweepHanding(journalOver()) { false }
        assertEquals(composed.size, handed.size)
        assertEquals(listed, handed.map { it.first }.toSet(), "ids handed over are the listed names")
    }

    @Test
    fun `the sweep prefers the listed spelling when the queue holds both`() = runBlocking {
        assertTrue(writeRaw(decomposed, OutcomeCodec.encode(responseFor(decomposed), now, null)))
        val handed = sweepHanding(journalOver()) { true }
        assertEquals(listOf(decomposed), handed.map { it.first })
    }

    @Test
    fun `the sweep leaves the entry on disk when no engine answers`() = runBlocking {
        val seeded: List<String> = seed()
        val handed = sweepHanding(journalOver()) { null }
        assertEquals(emptyList(), handed)
        val journal: OutcomeJournal = journalOver()
        for (id in seeded) assertTrue(journal.inspect(id) is JournalEntry.Valid, "$id was dropped")
    }

    @Test
    fun `an entry of a plain or escaped name never asks the queue`() = runBlocking {
        val journal: OutcomeJournal = journalOver()
        assertTrue(journal.record("item-1", responseFor("item-1"), attemptStamp = null))
        assertTrue(journal.record("Photo-AbC", responseFor("Photo-AbC"), attemptStamp = 1))
        assertTrue(journal.record(composed[0], responseFor(composed[0]), attemptStamp = 1))
        writeRaw("legacy-1", OutcomeCodec.encode(responseFor("legacy-1"), now, null))
        var asked = 0
        val handed = sweepHanding(journal) {
            asked += 1
            true
        }
        assertEquals(0, asked)
        assertEquals(4, handed.size)
    }

    @Test
    fun `a sweep and a replay of the composed id at the same time settle the entry once`() = runBlocking {
        val id: String = composed[0]
        assertTrue(writeRaw(id, OutcomeCodec.encode(responseFor(id), now, null)))
        val journal: OutcomeJournal = journalOver()
        var settles = 0
        val settle: OutcomeSettle = { _, _, _, onSettling ->
            settles += 1
            onSettling()
            JournalSettleOutcome.LANDED
        }
        withTimeout(20_000) {
            listOf(
                async(Dispatchers.Default) { journal.sweep(settle) { it == id } },
                async(Dispatchers.Default) { journal.replay(id, settle) },
            ).awaitAll()
        }
        assertEquals(1, settles, "settlements of the one entry")
    }
}
