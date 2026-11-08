package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Round 4 (contract-lawyer): the clock anomaly guard at its exact boundary. "A gate (or lease) further ahead than
 * `clockAnomalyFactor` times the reachable maximum was written before a backwards jump": exactly that far is real,
 * one millisecond further is a jump.
 */
class UploaderEngineAnomalyBoundaryRound4AdversarialTest {

    private val now = 10_000_000L
    private val dayMillis = 24L * 60 * 60 * 1000

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun row(id: String, state: UploaderItemState, gate: Long, lease: Long): UploaderItem = UploaderItem(
        id = id, type = TestHandler.DEFAULT_TYPE, payload = id, schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
        state = state, attempts = 1, nextRunAtEpochMillis = gate, createdAtEpochMillis = 0L, lastError = null,
        leaseUntilEpochMillis = lease, claim = 1L,
    )

    private suspend fun ranWith(rows: List<UploaderItem>, factor: Int, scope: kotlinx.coroutines.CoroutineScope): List<String> {
        val handler = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1_000L, giveUp = GiveUpPolicy.ParkAfterAttempts(2)))
        val engine: UploaderEngine = createUploaderEngine(
            store = TestUploaderStore(rows),
            handlers = listOf(handler),
            scope = scope,
            config = UploaderConfig(clockAnomalyFactor = factor),
            clock = TestClock(now),
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.drain()
        } finally {
            engine.close()
        }
        return handler.payloads.toList()
    }

    @Test
    fun `a backoff gate exactly factor times the maximum ahead is honoured and one millisecond more is a clock jump`() = runTest {
        val gates: List<UploaderItem> = listOf(
            row("exact", UploaderItemState.PENDING, gate = now + 3_000L, lease = 0L),
            row("beyond", UploaderItemState.PENDING, gate = now + 3_001L, lease = 0L),
            row("near", UploaderItemState.PENDING, gate = now + 1L, lease = 0L),
        )

        val ran: List<String> = ranWith(gates, factor = 3, scope = backgroundScope)

        assertEquals(listOf("beyond"), ran, "only the gate beyond the reachable maximum may be run early")
    }

    @Test
    fun `a lease exactly factor times a day ahead is held and one millisecond more is re-handed`() = runTest {
        val leases: List<UploaderItem> = listOf(
            row("exact", UploaderItemState.IN_FLIGHT, gate = 0L, lease = now + 3 * dayMillis),
            row("beyond", UploaderItemState.IN_FLIGHT, gate = 0L, lease = now + 3 * dayMillis + 1L),
            row("a-day", UploaderItemState.IN_FLIGHT, gate = 0L, lease = now + dayMillis),
            row("expired", UploaderItemState.IN_FLIGHT, gate = 0L, lease = now),
        )

        val ran: List<String> = ranWith(leases, factor = 3, scope = backgroundScope)

        assertEquals(listOf("beyond", "expired"), ran, "leases: exactly factor days ahead is real, one more millisecond is a jump")
    }

    private class HugeLeaseTransport : UploadTransport {
        override val leaseMillis: Long = Long.MAX_VALUE
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class UploadingHandler : UploadHandler<String>(HugeLeaseTransport()) {
        override val type: String = "huge.lease"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
    }

    @Test
    fun `a transport lease of Long MAX_VALUE is written as exactly one day`() = runTest {
        val store = TestUploaderStore()
        val handler = UploadingHandler()
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = TestClock(now),
            idGenerator = SequentialIds()::next,
        )
        try {
            val id: String = engine.enqueue(handler, "p")!!
            engine.drain()

            assertEquals(now + dayMillis, store.find(id)?.leaseUntilEpochMillis, "the lease written for a transport lease of Long.MAX_VALUE")
        } finally {
            engine.close()
        }
    }
}
