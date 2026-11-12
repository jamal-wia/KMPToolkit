package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploaderConfig
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 9 (mutation survivors): behaviours that a hand-made mutant of the engine left every earlier test green on, found by
 * deleting the `trigger()` of `cancel`, and the `PARKED` exclusion of a REPLACE (that exclusion was removed in 4.1.0, A1 boss round 3, rule P8;
 * the test now pins the cancel for a parked item too).
 */
class EngineSurvivorsRound9AdversarialTest {

    private class Failing : UploaderHandler<String> {
        override val type: String = "s9"
        override val schemaVersion: Int = 1
        override val retryPolicy = io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy()
        override val constraintKeys: Set<String> = emptySet()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
            AttemptResult.Retry(IllegalStateException("down"))
    }

    private class Upload(transport: RecordingUploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "u9"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "https://example.com/u", headers = emptyMap(), fields = emptyList()))
    }

    @Test
    fun `cancelling the last owed item disarms the platform wake at once and without any other trigger`() = runTest {
        val wake = RecordingWakeScheduler()
        val engine = createUploaderEngine(
            InMemoryUploaderStore(), listOf(Failing()), backgroundScope,
            wakeScheduler = wake, config = UploaderConfig(heartbeatInterval = 10.hours), clock = MutableUploaderClock(1_000_000L),
        )
        engine.start()
        runCurrent()
        val id: String = assertNotNull(engine.enqueue(Failing(), "x"))
        runCurrent() // the pass fails the item into a backoff gate: still owed, wake armed
        assertTrue(wake.isArmed, "an owed item keeps the wake armed")

        engine.cancel(id)
        runCurrent()

        assertFalse(wake.isArmed, "cancel is documented to let the drain disarm the wake of an emptied queue")
        engine.close()
    }

    @Test
    fun `a REPLACE cancels the upload of the item it supersedes whether parked or live`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = Upload(transport)
        fun row(id: String, key: String, state: UploaderItemState) = UploaderItem(
            id = id, type = "u9", payload = id, schemaVersion = 1, uniqueKey = key, orderingKey = null, tag = null, state = state,
            attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 1L, lastError = null,
        )
        val store = InMemoryUploaderStore(listOf(row("parked", "kp", UploaderItemState.PARKED), row("live", "kl", UploaderItemState.PENDING)))
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, config = UploaderConfig(heartbeatInterval = 10.hours))

        engine.enqueue(handler, "p2", uniqueKey = "kp", conflictPolicy = ConflictPolicy.REPLACE)
        assertEquals(listOf("parked"), transport.cancels, "a parked item can still have an earlier claim's upload running, so a REPLACE cancels it as cancel(id) would")
        engine.enqueue(handler, "l2", uniqueKey = "kl", conflictPolicy = ConflictPolicy.REPLACE)
        assertEquals(listOf("parked", "live"), transport.cancels)
        engine.close()
    }

    @Test
    fun `every enqueue arms the wake also one that a KEEP conflict turned away`() = runTest {
        val wake = RecordingWakeScheduler()
        val handler = Failing()
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope, wakeScheduler = wake)

        engine.enqueue(handler, "a", uniqueKey = "k")
        val afterFirst: Int = wake.scheduleCount
        assertTrue(afterFirst >= 1, "the enqueue arms the platform wake before any drain runs")
        assertEquals(null, engine.enqueue(handler, "b", uniqueKey = "k"))
        assertTrue(wake.scheduleCount > afterFirst, "a rejected KEEP still re-arms the wake (the row it kept is owed)")
        engine.close()
    }
}
