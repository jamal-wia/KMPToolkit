package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * Round 9 (A2), environment hostility, storage, iOS: the journal on Foundation files that are changed under it. Its
 * directory deleted or replaced by a file, an entry replaced by a directory, entries cut at every length by another
 * process, and writers and readers racing a third party that keeps deleting the tree. Nothing throws, an entry is whole
 * or absent, and the journal works again as soon as the environment does.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosStorageEnvironmentRound9AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-r9-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"
    private val files: NSFileManager = NSFileManager.defaultManager
    private val storage = IosOutcomeStorage(dir)
    private val journal = OutcomeJournal(storage, UploaderClock.System)
    private val response = UploadResponse(200, mapOf("a" to "b"), ByteArray(256) { it.toByte() }, false)

    @AfterTest
    fun cleanUp() {
        files.removeItemAtPath(root, error = null)
    }

    private fun writeText(path: String) {
        NSString.create(string = "not a journal entry").writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    @Test
    fun a_directory_deleted_between_writes_is_created_again() {
        for (round in 1..20) {
            assertTrue(journal.record("a$round", response), "round $round")
            assertIs<JournalEntry.Valid>(journal.inspect("a$round"))
            files.removeItemAtPath(dir, error = null)
            assertEquals(JournalEntry.Absent, journal.inspect("a$round"))
        }
    }

    @Test
    fun a_directory_replaced_by_a_regular_file_never_throws_and_recovers_when_it_is_removed() {
        assertTrue(journal.record("seed", response))
        files.removeItemAtPath(dir, error = null)
        writeText(dir)
        assertFalse(journal.record("x", response))
        assertEquals(JournalEntry.Absent, journal.inspect("seed"))
        journal.delete("seed")
        runBlocking { journal.sweep { _, _, _, _ -> JournalSettleOutcome.LANDED } }
        files.removeItemAtPath(dir, error = null)
        assertTrue(journal.record("x", response))
        assertIs<JournalEntry.Valid>(journal.inspect("x"))
    }

    @Test
    fun an_entry_replaced_by_a_directory_reads_as_absent_and_leaves_the_others_alone() {
        assertTrue(journal.record("other", response))
        val squat = "$dir/${journalFileName("x")}"
        files.createDirectoryAtPath(squat, withIntermediateDirectories = true, attributes = null, error = null)
        writeText("$squat/inner")
        val read: JournalEntry = journal.inspect("x")
        assertTrue(read == JournalEntry.Absent || read == JournalEntry.Unreadable || read == JournalEntry.Discarded, "squatted name read as $read")
        journal.record("x", response) // may refuse, must not throw
        journal.delete("x")
        val replayed: MutableList<String> = mutableListOf()
        runBlocking { journal.sweep { id, _, _, _ -> replayed += id; JournalSettleOutcome.LANDED } }
        assertTrue("other" in replayed, "a squatted name kept another entry from being replayed: $replayed")
    }

    @Test
    fun an_entry_cut_at_any_length_by_another_process_is_discarded_and_removed() {
        assertTrue(journal.record("cut", response))
        val whole: ByteArray = storage.read("cut")!!
        for (length in 0 until whole.size step 5) {
            storage.write("cut", whole.copyOf(length))
            assertEquals(JournalEntry.Discarded, journal.inspect("cut"), "an entry cut at $length of ${whole.size} bytes")
        }
        assertEquals(JournalEntry.Absent, journal.inspect("cut"))
        assertTrue(journal.record("cut", response))
        assertIs<JournalEntry.Valid>(journal.inspect("cut"))
    }

    @Test
    fun writers_and_readers_while_a_third_party_keeps_deleting_the_tree_see_only_whole_entries() = runBlocking {
        var discarded = 0
        var threw = 0
        var wrote = 0
        withTimeout(60_000) {
            coroutineScope {
                val workers = (0 until 4).map { w ->
                    async(Dispatchers.Default) {
                        var n = 0
                        val end: Double = NSDate().timeIntervalSince1970 + 2.5
                        while (NSDate().timeIntervalSince1970 < end) {
                            val id = "w$w-${n++ % 20}"
                            try {
                                if (journal.record(id, response)) wrote++
                                if (journal.inspect(id) == JournalEntry.Discarded) discarded++
                                if (n % 3 == 0) journal.delete(id)
                            } catch (e: Throwable) {
                                threw++
                            }
                            delay(1)
                        }
                    }
                }
                launch(Dispatchers.Default) {
                    repeat(250) {
                        files.removeItemAtPath(dir, error = null)
                        delay(8)
                    }
                }
                workers.awaitAll()
            }
        }
        assertEquals(0, threw, "the journal threw under a vanishing tree")
        assertEquals(0, discarded, "a half-written entry was visible to a reader")
        assertTrue(wrote > 0, "no write succeeded")
        withContext(Dispatchers.Default) {
            for (id in storage.itemIds()) assertIs<JournalEntry.Valid>(journal.inspect(id), "$id is not whole after the storm")
        }
    }
}
