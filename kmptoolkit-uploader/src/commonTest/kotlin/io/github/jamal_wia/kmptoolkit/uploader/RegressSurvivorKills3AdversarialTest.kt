package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression round, third set of tests written for mutants that survived the existing suites (Task M of the
 * regress round). Each one is derived from the stated contract: a `NaN` progress fraction is dropped while a number
 * outside `0..1` is clamped (04 `UploadGateway.progress`, the KDoc of the same method), `start()` is idempotent and
 * launches one drain coroutine so that two drains never overlap (04 `start`, the `EngineLifecycle` class KDoc), and a
 * closed engine's `drain()` runs nothing (04 `drain`, the `EngineLifecycle` P6 line).
 *
 * Deterministic by construction: virtual time, a latch instead of a wait, no sleep, no randomness. Every id carries the
 * prefix `k3` because the registry of delivered-but-unconfirmed rows is process-wide.
 */
class RegressSurvivorKills3AdversarialTest {

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class StubTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    /** An upload handler that hands every item to the stub transport and records the progress fractions it is given. */
    private class ProgressHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        val fractions: MutableList<Float> = mutableListOf()
        override val type: String = "k3.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "https://example.com/upload", fields = listOf(UploadField.Text("a", "b"))))

        override suspend fun onUploadProgress(payload: String, fraction: Float) {
            fractions += fraction
        }
    }

    // ---- S9: a NaN fraction is dropped, a number outside 0..1 is clamped ------------------------------------------

    @Test
    fun `a NaN progress fraction is dropped while numbers outside the range are clamped`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val handler = ProgressHandler(StubTransport())
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("k3s9")::next)
            UploaderEngineRegistry.register(engine)
            val id: String = assertNotNull(engine.enqueue(handler, "payload"))
            engine.drain()
            val inFlight: UploaderItem = assertNotNull(store.find(id))
            assertEquals(UploaderItemState.IN_FLIGHT, inFlight.state, "setup: the item is handed to the transport and in flight")

            UploadGateway.progress(id, Float.NaN)
            assertEquals(emptyList(), handler.fractions, "a NaN is not a fraction of anything: the hook is not called")

            UploadGateway.progress(id, 0.5f)
            UploadGateway.progress(id, Float.POSITIVE_INFINITY)
            UploadGateway.progress(id, Float.NEGATIVE_INFINITY)
            UploadGateway.progress(id, Float.NaN)
            assertEquals(listOf(0.5f, 1f, 0f), handler.fractions, "a number is delivered, outside 0..1 it is clamped, a NaN never")
            assertEquals(inFlight, store.find(id), "a progress signal never changes the row")
        } finally {
            parent.cancel()
        }
    }

    // ---- L1: a second start() launches nothing --------------------------------------------------------------------

    /** Records how many handler calls overlap, and holds the one for payload `a` until [gateA] opens. */
    private class OverlapProbe {
        val gateA: CompletableDeferred<Unit> = CompletableDeferred()
        val entered: MutableList<String> = mutableListOf()
        var inside: Int = 0
        var mostInside: Int = 0

        suspend fun run(payload: String): AttemptResult {
            entered += payload
            inside++
            mostInside = maxOf(mostInside, inside)
            try {
                if (payload == "a") gateA.await()
            } finally {
                inside--
            }
            return AttemptResult.Success
        }
    }

    @Test
    fun `a repeated start keeps one drain so a second item waits for the first attempt`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val probe = OverlapProbe()
            val handler = TestHandler(onExecute = { _, payload -> probe.run(payload) })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("k3l1")::next)
            engine.start()
            engine.start()
            runCurrent()

            val first: String = assertNotNull(engine.enqueue(handler, "a"))
            runCurrent()
            assertEquals(listOf("a"), probe.entered, "setup: the first attempt is running and holds its latch")

            val second: String = assertNotNull(engine.enqueue(handler, "b"))
            runCurrent()
            assertEquals(listOf("a"), probe.entered, "one drain coroutine: the second item waits while the first attempt runs")
            assertEquals(1, probe.mostInside, "two drains never overlap")

            probe.gateA.complete(Unit)
            runCurrent()

            assertEquals(listOf("a", "b"), probe.entered, "the second item runs once the first attempt is over")
            assertEquals(1, probe.mostInside, "and still never beside it")
            assertNull(store.find(first), "the first row is delivered")
            assertNull(store.find(second), "the second row is delivered")
        } finally {
            parent.cancel()
        }
    }

    // ---- L8: a closed engine's drain runs nothing -----------------------------------------------------------------

    private fun TestScope.lifecycleOn(scope: CoroutineScope): EngineLifecycle {
        val store = TestUploaderStore()
        val owner: UploaderEngine = testEngine(store, listOf(TestHandler()), scope)
        return EngineLifecycle(
            owner = owner,
            scope = scope,
            ledger = RowLedger(store, TransactionRunner.Direct, NoopLogger),
            constraintProviders = emptyList(),
            config = UploaderConfig(),
            clock = TestClock(5_000_000L),
            logger = NoopLogger,
        )
    }

    @Test
    fun `the drain of a closed engine runs no pass at all`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val lifecycle: EngineLifecycle = lifecycleOn(scopeOn(parent))
            lifecycle.close()
            var passStarted = false

            lifecycle.drain { passStarted = true }

            assertFalse(passStarted, "a closed engine is permanently inert: drain() runs nothing and returns")
            assertTrue(lifecycle.isClosed, "setup: the engine stayed closed")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the drain of an engine whose scope was cancelled runs no pass at all`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        val lifecycle: EngineLifecycle = lifecycleOn(scopeOn(parent))
        parent.cancel()
        var passStarted = false

        lifecycle.drain { passStarted = true }

        assertFalse(passStarted, "an engine stopped by cancelling its scope is as inert as a closed one")
    }
}
