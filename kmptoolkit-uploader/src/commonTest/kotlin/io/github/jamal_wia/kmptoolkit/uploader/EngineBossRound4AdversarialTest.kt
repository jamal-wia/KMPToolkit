package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 4: the three funnels of boss round 3 (P7 Confirm, P8 Withdraw, P9 Turn) attacked from the paths
 * the round-3 tests did not take: the worker-side preparation's drop, a relaunch that throws at a re-hand, a give-up drop
 * at a first hand-off, a give-up park at a re-hand followed by a wipe, a wipe by an engine built after a crash, two engines
 * over two stores sharing one transport, the park of an orphaned type, a withdrawal of a row held as delivered, and the
 * delivered memory across a clock that jumps from the top of its range to zero.
 */
class EngineBossRound4AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Tracks live transfers; a relaunch throws while [failRelaunch] is set. */
    private class LiveTransport(private val failRelaunch: Boolean = false) : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        val live: MutableSet<String> = mutableSetOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
            live += itemId
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            if (isRehandOff && failRelaunch) {
                events += "relaunch-failed:$itemId"
                throw IllegalStateException("the platform scheduler is unavailable")
            }
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
            live += itemId
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
            live -= itemId
        }
    }

    private class ScriptedUploadHandler(
        transport: UploadTransport,
        override val retryPolicy: RetryPolicy = FixedRetryPolicy(),
        private val prepare: (Int, AttemptContext) -> UploadPreparation,
    ) : UploadHandler<String>(transport) {
        override val type: String = "boss4.scripted"
        override val schemaVersion: Int = 1
        var prepares: Int = 0
        var delivered: Int = 0
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation = prepare(++prepares, context)
        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered++
        }
    }

    private fun proceed(): UploadPreparation =
        UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", "b"))))

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
    fun `a drop answered at the worker side whose delete fails is confirmed without a relaunch`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport) { n, _ -> if (n == 1) proceed() else UploadPreparation.Drop("the server has it") }
            val clock = TestClock(5_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4w")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "photo"))
            engine.drain()
            assertEquals(listOf("launch:$id"), transport.events, "setup: handed off")

            store.deleteFailures = 1
            val attempt: UploadAttempt = (engine as DefaultUploaderEngine).prepareUploadAttempt(id)
            assertEquals(UploadAttempt.EngineUnavailable, attempt, "setup: the worker-side drop could not be written")
            assertNotNull(inner.find(id), "setup: the row is still there")

            clock.millis += 61_000L
            engine.drain()

            assertNull(inner.find(id), "the next turn after the lease only retried the delete (P7)")
            assertEquals(2, handler.prepares, "nothing was prepared again")
            assertEquals(listOf("launch:$id"), transport.events, "nothing was relaunched")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a relaunch that throws at a re-hand and is given up with a drop cancels the earlier transfer`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport(failRelaunch = true)
            val handler = ScriptedUploadHandler(transport, FixedRetryPolicy(giveUp = GiveUpPolicy.DropAfterAttempts(1))) { _, _ -> proceed() }
            val clock = TestClock(5_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4r")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "photo"))
            engine.drain()
            clock.millis += 61_000L

            engine.drain()

            assertNull(store.find(id), "setup: the failed relaunch was given up with a drop")
            assertEquals(listOf("launch:$id", "relaunch-failed:$id", "cancel:$id"), transport.events)
            assertTrue(id !in transport.live, "the earlier claim's transfer of the dropped item is cancelled (P8)")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a first hand-off given up with a drop cancels no upload`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport, FixedRetryPolicy(giveUp = GiveUpPolicy.DropAfterAttempts(1))) { _, _ ->
                throw IllegalStateException("preparation failed")
            }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("bv4m5")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "photo"))

            engine.drain()

            assertNull(store.find(id), "setup: given up with a drop")
            // P8: only a re-hand that drops cancels an earlier claim's upload; a first hand-off has none, and a cancel would
            // erase a transport's outcome journal entry for the id.
            assertEquals(emptyList(), transport.events)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a re-hand given up with a park leaves the earlier transfer alone until a wipe cancels it`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport, FixedRetryPolicy(giveUp = GiveUpPolicy.ParkAfterAttempts(1))) { _, context ->
                if (context.wasDetached) throw IllegalStateException("token refresh failed") else proceed()
            }
            val clock = TestClock(5_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4p")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "photo", tag = "account-1"))
            engine.drain()
            clock.millis += 61_000L
            engine.drain()
            assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "setup: the give-up parked the item")
            assertEquals(listOf("launch:$id"), transport.events, "a give-up park at a re-hand cancels nothing (D-38)")

            engine.cancelByTag("account-1")

            assertEquals(listOf("launch:$id", "cancel:$id"), transport.events, "the wipe cancels the parked item's transfer (P8)")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `an engine built after a crash wipes a row the dead engine parked at its re-hand and cancels its transfer`() = runTest {
        val crashed = Job()
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport) { _, context -> if (context.wasDetached) UploadPreparation.Park("signed out") else proceed() }
            val clock = TestClock(5_000_000L)
            val dead: UploaderEngine = testEngine(store, listOf(handler), scopeOn(crashed), clock, idGenerator = SequentialIds("bv4x")::next)
            val id: String = assertNotNull(dead.enqueue(handler, "photo", tag = "account-1"))
            dead.drain()
            clock.millis += 61_000L
            dead.drain()
            assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "setup: parked at the re-hand")
            crashed.cancel() // the scope ends without close()

            val replacement: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4x2")::next)
            replacement.cancelByTag("account-1")

            assertNull(store.find(id))
            assertEquals(listOf("launch:$id", "cancel:$id"), transport.events, "P8 holds across engines: the replacement cancels the transfer")
        } finally {
            crashed.cancel()
            parent.cancel()
        }
    }

    @Test
    fun `a wipe by tag over one store cancels only that store's transfers when two engines share one transport`() = runTest {
        val parent = Job()
        try {
            val storeA = TestUploaderStore()
            val storeB = TestUploaderStore()
            val transport = LiveTransport()
            val handler = ScriptedUploadHandler(transport) { _, context -> if (context.wasDetached) UploadPreparation.Park("signed out") else proceed() }
            val clock = TestClock(5_000_000L)
            val engineA: UploaderEngine = testEngine(storeA, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4a")::next)
            val engineB: UploaderEngine = testEngine(storeB, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4b")::next)
            val a: String = assertNotNull(engineA.enqueue(handler, "photo-a", tag = "account-1"))
            engineA.drain()
            clock.millis += 61_000L
            engineA.drain() // A's item parks at its re-hand, its transfer still live
            val b: String = assertNotNull(engineB.enqueue(handler, "photo-b", tag = "account-1"))
            engineB.drain() // B's item is handed off under a fresh lease

            engineA.cancelByTag("account-1")

            assertEquals(setOf(b), transport.live, "only A's withdrawn transfer is cancelled")
            assertEquals(UploaderItemState.IN_FLIGHT, storeB.find(b)?.state)
            assertNull(storeA.find(a))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `an orphaned type is parked once and the rest of the queue runs in the same drain`() = runTest {
        val parent = Job()
        try {
            val orphan = UploaderItem(
                id = "bv4o-orphan", type = "boss4.removed", payload = "p", schemaVersion = 1, uniqueKey = null,
                orderingKey = null, tag = null, state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L,
                createdAtEpochMillis = 1L, lastError = null,
            )
            val store = TestUploaderStore(listOf(orphan))
            val handler = TestHandler(type = "boss4.kept")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("bv4o")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "kept"))

            engine.drain()

            assertEquals(UploaderItemState.PARKED, store.find("bv4o-orphan")?.state)
            assertEquals(1L, store.find("bv4o-orphan")?.claim, "parked exactly once (the claim moved from 0 by one guarded write)")
            assertEquals(listOf("kept"), handler.payloads)
            assertNull(store.find(id))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a withdrawal of a row held as delivered keeps the memory and a KEEP of the same key then runs once`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val handler = TestHandler(type = "boss4.du")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("bv4du")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "v1", uniqueKey = "k"))
            store.deleteFailures = 1
            engine.drain()
            val held: UploaderItem = assertNotNull(inner.find(id), "setup: the confirming delete failed")
            assertTrue(DeliveredUnconfirmed.matches(held), "setup: remembered as delivered")

            engine.cancel(id)

            assertNull(inner.find(id))
            assertTrue(DeliveredUnconfirmed.matches(held), "a withdrawal never clears the delivered memory (D-34)")
            val fresh: String = assertNotNull(engine.enqueue(handler, "v2", uniqueKey = "k"))
            engine.drain()
            assertEquals(listOf("v1", "v2"), handler.payloads, "the new item of the key is another item and runs once")
            assertNull(inner.find(fresh))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a delivery remembered at the top of the clock range is confirmed without a run after the clock drops to zero`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val handler = TestHandler(type = "boss4.max")
            val clock = TestClock(Long.MAX_VALUE)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("bv4max")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "once"))
            store.deleteFailures = 1
            engine.drain()
            assertNotNull(inner.find(id), "setup: the confirming delete failed at Long.MAX_VALUE")
            engine.drain() // P9 at MAX: one turn per call, the retry of the delete succeeds now
            assertNull(inner.find(id))

            val second: String = assertNotNull(engine.enqueue(handler, "twice-guard"))
            store.deleteFailures = 1
            engine.drain()
            assertNotNull(inner.find(second), "setup: the second confirming delete failed")
            clock.millis = 0L // a wall clock reset far backwards

            engine.drain()

            assertNull(inner.find(second), "the remembered delivery is confirmed after the jump")
            assertEquals(listOf("once", "twice-guard"), handler.payloads, "no effect ran twice across the jump")
        } finally {
            parent.cancel()
        }
    }
}
