package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 9 (A2), mutation survivors: the attempt stamp rules of [OutcomeJournal.settleRecorded] at their edges. An entry is
 * "own" only when both the stamp and the answer match; a stamped answer is settled under its own stamp, never under the
 * stamp of another attempt's entry, and another answer's entry is left to its writer.
 */
class JournalStampRound9AdversarialTest {

    private class MemoryStorage : OutcomeStorage {
        val files: MutableMap<String, ByteArray> = LinkedHashMap()
        override fun write(itemId: String, bytes: ByteArray): Boolean {
            files[itemId] = bytes.copyOf()
            return true
        }

        override fun read(itemId: String): ByteArray? = files[itemId]?.copyOf()
        override fun delete(itemId: String) {
            files.remove(itemId)
        }

        override fun itemIds(): List<String> = files.keys.sorted()
        override fun purgeLeftovers() = Unit
    }

    private val storage = MemoryStorage()
    private val journal = OutcomeJournal(storage, TestClock(1_000L))
    private val answer = UploadResponse(201, mapOf("location" to "/f"), "ok".encodeToByteArray(), false)
    private val calls: MutableList<Int?> = mutableListOf()

    private fun settleWith(outcome: JournalSettleOutcome): OutcomeSettle = { _, _, stamp, _ ->
        calls += stamp
        outcome
    }

    @Test
    fun `an entry of another attempt holding the same answer is not own so the answer settles under its own stamp and the entry stays`() = runTest {
        journal.record("x", answer, attemptStamp = 5)

        val landed: Boolean = journal.settleRecorded("x", answer, attemptStamp = 6, settle = settleWith(JournalSettleOutcome.LANDED))

        assertTrue(landed)
        assertEquals(listOf<Int?>(6), calls, "the answer of attempt 6 was settled under the stamp of the entry of attempt 5")
        val entry: JournalEntry.Valid = assertIs(journal.inspect("x"), "the other attempt's entry was deleted")
        assertEquals(5, entry.attemptStamp)
    }

    @Test
    fun `an entry of the same attempt holding another answer is not own either`() = runTest {
        journal.record("x", UploadResponse(500, emptyMap(), ByteArray(0), false), attemptStamp = 5)

        journal.settleRecorded("x", answer, attemptStamp = 5, settle = settleWith(JournalSettleOutcome.LANDED))

        assertEquals(listOf<Int?>(5), calls)
        val entry: JournalEntry.Valid = assertIs(journal.inspect("x"), "an entry holding another answer was deleted")
        assertEquals(500, entry.response.statusCode)
    }

    @Test
    fun `an own entry is deleted once the settlement landed or was stale and kept when it did not land`() = runTest {
        for ((outcome, kept) in listOf(
            JournalSettleOutcome.LANDED to false,
            JournalSettleOutcome.STALE to false,
            JournalSettleOutcome.NOT_LANDED to true,
        )) {
            storage.files.clear()
            journal.record("x", answer, attemptStamp = 5)
            val result: Boolean = journal.settleRecorded("x", answer, attemptStamp = 5, settle = settleWith(outcome))
            assertEquals(!kept, result, "$outcome")
            assertEquals(kept, storage.files.containsKey("x"), "$outcome: entry kept = $kept")
        }
    }

    @Test
    fun `a stamped answer with no entry settles from memory and an unstamped one with no entry settles nothing`() = runTest {
        val a: Boolean = journal.settleRecorded("x", answer, attemptStamp = 3, settle = settleWith(JournalSettleOutcome.LANDED))
        assertTrue(a)
        assertEquals(listOf<Int?>(3), calls)
        calls.clear()
        val b: Boolean = journal.settleRecorded("y", answer, attemptStamp = null, settle = settleWith(JournalSettleOutcome.LANDED))
        assertTrue(b, "an unstamped answer whose entry is gone was handled by a replayer")
        assertEquals(emptyList(), calls)
    }
}
