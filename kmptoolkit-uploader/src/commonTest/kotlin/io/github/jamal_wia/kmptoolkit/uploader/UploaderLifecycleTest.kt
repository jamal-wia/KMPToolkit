package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Starting, triggering, waking, closing — and the constraint gating that decides whether an item is
 * even eligible.
 *
 * Tests that start the engine never call `advanceUntilIdle`: the heartbeat loops forever, so
 * "advance until there is nothing left to do" never returns. They use `runCurrent` and bounded
 * `advanceTimeBy` instead.
 */
class UploaderLifecycleTest {

    @Test
    fun `start drains what a previous process left behind`() = runTest {
        val handler = TestHandler()
        val store = TestUploaderStore(
            listOf(pendingItem(id = "leftover", type = handler.type, payload = "from-yesterday")),
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)

        engine.start()
        runCurrent()

        assertEquals(listOf("from-yesterday"), handler.payloads)
        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `start is idempotent`() = runTest {
        val handler = TestHandler()
        val store = TestUploaderStore(listOf(pendingItem(id = "a", type = handler.type)))
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)

        engine.start()
        engine.start()
        engine.start()
        runCurrent()

        assertEquals(1, handler.attempts.size, "a repeated start must not add a second drain")
    }

    @Test
    fun `enqueue on a started engine delivers without an explicit drain`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.start()
        runCurrent()

        engine.enqueue(handler, "p")
        runCurrent()

        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `a backoff gate re-triggers the drain on its own alarm`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        var failFirstOnly = true
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 1_000L),
            onExecute = { _, _ ->
                if (failFirstOnly) {
                    failFirstOnly = false
                    AttemptResult.Retry()
                } else {
                    AttemptResult.Success
                }
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.start()
        engine.enqueue(handler, "p")
        runCurrent()
        assertEquals(1, handler.attempts.size)

        // Both clocks move: the coroutine clock releases the alarm's delay, the wall clock opens
        // the persisted gate. They are separate on purpose.
        clock.millis = 1_000L
        advanceTimeBy(1_100.milliseconds)
        runCurrent()

        assertEquals(2, handler.attempts.size, "the alarm must re-trigger the drain at the gate")
        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `the heartbeat re-triggers the drain even with no other signal`() = runTest {
        val handler = TestHandler()
        val store = TestUploaderStore()
        val engine: UploaderEngine = testEngine(
            store,
            listOf(handler),
            backgroundScope,
            config = UploaderConfig(heartbeatInterval = 10.seconds),
        )
        engine.start()
        runCurrent()

        // An item appears in the store without going through enqueue — the shape of a second
        // process, or a store written to directly. Only the heartbeat can notice it.
        store.insertKeep(pendingItem(id = "sneaked", type = handler.type))
        advanceTimeBy(11.seconds)
        runCurrent()

        assertEquals(1, handler.attempts.size)
    }

    @Test
    fun `the drain survives a transient store failure and retries on the next trigger`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.start()
        runCurrent()

        store.failNextGetAllActive = IllegalStateException("database is locked")
        engine.enqueue(handler, "p")
        runCurrent()
        assertEquals(0, handler.attempts.size, "this pass failed")

        engine.trigger()
        runCurrent()

        assertEquals(1, handler.attempts.size, "the drain coroutine must still be alive")
    }

    @Test
    fun `an empty queue disarms the platform wake`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val wake = TestWakeScheduler()
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, wakeScheduler = wake)
        engine.enqueue(handler, "p")
        assertTrue(wake.armed)

        engine.drain()

        assertFalse(wake.armed, "nothing is owed, so nothing should wake the app")
        assertEquals(1, wake.cancelCount)
    }

    @Test
    fun `a queue left gated keeps the platform wake armed`() = runTest {
        val store = TestUploaderStore()
        val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 60_000L))
        val wake = TestWakeScheduler()
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, wakeScheduler = wake)
        engine.enqueue(handler, "p")

        engine.drain()

        assertTrue(wake.armed, "a killed process must still get to deliver what is owed")
        assertEquals(0, wake.cancelCount)
    }

    @Test
    fun `awaitDrained returns true once the queue empties`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.start()
        engine.enqueue(handler, "p")

        assertTrue(engine.awaitDrained(5.seconds))
    }

    @Test
    fun `awaitDrained returns false when the queue is stuck`() = runTest {
        val store = TestUploaderStore()
        val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 600_000L))
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.start()
        engine.enqueue(handler, "p")

        assertFalse(engine.awaitDrained(2.seconds))
    }

    @Test
    fun `awaitDrained counts an in-flight item as not drained`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 600_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.start()
        engine.enqueue(handler, "p")

        assertFalse(
            engine.awaitDrained(2.seconds),
            "the effect is still owed until the executor confirms",
        )
    }

    @Test
    fun `close stops the drain and is idempotent`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.start()
        runCurrent()

        engine.close()
        engine.close()

        engine.enqueue(handler, "p")
        runCurrent()
        assertEquals(0, handler.attempts.size, "a closed engine must not deliver")
        assertNotNull(store.find("item-1"), "but what was enqueued is still owed")
    }

    @Test
    fun `close on an engine that was never started is safe`() = runTest {
        testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope).close()
    }

    @Test
    fun `start after close does nothing`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.close()

        engine.start()
        engine.enqueue(handler, "p")
        runCurrent()

        assertEquals(0, handler.attempts.size)
    }

    @Test
    fun `close unregisters the engine only if it is the registered one`() = runTest {
        val first: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        val second: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        UploaderEngineRegistry.register(first)
        UploaderEngineRegistry.register(second)

        first.close()

        assertEquals(second, UploaderEngineRegistry.current, "a late close must not evict the new one")
        second.close()
        assertEquals(null, UploaderEngineRegistry.current)
    }

    @Test
    fun `a gate further ahead than the policy can reach is treated as a clock jump`() = runTest {
        val handler = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1_000L))
        // The gate was written before the wall clock was moved back a day.
        val store = TestUploaderStore(
            listOf(pendingItem(id = "a", type = handler.type).copy(nextRunAtEpochMillis = 86_400_000L)),
        )
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, clock = TestClock(millis = 0L))

        engine.drain()

        assertEquals(1, handler.attempts.size, "a corrupt gate must not freeze the item for a day")
    }

    @Test
    fun `a gate within the policy's reach is honored`() = runTest {
        val handler = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1_000L))
        val store = TestUploaderStore(
            listOf(pendingItem(id = "a", type = handler.type).copy(nextRunAtEpochMillis = 1_500L)),
        )
        val engine: UploaderEngine =
            testEngine(store, listOf(handler), backgroundScope, clock = TestClock(millis = 0L))

        engine.drain()

        assertEquals(0, handler.attempts.size, "1500ms is within maxDelay x clockAnomalyFactor")
    }

    private fun pendingItem(
        id: String,
        type: String,
        payload: String = "payload",
    ): UploaderItem = UploaderItem(
        id = id,
        type = type,
        payload = payload,
        schemaVersion = 1,
        uniqueKey = null,
        orderingKey = null,
        tag = null,
        state = UploaderItemState.PENDING,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 0L,
        lastError = null,
    )
}
