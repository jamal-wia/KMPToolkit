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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Round 7 (A1), attack 4: time. Lease and backoff arithmetic at the ends of the `Long` range, a clock that does not
 * move at all, many items sharing every timestamp.
 */
class UploaderEngineTimeRound7AdversarialTest {

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

    private class UploadingHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "r7.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    /** A claim that waits where it cannot be interrupted and then reports the cancelled caller without having written. */
    private class LateRefusedClaimStore(
        private val inner: UploaderStore,
        val entered: CompletableDeferred<Unit>,
        val gate: CompletableDeferred<Unit>,
    ) : UploaderStore by inner {
        private var first = true
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            if (!first) return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            first = false
            entered.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            currentCoroutineContext().ensureActive()
            return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** R6-09's overlap at a given clock. The same overlap a few milliseconds from `Long.MAX_VALUE` is suspect R7-01. */
    private fun lateReleaseAt(startMillis: Long) = runTest {
        val parent1 = Job()
        val parent2 = Job()
        try {
            val inner = TestUploaderStore()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport = Transport()
            val handler = UploadingHandler(transport)
            val clock = TestClock(startMillis)
            val first: UploaderEngine = testEngine(LateRefusedClaimStore(inner, entered, gate), listOf(handler), scopeOn(parent1), clock)
            val id: String = first.enqueue(handler, "x")!!
            first.start()
            runCurrent()
            assertEquals(true, entered.isCompleted)
            first.close()

            val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent2), clock)
            second.start()
            runCurrent()
            assertEquals(listOf("launch:$id"), transport.events, "the second engine claimed and launched it (clock $startMillis)")
            val claimed: UploaderItem = assertNotNull(inner.find(id))
            assertEquals(UploaderItemState.IN_FLIGHT, claimed.state)

            gate.complete(Unit)
            runCurrent()
            assertEquals(
                UploaderItemState.IN_FLIGHT,
                assertNotNull(inner.find(id)).state,
                "the first engine's late release reset the second engine's live claim (clock $startMillis, lease ${claimed.leaseUntilEpochMillis})",
            )
            advanceTimeBy(UploaderConfig().heartbeatInterval + 2.seconds)
            runCurrent()
            assertEquals(listOf("launch:$id"), transport.events, "the upload was launched a second time (clock $startMillis)")
        } finally {
            parent1.cancel()
            parent2.cancel()
        }
    }

    @Test
    fun `control - the late release of a closed engine leaves its replacement's claim alone at an ordinary clock`() =
        lateReleaseAt(1_000L)

    @Test
    fun `many items with one identical timestamp in one ordering channel run in enqueue order`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val ran: MutableList<String> = mutableListOf()
            val handler = TestHandler(ordering = { "chan" }, onExecute = { _, payload ->
                ran += payload
                AttemptResult.Success
            })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(Long.MAX_VALUE))
            val expected: List<String> = (0 until 60).map { "p$it" }
            expected.forEach { engine.enqueue(handler, it) }
            engine.start()
            runCurrent()
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(expected, ran)
            assertEquals(emptyList(), store.items)
        } finally {
            parent.cancel()
        }
    }

    private class FailingPolicy(private val backoff: Long) : RetryPolicy {
        override val maxDelayMillis: Long = 50L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = backoff
    }

    @Test
    fun `a retry policy that answers a negative backoff does not spin the drain faster than the alarm floor`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            var attempts = 0
            val handler = TestHandler(retryPolicy = FailingPolicy(-1L), onExecute = { _, _ ->
                attempts++
                AttemptResult.Retry(IllegalStateException("no"))
            })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            engine.enqueue(handler, "x")
            engine.start()
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(attempts <= 40, "a negative backoff made $attempts attempts in one virtual second")
            assertTrue(attempts >= 1)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a clock stepped to Long MIN and back never strands or repeats a delivered item`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val clock = TestClock(1_000L)
            val ran: MutableList<String> = mutableListOf()
            val handler = TestHandler(onExecute = { _, payload ->
                ran += payload
                AttemptResult.Success
            })
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock)
            engine.start()
            for (step in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, -10_000_000_000L)) {
                clock.millis = step
                engine.enqueue(handler, "at$step")
                runCurrent()
                advanceTimeBy(1.seconds)
                runCurrent()
            }
            assertEquals(6, ran.size, "ran=$ran")
            assertEquals(ran.toSet().size, ran.size, "an item ran twice: $ran")
            assertEquals(emptyList(), store.items)
        } finally {
            parent.cancel()
        }
    }
}
