package io.github.jamal_wia.kmptoolkit.outbox.testing

import io.github.jamal_wia.kmptoolkit.outbox.AttemptContext
import io.github.jamal_wia.kmptoolkit.outbox.AttemptResult
import io.github.jamal_wia.kmptoolkit.outbox.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.outbox.OutboxHandler
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItem
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItemState
import io.github.jamal_wia.kmptoolkit.outbox.spi.WakeScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** The lightweight doubles: [FakeOutbox], [RecordingWakeScheduler], [MutableConstraintProvider]. */
class FakeOutboxTest {

    @Test
    fun `it records every field of an enqueue call`() = runTest {
        val outbox = FakeOutbox()
        val handler = NoopHandler()

        outbox.enqueue(
            handler = handler,
            payload = "hello",
            uniqueKey = "u",
            tag = "session",
            conflictPolicy = ConflictPolicy.REPLACE,
        )

        val call: FakeOutbox.Enqueued = assertNotNull(outbox.lastEnqueued())
        assertEquals(1, call.ordinal)
        assertSame(handler, call.handler)
        assertEquals("hello", call.payload)
        assertEquals("u", call.uniqueKey)
        assertEquals("session", call.tag)
        assertEquals(ConflictPolicy.REPLACE, call.conflictPolicy)
    }

    @Test
    fun `the recorded payload is the object passed in and not an encoded string`() = runTest {
        val outbox = FakeOutbox()
        val handler = UppercasingHandler()

        outbox.enqueue(handler, "lower")

        assertEquals("lower", outbox.lastEnqueued()?.payload)
    }

    @Test
    fun `the defaults of enqueue are recorded as they are`() = runTest {
        val outbox = FakeOutbox()

        outbox.enqueue(NoopHandler(), "p")

        val call: FakeOutbox.Enqueued = assertNotNull(outbox.lastEnqueued())
        assertNull(call.uniqueKey)
        assertNull(call.tag)
        assertEquals(ConflictPolicy.KEEP, call.conflictPolicy)
    }

    @Test
    fun `each call gets a distinct id by default`() = runTest {
        val outbox = FakeOutbox()
        val handler = NoopHandler()

        val ids: List<String?> = List(3) { outbox.enqueue(handler, "p") }

        assertEquals(listOf("fake-id-1", "fake-id-2", "fake-id-3"), ids)
        assertEquals(3, outbox.enqueued.size)
    }

    @Test
    fun `a custom result can simulate a KEEP conflict`() = runTest {
        val outbox = FakeOutbox { call -> if (call.uniqueKey == "taken") null else "id" }
        val handler = NoopHandler()

        assertNull(outbox.enqueue(handler, "p", uniqueKey = "taken"))
        assertEquals("id", outbox.enqueue(handler, "p", uniqueKey = "free"))
    }

    @Test
    fun `trigger is counted`() = runTest {
        val outbox = FakeOutbox()

        repeat(3) { outbox.trigger() }

        assertEquals(3, outbox.triggerCount)
    }

    @Test
    fun `observe emits what was pushed filtered by type`() = runTest {
        val outbox = FakeOutbox()
        outbox.emitObserved(listOf(item("a", type = "one"), item("b", type = "two")))

        assertEquals(listOf("a"), outbox.observe("one").first().map { it.id })
        assertEquals(emptyList(), outbox.observe("three").first())
    }

    @Test
    fun `reset forgets everything`() = runTest {
        val outbox = FakeOutbox()
        outbox.enqueue(NoopHandler(), "p")
        outbox.trigger()
        outbox.emitObserved(listOf(item("a")))

        outbox.reset()

        assertEquals(emptyList(), outbox.enqueued)
        assertEquals(0, outbox.triggerCount)
        assertEquals(emptyList(), outbox.observe("test").first())
    }

    @Test
    fun `the recording wake scheduler tracks arming and disarming`() {
        val wake: RecordingWakeScheduler = RecordingWakeScheduler()

        wake.scheduleWake()
        wake.scheduleWake()
        assertTrue(wake.isArmed)
        assertEquals(2, wake.scheduleCount)

        wake.cancelWake()
        assertTrue(!wake.isArmed)
        assertEquals(1, wake.cancelCount)

        wake.reset()
        assertEquals(0, wake.scheduleCount)
        assertEquals(0, wake.cancelCount)
        assertTrue(!wake.isArmed)
    }

    @Test
    fun `the no-op wake scheduler does nothing at all`() {
        val wake: WakeScheduler = WakeScheduler.NoOp

        wake.scheduleWake()
        wake.cancelWake()
    }

    @Test
    fun `the mutable constraint provider flips and emits`() = runTest {
        val constraint = MutableConstraintProvider("network", satisfied = false)

        assertEquals("network", constraint.key)
        assertTrue(!constraint.satisfied.value)

        constraint.satisfy()
        assertTrue(constraint.satisfied.value)

        constraint.block()
        assertTrue(!constraint.satisfied.value)

        constraint.set(true)
        assertTrue(constraint.satisfied.first())
    }

    @Test
    fun `the mutable clock moves both ways`() {
        val clock = MutableOutboxClock(initial = 1_000L)

        assertEquals(1_000L, clock.nowEpochMillis())

        clock.advanceBy(500L)
        assertEquals(1_500L, clock.nowEpochMillis())

        clock.advanceBy(-2_000L)
        assertEquals(-500L, clock.nowEpochMillis(), "a backwards jump must be expressible")

        clock.nowMillis = 42L
        assertEquals(42L, clock.nowEpochMillis())
    }

    private fun item(id: String, type: String = "test"): OutboxItem = OutboxItem(
        id = id,
        type = type,
        payload = "p",
        schemaVersion = 1,
        uniqueKey = null,
        orderingKey = null,
        tag = null,
        state = OutboxItemState.PENDING,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 0L,
        lastError = null,
    )

    private class NoopHandler : OutboxHandler<String> {
        override val type: String = "test"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
            AttemptResult.Success
    }

    private class UppercasingHandler : OutboxHandler<String> {
        override val type: String = "test"
        override fun encodePayload(payload: String): String = payload.uppercase()
        override fun decodePayload(raw: String): String = raw.lowercase()
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
            AttemptResult.Success
    }
}
