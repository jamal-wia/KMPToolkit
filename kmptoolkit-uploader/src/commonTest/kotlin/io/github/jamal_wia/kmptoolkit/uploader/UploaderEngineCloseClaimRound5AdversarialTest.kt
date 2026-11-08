package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Round 5 (lifecycle chaos): `close()` promises that "everything owed is still owed, and a freshly constructed engine
 * picks it up". An upload the closed engine had claimed (its row is IN_FLIGHT under a lease) but not yet launched
 * is owed too: the replacement engine has to start it, not wait out the lease of an upload that never began.
 */
class UploaderEngineCloseClaimRound5AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    /** A store whose claim returns through a dispatcher hop, which reports a cancelled caller although the claim committed. */
    private class HoppingClaimStore(private val inner: UploaderStore) : UploaderStore by inner {
        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            val claimed: Boolean = inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            yield()
            return claimed
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

    private class Handler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "r5.claim"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    @Test
    fun `an upload claimed by an engine closed before its launch is started by the engine that replaces it`() =
        runTest(timeout = 30.seconds) {
            val parent1 = Job()
            val parent2 = Job()
            try {
                val inner = TestUploaderStore()
                val store = HoppingClaimStore(inner)
                val transport = Transport()
                val handler = Handler(transport)
                val clock = TestClock(1_000L)
                val first: UploaderEngine = testEngine(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler) + parent1), clock)
                val id: String = first.enqueue(handler, "x")!!
                inner.afterNextMarkInFlight = { first.close() } // the owner shuts the engine down right after the claim committed
                first.start()
                runCurrent()
                assertEquals(emptyList(), transport.events, "the closed engine never launched it")

                val second: UploaderEngine = testEngine(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler) + parent2), clock)
                second.start()
                advanceTimeBy(2.seconds)
                runCurrent()

                assertEquals(
                    listOf("launch:$id"),
                    transport.events,
                    "the item is owed and nothing runs it; state=${inner.find(id)?.state}, lease until ${inner.find(id)?.leaseUntilEpochMillis}",
                )
            } finally {
                parent1.cancel()
                parent2.cancel()
            }
        }
}
