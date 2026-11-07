package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

/**
 * The outcome journal from the ticket's contract: a Completed outcome is kept until the engine settled it,
 * replayed (never uploaded again) through [UploadGateway.complete], deleted when it landed, discarded when
 * expired or written by another format version, and a storage failure is today's behaviour, not an error.
 */
class OutcomeJournalTest {

    private val clock = TestClock(1_000_000L)
    private val storage = MemoryOutcomeStorage()
    private val journal = OutcomeJournal(storage, clock)
    private var engine: UploaderEngine? = null

    @AfterTest
    fun closeEngine() {
        engine?.close()
    }

    private fun response(status: Int = 201, body: String = """{"id":9}""", truncated: Boolean = false) =
        UploadResponse(status, mapOf("Location" to "/files/9", "X-Empty" to ""), body.encodeToByteArray(), truncated)

    // --- The entry itself -------------------------------------------------------------------------------

    @Test
    fun `an entry round-trips status headers body and the truncated flag`() {
        val sent = response(status = 422, body = "café 中", truncated = true)

        assertTrue(journal.record("item-1", sent))

        val entry: JournalEntry.Valid = assertIs(journal.inspect("item-1"))
        assertEquals(sent, entry.response)
        assertEquals(1_000_000L, entry.writtenAtMillis)
    }

    @Test
    fun `an empty body and no headers round-trip`() {
        val sent = UploadResponse(204, emptyMap(), ByteArray(0), false)

        assertTrue(journal.record("item-1", sent))

        assertEquals(sent, assertIs<JournalEntry.Valid>(journal.inspect("item-1")).response)
    }

    @Test
    fun `an item with no entry is absent`() {
        assertEquals(JournalEntry.Absent, journal.inspect("item-1"))
    }

    @Test
    fun `an entry written by another format version is ignored and deleted`() {
        val current: ByteArray = OutcomeCodec.encode(response(), 1_000_000L)
        for (version in listOf(0, 2, OutcomeCodec.VERSION + 1, -1, Int.MAX_VALUE)) {
            val other: ByteArray = current.copyOf()
            other[0] = (version ushr 24).toByte()
            other[1] = (version ushr 16).toByte()
            other[2] = (version ushr 8).toByte()
            other[3] = version.toByte()
            storage.files["item-v"] = other

            assertEquals(JournalEntry.Discarded, journal.inspect("item-v"), "version $version")
            assertNull(storage.files["item-v"], "version $version is deleted")
        }
    }

    @Test
    fun `every cut-short prefix of an entry reads as corrupt and is deleted`() {
        val whole: ByteArray = OutcomeCodec.encode(response(), 1_000_000L)
        for (length in 0 until whole.size) {
            storage.files["item-1"] = whole.copyOf(length)

            assertEquals(JournalEntry.Discarded, journal.inspect("item-1"), "prefix of $length bytes")
            assertNull(storage.files["item-1"])
        }
    }

    @Test
    fun `trailing bytes and absurd lengths read as corrupt`() {
        val whole: ByteArray = OutcomeCodec.encode(response(), 1_000_000L)
        storage.files["a"] = whole + byteArrayOf(1)
        val absurdHeaderCount: ByteArray = whole.copyOf().also { it[17] = 0x7f.toByte() }
        storage.files["b"] = absurdHeaderCount

        assertEquals(JournalEntry.Discarded, journal.inspect("a"))
        assertEquals(JournalEntry.Discarded, journal.inspect("b"))
    }

    @Test
    fun `the body and headers are held to the engine's ceilings`() {
        val huge = UploadResponse(200, emptyMap(), ByteArray(MAX_RESPONSE_BODY_BYTES + 10), false)

        assertTrue(journal.record("item-1", huge))

        val kept: UploadResponse = assertIs<JournalEntry.Valid>(journal.inspect("item-1")).response
        assertEquals(MAX_RESPONSE_BODY_BYTES, kept.body().size)
        assertTrue(kept.isBodyTruncated)
    }

    @Test
    fun `an id that cannot name a file is never journaled`() {
        for (id in listOf("", ".", "..", "a/b", "a\\b", "..\\x", "a b", "a\u0000b")) {
            assertFalse(journal.record(id, response()), "id '$id'")
            assertEquals(JournalEntry.Absent, journal.inspect(id))
        }
        assertTrue(storage.files.isEmpty())
    }

    // --- Expiry -----------------------------------------------------------------------------------------

    @Test
    fun `an entry is replayable up to seven days and discarded after`() {
        journal.record("item-1", response())

        clock.millis += 7.days.inWholeMilliseconds
        assertIs<JournalEntry.Valid>(journal.inspect("item-1"), "exactly seven days is still fresh")

        clock.millis += 1
        assertEquals(JournalEntry.Discarded, journal.inspect("item-1"))
        assertNull(storage.files["item-1"], "expired entry is deleted")
    }

    @Test
    fun `an expired entry is never handed to the engine`() = runTest {
        journal.record("item-1", response())
        clock.millis += 8.days.inWholeMilliseconds
        var calls = 0

        val outcome: ReplayOutcome = journal.replay("item-1") { _, _ -> calls++; true }

        assertEquals(ReplayOutcome.Discarded, outcome)
        assertEquals(0, calls)
    }

    // --- Write failure ----------------------------------------------------------------------------------

    @Test
    fun `a write that fails is reported and never throws`() {
        storage.failWrites = true
        assertFalse(journal.record("item-1", response()))

        storage.failWrites = false
        storage.throwOnWrite = true
        assertFalse(journal.record("item-1", response()))
        assertEquals(JournalEntry.Absent, journal.inspect("item-1"))
    }

    @Test
    fun `a read or delete that throws is swallowed`() {
        journal.record("item-1", response())
        storage.throwOnRead = true
        assertEquals(JournalEntry.Absent, journal.inspect("item-1"))

        storage.throwOnDelete = true
        journal.delete("item-1")
    }

    // --- Replay -----------------------------------------------------------------------------------------

    @Test
    fun `a replay hands the same response to the engine and deletes the entry once it landed`() = runTest {
        val sent: UploadResponse = response()
        journal.record("item-1", sent)
        val seen = mutableListOf<Pair<String, UploadResponse>>()

        val outcome: ReplayOutcome = journal.replay("item-1") { id, r -> seen += id to r; true }

        assertEquals(ReplayOutcome.Replayed, outcome)
        assertEquals(listOf("item-1" to sent), seen)
        assertNull(storage.files["item-1"])
    }

    @Test
    fun `a replay that did not land keeps the entry for the next attempt`() = runTest {
        journal.record("item-1", response())

        assertEquals(ReplayOutcome.Kept, journal.replay("item-1") { _, _ -> false })
        assertIs<JournalEntry.Valid>(journal.inspect("item-1"))

        assertEquals(ReplayOutcome.Replayed, journal.replay("item-1") { _, _ -> true })
        assertEquals(ReplayOutcome.NoEntry, journal.replay("item-1") { _, _ -> error("no entry, nothing to replay") })
    }

    @Test
    fun `a sweep replays what is owed and deletes what is expired or unreadable`() = runTest {
        journal.record("old", response(status = 201))
        storage.files["junk"] = byteArrayOf(1, 2, 3)
        clock.millis += 8.days.inWholeMilliseconds
        journal.record("fresh-b", response(status = 202))
        val replayed = mutableListOf<String>()

        journal.sweep { id, _ -> replayed += id; true }

        assertEquals(listOf("fresh-b"), replayed)
        assertTrue(storage.files.isEmpty(), "everything handled is gone: ${storage.files.keys}")
        assertTrue(storage.purged)
    }

    @Test
    fun `a sweep over a storage that cannot list does nothing and does not throw`() = runTest {
        journal.record("item-1", response())
        storage.throwOnList = true

        journal.sweep { _, _ -> error("not reached") }

        storage.throwOnList = false
        assertIs<JournalEntry.Valid>(journal.inspect("item-1"))
    }

    // --- Against a real engine --------------------------------------------------------------------------

    @Test
    fun `a replay settles the item and delivers the very response that was journaled once`() = runTest {
        val handler = DeliveryRecorder()
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), backgroundScope, clock = TestClock(1_000L))
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain()
        val sent: UploadResponse = response(status = 201, body = """{"job":"j1"}""")
        journal.record("item-1", sent)

        val complete: suspend (String, UploadResponse) -> Boolean = { id, r -> UploadGateway.complete(id, r, 10.milliseconds) }
        assertEquals(ReplayOutcome.Replayed, journal.replay("item-1", complete))

        assertEquals(listOf<UploadResponse?>(sent), handler.delivered)
        assertContentEquals("""{"job":"j1"}""".encodeToByteArray(), handler.delivered.single()?.body())
        // A second replay of the same entry (a delete that failed, a sweep after a worker) delivers nothing again.
        journal.record("item-1", sent)
        assertEquals(ReplayOutcome.Replayed, journal.replay("item-1", complete))
        assertEquals(1, handler.delivered.size, "the item settled, so the stale entry is a no-op")
        assertNull(storage.files["item-1"])
    }

    @Test
    fun `an entry for an item that is gone settles nothing and is deleted`() = runTest {
        val handler = DeliveryRecorder()
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), backgroundScope, clock = TestClock(1_000L))
        engine = started
        UploaderEngineRegistry.register(started)
        journal.record("never-enqueued", response())

        val outcome: ReplayOutcome =
            journal.replay("never-enqueued") { id, r -> UploadGateway.complete(id, r, 10.milliseconds) }

        assertEquals(ReplayOutcome.Replayed, outcome)
        assertTrue(handler.delivered.isEmpty())
        assertNull(storage.files["never-enqueued"])
    }

    @Test
    fun `an entry whose engine is not registered is kept`() = runTest {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        journal.record("item-1", response())

        val outcome: ReplayOutcome = journal.replay("item-1") { id, r -> UploadGateway.complete(id, r, 5.milliseconds) }

        assertEquals(ReplayOutcome.Kept, outcome)
        assertIs<JournalEntry.Valid>(journal.inspect("item-1"))
    }

    private class MemoryOutcomeStorage : OutcomeStorage {
        val files: MutableMap<String, ByteArray> = LinkedHashMap()
        var failWrites = false
        var throwOnWrite = false
        var throwOnRead = false
        var throwOnDelete = false
        var throwOnList = false
        var purged = false

        override fun write(itemId: String, bytes: ByteArray): Boolean {
            if (throwOnWrite) error("disk full")
            if (failWrites) return false
            files[itemId] = bytes.copyOf()
            return true
        }

        override fun read(itemId: String): ByteArray? {
            if (throwOnRead) error("unreadable")
            return files[itemId]?.copyOf()
        }

        override fun delete(itemId: String) {
            if (throwOnDelete) error("undeletable")
            files.remove(itemId)
        }

        override fun itemIds(): List<String> {
            if (throwOnList) error("unlistable")
            return files.keys.toList()
        }

        override fun purgeLeftovers() {
            purged = true
        }
    }

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class DeliveryRecorder : UploadHandler<String>(NoopTransport()) {
        override val type: String = "journal"
        val delivered = mutableListOf<UploadResponse?>()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "https://example.com/u", fields = emptyList()))

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }
}
