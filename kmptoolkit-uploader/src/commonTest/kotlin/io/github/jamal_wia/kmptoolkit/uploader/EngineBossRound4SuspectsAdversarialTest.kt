package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * A1 final boss round 4, candidate defects.
 *
 * Group bv4-S1: rule P8 (Withdraw) names three paths that remove a row (cancel, cancelByTag, REPLACE), but a fourth one
 * removes rows too: a KEEP enqueue whose key is held by a PARKED item supersedes that item (ConflictPolicy: "Both
 * policies treat a PARKED conflict the same way"). The engine's KEEP branch never learns which row it removed
 * (`insertKeep` returns a Boolean), so it neither stops the attempt the engine runs for it nor cancels its upload,
 * while a REPLACE of the very same row does both.
 *
 * Group bv4-S2: rule P9 (Turn) says an item's turn is "decided in one place in drainPass, before any work on the item",
 * but the park of an item whose type has no handler runs before that check and counts as executed. With a store whose
 * list read keeps disagreeing with its guarded writes (the case the drain's own "moved" guard exists for), one drain()
 * repeats that park in a loop.
 *
 * Group bv4-S3: rule P7 (Confirm) promises that a delivery whose delete failed is deleted at a later pass and never
 * handed out again. A Park verdict on the attempt the engine still runs (settled by the current executor, or answered by
 * a running re-hand attempt) is applied to such a row like to any other: the row leaves rotation as PARKED, the drain
 * never visits it, and the delivered item shows as a failed one that a retry (a KEEP or REPLACE enqueue) sends again.
 */
class EngineBossRound4SuspectsAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Tracks which transfers are live: launched and not cancelled since. */
    private class LiveTransport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        val live: MutableSet<String> = mutableSetOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
            live += itemId
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
            live += itemId
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
            live -= itemId
        }
    }

    /** Proceeds on a first hand-off and parks at a re-hand of an expired lease ("signed out"). */
    private class ParkAtRehandHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "boss4.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        var delivered: MutableList<String> = mutableListOf()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            if (context.wasDetached) {
                UploadPreparation.Park("signed out")
            } else {
                UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
            }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += payload
        }
    }

    /** Hands an upload off, lets its lease run out with the transfer still uploading, and lets the re-hand park it. */
    private suspend fun parkedWithLiveTransfer(
        engine: UploaderEngine,
        handler: ParkAtRehandHandler,
        transport: LiveTransport,
        clock: TestClock,
        store: TestUploaderStore,
    ): String {
        val id: String = assertNotNull(engine.enqueue(handler, "avatar-v1", uniqueKey = "avatar"))
        engine.drain()
        assertEquals(listOf("launch:$id"), transport.events, "setup: the first hand-off launched the transfer")
        clock.millis += 61_000L
        engine.drain()
        assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "setup: the re-hand parked the item")
        assertTrue(id in transport.live, "setup: a park at a re-hand leaves the earlier transfer running (D-38)")
        return id
    }

    @Test
    fun `bv4-S1a a KEEP that supersedes an item parked at its re-hand cancels that item's transfer`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ParkAtRehandHandler(transport)
            val clock = TestClock(2_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4s1a")::next)
            val id: String = parkedWithLiveTransfer(engine, handler, transport, clock, store)

            val fresh: String = assertNotNull(
                engine.enqueue(handler, "avatar-v2", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.KEEP),
                "setup: a KEEP over a parked key supersedes it (ConflictPolicy)",
            )

            assertTrue(fresh != id)
            assertEquals(null, store.find(id), "setup: the KEEP removed the parked row")
            assertTrue(
                id !in transport.live,
                "the superseded version keeps uploading next to the new one after a KEEP (a REPLACE of the same row cancels it): ${transport.events}",
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bv4-S1 control a REPLACE of the same parked item cancels its transfer`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ParkAtRehandHandler(transport)
            val clock = TestClock(2_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4s1k")::next)
            val id: String = parkedWithLiveTransfer(engine, handler, transport, clock, store)

            assertNotNull(engine.enqueue(handler, "avatar-v2", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.REPLACE))

            assertTrue(id !in transport.live, "a REPLACE cancels the parked item's transfer (P8): ${transport.events}")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bv4-S1b after a KEEP over a parked item both versions are uploaded and the old delivery is lost`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ParkAtRehandHandler(transport)
            val clock = TestClock(2_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4s1b")::next)
            val id: String = parkedWithLiveTransfer(engine, handler, transport, clock, store)
            val fresh: String = assertNotNull(engine.enqueue(handler, "avatar-v2", uniqueKey = "avatar"))

            engine.drain() // the new version is handed off
            val oldStillUploading: Boolean = id in transport.live
            // The old transfer finishes: its row is gone, so its delivery settles nothing.
            (engine as DefaultUploaderEngine).settleUpload(id, UploadResult.Completed(200))

            assertTrue(fresh in transport.live, "setup: the new version was handed off")
            assertFalse(
                oldStillUploading,
                "a KEEP that superseded a parked item left its old version uploading next to the new one " +
                    "(events ${transport.events}, deliveries seen ${handler.delivered})",
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bv4-S1c a KEEP that supersedes a parked item stops the attempt the engine still runs for it`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            var rehandCancelled = false
            var first: AttemptContext? = null
            val handler = TestHandler(type = "boss4.plain", onExecute = { context, _ ->
                calls++
                if (calls == 1) {
                    first = context
                    AttemptResult.Detached(60_000L)
                } else {
                    try {
                        gate.await() // the re-hand's send is under way
                    } catch (e: CancellationException) {
                        rehandCancelled = true
                        throw e
                    }
                    AttemptResult.Success
                }
            })
            val clock = TestClock(2_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4s1c")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "message-v1", uniqueKey = "msg"))
            engine.drain()
            val earlier: AttemptContext = assertNotNull(first, "setup: handed off")
            clock.millis += 61_000L
            val drain: Job = launch { engine.drain() }
            runCurrent()
            assertEquals(2, calls, "setup: the re-hand attempt is running")
            // The earlier executor reports a permanent failure while the re-hand runs: the row parks.
            engine.settle(id, earlier.claim, SettleResult.Park("rejected by the server"))
            assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "setup: the row is parked while its re-hand attempt runs")

            assertNotNull(engine.enqueue(handler, "message-v2", uniqueKey = "msg", conflictPolicy = ConflictPolicy.KEEP))
            runCurrent()
            val stopped: Boolean = rehandCancelled
            gate.complete(Unit)
            withTimeout(5_000L) { drain.join() }

            assertTrue(
                stopped,
                "a KEEP that superseded the parked row left the attempt of the old version running (a REPLACE stops it, P8)",
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bv4-S1c control a REPLACE of the same parked item stops its running attempt`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            var rehandCancelled = false
            var first: AttemptContext? = null
            val handler = TestHandler(type = "boss4.plain", onExecute = { context, _ ->
                calls++
                if (calls == 1) {
                    first = context
                    AttemptResult.Detached(60_000L)
                } else {
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        rehandCancelled = true
                        throw e
                    }
                    AttemptResult.Success
                }
            })
            val clock = TestClock(2_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4s1d")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "message-v1", uniqueKey = "msg"))
            engine.drain()
            val earlier: AttemptContext = assertNotNull(first)
            clock.millis += 61_000L
            val drain: Job = launch { engine.drain() }
            runCurrent()
            engine.settle(id, earlier.claim, SettleResult.Park("rejected by the server"))

            assertNotNull(engine.enqueue(handler, "message-v2", uniqueKey = "msg", conflictPolicy = ConflictPolicy.REPLACE))
            runCurrent()
            val stopped: Boolean = rehandCancelled
            gate.complete(Unit)
            withTimeout(5_000L) { drain.join() }

            assertTrue(stopped, "a REPLACE stops the superseded item's running attempt")
        } finally {
            parent.cancel()
        }
    }

    /** Fails the next [deleteFailures] deletes: a locked database. */
    private class FlakyDeleteStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var deleteFailures: Int = 0
        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    @Test
    fun `bv4-S3a a park settled after a delivery whose delete failed does not keep the delivered row parked`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = TestHandler(type = "boss4.verdict", onExecute = { context, _ ->
                contexts += context
                AttemptResult.Detached(60_000L)
            })
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4s3a")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            engine.drain()
            clock.millis += 61_000L
            engine.drain() // the first executor went quiet: re-handed to a second one
            assertEquals(2, contexts.size, "setup: two executors hold claims of the item")

            // The first executor delivered (a fact, it stands from any claim) but the delete failed.
            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { engine.settle(id, contexts[0].claim, SettleResult.Delivered) }
            // The second, current executor then reports a permanent failure of its own attempt.
            engine.settle(id, contexts[1].claim, SettleResult.Park("rejected"))
            clock.millis += 61_000L
            engine.drain()
            engine.drain()

            assertEquals(2, handler.payloads.size, "setup: nothing ran again")
            assertEquals(
                null, inner.find(id)?.state,
                "the engine was told the effect was delivered (settle KDoc: the row is deleted at a later pass), yet the row " +
                    "stays parked as a failed item that a retry would send again",
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bv4-S3b a re-hand attempt that parks after a delivery whose delete failed does not keep the delivered row parked`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val gate = CompletableDeferred<Unit>()
            var first: AttemptContext? = null
            var calls = 0
            val handler = TestHandler(type = "boss4.verdict2", onExecute = { context, _ ->
                calls++
                if (calls == 1) {
                    first = context
                    AttemptResult.Detached(60_000L)
                } else {
                    gate.await()
                    AttemptResult.Park("the server rejected the re-send")
                }
            })
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4s3b")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            engine.drain()
            val earlier: AttemptContext = assertNotNull(first)
            clock.millis += 61_000L
            val drain: Job = launch { engine.drain() }
            runCurrent()
            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { engine.settle(id, earlier.claim, SettleResult.Delivered) }
            gate.complete(Unit)
            withTimeout(5_000L) { drain.join() }
            clock.millis += 61_000L
            engine.drain()

            assertEquals(
                null, inner.find(id)?.state,
                "a delivered row whose delete failed was parked by a later verdict and is never confirmed (P7)",
            )
        } finally {
            parent.cancel()
        }
    }

    /**
     * A store whose list read serves the claim an item had before its last guarded write, for the first [staleReads]
     * list reads: point reads and guarded writes see the real row. Bounded, so the test cannot hang.
     */
    private class StaleListStore(private val inner: TestUploaderStore, private val staleId: String, var staleReads: Int) :
        UploaderStore by inner {
        var listReads: Int = 0
        var parks: Int = 0
        override suspend fun getAllActive(): List<UploaderItem> {
            listReads++
            val active: List<UploaderItem> = inner.getAllActive()
            if (staleReads <= 0) return active
            staleReads--
            return active.map { if (it.id == staleId) it.copy(claim = it.claim - 1) else it }
        }

        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            parks++
            return inner.park(id, lastError, expectedState, expectedClaim)
        }
    }

    private fun orphan(id: String, type: String): UploaderItem = UploaderItem(
        id = id,
        type = type,
        payload = "p",
        schemaVersion = 1,
        uniqueKey = null,
        orderingKey = null,
        tag = null,
        state = UploaderItemState.PENDING,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 1L,
        lastError = null,
        claim = 1L,
    )

    @Test
    fun `bv4-S2a one drain does not repeat the park of an item with no handler when the list read is stale`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore(listOf(orphan("bv4s2a-1", "boss4.gone")))
            val store = StaleListStore(inner, "bv4s2a-1", staleReads = 500)
            val engine: UploaderEngine = testEngine(store, listOf(TestHandler(type = "boss4.other")), scopeOn(parent), TestClock(2_000_000L))

            engine.drain()

            assertTrue(
                store.parks <= 2,
                "one drain() repeated the orphan park ${store.parks} times (${store.listReads} list reads); P9 gives an item one turn per call",
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `bv4-S2 control one drain gives up on an attempt the stale list keeps offering`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore(listOf(orphan("bv4s2k-1", "boss4.here")))
            val store = StaleListStore(inner, "bv4s2k-1", staleReads = 500)
            val handler = TestHandler(type = "boss4.here")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(2_000_000L))

            engine.drain()

            assertTrue(store.listReads <= 4, "the drain's moved guard stops after two skips: ${store.listReads} list reads")
            assertEquals(0, handler.payloads.size, "a row the point read disagrees with is never attempted")
        } finally {
            parent.cancel()
        }
    }
}
