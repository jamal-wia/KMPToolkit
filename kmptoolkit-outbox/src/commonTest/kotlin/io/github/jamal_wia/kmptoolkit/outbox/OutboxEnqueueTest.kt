package io.github.jamal_wia.kmptoolkit.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Enqueueing: what gets persisted, what conflicts do, and what the caller gets back. */
class OutboxEnqueueTest {

    @Test
    fun `enqueue persists the item with the handler's type and encoded payload`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(type = "chat.send")
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)

        val id: String? = engine.enqueue(handler, "hello")

        assertEquals("item-1", id)
        val stored: OutboxItem = assertNotNull(store.find("item-1"))
        assertEquals("chat.send", stored.type)
        assertEquals("hello", stored.payload)
        assertEquals(OutboxItemState.PENDING, stored.state)
        assertEquals(0, stored.attempts)
        assertEquals(0L, stored.nextRunAtEpochMillis)
        assertNull(stored.lastError)
    }

    @Test
    fun `enqueue stamps the handler's schema version onto the item`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(schemaVersion = 4)
        testEngine(store, listOf(handler), backgroundScope).enqueue(handler, "p")

        assertEquals(4, store.find("item-1")?.schemaVersion)
    }

    @Test
    fun `enqueue stamps the creation time from the injected clock`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler()
        val clock = TestClock(millis = 5_000L)
        testEngine(store, listOf(handler), backgroundScope, clock = clock).enqueue(handler, "p")

        assertEquals(5_000L, store.find("item-1")?.createdAtEpochMillis)
    }

    @Test
    fun `enqueue derives the ordering key from the handler once and persists it`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(ordering = { payload -> "thread-$payload" })
        testEngine(store, listOf(handler), backgroundScope).enqueue(handler, "42")

        assertEquals("thread-42", store.find("item-1")?.orderingKey)
    }

    @Test
    fun `enqueue records the unique key and the tag verbatim`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler()
        testEngine(store, listOf(handler), backgroundScope)
            .enqueue(handler, "p", uniqueKey = "u", tag = "session")

        val stored: OutboxItem = assertNotNull(store.find("item-1"))
        assertEquals("u", stored.uniqueKey)
        assertEquals("session", stored.tag)
    }

    @Test
    fun `enqueue runs the insert inside a transaction`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler()
        val runner = CountingTransactionRunner()
        val engine: OutboxEngine =
            testEngine(store, listOf(handler), backgroundScope, transactionRunner = runner)

        engine.enqueue(handler, "p")

        assertEquals(1, runner.transactions)
    }

    @Test
    fun `enqueue arms the platform wake before anything is delivered`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler()
        val wake = TestWakeScheduler()
        testEngine(store, listOf(handler), backgroundScope, wakeScheduler = wake)
            .enqueue(handler, "p")

        assertTrue(wake.armed, "a persisted debt must leave a wake armed even if the process dies")
        assertEquals(1, wake.scheduleCount)
    }

    @Test
    fun `KEEP returns null and inserts nothing when the same key is already pending`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)

        val first: String? = engine.enqueue(handler, "a", uniqueKey = "u")
        val second: String? = engine.enqueue(handler, "b", uniqueKey = "u")

        assertEquals("item-1", first)
        assertNull(second, "a KEEP conflict must report that nothing was queued")
        assertEquals(listOf("item-1"), store.items.map { it.id })
        assertEquals("a", store.find("item-1")?.payload)
    }

    @Test
    fun `KEEP loses to an in-flight item so the effect is not sent twice`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 60_000) })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "a", uniqueKey = "u")
        engine.drain()

        assertEquals(OutboxItemState.IN_FLIGHT, store.find("item-1")?.state)
        assertNull(engine.enqueue(handler, "b", uniqueKey = "u"))
    }

    @Test
    fun `KEEP supersedes a parked item so its key does not stay dead forever`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Park("permanent") })
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "a", uniqueKey = "u")
        engine.drain()
        assertEquals(OutboxItemState.PARKED, store.find("item-1")?.state)

        val revived: String? = engine.enqueue(handler, "b", uniqueKey = "u")

        assertEquals("item-2", revived)
        assertNull(store.find("item-1"), "the parked item must be superseded")
        assertEquals(OutboxItemState.PENDING, store.find("item-2")?.state)
        assertEquals(0, store.find("item-2")?.attempts)
    }

    @Test
    fun `REPLACE supersedes a queued item and resets its retry state`() = runTest {
        val store = TestOutboxStore()
        val handler = failingHandler()
        val clock = TestClock()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "a", uniqueKey = "u")
        engine.drain()
        assertEquals(1, store.find("item-1")?.attempts)

        val replaced: String? =
            engine.enqueue(handler, "b", uniqueKey = "u", conflictPolicy = ConflictPolicy.REPLACE)

        assertEquals("item-2", replaced)
        assertNull(store.find("item-1"))
        val fresh: OutboxItem = assertNotNull(store.find("item-2"))
        assertEquals("b", fresh.payload)
        assertEquals(0, fresh.attempts)
        assertEquals(0L, fresh.nextRunAtEpochMillis)
    }

    @Test
    fun `a null unique key never conflicts however many times it is enqueued`() = runTest {
        val store = TestOutboxStore()
        val handler = TestHandler()
        val engine: OutboxEngine = testEngine(store, listOf(handler), backgroundScope)

        repeat(3) { engine.enqueue(handler, "p") }

        assertEquals(listOf("item-1", "item-2", "item-3"), store.items.map { it.id })
    }

    @Test
    fun `the same unique key under two handler types does not conflict`() = runTest {
        val store = TestOutboxStore()
        val first = TestHandler(type = "one")
        val second = TestHandler(type = "two")
        val engine: OutboxEngine = testEngine(store, listOf(first, second), backgroundScope)

        assertEquals("item-1", engine.enqueue(first, "a", uniqueKey = "u"))
        assertEquals("item-2", engine.enqueue(second, "a", uniqueKey = "u"))
    }

    @Test
    fun `observe emits only the requested type`() = runTest {
        val store = TestOutboxStore()
        val first = TestHandler(type = "one")
        val second = TestHandler(type = "two")
        val engine: OutboxEngine = testEngine(store, listOf(first, second), backgroundScope)
        engine.enqueue(first, "a")
        engine.enqueue(second, "b")

        assertEquals(listOf("item-1"), engine.observe("one").first().map { it.id })
    }

    @Test
    fun `constructing an engine with two handlers of the same type fails`() = runTest {
        val failure: IllegalArgumentException = assertFailsWith {
            testEngine(
                TestOutboxStore(),
                listOf(TestHandler(type = "same"), TestHandler(type = "same")),
                backgroundScope,
            )
        }
        assertTrue(failure.message.orEmpty().contains("same"))
    }

    @Test
    fun `constructing an engine with two constraint providers of the same key fails`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            testEngine(
                TestOutboxStore(),
                listOf(TestHandler()),
                backgroundScope,
                constraintProviders = listOf(TestConstraint("network"), TestConstraint("network")),
            )
        }
    }
}
