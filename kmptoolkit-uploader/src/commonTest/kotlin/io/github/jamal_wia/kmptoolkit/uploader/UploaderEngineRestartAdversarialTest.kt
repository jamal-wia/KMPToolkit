package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Re-initialisation and restart hazards of the engine: an engine started and closed many times over one
 * queue, a replaced engine closed after its successor registered, a caller cancelled before, or while, its
 * enqueue is inside the store, foreign cancellations leaking out of a handler, and waiting on a closed engine.
 *
 * Hazard: activity or Application re-creation builds a new engine over the same persisted queue while the
 * old one is torn down in whatever order the framework chooses.
 */
class UploaderEngineRestartAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private enum class Mode { RETRY, BLOCK, SUCCEED }

    @Test
    fun `a hundred start and close cycles leave no child of the parent scope and the last engine drains the queue`() =
        runTest(timeout = 30.seconds) {
            val parentJob = Job()
            try {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parentJob)
            val store = TestUploaderStore()
            val clock = TestClock(0L)
            val network = TestConstraint("network", satisfied = true)
            var mode: Mode = Mode.RETRY
            val handler = TestHandler(
                constraintKeys = setOf("network"),
                onExecute = { _, _ ->
                    when (mode) {
                        Mode.BLOCK -> awaitCancellation()
                        Mode.RETRY -> AttemptResult.Retry(null)
                        Mode.SUCCEED -> AttemptResult.Success
                    }
                },
            )
            val ids = SequentialIds()

            repeat(100) { cycle ->
                // Odd cycles park the drain inside the handler, so the close lands mid-attempt; even cycles
                // fail the attempt, which leaves a backoff alarm armed when the close lands.
                mode = if (cycle % 2 == 1) Mode.BLOCK else Mode.RETRY
                val engine: UploaderEngine = testEngine(
                    store = store,
                    handlers = listOf(handler),
                    scope = scope,
                    clock = clock,
                    constraintProviders = listOf(network),
                    idGenerator = ids::next,
                )
                engine.start()
                engine.enqueue(handler, "payload-$cycle")
                runCurrent()
                assertTrue(
                    parentJob.children.any { it.isActive },
                    "cycle $cycle: the started engine has no running coroutine, so the leak check below would be vacuous",
                )

                engine.close()
                advanceUntilIdle()

                assertEquals(
                    0,
                    parentJob.children.count(),
                    "cycle $cycle: a coroutine of the closed engine is still a child of the parent scope",
                )
            }

            assertTrue(parentJob.isActive, "closing an engine must never cancel the scope it was given")
            assertEquals(100, store.items.size, "every cycle's item is still owed: nothing is lost by a close")

            clock.millis = 1_000_000L // every backoff gate written by the failed cycles has passed
            mode = Mode.SUCCEED
            val last: UploaderEngine = testEngine(
                store = store,
                handlers = listOf(handler),
                scope = scope,
                clock = clock,
                constraintProviders = listOf(network),
                idGenerator = ids::next,
            )
            last.start()
            runCurrent()

            assertEquals(emptyList(), store.items, "the last engine must drain everything the earlier ones left")
            last.close()
            advanceUntilIdle()
            assertEquals(0, parentJob.children.count())
            } finally {
                // not the background scope: a failed assertion must not leave the heartbeat to runTest's cleanup
                parentJob.cancel()
            }
        }

    @Test
    fun `closing a replaced engine leaves the engine that replaced it registered`() = runTest {
        val first: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        val second: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        UploaderEngineRegistry.register(first)
        UploaderEngineRegistry.register(second)

        first.close()

        assertSame(second, UploaderEngineRegistry.current, "the old engine's close evicted its successor")

        second.close()
        assertNull(UploaderEngineRegistry.current, "closing the registered engine clears the slot")

        first.close() // a second, late close of the replaced engine
        assertNull(UploaderEngineRegistry.current)
    }

    @Test
    fun `fifty rotations that register the new engine before closing the old one always end on the new one`() =
        runTest {
            var previous: UploaderEngine? = null
            repeat(50) { round ->
                val next: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
                next.start()
                UploaderEngineRegistry.register(next)

                previous?.close()

                assertSame(next, UploaderEngineRegistry.current, "round $round: the registry lost the engine in use")
                previous = next
            }
            previous?.close()
            assertNull(UploaderEngineRegistry.current)
        }

    @Test
    fun `a caller cancelled before its enqueue reached the store writes nothing and arms no wake`() = runTest {
        for (policy in ConflictPolicy.entries) {
            val store = TestUploaderStore()
            val wake = TestWakeScheduler()
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, wakeScheduler = wake)
            var storeReached = false
            store.beforeNextInsertReplace = { storeReached = true }

            val caller: Job = launch {
                // Cancelled before enqueue starts its write, and still running: the shape of a call made from a
                // scope that was just cleared.
                coroutineContext.job.cancel()
                engine.enqueue(handler, "x", uniqueKey = "k", conflictPolicy = policy)
            }
            caller.join()

            assertTrue(caller.isCancelled, "$policy: the caller should end cancelled")
            assertFalse(storeReached, "$policy: a caller cancelled before the write must not reach the store")
            assertEquals(emptyList(), store.items, "$policy: a caller cancelled before the write writes nothing")
            assertEquals(0, wake.scheduleCount, "$policy: nothing was written, so no wake may be armed")

            val id: String? = engine.enqueue(handler, "y", uniqueKey = "k", conflictPolicy = policy)
            assertTrue(id != null, "$policy: a fresh enqueue under the same key works after the cancelled one")
            engine.drain()
            assertEquals(listOf("y"), handler.payloads, "$policy")
            assertEquals(emptyList(), store.items, "$policy")
            assertFalse(wake.armed, "$policy: the wake is disarmed once the queue is empty")
        }
    }

    @Test
    fun `a caller cancelled while its enqueue is inside the store completes the write and leaves a normal row`() = runTest {
        val store = TestUploaderStore()
        val wake = TestWakeScheduler()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, wakeScheduler = wake)
        val gate = CompletableDeferred<Unit>()
        store.beforeNextInsertReplace = { gate.await() }

        val caller: Job = launch {
            engine.enqueue(handler, "x", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
        }
        runCurrent()
        assertTrue(caller.isActive, "the enqueue should be parked inside the store")

        caller.cancel()
        runCurrent()
        assertFalse(caller.isCompleted, "a write already in progress is not abandoned when its caller is cancelled")

        gate.complete(Unit)
        caller.join()

        assertEquals(listOf("x"), store.items.map { it.payload }, "the started write completes: one row, no half state")
        assertEquals(UploaderItemState.PENDING, store.items.single().state, "the completed write is a normal pending row")
        assertTrue(wake.armed, "a committed row has its wake armed even though its caller was cancelled")

        val id: String? = engine.enqueue(handler, "y", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)
        assertTrue(id != null, "a fresh enqueue under the same key works after the cancelled one")
        engine.drain()
        assertEquals(listOf("y"), handler.payloads, "the later REPLACE superseded the completed row")
        assertEquals(emptyList(), store.items)
        assertFalse(wake.armed, "the wake is disarmed once the queue is empty")
    }

    @Test
    fun `foreign cancellations thrown by handlers never stop the drain or its triggers`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(
            decode = { raw -> if (raw == "bad-decode") throw CancellationException("foreign decode") else raw },
            onExecute = { _, payload ->
                if (payload.startsWith("boom")) throw CancellationException("foreign $payload")
                AttemptResult.Success
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.start()
        runCurrent()

        engine.enqueue(handler, "a")
        engine.enqueue(handler, "boom-1")
        engine.enqueue(handler, "bad-decode")
        engine.enqueue(handler, "boom-2")
        engine.enqueue(handler, "b")
        runCurrent()

        assertTrue(handler.payloads.containsAll(listOf("a", "b")), "items behind a foreign cancellation never ran: ${handler.payloads}")
        assertEquals(
            setOf("boom-1", "boom-2", "bad-decode"),
            store.items.map { it.payload }.toSet(),
            "only the items that threw stay owed",
        )

        engine.enqueue(handler, "c") // the engine must still react to a trigger
        runCurrent()
        assertTrue("c" in handler.payloads, "the engine stopped reacting to triggers after foreign cancellations")
        assertEquals(3, store.items.size)
    }

    /** Counts how often the queue is read, delegating everything else. */
    private class CountingStore(private val delegate: UploaderStore) : UploaderStore by delegate {
        var activeReads: Int = 0
        override suspend fun getAllActive(): List<UploaderItem> {
            activeReads++
            return delegate.getAllActive()
        }
    }

    @Test
    fun `awaitDrained on a closed engine returns false at the timeout after a bounded number of reads`() = runTest {
        val inner = TestUploaderStore()
        val counting = CountingStore(inner)
        val handler = failingHandler()
        val engine: UploaderEngine = testEngine(counting, listOf(handler), backgroundScope)
        engine.enqueue(handler, "stuck")
        engine.drain() // fails once, so the item is gated and stays owed
        engine.close()
        counting.activeReads = 0
        val startedAt: Long = currentTime

        val drained: Boolean = engine.awaitDrained(10.seconds)

        assertFalse(drained, "a stuck queue on a closed engine is not drained")
        assertEquals(10_000L, currentTime - startedAt, "it must return at the timeout, neither earlier nor later")
        val pollInterval: Long = UploaderConfig.DEFAULT_DRAIN_POLL_INTERVAL.inWholeMilliseconds
        assertTrue(
            counting.activeReads <= 10_000L / pollInterval + 2,
            "polled ${counting.activeReads} times; the documented rate is one read per drainPollInterval",
        )
    }

    @Test
    fun `awaitDrained on a closed engine with an empty queue returns true at once`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        engine.close()
        val startedAt: Long = currentTime

        assertTrue(engine.awaitDrained(10.seconds))
        assertEquals(0L, currentTime - startedAt)
    }

    @Test
    fun `a hundred cancelled registry waiters resume nothing and a fresh waiter resumes at once`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        val waiters: List<Deferred<UploaderEngine?>> = List(100) {
            async { UploaderEngineRegistry.await(30.seconds) }
        }
        runCurrent()
        waiters.forEach { it.cancel() }
        runCurrent()

        UploaderEngineRegistry.register(engine)
        val fresh: Deferred<UploaderEngine?> = async { UploaderEngineRegistry.await(1.seconds) }
        runCurrent()

        assertTrue(fresh.isCompleted, "the fresh waiter did not resume when the engine was already registered")
        assertSame(engine, fresh.await())
        assertTrue(waiters.all { it.isCancelled }, "a cancelled waiter must stay cancelled after the registration")
        assertEquals(0L, currentTime, "no waiter may have consumed time")
    }

    @Test
    fun `a waiter registered after the cancelled ones still resumes on a later registration`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        val cancelled: List<Deferred<UploaderEngine?>> = List(100) {
            async { UploaderEngineRegistry.await(30.seconds) }
        }
        val live: Deferred<UploaderEngine?> = async { UploaderEngineRegistry.await(30.seconds) }
        runCurrent()
        cancelled.forEach { it.cancel() }
        advanceTimeBy(5.seconds)

        UploaderEngineRegistry.register(engine)
        runCurrent()

        assertTrue(live.isCompleted)
        assertSame(engine, live.await())
    }

    /** A store whose writes commit and then park their caller, as a cancelled `withContext` return does. */
    private class ParkingAfterCommitStore(
        private val delegate: io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore,
        private val gate: CompletableDeferred<Unit>,
    ) : io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore by delegate {
        override suspend fun insertKeep(record: UploaderItem): Boolean {
            val inserted: Boolean = delegate.insertKeep(record)
            gate.await()
            return inserted
        }
    }

    @Test
    fun `a caller cancelled after its row committed leaves an item that the platform wake still covers and a drain delivers`() =
        runTest {
            val inner = TestUploaderStore()
            val gate = CompletableDeferred<Unit>()
            val wake = TestWakeScheduler()
            val handler = TestHandler()
            val engine: UploaderEngine =
                testEngine(ParkingAfterCommitStore(inner, gate), listOf(handler), backgroundScope, wakeScheduler = wake)

            val caller: Job = launch { engine.enqueue(handler, "x") }
            runCurrent()
            assertEquals(1, inner.items.size, "the row is committed while the caller is parked")
            // The write is non-cancellable once started, so the parked store call returns only when its gate
            // opens; opening it after the cancel is what makes this a cancelled caller seeing a committed write.
            caller.cancel()
            gate.complete(Unit)
            caller.join()

            // Either the enqueue left nothing behind, or what it persisted is covered by a platform wake:
            // a process killed now must still be revived to deliver the committed row.
            assertTrue(
                inner.items.isEmpty() || wake.armed,
                "a committed row (${inner.items.size}) exists but the platform wake was never armed for it",
            )

            engine.start()
            runCurrent()
            assertEquals(listOf("x"), handler.payloads, "the committed row is delivered by the next drain")
            assertEquals(emptyList(), inner.items)
            assertFalse(wake.armed, "the wake is disarmed once the queue is empty")
        }

    @Test
    fun `a caller cancelled after its row committed still triggers the running drain`() = runTest {
        val inner = TestUploaderStore()
        val gate = CompletableDeferred<Unit>()
        val handler = TestHandler()
        val engine: UploaderEngine =
            testEngine(ParkingAfterCommitStore(inner, gate), listOf(handler), backgroundScope)
        engine.start()
        runCurrent()

        val caller: Job = launch { engine.enqueue(handler, "x") }
        runCurrent()
        // Cancel first, open the gate second: the started write is non-cancellable (see the test above).
        caller.cancel()
        gate.complete(Unit)
        caller.join()
        runCurrent()

        assertEquals(listOf("x"), handler.payloads, "the committed row waited for a later trigger instead of draining now")
    }

    @Test
    fun `registering an engine that was already closed never hands it out`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        engine.close()

        UploaderEngineRegistry.register(engine) // a bootstrap thread racing the teardown thread

        assertNull(UploaderEngineRegistry.current, "a closed engine is never handed out, whatever order register and close ran in")
        assertNull(UploaderEngineRegistry.await(1.seconds))
    }

    @Test
    fun `registering an engine that was already closed leaves a previously registered live engine in place`() = runTest {
        val live: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        UploaderEngineRegistry.register(live)
        val closed: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        closed.close()

        UploaderEngineRegistry.register(closed)

        assertSame(live, UploaderEngineRegistry.current, "registering a closed engine must neither be handed out nor evict the live one")
    }
}
