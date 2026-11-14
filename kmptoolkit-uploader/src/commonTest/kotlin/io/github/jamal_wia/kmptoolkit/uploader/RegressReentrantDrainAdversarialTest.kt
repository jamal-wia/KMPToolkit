package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression round, rule P11 (one attempt per item): a pass that asks for an item whose attempt the engine is
 * already running skips it, whichever pass asks (a nested `drain()` from inside the attempt, two manual calls, a manual
 * call next to the engine's own loop), so an item never runs twice at once and the running attempt keeps its
 * withdrawal handle. Every test is deterministic: a test dispatcher on the test scheduler and a gate in place of time.
 */
class RegressReentrantDrainAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    @Test
    fun `a handler that drains the engine from inside its own attempt does not deadlock and runs each item once`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            var nested = 0
            val handler = TestHandler(onExecute = { _, payload ->
                if (payload == "outer" && nested++ == 0) engine.drain()
                AttemptResult.Success
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("re")::next)
            engine.enqueue(handler, "outer")
            engine.enqueue(handler, "inner")

            engine.drain()

            assertEquals(emptyList(), store.items, "both items are delivered")
            assertEquals(setOf("outer", "inner"), handler.payloads.toSet())
            assertEquals(2, handler.payloads.size, "no item ran twice: ${handler.payloads}")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a withdrawal after a nested drain still stops the outer attempt`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        val gate = CompletableDeferred<Unit>()
        var drainJob: Job? = null
        try {
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            lateinit var handler: TestHandler
            var cancelled = false
            handler = TestHandler(onExecute = { _, _ ->
                if (handler.attempts.size == 1) {
                    engine.drain()
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
                AttemptResult.Success
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("re")::next)
            val outerId: String = assertNotNull(engine.enqueue(handler, "outer"))

            drainJob = launch { engine.drain() }
            runCurrent()

            assertNotNull((engine as DefaultUploaderEngine).runningAttemptStamp(outerId), "the outer attempt is still registered after the nested drain returned")
            engine.cancel(outerId)
            runCurrent()

            assertTrue(cancelled, "the withdrawal reached the running outer attempt")
            assertNull(store.find(outerId), "no row is left")
            assertEquals(1, handler.attempts.size, "the outer item ran once: ${handler.payloads}")
        } finally {
            gate.complete(Unit)
            drainJob?.cancel()
            parent.cancel()
        }
    }

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class DrainingUploadHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "regress.reentrant.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        var prepares: Int = 0
        var onFirstPrepare: suspend () -> Unit = {}
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            if (prepares++ == 0) onFirstPrepare()
            return UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
        }
    }

    /** Counts the claims (a `markInFlight`, whether it wins or not) a hand-off asks the store for. */
    private class ClaimCountingStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var claims: Int = 0
        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            claims++
            return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
        }
    }

    @Test
    fun `a drain from inside prepareUpload hands the item off once`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = ClaimCountingStore(inner)
            val transport = Transport()
            val handler = DrainingUploadHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("re")::next)
            handler.onFirstPrepare = { engine.drain() }
            val id: String = assertNotNull(engine.enqueue(handler, "a"))

            engine.drain()

            assertEquals(listOf("launch:$id"), transport.events, "one launch for the one item")
            assertEquals(1, store.claims, "one claim")
            assertEquals(1, handler.prepares, "the upload was prepared once")
            assertEquals(UploaderItemState.IN_FLIGHT, inner.find(id)?.state, "the item is handed off, awaiting the transport")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a manual drain overlapping the started engine's running attempt does not start it again`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        val gate = CompletableDeferred<Unit>()
        var drainJob: Job? = null
        try {
            val store = TestUploaderStore()
            lateinit var handler: TestHandler
            handler = TestHandler(onExecute = { _, _ ->
                if (handler.attempts.size == 1) gate.await()
                AttemptResult.Success
            })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("re")::next)
            engine.start()
            val id: String = assertNotNull(engine.enqueue(handler, "x"))
            runCurrent()
            assertEquals(1, handler.attempts.size, "setup: the loop's attempt is running and waits on the gate")

            drainJob = launch { engine.drain() }
            runCurrent()

            assertTrue(drainJob.isCompleted, "the manual drain returned without waiting for the running attempt")
            assertEquals(1, handler.attempts.size, "the manual drain did not start the running item again: ${handler.payloads}")
            gate.complete(Unit)
            runCurrent()

            assertNull(store.find(id), "the item is delivered")
            assertEquals(1, handler.attempts.size, "and ran once")
        } finally {
            gate.complete(Unit)
            drainJob?.cancel()
            parent.cancel()
        }
    }
}
