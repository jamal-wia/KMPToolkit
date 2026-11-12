package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Round 9 (A2), mutation survivors found by changing the engine by hand and running the existing suites: a stale
 * stamped DROP is a fact and still applies; progress reaches only an item that is in flight; a failure outside the
 * handler waits out its in-memory backoff exactly up to the heartbeat when the clock steps back.
 */
class EngineSurvivorsRound9AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class NoTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class RecordingHandler : UploadHandler<String>(NoTransport()) {
        override val type: String = "r9.surv"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        val progress: MutableList<Float> = mutableListOf()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = emptyList()))

        override suspend fun onUploadProgress(payload: String, fraction: Float) {
            progress += fraction
        }
    }

    private fun row(id: String, state: UploaderItemState, attempts: Int, type: String = "r9.surv"): UploaderItem = UploaderItem(
        id = id, type = type, payload = "p", schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
        state = state, attempts = attempts, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
        leaseUntilEpochMillis = if (state == UploaderItemState.IN_FLIGHT) Long.MAX_VALUE / 4 else 0L, claim = 1L,
    )

    @Test
    fun `a stamped drop of an attempt the item has moved past still deletes it while a stale failure is not charged`() = runTest {
        val store = TestUploaderStore(listOf(row("drop", UploaderItemState.IN_FLIGHT, attempts = 2), row("fail", UploaderItemState.IN_FLIGHT, attempts = 2)))
        val handler = RecordingHandler()
        val engine = testEngine(store, listOf(handler), backgroundScope) as DefaultUploaderEngine
        try {
            val dropped: JournalSettleOutcome =
                engine.settleAttempt("drop", UploadResult.Completed(404), UploadResponse(404, emptyMap(), ByteArray(0), false), attemptStamp = 1)
            val failed: JournalSettleOutcome =
                engine.settleAttempt("fail", UploadResult.Completed(503), UploadResponse(503, emptyMap(), ByteArray(0), false), attemptStamp = 1)

            assertEquals(JournalSettleOutcome.LANDED, dropped, "a drop is a fact about the server's answer and stands whatever attempt it was")
            assertNull(store.find("drop"), "the item the server rejected for good is still queued")
            assertEquals(JournalSettleOutcome.STALE, failed)
            assertEquals(2, store.find("fail")?.attempts, "a stale failure was charged to the new attempt")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `progress reaches the handler only while the item is in flight`() = runTest {
        val store = TestUploaderStore(
            listOf(
                row("flying", UploaderItemState.IN_FLIGHT, 0),
                row("waiting", UploaderItemState.PENDING, 0),
                row("parked", UploaderItemState.PARKED, 0),
            ),
        )
        val handler = RecordingHandler()
        val engine = testEngine(store, listOf(handler), backgroundScope) as DefaultUploaderEngine
        try {
            engine.reportUploadProgress("waiting", 0.25f)
            engine.reportUploadProgress("parked", 0.5f)
            engine.reportUploadProgress("gone", 0.75f)
            assertEquals(emptyList(), handler.progress, "progress was delivered for an item that is not uploading")
            engine.reportUploadProgress("flying", 1f)
            assertEquals(listOf(1f), handler.progress)
        } finally {
            engine.close()
        }
    }

    /** Fails the first [recordFailure] after it is armed, as a store that loses a write after the effect ran. */
    private class ThrowOnceStore(private val inner: UploaderStore) : UploaderStore by inner {
        var armed: Boolean = true
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean {
            if (armed) {
                armed = false
                error("disk full")
            }
            return inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }
    }

    @Test
    fun `a failure outside the handler waits out its backoff until the clock is exactly a heartbeat behind it and not beyond`() = runTest {
        val heartbeat: Long = UploaderConfig().heartbeatInterval.inWholeMilliseconds
        val floor: Long = UploaderConfig().minAlarmDelay.inWholeMilliseconds
        for ((offset: Long, shouldRun: Boolean) in listOf(0L to false, 1L to true, -1L to false)) {
            val clock = TestClock(1_000_000L)
            val inner = TestUploaderStore()
            val store = ThrowOnceStore(inner)
            val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Retry() })
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock)
            try {
                engine.enqueue(handler, "x")
                engine.drain()
                assertEquals(1, handler.payloads.size, "the first attempt")
                // The in-memory gate is now `t0 + floor`; step the clock back so that it is `heartbeat + offset` ahead.
                clock.millis = 1_000_000L + floor - (heartbeat + offset)
                engine.drain()
                assertEquals(if (shouldRun) 2 else 1, handler.payloads.size, "offset $offset: the item ran = ${handler.payloads.size == 2}")
            } finally {
                engine.close()
            }
        }
    }
}
