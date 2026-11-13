package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Boss round 5 of unit A1, the two bounds fixed in D-85, attacked on a STARTED engine (the earlier tests drove the
 * re-check cadence with manual drains only):
 * - M1: a parked held row that read absent once is rescued within two heartbeat intervals of the last re-check (P10
 *   KDoc). The heartbeat is the engine's own monotonic delay, the cadence reads the wall clock: the tests set the wall
 *   clock each tick reads, so "a tick that falls just short of one interval" is a value, not a race.
 * - M5: the P11 clause "the pass that owns the attempt sweeps again after it ends or, when that pass is cancelled, the
 *   next trigger or heartbeat does", attacked with an owner pass that ends neither normally nor cancelled: it fails.
 *
 * Deterministic by construction: virtual time for the heartbeat, a hand-driven wall clock, no sleep, no randomness.
 * Every id carries the prefix `b5h-` because the delivered memory is process-wide.
 */
class Boss5HeldRecheckBoundAdversarialTest {

    private val remembered: MutableList<UploaderItem> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        remembered.forEach { DeliveredUnconfirmed.forget(it) }
        remembered.clear()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Answers `null` once for one id, fails a number of deletes, and can fail the next active read once. */
    private class ProbeStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var nullOnceFor: String? = null
        var deleteFailures: Int = 0
        var failNextActiveRead: Boolean = false

        override suspend fun getById(id: String): UploaderItem? {
            if (id == nullOnceFor) {
                nullOnceFor = null
                return null
            }
            return inner.getById(id)
        }

        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }

        override suspend fun getAllActive(): List<UploaderItem> {
            if (failNextActiveRead) {
                failNextActiveRead = false
                throw IllegalStateException("database is locked")
            }
            return inner.getAllActive()
        }
    }

    private class Held(val engine: UploaderEngine, val inner: TestUploaderStore, val id: String, val lastRecheck: Long)

    /**
     * A delivered row whose confirming delete failed (held), then parked by a late verdict, then read as absent once by
     * the started engine's first pass. The last re-check is the setup drain's first sweep, at [Held.lastRecheck].
     */
    private suspend fun TestScope.heldParkedRowReadAbsentOnStart(parent: Job, clock: TestClock, handlers: List<UploaderHandler<*>>): Held {
        val inner = TestUploaderStore()
        val store = ProbeStore(inner)
        val engine: UploaderEngine = testEngine(store, handlers, scopeOn(parent), clock, idGenerator = SequentialIds("b5h-row")::next)
        val plain: TestHandler = handlers.first() as TestHandler
        var id = ""
        val lastRecheck: Long = clock.millis
        launch {
            id = assertNotNull(engine.enqueue(plain, "send-report"))
            store.deleteFailures = 1
            engine.drain()
        }
        testScheduler.runCurrent()
        val row: UploaderItem = assertNotNull(inner.find(id), "setup: the delivered row could not be deleted")
        remembered += row
        assertTrue(inner.park(id, "late verdict", row.state, row.claim), "setup: the row is parked")
        store.nullOnceFor = id
        engine.start()
        testScheduler.runCurrent()
        assertEquals(null, store.nullOnceFor, "setup: the first pass of the started engine read the row as absent")
        assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "setup: the sweep that read null leaves the row alone")
        return Held(engine, inner, id, lastRecheck)
    }

    /** Sets the wall clock the next heartbeat pass reads, then lets that tick and its pass run. */
    private fun TestScope.tick(clock: TestClock, heartbeat: Long, wallMillis: Long) {
        clock.millis = wallMillis
        testScheduler.advanceTimeBy(heartbeat)
        testScheduler.runCurrent()
    }

    @Test
    fun `a tick one heartbeat after the last re-check rescues the parked held row`() = runTest {
        val parent = Job()
        try {
            val clock = TestClock(5_000_000L)
            val heartbeat: Long = UploaderConfig().heartbeatInterval.inWholeMilliseconds
            val handler = TestHandler(type = "b5h.plain")
            val held: Held = heldParkedRowReadAbsentOnStart(parent, clock, listOf(handler))

            tick(clock, heartbeat, held.lastRecheck + heartbeat)

            assertNull(held.inner.find(held.id), "the first tick whose clock reads one full heartbeat after the last re-check confirms the row")
            assertEquals(1, handler.payloads.size, "the rescue only retries the delete")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a tick that falls short of one interval leaves the rescue to the second tick and never a third`() = runTest {
        val heartbeat: Long = UploaderConfig().heartbeatInterval.inWholeMilliseconds
        for (shortBy in listOf(1L, heartbeat / 2, heartbeat - 1)) {
            val parent = Job()
            try {
                val clock = TestClock(5_000_000L)
                val handler = TestHandler(type = "b5h.plain")
                val held: Held = heldParkedRowReadAbsentOnStart(parent, clock, listOf(handler))

                tick(clock, heartbeat, held.lastRecheck + heartbeat - shortBy)
                assertEquals(
                    UploaderItemState.PARKED, held.inner.find(held.id)?.state,
                    "a tick $shortBy ms short of one interval does not re-check",
                )
                tick(clock, heartbeat, held.lastRecheck + 2 * heartbeat - shortBy)

                assertNull(held.inner.find(held.id), "the second tick ($shortBy ms short of two intervals) re-checks and confirms the row")
                assertEquals(1, handler.payloads.size, "the rescue only retries the delete")
            } finally {
                parent.cancel()
                remembered.forEach { DeliveredUnconfirmed.forget(it) }
                remembered.clear()
                UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
            }
        }
    }

    @Test
    fun `an owner pass that fails after the attempt leaves the skipped item to the heartbeat`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = ProbeStore(inner)
            val clock = TestClock(5_000_000L)
            val heartbeat: Long = UploaderConfig().heartbeatInterval.inWholeMilliseconds
            val release = CompletableDeferred<Unit>()
            var runs = 0
            val handler = TestHandler(type = "b5h.m5", retryPolicy = FixedRetryPolicy(delayMillis = 0L), onExecute = { _, _ ->
                runs++
                if (runs == 1) {
                    release.await()
                    // The owner's next read of the queue fails: the manual drain() ends by throwing, neither normally
                    // nor cancelled.
                    store.failNextActiveRead = true
                    AttemptResult.Retry()
                } else {
                    AttemptResult.Success
                }
            })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("b5h-m5")::next)
            var id = ""
            launch { id = assertNotNull(engine.enqueue(handler, "x")) }
            testScheduler.runCurrent()
            var ownerFailure: Throwable? = null
            launch {
                try {
                    engine.drain()
                } catch (e: IllegalStateException) {
                    ownerFailure = e
                }
            }
            testScheduler.runCurrent()
            assertEquals(1, runs, "setup: the manual drain owns the running attempt")
            // The started engine's first pass finds the attempt running and skips the item (P11 BUSY): no gate, no turn.
            engine.start()
            testScheduler.runCurrent()
            assertEquals(1, runs, "P11: the engine's own pass does not start a second attempt")

            release.complete(Unit)
            testScheduler.runCurrent()
            assertNotNull(ownerFailure, "setup: the owner pass ended by throwing the store's failure")
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "setup: the item is still owed")

            testScheduler.advanceTimeBy(heartbeat - 1)
            testScheduler.runCurrent()
            assertEquals(1, runs, "neither pass swept again: the item waits for the heartbeat")
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()

            assertEquals(2, runs, "the heartbeat runs the skipped item, so the P11 bound holds for a failed owner pass too")
            assertNull(inner.find(id), "the second attempt succeeded and its row is gone")
        } finally {
            parent.cancel()
        }
    }
}
