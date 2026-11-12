package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

/** R9-S1 (fixed): journal expiry `now - writtenAt > retention` overflows for a gap larger than a Long. */
class JournalExpiryOverflowRound9Test {

    private class MemoryStorage : OutcomeStorage {
        val files: MutableMap<String, ByteArray> = LinkedHashMap()
        override fun write(itemId: String, bytes: ByteArray): Boolean { files[itemId] = bytes.copyOf(); return true }
        override fun read(itemId: String): ByteArray? = files[itemId]?.copyOf()
        override fun delete(itemId: String) { files.remove(itemId) }
        override fun itemIds(): List<String> = files.keys.sorted()
        override fun purgeLeftovers() = Unit
    }

    private val response = UploadResponse(201, mapOf("Location" to "/f/1"), "ok".encodeToByteArray(), false)

    @Test
    fun `an entry older than a Long can count is expired, not made fresh by an overflowing subtraction`() {
        for ((written: Long, read: Long) in listOf(
            -1L to Long.MAX_VALUE,
            Long.MIN_VALUE to 0L,
            Long.MIN_VALUE to 1_700_000_000_000L,
            Long.MIN_VALUE to Long.MAX_VALUE,
            -5_000_000_000_000L to Long.MAX_VALUE - 1_000L,
        )) {
            val clock = TestClock(written)
            val journal = OutcomeJournal(MemoryStorage(), clock)
            journal.record("old", response)
            clock.millis = read
            val entry: JournalEntry = journal.inspect("old")
            assertEquals(
                JournalEntry.Discarded,
                entry,
                "written at clock $written, read at $read: more than seven days later, yet the entry reads as fresh",
            )
        }
    }
}
