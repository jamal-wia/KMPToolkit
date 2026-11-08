package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Round 6 (contract lawyer + resource starvation): what holds of the close and withdrawal clauses against the engine as
 * D-67 left it: a stopped worker is not charged on a store that notices its caller, a withdrawal hung in its write
 * leaves close alone, and the journal's entry bound covers the largest response. The contract breaks found in the same
 * pass are in adversarial/A1/suspects/r6-*. Every test is single-threaded on a virtual clock and bounded by `runTest`'s
 * default timeout; each one waits only on gates that the test itself completes.
 */
class UploaderEngineContractRound6AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 15 * 60_000L
        override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class UploadingHandler(
        transport: UploadTransport,
        private val prepare: suspend () -> Unit = {},
    ) : UploadHandler<String>(transport) {
        override val type: String = "r6.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            prepare()
            return UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)



    private class InterruptedUploadHandler(transport: UploadTransport, private val gate: CompletableDeferred<Unit>) :
        UploadHandler<String>(transport) {
        override val type: String = "r6.interrupted"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            try {
                gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // What an HTTP client does when the call it was reading is cancelled.
                throw IllegalStateException("socket closed", e)
            }
            return UploadPreparation.Drop("unreachable")
        }
    }

    /** Every write first looks at its caller, as a store that hops to a database thread does. */
    private class NoticingStore(private val inner: UploaderStore) : UploaderStore by inner {
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean {
            yield()
            return inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }
    }

    private fun inFlightRow(): UploaderItem = UploaderItem(
        id = "w1",
        type = "r6.interrupted",
        payload = "p",
        schemaVersion = 1,
        uniqueKey = null,
        orderingKey = null,
        tag = null,
        state = UploaderItemState.IN_FLIGHT,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 0L,
        lastError = null,
        leaseUntilEpochMillis = 10_000_000L,
        claim = 1L,
    )

    private fun TestScope.workerPrepareCancelled(wrap: (UploaderStore) -> UploaderStore): Int {
        val parent = Job()
        try {
            val inner = TestUploaderStore(listOf(inFlightRow()))
            val gate = CompletableDeferred<Unit>()
            val handler = InterruptedUploadHandler(Transport(), gate)
            val engine = testEngine(wrap(inner), listOf(handler), scopeOn(parent), TestClock(1_000L)) as DefaultUploaderEngine
            val caller: Job = launch { engine.prepareUploadAttempt("w1") }
            runCurrent()
            caller.cancel() // the platform stops the worker while its handler prepares the request
            runCurrent()
            return assertNotNull(inner.find("w1")).attempts
        } finally {
            parent.cancel()
        }
    }


    @Test
    fun `control - the same stopped worker is not charged on a store that notices its caller`() =
        runTest {
            assertEquals(0, workerPrepareCancelled { NoticingStore(it) })
        }

    /** Never answers a withdrawal's delete until the test lets it, whatever happens to the caller. */
    private class HungDeleteStore(private val inner: UploaderStore, val gate: CompletableDeferred<Unit>) : UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            withContext(NonCancellable) { gate.await() }
            inner.deleteById(id)
        }
    }

    @Test
    fun `a withdrawal hung inside its store write does not block close and completes with the write`() =
        runTest {
            val parent = Job()
            try {
                val inner = TestUploaderStore()
                val gate = CompletableDeferred<Unit>()
                val transport = Transport()
                val handler = UploadingHandler(transport)
                val engine: UploaderEngine = testEngine(HungDeleteStore(inner, gate), listOf(handler), scopeOn(parent), TestClock(1_000L))
                val id: String = engine.enqueue(handler, "x")!!
                var finished = false
                val caller: Job = launch {
                    try {
                        withTimeout(1.seconds) { engine.cancel(id) }
                    } finally {
                        finished = true
                    }
                }
                advanceTimeBy(5.seconds)
                runCurrent()
                assertTrue(!finished, "the write is non-cancellable, so the caller's own timeout does not end the call")

                engine.close() // must return at once
                assertTrue(!finished)

                gate.complete(Unit)
                runCurrent()
                assertTrue(finished, "the withdrawal ends when the write does")
                assertNull(inner.find(id))
                assertEquals(listOf("cancel:$id"), transport.events, "its side effects run even after close")
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `the largest outcome entry a response can produce stays under the journal's entry bound`() {
        // 60 000 distinct one-character names (three UTF-8 bytes each) with empty values: the most headers the
        // character cap lets through, in the widest encoding.
        val names: List<String> = (0x0800..0xFFFF).filter { it !in 0xD800..0xDFFF && it.toChar().lowercaseChar() == it.toChar() }.take(60_000).map { it.toChar().toString() }
        val headers: Map<String, String> = names.associateWith { "" }
        val response = UploadResponse(200, headers, ByteArray(MAX_RESPONSE_BODY_BYTES), isBodyTruncated = false).enforced(MAX_RESPONSE_BODY_BYTES)
        val encoded: ByteArray = OutcomeCodec.encode(response, 1L, attemptStamp = 1)
        assertTrue(encoded.size <= MAX_ENCODED_ENTRY_BYTES, "an entry of ${encoded.size} bytes would read back as corrupt")
    }
}
