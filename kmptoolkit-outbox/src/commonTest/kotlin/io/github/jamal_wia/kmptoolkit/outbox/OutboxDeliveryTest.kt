package io.github.jamal_wia.kmptoolkit.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/** What each [AttemptResult] does to the queue, and how failures are paced and given up on. */
class OutboxDeliveryTest {

    @Test
    fun `a delivered item is deleted and the handler sees a first attempt`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "hello")

        engine.drain()

        assertEquals(emptyList(), store.items)
        assertEquals(1, handler.attempts.size)
        assertEquals(0, handler.attempts.single().attempts)
        assertEquals("item-1", handler.attempts.single().id)
        assertEquals(listOf("hello"), handler.payloads)
    }

    @Test
    fun `the handler receives the item's unique and ordering keys`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(ordering = { "channel" })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p", uniqueKey = "u")

        engine.drain()

        val context: AttemptContext = handler.attempts.single()
        assertEquals("u", context.uniqueKey)
        assertEquals("channel", context.orderingKey)
        assertTrue(!context.wasDetached)
    }

    @Test
    fun `a dropped item is deleted just like a delivered one`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Drop("obsolete") })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")

        engine.drain()

        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `a parked item is kept with its reason and is no longer attempted`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Park("permanent 400") })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")

        engine.drain()
        engine.drain()

        val parked: OutboxItem = assertNotNull(store.find("item-1"))
        assertEquals(OutboxItemState.PARKED, parked.state)
        assertEquals("permanent 400", parked.lastError)
        assertEquals(1, handler.attempts.size, "a parked item must not be attempted again")
    }

    @Test
    fun `a retry bumps the attempt counter and writes a backoff gate from the clock`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 5_000L))
        val clock = TestClock(millis = 1_000L)
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")

        engine.drain()

        val item: OutboxItem = assertNotNull(store.find("item-1"))
        assertEquals(OutboxItemState.PENDING, item.state)
        assertEquals(1, item.attempts)
        assertEquals(6_000L, item.nextRunAtEpochMillis)
    }

    @Test
    fun `a gated item is skipped until its backoff gate passes`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 5_000L))
        val clock = TestClock(millis = 0L)
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")

        engine.drain()
        assertEquals(1, handler.attempts.size)

        clock.millis = 4_999L
        engine.drain()
        assertEquals(1, handler.attempts.size, "the gate has not passed yet")

        clock.millis = 5_000L
        engine.drain()
        assertEquals(2, handler.attempts.size, "the gate has passed")
        assertEquals(2, store.find("item-1")?.attempts)
    }

    @Test
    fun `the attempt count the handler sees is the persisted one`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1L))
        val clock = TestClock()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")

        repeat(3) {
            engine.drain()
            clock.millis += 10L
        }

        assertEquals(listOf(0, 1, 2), handler.attempts.map { it.attempts })
    }

    @Test
    fun `the failure cause is stored on the item`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler(cause = IllegalStateException("socket closed"))
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")

        engine.drain()

        val lastError: String = assertNotNull(store.find("item-1")?.lastError)
        assertTrue(lastError.contains("socket closed"), "got: $lastError")
    }

    @Test
    fun `an exception thrown by the handler is treated as a retry`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(onExecute = { _, _ -> error("boom") })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")

        engine.drain()

        assertEquals(1, store.find("item-1")?.attempts)
        assertEquals(OutboxItemState.PENDING, store.find("item-1")?.state)
    }

    @Test
    fun `a cancellation leaked by the handler's own timeout is a retry not a stalled queue`() =
        runTest {
            val store = TestOutboxStore()
            // withTimeout throws TimeoutCancellationException, a CancellationException. The engine
            // must tell that apart from its own scope being cancelled.
            val handler = TestHandler(
                onExecute = { _, _ ->
                    withTimeout(timeMillis = 1) {
                        delay(1_000) // the network call that never came back
                        AttemptResult.Success
                    }
                },
            )
            val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
            engine.enqueue(handler, "p")

            engine.drain()

            assertEquals(1, store.find("item-1")?.attempts)
            assertEquals(OutboxItemState.PENDING, store.find("item-1")?.state)
        }

    @Test
    fun `ParkAfterAttempts parks the item once the budget is spent and keeps it`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler(
            retryPolicy = FixedRetryPolicy(
                delayMillis = 1L,
                giveUp = GiveUpPolicy.ParkAfterAttempts(maxAttempts = 3),
            ),
        )
        val clock = TestClock()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")

        repeat(5) {
            engine.drain()
            clock.millis += 10L
        }

        assertEquals(3, handler.attempts.size, "no attempt may happen after the give-up fired")
        val parked: OutboxItem = assertNotNull(store.find("item-1"))
        assertEquals(OutboxItemState.PARKED, parked.state)
        assertEquals(3, parked.attempts)
    }

    @Test
    fun `DropAfterAttempts deletes the item once the budget is spent`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler(
            retryPolicy = FixedRetryPolicy(
                delayMillis = 1L,
                giveUp = GiveUpPolicy.DropAfterAttempts(maxAttempts = 2),
            ),
        )
        val clock = TestClock()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")

        repeat(4) {
            engine.drain()
            clock.millis += 10L
        }

        assertEquals(2, handler.attempts.size)
        assertNull(store.find("item-1"))
    }

    @Test
    fun `GiveUpPolicy Never keeps retrying past any attempt count`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1L))
        val clock = TestClock()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")

        repeat(10) {
            engine.drain()
            clock.millis += 10L
        }

        assertEquals(10, handler.attempts.size)
        assertEquals(OutboxItemState.PENDING, store.find("item-1")?.state)
    }

    @Test
    fun `an item whose type has no handler is parked rather than retried forever`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(type = "known")
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")
        // Simulate a downgrade: the row survives, the handler that understood it does not.
        val orphaned: OutboxEngine = testEngine(store, emptyList(), backgroundScope)

        orphaned.drain()

        val parked: OutboxItem = assertNotNull(store.find("item-1"))
        assertEquals(OutboxItemState.PARKED, parked.state)
        assertTrue(parked.lastError.orEmpty().contains("known"))
    }

    @Test
    fun `an undecodable payload is parked and never deleted`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(decode = { error("corrupt json") })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "{bad")

        engine.drain()

        val parked: OutboxItem = assertNotNull(store.find("item-1"))
        assertEquals(OutboxItemState.PARKED, parked.state)
        assertTrue(parked.lastError.orEmpty().contains("corrupt json"))
        assertEquals(0, handler.attempts.size, "a payload that will not decode is never executed")
    }

    @Test
    fun `an item written by a newer schema is parked instead of mis-decoded`() = runTest {
        val store = TestOutboxStore()
        val newer = TestHandler(schemaVersion = 5)
        val engine: OutboxEngine = testEngine(store, listOf(newer), backgroundScope)
        engine.enqueue(newer, "p")
        // The user downgraded: the same type is now served by an older handler.
        val older = TestHandler(schemaVersion = 2)
        val downgraded: OutboxEngine = testEngine(store, listOf(older), backgroundScope)

        downgraded.drain()

        assertEquals(OutboxItemState.PARKED, store.find("item-1")?.state)
        assertEquals(0, older.attempts.size)
    }

    @Test
    fun `an item written by an older schema is still delivered`() = runTest {
        val store = TestOutboxStore()
        val older = TestHandler(schemaVersion = 1)
        val engine: OutboxEngine = testEngine(store, listOf(older), backgroundScope)
        engine.enqueue(older, "p")
        val newer = TestHandler(schemaVersion = 3)
        val upgraded: OutboxEngine = testEngine(store, listOf(newer), backgroundScope)

        upgraded.drain()

        assertEquals(1, newer.attempts.size)
        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `a drain pass keeps going after one item parks`() = runTest {
        val store = TestOutboxStore()
        val poison = TestHandler(type = "poison", decode = { error("nope") })
        val good = TestHandler(type = "good")
        val engine: OutboxEngine = testEngine(store, listOf(poison, good), backgroundScope)
        engine.enqueue(poison, "p")
        engine.enqueue(good, "q")

        engine.drain()

        assertEquals(OutboxItemState.PARKED, store.find("item-1")?.state)
        assertEquals(1, good.attempts.size, "one poison item must not stop the rest of the queue")
    }

    @Test
    fun `a cancelled drain propagates instead of being swallowed as a retry`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(onExecute = { _, _ -> throw CancellationException("scope gone") })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")

        // The drain coroutine is this test's, and it is still active, so the engine's ensureActive()
        // does not throw — the leaked cancellation is correctly downgraded to a retry.
        engine.drain()

        assertEquals(1, store.find("item-1")?.attempts)
    }
}
