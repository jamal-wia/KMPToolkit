package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Ordering channels: strict FIFO within one ([UploaderItem.type], [UploaderItem.orderingKey]) pair, and
 * deliberately nothing across pairs.
 */
class UploaderOrderingTest {

    @Test
    fun `items in one channel are delivered oldest first`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(ordering = { "thread" })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "first")
        engine.enqueue(handler, "second")
        engine.enqueue(handler, "third")

        engine.drain()

        assertEquals(listOf("first", "second", "third"), handler.payloads)
    }

    @Test
    fun `a failing channel head blocks the rest of its channel`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(
            ordering = { "thread" },
            onExecute = { _, payload ->
                if (payload == "first") AttemptResult.Retry() else AttemptResult.Success
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "first")
        engine.enqueue(handler, "second")

        engine.drain()

        assertEquals(listOf("first"), handler.payloads, "the tail must wait behind its head")
        assertEquals(UploaderItemState.PENDING, store.find("item-2")?.state)
        assertEquals(0, store.find("item-2")?.attempts, "a blocked item spends no retry budget")
    }

    @Test
    fun `a head that parks releases its channel`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(
            ordering = { "thread" },
            onExecute = { _, payload ->
                if (payload == "first") AttemptResult.Park("permanent") else AttemptResult.Success
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "first")
        engine.enqueue(handler, "second")

        engine.drain()

        assertEquals(listOf("first", "second"), handler.payloads)
        assertEquals(UploaderItemState.PARKED, store.find("item-1")?.state)
    }

    @Test
    fun `separate channels do not block each other`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(
            ordering = { payload -> payload.substringBefore('-') },
            onExecute = { _, payload ->
                if (payload.startsWith("a")) AttemptResult.Retry() else AttemptResult.Success
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "a-1")
        engine.enqueue(handler, "b-1")
        engine.enqueue(handler, "b-2")

        engine.drain()

        assertTrue("b-1" in handler.payloads && "b-2" in handler.payloads)
        assertEquals(1, store.find("item-1")?.attempts, "channel a is retrying on its own")
    }

    @Test
    fun `two handler types sharing a raw ordering key still get separate channels`() = runTest {
        val store = TestUploaderStore()
        val stuck = TestHandler(
            type = "stuck",
            ordering = { "shared" },
            onExecute = { _, _ -> AttemptResult.Retry() },
        )
        val fine = TestHandler(type = "fine", ordering = { "shared" })
        val engine: UploaderEngine = testEngine(store, listOf(stuck, fine), backgroundScope)
        engine.enqueue(stuck, "p")
        engine.enqueue(fine, "q")

        engine.drain()

        assertEquals(1, fine.attempts.size, "one type's backoff must not stall another's queue")
    }

    @Test
    fun `keyless items are all eligible in the same pass`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Retry() })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "a")
        engine.enqueue(handler, "b")

        engine.drain()

        assertEquals(listOf("a", "b"), handler.payloads, "a null ordering key means no ordering")
    }

    @Test
    fun `a replaced item re-enters at the tail of its channel`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(ordering = { "thread" })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "first", uniqueKey = "u")
        engine.enqueue(handler, "second")
        engine.enqueue(handler, "first-v2", uniqueKey = "u", conflictPolicy = ConflictPolicy.REPLACE)

        engine.drain()

        assertEquals(listOf("second", "first-v2"), handler.payloads)
    }

    @Test
    fun `an in-flight head keeps blocking its channel`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(
            ordering = { "thread" },
            onExecute = { _, payload ->
                if (payload == "first") AttemptResult.Detached(leaseMillis = 60_000) else AttemptResult.Success
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "first")
        engine.enqueue(handler, "second")

        engine.drain()

        assertEquals(listOf("first"), handler.payloads, "an in-flight delivery is still the head")
        assertEquals(UploaderItemState.IN_FLIGHT, store.find("item-1")?.state)
        assertEquals(UploaderItemState.PENDING, store.find("item-2")?.state)
    }
}
