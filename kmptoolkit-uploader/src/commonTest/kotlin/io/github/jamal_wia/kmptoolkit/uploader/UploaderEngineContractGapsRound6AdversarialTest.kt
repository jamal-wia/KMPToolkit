package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 6 suspects R6-07 (a cancel that failed in the store still killed the live upload), R6-08 (a wake scheduler that
 * throws, which its contract forbids, skips the stop and cancel of what a REPLACE superseded) and R6-09 (the late release of a closed engine resets its replacement's live claim).
 */
class UploaderEngineContractGapsRound6AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderRegistryCleanup.run()
    }

    private object UploaderRegistryCleanup {
        fun run() {
            UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        }
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
        override val type: String = "r6.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    private class FailingDeleteStore(private val inner: UploaderStore) : UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            throw IllegalStateException("database is locked") // nothing was written
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    @Test
    fun `a cancel that failed in the store changed nothing so the upload it was asked to withdraw still runs`() =
        runTest {
            val parent = Job()
            try {
                val inner = TestUploaderStore(
                    listOf(
                        UploaderItem(
                            id = "u1", type = "r6.upload", payload = "p", schemaVersion = 1, uniqueKey = null, orderingKey = null,
                            tag = null, state = UploaderItemState.IN_FLIGHT, attempts = 0, nextRunAtEpochMillis = 0L,
                            createdAtEpochMillis = 0L, lastError = null, leaseUntilEpochMillis = 100_000_000L, claim = 1L,
                        ),
                    ),
                )
                val transport = Transport()
                val handler = UploadingHandler(transport)
                val engine: UploaderEngine = testEngine(FailingDeleteStore(inner), listOf(handler), scopeOn(parent), TestClock(1_000L))
                assertFailsWith<IllegalStateException> { engine.cancel("u1") }
                assertNotNull(inner.find("u1"))
                assertEquals(emptyList(), transport.events, "the row is still in flight under a lease, but its upload was cancelled")
            } finally {
                parent.cancel()
            }
        }

    private class ThrowingWake : WakeScheduler {
        override fun scheduleWake(): Unit = throw IllegalStateException("WorkManager is not initialised")
        override fun cancelWake(): Unit = Unit
    }

    @Test
    fun `a replace whose wake scheduler throws still stops and cancels the item it superseded`() =
        runTest {
            val parent = Job()
            try {
                val inner = TestUploaderStore()
                val transport = Transport()
                val handler = UploadingHandler(transport)
                val engine: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L), wakeScheduler = ThrowingWake())
                val old: String? = runCatching { engine.enqueue(handler, "a", uniqueKey = "k") }.getOrNull()
                val ids: List<String> = inner.items.map { it.id }
                runCatching { engine.enqueue(handler, "b", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE) }
                assertEquals(listOf("cancel:${ids.single()}"), transport.events, "the superseded upload was left running; first enqueue returned $old")
            } finally {
                parent.cancel()
            }
        }

    /** A claim that waits where it cannot be interrupted and then reports the cancelled caller without having written. */
    private class LateRefusedClaimStore(
        private val inner: UploaderStore,
        val entered: kotlinx.coroutines.CompletableDeferred<Unit>,
        val gate: kotlinx.coroutines.CompletableDeferred<Unit>,
    ) : UploaderStore by inner {
        private var first = true
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            if (!first) return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            first = false
            entered.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            kotlinx.coroutines.currentCoroutineContext().ensureActive() // refused: the engine was closed meanwhile, nothing written
            return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
        }
    }

    @Test
    fun `the late release of a closed engine does not undo the claim its replacement made on the same row`() =
        runTest {
            val parent1 = Job()
            val parent2 = Job()
            try {
                val inner = TestUploaderStore()
                val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                val transport = Transport()
                val handler = UploadingHandler(transport)
                val clock = TestClock(1_000L)
                val first: UploaderEngine = testEngine(LateRefusedClaimStore(inner, entered, gate), listOf(handler), scopeOn(parent1), clock)
                val id: String = first.enqueue(handler, "x")!!
                first.start()
                runCurrent()
                assertEquals(true, entered.isCompleted)
                first.close() // the old engine's claim write is still waiting; the owner already builds the next engine

                val second: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent2), clock)
                second.start()
                runCurrent()
                assertEquals(listOf("launch:$id"), transport.events, "the second engine claimed and launched it")
                val claimed: UploaderItem = assertNotNull(inner.find(id))
                assertEquals(UploaderItemState.IN_FLIGHT, claimed.state)

                gate.complete(Unit) // the old engine's refused claim now unwinds and releases "its" claim
                runCurrent()
                assertEquals(UploaderItemState.IN_FLIGHT, assertNotNull(inner.find(id)).state, "the first engine's release reset the second engine's live claim")
                advanceTimeBy(UploaderConfig().heartbeatInterval + kotlin.time.Duration.parse("2s"))
                runCurrent()
                assertEquals(listOf("launch:$id"), transport.events, "the upload was launched a second time")
            } finally {
                parent1.cancel()
                parent2.cancel()
            }
        }
}
