package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import kotlinx.coroutines.yield

/**
 * Round 6 suspects R6-01, R6-02, R6-03 (worker side) and R6-05 (red on release/3.1.0 244c8b37). The control, hung-write and entry-bound tests are in [UploaderEngineContractRound6AdversarialTest]. Reads the close and withdrawal clauses of the KDoc literally
 * against the engine as D-67 left it. Every test is single-threaded on a virtual clock and bounded by `runTest`'s
 * default timeout; each one waits only on gates that the test itself completes.
 */
class UploaderEngineContractSuspectsRound6AdversarialTest {

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

    /** A claim that suspends where it does not notice cancellation (the SPI allows a store that never does). */
    private class SlowClaimStore(
        private val inner: UploaderStore,
        val entered: CompletableDeferred<Unit>,
        val gate: CompletableDeferred<Unit>,
    ) : UploaderStore by inner {
        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            entered.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    @Test
    fun `an upload whose claim was still being written when close returned is not launched after close returned`() =
        runTest {
            val parent = Job()
            try {
                val inner = TestUploaderStore()
                val gate = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val store = SlowClaimStore(inner, entered, gate)
                val transport = Transport()
                val handler = UploadingHandler(transport)
                val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
                val id: String = engine.enqueue(handler, "x")!!
                engine.start()
                runCurrent()
                assertTrue(entered.isCompleted, "the drain is inside the claim")

                engine.close() // the documented promise starts here: "nothing is claimed or launched after close returns"
                gate.complete(Unit) // the store's claim write now finishes, as a store that never notices cancellation does
                runCurrent()

                assertEquals(
                    emptyList(),
                    transport.events,
                    "close() had returned before the launch; row=${inner.find(id)?.state}",
                )
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `an effect that returned Detached as close landed is told to the next engine as a possible earlier executor`() =
        runTest {
            val parent1 = Job()
            val parent2 = Job()
            try {
                val store = TestUploaderStore()
                val entered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val seenWasDetached: MutableList<Boolean> = mutableListOf()
                val handler = TestHandler(onExecute = { ctx, _ ->
                    seenWasDetached += ctx.wasDetached
                    if (seenWasDetached.size == 1) {
                        // The handler already handed the work to its own executor; only its return is still to come.
                        entered.complete(Unit)
                        withContext(NonCancellable) { gate.await() }
                    }
                    AttemptResult.Detached(60_000L)
                })
                val clock = TestClock(1_000L)
                val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent1), clock)
                first.enqueue(handler, "x")
                first.start()
                runCurrent()
                assertTrue(entered.isCompleted)
                first.close()
                gate.complete(Unit)
                runCurrent()

                val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent2), clock)
                second.start()
                advanceTimeBy(2.seconds)
                runCurrent()

                // Either the executor's lease still holds (one execute so far), or the handler is told it may be a re-hand-off.
                assertTrue(
                    seenWasDetached.size == 1 || seenWasDetached.last(),
                    "the second run started an executor again as a first-time attempt: wasDetached=$seenWasDetached",
                )
            } finally {
                parent1.cancel()
                parent2.cancel()
            }
        }

    /** A delete that waits for a dispatcher before it writes, as a database hop does, and so notices a cancellation meanwhile. */
    private class QueuedDeleteStore(private val inner: UploaderStore) : UploaderStore by inner {
        var beforeWrite: () -> Unit = {}

        override suspend fun deleteById(id: String) {
            beforeWrite()
            yield() // waiting for the database dispatcher: a close() meanwhile cancels this call before its write
            inner.deleteById(id)
        }
    }

    @Test
    fun `an effect that delivered is not run again by the next engine when close lands between its answer and the delete`() =
        runTest {
            val parent1 = Job()
            val parent2 = Job()
            try {
                val inner = TestUploaderStore()
                val store = QueuedDeleteStore(inner)
                val handler = TestHandler()
                val clock = TestClock(1_000L)
                val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent1), clock)
                val id: String = first.enqueue(handler, "x")!!
                store.beforeWrite = { first.close() }
                first.start()
                runCurrent()
                assertEquals(1, handler.attempts.size, "the effect ran once in the first engine")

                val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent2), clock)
                second.start()
                advanceTimeBy(2.seconds)
                runCurrent()

                assertEquals(
                    1,
                    handler.attempts.size,
                    "a delivered effect ran a second time; the row is ${inner.find(id)?.state}",
                )
            } finally {
                parent1.cancel()
                parent2.cancel()
            }
        }

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
    fun `a worker stopped while its handler prepares the upload is not charged an attempt on a store that does not notice`() =
        runTest {
            assertEquals(0, workerPrepareCancelled { it }, "a cancelled preparation recorded a failed attempt")
        }
}
