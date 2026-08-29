package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** The lightweight doubles: [FakeUploader], [RecordingWakeScheduler], [MutableConstraintProvider]. */
class FakeUploaderTest {

    @Test
    fun `it records every field of an enqueue call`() = runTest {
        val uploader = FakeUploader()
        val handler = NoopHandler()

        uploader.enqueue(
            handler = handler,
            payload = "hello",
            uniqueKey = "u",
            tag = "session",
            conflictPolicy = ConflictPolicy.REPLACE,
        )

        val call: FakeUploader.Enqueued = assertNotNull(uploader.lastEnqueued())
        assertEquals(1, call.ordinal)
        assertSame(handler, call.handler)
        assertEquals("hello", call.payload)
        assertEquals("u", call.uniqueKey)
        assertEquals("session", call.tag)
        assertEquals(ConflictPolicy.REPLACE, call.conflictPolicy)
    }

    @Test
    fun `the recorded payload is the object passed in and not an encoded string`() = runTest {
        val uploader = FakeUploader()
        val handler = UppercasingHandler()

        uploader.enqueue(handler, "lower")

        assertEquals("lower", uploader.lastEnqueued()?.payload)
    }

    @Test
    fun `the defaults of enqueue are recorded as they are`() = runTest {
        val uploader = FakeUploader()

        uploader.enqueue(NoopHandler(), "p")

        val call: FakeUploader.Enqueued = assertNotNull(uploader.lastEnqueued())
        assertNull(call.uniqueKey)
        assertNull(call.tag)
        assertEquals(ConflictPolicy.KEEP, call.conflictPolicy)
    }

    @Test
    fun `each call gets a distinct id by default`() = runTest {
        val uploader = FakeUploader()
        val handler = NoopHandler()

        val ids: List<String?> = List(3) { uploader.enqueue(handler, "p") }

        assertEquals(listOf("fake-id-1", "fake-id-2", "fake-id-3"), ids)
        assertEquals(3, uploader.enqueued.size)
    }

    @Test
    fun `a custom result can simulate a KEEP conflict`() = runTest {
        val uploader = FakeUploader { call -> if (call.uniqueKey == "taken") null else "id" }
        val handler = NoopHandler()

        assertNull(uploader.enqueue(handler, "p", uniqueKey = "taken"))
        assertEquals("id", uploader.enqueue(handler, "p", uniqueKey = "free"))
    }

    @Test
    fun `trigger is counted`() = runTest {
        val uploader = FakeUploader()

        repeat(3) { uploader.trigger() }

        assertEquals(3, uploader.triggerCount)
    }

    @Test
    fun `observe emits what was pushed filtered by type`() = runTest {
        val uploader = FakeUploader()
        uploader.emitObserved(listOf(item("a", type = "one"), item("b", type = "two")))

        assertEquals(listOf("a"), uploader.observe("one").first().map { it.id })
        assertEquals(emptyList(), uploader.observe("three").first())
    }

    @Test
    fun `reset forgets everything`() = runTest {
        val uploader = FakeUploader()
        uploader.enqueue(NoopHandler(), "p")
        uploader.trigger()
        uploader.emitObserved(listOf(item("a")))

        uploader.reset()

        assertEquals(emptyList(), uploader.enqueued)
        assertEquals(0, uploader.triggerCount)
        assertEquals(emptyList(), uploader.observe("test").first())
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
        val clock = MutableUploaderClock(initial = 1_000L)

        assertEquals(1_000L, clock.nowEpochMillis())

        clock.advanceBy(500L)
        assertEquals(1_500L, clock.nowEpochMillis())

        clock.advanceBy(-2_000L)
        assertEquals(-500L, clock.nowEpochMillis(), "a backwards jump must be expressible")

        clock.nowMillis = 42L
        assertEquals(42L, clock.nowEpochMillis())
    }

    private fun item(id: String, type: String = "test"): UploaderItem = UploaderItem(
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
        createdAtEpochMillis = 0L,
        lastError = null,
    )

    private class NoopHandler : UploaderHandler<String> {
        override val type: String = "test"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
            AttemptResult.Success
    }

    private class UppercasingHandler : UploaderHandler<String> {
        override val type: String = "test"
        override fun encodePayload(payload: String): String = payload.uppercase()
        override fun decodePayload(raw: String): String = raw.lowercase()
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
            AttemptResult.Success
    }
}
