package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Review finding MAJOR-1 of the A1 split step 1: a delivery whose confirming delete failed on a row that a verdict had
 * already parked (or parks at the same moment) stays held in [DeliveredUnconfirmed], but the drain reads only the
 * active rows, so the delete is never retried and the delivered item shows as a failed one that a retry sends again.
 * Rule P7 says a row held as delivered is confirmed in whatever state it is, PARKED included.
 */
class EngineSplit1HeldRescueAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Fails the next [deleteFailures] deletes (a locked database) and counts every delete it is asked for. */
    private class FlakyDeleteStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var deleteFailures: Int = 0
        var deleteCalls: Int = 0
        override suspend fun deleteById(id: String) {
            deleteCalls++
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    /** Holds every park until [gate] opens: a verdict whose store write is slow. */
    private class GatedParkStore(private val inner: TestUploaderStore, private val gate: CompletableDeferred<Unit>) :
        UploaderStore by inner {
        override suspend fun park(
            id: String,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            gate.await()
            return inner.park(id, lastError, expectedState, expectedClaim)
        }
    }

    private fun recordingHandler(type: String, contexts: MutableList<AttemptContext>): TestHandler = TestHandler(
        type = type,
        onExecute = { context, _ ->
            contexts += context
            AttemptResult.Detached(60_000L)
        },
    )

    /** The first executor goes quiet, its lease runs out and a second one is handed the item: two claims are out. */
    private suspend fun twoExecutors(
        engine: UploaderEngine,
        handler: TestHandler,
        clock: TestClock,
        contexts: List<AttemptContext>,
    ): String {
        val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
        engine.drain()
        clock.millis += 61_000L
        engine.drain()
        assertEquals(2, contexts.size, "setup: two executors hold claims of the item")
        return id
    }

    /** The current executor parks the row, then the first one reports a delivery whose delete fails. */
    private suspend fun parkThenFailedDelivery(
        engine: UploaderEngine,
        store: FlakyDeleteStore,
        inner: TestUploaderStore,
        id: String,
        contexts: List<AttemptContext>,
        delivery: SettleResult,
    ) {
        engine.settle(id, contexts[1].claim, SettleResult.Park("rejected"))
        assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "setup: the verdict parked the row")
        store.deleteFailures = 1
        assertFailsWith<IllegalStateException> { engine.settle(id, contexts[0].claim, delivery) }
        assertHeldAndParked(inner, id)
    }

    private fun assertHeldAndParked(inner: TestUploaderStore, id: String) {
        val row: UploaderItem = assertNotNull(inner.find(id), "setup: the delivered row could not be deleted")
        assertEquals(UploaderItemState.PARKED, row.state, "setup: the row is parked")
        assertTrue(DeliveredUnconfirmed.matches(row), "setup: the row is held as delivered")
    }

    @Test
    fun `R1 a delivery whose delete failed on an already parked row is confirmed by the next drain`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = recordingHandler("split1fix.plain", contexts)
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("s1fix1")::next)
            val id: String = twoExecutors(engine, handler, clock, contexts)
            parkThenFailedDelivery(engine, store, inner, id, contexts, SettleResult.Delivered)

            clock.millis += 61_000L
            engine.drain()

            assertEquals(null, inner.find(id), "the parked row of a delivered item is deleted by the drain (P7)")
            assertEquals(2, handler.payloads.size, "nothing ran again")
            assertEquals(2, contexts.size, "nothing was handed out again")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `R2 a drop whose delete failed on an already parked row is confirmed by the next drain`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = recordingHandler("split1fix.drop", contexts)
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("s1fix2")::next)
            val id: String = twoExecutors(engine, handler, clock, contexts)
            parkThenFailedDelivery(engine, store, inner, id, contexts, SettleResult.Drop("not owed any more"))

            clock.millis += 61_000L
            engine.drain()

            assertEquals(null, inner.find(id), "the parked row of a dropped item is deleted by the drain (P7)")
            assertEquals(2, handler.payloads.size, "nothing ran again")
        } finally {
            parent.cancel()
        }
    }

    /** Parks at the re-hand of an expired lease ("signed out"), proceeds on a first hand-off. */
    private class ParkAtRehandHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "split1fix.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        var delivered: Int = 0
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            if (context.wasDetached) {
                UploadPreparation.Park("signed out")
            } else {
                UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
            }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered++
        }
    }

    private class RecordingTransport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    @Test
    fun `R3 an upload delivery whose commit failed on an already parked row is confirmed by the next drain`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val transport = RecordingTransport()
            val handler = ParkAtRehandHandler(transport)
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("s1fix3")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar-v1", uniqueKey = "avatar"))
            engine.drain()
            clock.millis += 61_000L
            engine.drain()
            assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "setup: the re-hand parked the item")

            store.deleteFailures = 1
            assertFalse(
                (engine as DefaultUploaderEngine).settleUpload(id, UploadResult.Completed(200)),
                "setup: the commit of the earlier transfer's delivery did not land",
            )
            assertHeldAndParked(inner, id)
            assertEquals(1, handler.delivered, "setup: onDelivered ran before the commit")

            clock.millis += 61_000L
            engine.drain()

            assertEquals(null, inner.find(id), "the drain confirmed the delivery of the parked row")
            assertEquals(1, handler.delivered, "onDelivered ran exactly once")
            assertEquals(listOf("launch:$id"), transport.events, "nothing was launched again")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `R4 a delete that keeps failing on a parked held row is tried once per drain call with an alarm kept and the wake disarmed`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = recordingHandler("split1fix.again", contexts)
            val clock = TestClock(3_000_000L)
            val wake = TestWakeScheduler()
            val engine: UploaderEngine = testEngine(
                store, listOf(handler), scopeOn(parent), clock,
                wakeScheduler = wake,
                config = UploaderConfig(heartbeatInterval = 1.hours),
                idGenerator = SequentialIds("s1fix4")::next,
            )
            val id: String = twoExecutors(engine, handler, clock, contexts)
            parkThenFailedDelivery(engine, store, inner, id, contexts, SettleResult.Delivered)
            store.deleteFailures = Int.MAX_VALUE
            store.deleteCalls = 0

            engine.drain()
            assertEquals(1, store.deleteCalls, "one drain call spends one delete on the held row")
            engine.drain()
            assertEquals(1, store.deleteCalls, "its backoff has not passed: the next call leaves it alone")
            clock.millis += 10_000L
            engine.drain()
            assertEquals(2, store.deleteCalls, "once due, one more delete per call")
            assertFalse(wake.armed, "the queue is empty and the memory dies with the process: no platform wake")

            // The engine's own loop keeps retrying through its alarm, with the heartbeat far away.
            store.deleteCalls = 0
            clock.millis += 10_000L
            engine.start()
            runCurrent()
            assertEquals(1, store.deleteCalls, "setup: the first pass of the loop tried the delete once")
            repeat(3) {
                clock.millis += 10_000L
                advanceTimeBy(10_000L)
                runCurrent()
            }
            assertEquals(4, store.deleteCalls, "an alarm stays armed for the held row although the queue is empty")
            assertFalse(wake.armed, "the platform wake stays disarmed")

            store.deleteFailures = 0
            clock.millis += 10_000L
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(null, inner.find(id), "the store recovered: the alarm's pass deleted the row")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `R5 an engine built to replace a closed one confirms a parked held row`() = runTest {
        val first = Job()
        val second = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = recordingHandler("split1fix.replacement", contexts)
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(first), clock, idGenerator = SequentialIds("s1fix5")::next)
            val id: String = twoExecutors(engine, handler, clock, contexts)
            parkThenFailedDelivery(engine, store, inner, id, contexts, SettleResult.Delivered)
            engine.close()

            val replacement: UploaderEngine = testEngine(store, listOf(handler), scopeOn(second), clock, idGenerator = SequentialIds("s1fix5b")::next)
            clock.millis += 61_000L
            replacement.drain()

            assertEquals(null, inner.find(id), "the replacement engine confirmed the delivery of the parked row")
            assertEquals(2, handler.payloads.size, "nothing ran again")
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    private fun parkedRow(id: String, type: String, payload: String): UploaderItem = UploaderItem(
        id = id,
        type = type,
        payload = payload,
        schemaVersion = 1,
        uniqueKey = null,
        orderingKey = null,
        tag = null,
        state = UploaderItemState.PARKED,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 5L,
        lastError = "parked",
        claim = 3L,
    )

    @Test
    fun `R6 a held entry whose row is gone or belongs to another store is skipped and never forgotten`() = runTest {
        val parent = Job()
        try {
            val foreign: UploaderItem = parkedRow("s1fix6-foreign", "split1fix.skip", "another store")
            val ours: UploaderItem = parkedRow("s1fix6-foreign", "split1fix.skip", "our own row")
            val inner = TestUploaderStore(listOf(ours))
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = recordingHandler("split1fix.skip", contexts)
            val quick = TestHandler(type = "split1fix.skip.quick")
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler, quick), scopeOn(parent), clock, idGenerator = SequentialIds("s1fix6")::next)

            // An entry of another store: same id, another fingerprint.
            DeliveredUnconfirmed.remember(foreign)
            // An entry whose row was withdrawn: a held delivery, then a cancel (D-34 leaks the entry on purpose).
            val withdrawnId: String = assertNotNull(engine.enqueue(quick, "withdrawn"))
            store.deleteFailures = 1
            engine.drain()
            val withdrawn: UploaderItem = assertNotNull(inner.find(withdrawnId), "setup: the delivered row could not be deleted")
            assertTrue(DeliveredUnconfirmed.matches(withdrawn), "setup: the row is held as delivered")
            engine.cancel(withdrawnId)
            assertEquals(null, inner.find(withdrawnId), "setup: the withdrawal removed the row")
            // A real parked held row beside them, which the sweep has to find and confirm.
            val rescuedId: String = twoExecutors(engine, handler, clock, contexts)
            parkThenFailedDelivery(engine, store, inner, rescuedId, contexts, SettleResult.Delivered)

            clock.millis += 61_000L
            engine.drain()

            assertEquals(null, inner.find(rescuedId), "the parked held row is confirmed")
            assertEquals(ours, inner.find(ours.id), "the row of the other store's entry is untouched")
            assertTrue(DeliveredUnconfirmed.matches(foreign), "the other store's entry is still there")
            assertTrue(DeliveredUnconfirmed.matches(withdrawn), "the entry of the withdrawn row is still there")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `R7 a verdict still being written when a delivery's delete fails does not leave the delivered row parked`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val gate = CompletableDeferred<Unit>()
            val store = FlakyDeleteStore(inner)
            val gated = GatedParkStore(inner, gate)
            // One store behind both views: the delete fault comes from [store], the slow park from [gated].
            val view: UploaderStore = object : UploaderStore by gated {
                override suspend fun deleteById(id: String) = store.deleteById(id)
            }
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = recordingHandler("split1fix.race", contexts)
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(view, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("s1fix7")::next)
            val id: String = twoExecutors(engine, handler, clock, contexts)

            val verdict: Job = launch { engine.settle(id, contexts[1].claim, SettleResult.Park("rejected")) }
            runCurrent()
            assertTrue(verdict.isActive, "setup: the verdict is parked inside the store write")
            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { engine.settle(id, contexts[0].claim, SettleResult.Delivered) }
            gate.complete(Unit)
            withTimeout(5_000L) { verdict.join() }
            assertHeldAndParked(inner, id)

            clock.millis += 61_000L
            engine.drain()

            assertEquals(null, inner.find(id), "the row the verdict parked under the delivery is deleted by the drain")
            assertEquals(2, handler.payloads.size, "nothing ran again")
        } finally {
            parent.cancel()
        }
    }
}
