package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
        for (version in listOf(0, 3, OutcomeCodec.VERSION_STAMPED + 1, -1, Int.MAX_VALUE)) {
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

        val outcome: ReplayOutcome = journal.replay("item-1") { _, _, _, _ -> calls++; landedIf(true) }

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
        // Not Absent: nothing is known about the entry, and a caller holding the answer must not read it as handled.
        assertEquals(JournalEntry.Unreadable, journal.inspect("item-1"))

        storage.throwOnDelete = true
        journal.delete("item-1")
    }

    // --- Replay -----------------------------------------------------------------------------------------

    @Test
    fun `a replay hands the same response to the engine and deletes the entry once it landed`() = runTest {
        val sent: UploadResponse = response()
        journal.record("item-1", sent)
        val seen = mutableListOf<Pair<String, UploadResponse>>()

        val outcome: ReplayOutcome = journal.replay("item-1") { id, r, _, _ -> seen += id to r; landedIf(true) }

        assertEquals(ReplayOutcome.Replayed, outcome)
        assertEquals(listOf("item-1" to sent), seen)
        assertNull(storage.files["item-1"])
    }

    @Test
    fun `a replay that did not land keeps the entry for the next attempt`() = runTest {
        journal.record("item-1", response())

        assertEquals(ReplayOutcome.Kept, journal.replay("item-1") { _, _, _, _ -> landedIf(false) })
        assertIs<JournalEntry.Valid>(journal.inspect("item-1"))

        assertEquals(ReplayOutcome.Replayed, journal.replay("item-1") { _, _, _, _ -> landedIf(true) })
        assertEquals(ReplayOutcome.NoEntry, journal.replay("item-1") { _, _, _, _ -> error("no entry, nothing to replay") })
    }

    @Test
    fun `a sweep replays what is owed and deletes what is expired or unreadable`() = runTest {
        journal.record("old", response(status = 201))
        storage.files["junk"] = byteArrayOf(1, 2, 3)
        clock.millis += 8.days.inWholeMilliseconds
        journal.record("fresh-b", response(status = 202))
        val replayed = mutableListOf<String>()

        journal.sweep { id, _, _, _ -> replayed += id; landedIf(true) }

        assertEquals(listOf("fresh-b"), replayed)
        assertTrue(storage.files.isEmpty(), "everything handled is gone: ${storage.files.keys}")
        assertTrue(storage.purged)
    }

    @Test
    fun `a sweep over a storage that cannot list does nothing and does not throw`() = runTest {
        journal.record("item-1", response())
        storage.throwOnList = true

        journal.sweep { _, _, _, _ -> error("not reached") }

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

        val complete: OutcomeSettle = gatewaySettle(10.milliseconds)
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
            journal.replay("never-enqueued", gatewaySettle(10.milliseconds))

        assertEquals(ReplayOutcome.Replayed, outcome)
        assertTrue(handler.delivered.isEmpty())
        assertNull(storage.files["never-enqueued"])
    }

    @Test
    fun `an entry whose engine is not registered is kept`() = runTest {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        journal.record("item-1", response())

        val outcome: ReplayOutcome = journal.replay("item-1", gatewaySettle(5.milliseconds))

        assertEquals(ReplayOutcome.Kept, outcome)
        assertIs<JournalEntry.Valid>(journal.inspect("item-1"))
    }

    // --- Review findings: body limit, settle ordering, exclusive replay ---------------------------------

    @Test
    fun `the body is held to the limit given and zero keeps none`() {
        val sent = response(body = "0123456789")

        assertTrue(journal.record("a", sent, bodyLimit = 4))
        assertTrue(journal.record("b", sent, bodyLimit = 0))
        assertTrue(journal.record("c", sent, bodyLimit = Int.MAX_VALUE))
        assertTrue(journal.record("d", sent, bodyLimit = -5))

        assertEquals("0123", assertIs<JournalEntry.Valid>(journal.inspect("a")).response.body().decodeToString())
        assertTrue(assertIs<JournalEntry.Valid>(journal.inspect("a")).response.isBodyTruncated)
        assertEquals(0, assertIs<JournalEntry.Valid>(journal.inspect("b")).response.body().size)
        assertTrue(assertIs<JournalEntry.Valid>(journal.inspect("b")).response.isBodyTruncated)
        assertEquals("0123456789", assertIs<JournalEntry.Valid>(journal.inspect("c")).response.body().decodeToString())
        assertEquals(0, assertIs<JournalEntry.Valid>(journal.inspect("d")).response.body().size)
    }

    @Test
    fun `restricting an entry cuts a longer body and leaves a shorter one`() = runTest {
        journal.record("a", response(body = "0123456789"))
        journal.record("b", response(body = "01"))

        journal.restrictBody("a", 3)
        journal.restrictBody("b", 3)
        journal.restrictBody("missing", 3)

        assertEquals("012", assertIs<JournalEntry.Valid>(journal.inspect("a")).response.body().decodeToString())
        assertEquals("01", assertIs<JournalEntry.Valid>(journal.inspect("b")).response.body().decodeToString())
    }

    @Test
    fun `restricting waits for a replayer holding the item so it cannot bring back a handled entry`() = runTest {
        journal.record("a", response(body = "0123456789"))
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holding = kotlinx.coroutines.CompletableDeferred<Unit>()
        val replayer = launch {
            OutcomeReplayLocks.withItem("a") {
                holding.complete(Unit)
                release.await()
                journal.delete("a") // the replayer settles the entry and deletes it
            }
        }
        holding.await()

        val restricting = launch { journal.restrictBody("a", 3) }
        testScheduler.advanceUntilIdle()
        assertTrue(restricting.isActive, "the cut waits for the replayer instead of reading the entry beside it")

        release.complete(Unit)
        replayer.join()
        restricting.join()

        assertEquals(JournalEntry.Absent, journal.inspect("a"), "the handled entry stays gone")
    }

    @Test
    fun `the entry is gone before the engine commits a retry so a re-hand cannot replay it`() = runTest {
        val handler = DeliveryRecorder(classifier = { SettleResult.Failed(IllegalStateException("retry")) })
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), backgroundScope, clock = TestClock(1_000L))
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain()
        handler.onSettledHook = { handler.entryAtSettled = storage.files.containsKey("item-1") }
        journal.record("item-1", response(status = 503))

        assertEquals(ReplayOutcome.Replayed, journal.replay("item-1", gatewaySettle(10.milliseconds)))

        assertEquals(false, handler.entryAtSettled, "onSettled can run for as long as it likes; the entry must already be gone")
        assertEquals(1, handler.classifiedCount)
    }

    @Test
    fun `two replayers of one entry deliver it once`() = runTest {
        val handler = DeliveryRecorder(deliveryDelay = 100.milliseconds)
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), backgroundScope, clock = TestClock(1_000L))
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain()
        journal.record("item-1", response())
        val other = OutcomeJournal(storage, clock) // a second instance, as the worker and the sweep each have

        val first = async { journal.replay("item-1", gatewaySettle(10.milliseconds)) }
        val second = async { other.replay("item-1", gatewaySettle(10.milliseconds)) }

        assertEquals(setOf(ReplayOutcome.Replayed, ReplayOutcome.NoEntry), setOf(first.await(), second.await()))
        assertEquals(1, handler.delivered.size, "the response is delivered once, not once per replayer")
    }

    @Test
    fun `a recorded response is not settled again when a replayer already handled it`() = runTest {
        var calls = 0
        journal.record("item-1", response())
        journal.delete("item-1") // the sweep replayed and deleted it between the write and the settle

        assertTrue(journal.settleRecorded("item-1", response()) { _, _, _, _ -> calls++; landedIf(true) })
        assertEquals(0, calls)
    }

    // --- Round 2: attempt-stamped entries, the three-way settle outcome -------------------------------------

    private class ProcessDeath : Error()

    @Test
    fun `a stamped entry is never deleted before the settlement returns`() = runTest {
        // The process-death window of an entry that was deleted just before the commit: the stamp lets the entry
        // stay until the commit is durable, so a death anywhere inside the settlement leaves it for the next run.
        journal.record("a", response(), attemptStamp = 0)
        val writesBefore: Int = storage.writes

        assertFailsWith<ProcessDeath> {
            journal.settleRecorded("a", response()) { _, _, _, onSettling -> onSettling(); throw ProcessDeath() }
        }

        assertEquals(0, storage.deletes, "nothing deleted before the settlement returned")
        assertEquals(writesBefore, storage.writes, "no restore was needed")
        assertEquals(0, assertIs<JournalEntry.Valid>(journal.inspect("a")).attemptStamp)

        assertFalse(
            journal.settleRecorded("a", response()) { _, _, _, onSettling -> onSettling(); JournalSettleOutcome.NOT_LANDED },
        )
        assertEquals(0, storage.deletes)
        assertEquals(writesBefore, storage.writes)
        assertEquals(0, assertIs<JournalEntry.Valid>(journal.inspect("a")).attemptStamp)
    }

    @Test
    fun `a stamped entry whose delete failed after the commit replays as stale and is deleted`() = runTest {
        journal.record("a", response(), attemptStamp = 0)
        storage.silentDeletes = 1

        assertTrue(journal.settleRecorded("a", response()) { _, _, _, _ -> JournalSettleOutcome.LANDED })
        assertNotNull(storage.files["a"], "the delete after the commit quietly failed, so a leftover remains")

        val outcome: ReplayOutcome = journal.replay("a") { _, _, stamp, _ ->
            assertEquals(0, stamp)
            JournalSettleOutcome.STALE
        }

        assertEquals(ReplayOutcome.Stale, outcome)
        assertNull(storage.files["a"])
    }

    @Test
    fun `a stamped entry round-trips and an unstamped one reads with no stamp`() {
        val stamped: ByteArray = OutcomeCodec.encode(response(), 5L, attemptStamp = 7)
        val unstamped: ByteArray = OutcomeCodec.encode(response(), 5L)

        assertEquals(OutcomeCodec.VERSION_STAMPED, stamped[3].toInt())
        assertEquals(OutcomeCodec.VERSION_UNSTAMPED, unstamped[3].toInt())
        assertEquals(7, assertIs<DecodedOutcome.Ok>(OutcomeCodec.decode(stamped)).attemptStamp)
        assertNull(assertIs<DecodedOutcome.Ok>(OutcomeCodec.decode(unstamped)).attemptStamp)
        assertEquals(response(), assertIs<DecodedOutcome.Ok>(OutcomeCodec.decode(stamped)).response)
    }

    @Test
    fun `a negative stamp is a damaged entry`() {
        storage.files["a"] = OutcomeCodec.encode(response(), 1_000_000L, attemptStamp = -1)

        assertEquals(JournalEntry.Discarded, journal.inspect("a"))
        assertNull(storage.files["a"])
    }

    @Test
    fun `a body cut keeps the attempt stamp`() = runTest {
        journal.record("a", response(body = "0123456789"), attemptStamp = 4)

        journal.restrictBody("a", 3)

        val entry: JournalEntry.Valid = assertIs(journal.inspect("a"))
        assertEquals("012", entry.response.body().decodeToString())
        assertEquals(4, entry.attemptStamp)
    }

    @Test
    fun `an unstamped entry is written back when the settlement ends in a cancellation after onSettling`() = runTest {
        val writtenAt: Long = clock.millis
        journal.record("a", response())
        clock.millis += 3.days.inWholeMilliseconds

        assertFailsWith<CancellationException> {
            journal.settleRecorded("a", response()) { _, _, _, onSettling -> onSettling(); throw CancellationException("store scope") }
        }

        val back: JournalEntry.Valid = assertIs(journal.inspect("a"))
        assertEquals(writtenAt, back.writtenAtMillis, "the original write time is kept")
        assertNull(back.attemptStamp)
    }

    private class DeleteFaultStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        fun innerStore(): TestUploaderStore = inner
        var failWith: Throwable? = null
        var afterDelete: (() -> Unit)? = null

        override suspend fun deleteById(id: String) {
            failWith?.let { failure ->
                failWith = null
                throw failure
            }
            inner.deleteById(id)
            afterDelete?.invoke()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.startedEngine(
        store: UploaderStore,
        handler: DeliveryRecorder,
        testClock: TestClock,
    ): DefaultUploaderEngine {
        val started: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = testClock)
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain()
        return started as DefaultUploaderEngine
    }

    @Test
    fun `a journaled answer of an attempt the item has moved past is not applied`() = runTest {
        val handler = DeliveryRecorder()
        val testClock = TestClock(1_000L)
        val store = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, testClock)
        var settledCount = 0
        handler.onSettledHook = { settledCount++ }
        assertEquals(0, store.find("item-1")?.attempts)

        assertEquals(JournalSettleOutcome.LANDED, started.settleJournaled("item-1", response(status = 503), 0) { })
        assertEquals(1, store.find("item-1")?.attempts)
        assertEquals(UploaderItemState.PENDING, store.find("item-1")?.state)
        assertEquals(1, settledCount)

        testClock.millis += 3_600_000L
        started.drain()
        assertEquals(UploaderItemState.IN_FLIGHT, store.find("item-1")?.state)
        assertEquals(1, store.find("item-1")?.attempts)

        assertEquals(JournalSettleOutcome.STALE, started.settleJournaled("item-1", response(status = 503), 0) { })
        assertEquals(UploaderItemState.IN_FLIGHT, store.find("item-1")?.state)
        assertEquals(1, store.find("item-1")?.attempts, "the stale failure did not charge the new attempt")
        assertEquals(1, settledCount, "no hook ran for the stale answer")

        assertEquals(JournalSettleOutcome.LANDED, started.settleJournaled("item-1", response(status = 201), 0) { })
        assertNull(store.find("item-1"), "a delivery is a fact and stands from any attempt")
    }

    @Test
    fun `a cancellation the store throws inside the commit is a settlement that did not land`() = runTest {
        val handler = DeliveryRecorder()
        val store = DeleteFaultStore(TestUploaderStore())
        val inner: TestUploaderStore = store.innerStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        store.failWith = CancellationException("the store's own scope")

        val outcome: JournalSettleOutcome = started.settleJournaled("item-1", response(status = 201), 0) { }

        assertEquals(JournalSettleOutcome.NOT_LANDED, outcome)
        assertNotNull(inner.find("item-1"), "nothing was committed")
    }

    @Test
    fun `a stop that lands during the commit still reports the commit as landed`() = runTest {
        val handler = DeliveryRecorder()
        val store = DeleteFaultStore(TestUploaderStore())
        val inner: TestUploaderStore = store.innerStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        handler.onSettledHook = { yield() }
        var caller: Job? = null
        store.afterDelete = { caller?.cancel() }
        var result: Result<JournalSettleOutcome>? = null

        caller = launch { result = runCatching { started.settleJournaled("item-1", response(status = 201), 0) { } } }
        caller.join()

        assertEquals(JournalSettleOutcome.LANDED, result?.getOrNull(), "result: $result")
        assertNull(inner.find("item-1"))
    }

    // --- Round 3 (D-59): one answer per item at a time, settled from memory unless the entry is its own -----

    private val created = UploadResponse(201, emptyMap(), ByteArray(0), false)
    private val failed = UploadResponse(500, emptyMap(), ByteArray(0), false)

    @Test
    fun `an answer whose entry another job overwrote and deleted is still settled from memory`() = runTest {
        val settledStatuses = mutableListOf<Int>()
        val settle: OutcomeSettle = { _, answer, _, _ ->
            settledStatuses += answer.statusCode
            JournalSettleOutcome.LANDED
        }
        assertTrue(journal.record("item-1", created, attemptStamp = 0))
        assertTrue(journal.record("item-1", failed, attemptStamp = 0))

        assertTrue(journal.settleRecorded("item-1", failed, 0, settle = settle))
        assertTrue(journal.settleRecorded("item-1", created, 0, settle = settle))

        assertEquals(listOf(500, 201), settledStatuses, "the 201 is settled although its entry was gone")
        assertEquals(JournalEntry.Absent, journal.inspect("item-1"))
    }

    @Test
    fun `a stamped answer whose entry holds another answer settles its own and leaves the other`() = runTest {
        val settledStatuses = mutableListOf<Int>()
        val settle: OutcomeSettle = { _, answer, _, _ ->
            settledStatuses += answer.statusCode
            JournalSettleOutcome.LANDED
        }
        assertTrue(journal.record("item-1", created, attemptStamp = 0))
        assertTrue(journal.record("item-1", failed, attemptStamp = 0))

        assertTrue(journal.settleRecorded("item-1", created, 0, settle = settle))

        assertEquals(listOf(201), settledStatuses)
        assertEquals(500, assertIs<JournalEntry.Valid>(journal.inspect("item-1")).response.statusCode, "the other answer stays")

        assertTrue(journal.settleRecorded("item-1", failed, 0, settle = settle))

        assertEquals(listOf(201, 500), settledStatuses)
        assertEquals(JournalEntry.Absent, journal.inspect("item-1"))
    }

    @Test
    fun `an unstamped answer whose entry a replayer already handled is not settled again`() = runTest {
        var calls = 0
        assertTrue(journal.record("item-1", created))
        journal.delete("item-1")

        assertTrue(journal.settleRecorded("item-1", created) { _, _, _, _ -> calls++; JournalSettleOutcome.LANDED })

        assertEquals(0, calls)
    }

    @Test
    fun `a stamped answer with no entry that does not land is recorded again with its stamp`() = runTest {
        var calls = 0

        assertFalse(
            journal.settleRecorded("item-1", created, 0) { _, _, _, _ -> calls++; JournalSettleOutcome.NOT_LANDED },
        )

        assertEquals(1, calls)
        assertEquals(0, assertIs<JournalEntry.Valid>(journal.inspect("item-1")).attemptStamp)
    }

    @Test
    fun `exclusively runs a second holder of the same item only after the first`() = runTest {
        val log = mutableListOf<String>()
        val first: Job = launch {
            journal.exclusively("item-1") {
                log += "first start"
                delay(200.milliseconds)
                log += "first end"
            }
        }
        val second: Job = launch {
            journal.exclusively("item-1") { log += "second" }
        }

        first.join()
        second.join()

        assertEquals(listOf("first start", "first end", "second"), log)
        assertEquals(0, OutcomeReplayLocks.activeKeys)
    }

    // --- Round 3: the engine settles an upload once, and runs onSettled only for a commit that applied ------

    private class CommitRefusingStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition,
        ): Boolean = false
    }

    @Test
    fun `a failure report the store rejects at commit runs no onSettled`() = runTest {
        val handler = DeliveryRecorder()
        val inner = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(CommitRefusingStore(inner), handler, TestClock(1_000L))
        val before: UploaderItem? = inner.find("item-1")

        assertTrue(started.settleUpload("item-1", UploadResult.TransportFailure("reset")))

        assertEquals(before, inner.find("item-1"), "the row is unchanged")
        assertEquals(0, handler.settledCount)
    }

    @Test
    fun `eight concurrent failure reports of one attempt charge once and run onSettled once`() = runTest {
        val handler = DeliveryRecorder()
        val store = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        var arrived = 0
        handler.onClassifyHook = {
            arrived++
            withTimeoutOrNull(200) { while (arrived < 8) delay(5) }
        }

        val reports: List<Job> = List(8) {
            launch { started.settleUpload("item-1", UploadResult.TransportFailure("reset")) }
        }
        reports.forEach { it.join() }

        assertEquals(1, store.find("item-1")?.attempts, "one attempt, one charge")
        assertEquals(1, handler.settledCount, "onSettled runs for the one commit that applied")
    }

    @Test
    fun `two concurrent deliveries of one item run onSettled once`() = runTest {
        val handler = DeliveryRecorder()
        val store = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        val secondArrived = CompletableDeferred<Unit>()
        var classified = 0
        handler.onClassifyHook = {
            if (++classified == 2) secondArrived.complete(Unit)
        }
        handler.onDeliveredHook = { withTimeoutOrNull(500) { secondArrived.await() } }

        val first: Job = launch { started.settleUpload("item-1", UploadResult.Completed(201), created) }
        val second: Job = launch { started.settleUpload("item-1", UploadResult.Completed(201), created) }
        first.join()
        second.join()

        assertEquals(1, handler.delivered.size, "onDelivered ran once")
        assertEquals(1, handler.settledCount, "onSettled ran once")
        assertNull(store.find("item-1"))
    }

    @Test
    fun `a delivery whose row is withdrawn before its commit still runs onSettled once`() = runTest {
        val handler = DeliveryRecorder()
        val store = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        handler.onDeliveredHook = { store.deleteById("item-1") }

        assertTrue(started.settleUpload("item-1", UploadResult.Completed(201), created))

        assertEquals(1, handler.delivered.size, "onDelivered ran once")
        assertEquals(1, handler.settledCount, "onSettled pairs with the onDelivered that ran")
        assertNull(store.find("item-1"))
    }

    @Test
    fun `a drop whose row is withdrawn before its commit still runs onSettled once`() = runTest {
        val store = TestUploaderStore()
        val handler = DeliveryRecorder(classifier = { SettleResult.Drop("gone") })
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        handler.onClassifyHook = { store.deleteById("item-1") }

        assertTrue(started.settleUpload("item-1", UploadResult.Completed(404)))

        assertEquals(0, handler.delivered.size, "a drop is not a delivery")
        assertEquals(1, handler.settledCount, "onSettled runs once for the drop")
        assertNull(store.find("item-1"))
    }

    @Test
    fun `a stamped transport failure of an attempt the item has moved past is not charged`() = runTest {
        val handler = DeliveryRecorder()
        val testClock = TestClock(1_000L)
        val store = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, testClock)
        assertEquals(JournalSettleOutcome.LANDED, started.settleJournaled("item-1", response(status = 503), 0) { })
        testClock.millis += 3_600_000L
        started.drain()
        assertEquals(UploaderItemState.IN_FLIGHT, store.find("item-1")?.state)
        assertEquals(1, store.find("item-1")?.attempts)
        val settledBefore: Int = handler.settledCount

        val outcome: JournalSettleOutcome =
            started.settleAttempt("item-1", UploadResult.TransportFailure("late"), null, 0)

        assertEquals(JournalSettleOutcome.STALE, outcome)
        assertEquals(1, store.find("item-1")?.attempts, "the late failure did not charge the new attempt")
        assertEquals(settledBefore, handler.settledCount)
    }

    @Test
    fun `an item deleted while its handler prepares the upload is nothing owed`() = runTest {
        val handler = DeliveryRecorder()
        val store = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        handler.onPrepareHook = { store.deleteById("item-1") }

        val prepared: PreparedUpload = started.prepareStampedUploadAttempt("item-1")

        assertEquals(UploadAttempt.NothingOwed, prepared.attempt)
    }

    @Test
    fun `an item charged while its handler prepares the upload is retried and not uploaded`() = runTest {
        val handler = DeliveryRecorder()
        val store = TestUploaderStore()
        val started: DefaultUploaderEngine = startedEngine(store, handler, TestClock(1_000L))
        handler.onPrepareHook = {
            val row: UploaderItem = store.find("item-1")!!
            assertTrue(
                store.recordFailure(
                    "item-1", row.attempts + 1, 0L, null, row.state, row.claim,
                    io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition.RETRY,
                ),
            )
            val pending: UploaderItem = store.find("item-1")!!
            assertTrue(store.markInFlight("item-1", 60_000L, pending.state, pending.claim))
        }

        val prepared: PreparedUpload = started.prepareStampedUploadAttempt("item-1")

        assertEquals(UploadAttempt.EngineUnavailable, prepared.attempt)
        assertNull(prepared.attemptStamp)
    }

    private class MemoryOutcomeStorage : OutcomeStorage {
        val files: MutableMap<String, ByteArray> = LinkedHashMap()
        var failWrites = false
        var throwOnWrite = false
        var throwOnRead = false
        var throwOnDelete = false
        var throwOnList = false
        var purged = false
        var writes = 0
        var deletes = 0

        /** Deletes that silently do nothing before the next one really deletes (a delete that quietly failed). */
        var silentDeletes = 0

        override fun write(itemId: String, bytes: ByteArray): Boolean {
            if (throwOnWrite) error("disk full")
            if (failWrites) return false
            writes++
            files[itemId] = bytes.copyOf()
            return true
        }

        override fun read(itemId: String): ByteArray? {
            if (throwOnRead) error("unreadable")
            return files[itemId]?.copyOf()
        }

        override fun delete(itemId: String) {
            if (throwOnDelete) error("undeletable")
            if (silentDeletes > 0) {
                silentDeletes--
                return
            }
            deletes++
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

    private class DeliveryRecorder(
        private val classifier: (UploadResult) -> SettleResult = { defaultUploadClassification(it) },
        private val deliveryDelay: Duration = Duration.ZERO,
    ) : UploadHandler<String>(NoopTransport()) {
        override val type: String = "journal"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        val delivered = mutableListOf<UploadResponse?>()
        var classifiedCount = 0
        var entryAtSettled: Boolean? = null
        var onSettledHook: suspend () -> Unit = {}
        var onClassifyHook: suspend () -> Unit = {}
        var onDeliveredHook: suspend () -> Unit = {}
        var onPrepareHook: suspend () -> Unit = {}
        var settledCount = 0

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            classifiedCount++
            onClassifyHook()
            return classifier(result)
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settledCount++
            onSettledHook()
        }

        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            onPrepareHook()
            return UploadPreparation.Proceed(UploadRequest(url = "https://example.com/u", fields = emptyList()))
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            onDeliveredHook()
            if (deliveryDelay > Duration.ZERO) delay(deliveryDelay)
            delivered += response
        }
    }
}
