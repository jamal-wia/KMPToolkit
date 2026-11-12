package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

/**
 * Round 9 (A2), environment hostility, clock: the outcome journal's expiry (seven days of the wall clock) under a clock
 * that steps back, runs far ahead or reads at the ends of the `Long` range. An entry is dated by the clock that wrote it;
 * a step back must never expire it, and a gap larger than a `Long` can hold is still a gap larger than seven days.
 */
class JournalClockRound9AdversarialTest {

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

    private val response = UploadResponse(201, mapOf("Location" to "/f/1"), "ok".encodeToByteArray(), false)

    @Test
    fun `a clock stepped back never expires an entry and expiry counts from the writing clock`() {
        val clock = TestClock(2_000_000_000_000L)
        val storage = MemoryStorage()
        val journal = OutcomeJournal(storage, clock)
        assertEquals(true, journal.record("x", response))

        for (back in listOf(1L, 3_600_000L, 86_400_000L, 30 * 86_400_000L, 3_650 * 86_400_000L)) {
            clock.millis = 2_000_000_000_000L - back
            assertIs<JournalEntry.Valid>(journal.inspect("x"), "an entry was lost to a clock $back ms behind its writer")
        }
        assertNotNull(storage.files["x"])
        clock.millis = 2_000_000_000_000L + 7.days.inWholeMilliseconds
        assertIs<JournalEntry.Valid>(journal.inspect("x"), "exactly seven days after the writing clock reading")
        clock.millis += 1L
        assertEquals(JournalEntry.Discarded, journal.inspect("x"))
        assertNull(storage.files["x"])
    }

    @Test
    fun `an entry written ahead of the clock that reads it is kept and ages from its own date`() {
        val clock = TestClock(1_000_000L)
        val journal = OutcomeJournal(MemoryStorage(), clock)
        clock.millis = 1_000_000L + 100.days.inWholeMilliseconds
        journal.record("future", response)
        clock.millis = 1_000_000L
        assertIs<JournalEntry.Valid>(journal.inspect("future"))
        clock.millis = 1_000_000L + 106.days.inWholeMilliseconds
        assertIs<JournalEntry.Valid>(journal.inspect("future"), "six days after its writing date it is still fresh")
        clock.millis = 1_000_000L + 108.days.inWholeMilliseconds
        assertEquals(JournalEntry.Discarded, journal.inspect("future"))
    }

    @Test
    fun `expiry at the very end of the range still honours the seven days exactly`() {
        val written = Long.MAX_VALUE - 7.days.inWholeMilliseconds - 10L
        val clock = TestClock(written)
        val journal = OutcomeJournal(MemoryStorage(), clock)
        journal.record("edge", response)
        clock.millis = written + 7.days.inWholeMilliseconds
        assertIs<JournalEntry.Valid>(journal.inspect("edge"))
        clock.millis = written + 7.days.inWholeMilliseconds + 1L
        assertEquals(JournalEntry.Discarded, journal.inspect("edge"))
    }
}
