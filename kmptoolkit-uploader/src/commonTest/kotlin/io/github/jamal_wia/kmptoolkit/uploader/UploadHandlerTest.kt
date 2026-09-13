package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

/**
 * [UploadHandler] and [UploadGateway] against a real engine, from their KDoc: the hand-off, the
 * preparation that runs again when the transport starts, classification, and hooks that run in order
 * and in isolation.
 */
class UploadHandlerTest {

    private val store = TestUploaderStore()
    private val clock = TestClock(millis = 1_000L)
    private val transport = RecordingTransport()
    private var engine: UploaderEngine? = null

    @AfterTest
    fun closeEngine() {
        engine?.close()
    }

    private fun startEngine(handler: UploaderHandler<*>, scope: kotlinx.coroutines.CoroutineScope): UploaderEngine =
        testEngine(store, listOf(handler), scope, clock = clock).also {
            engine = it
            UploaderEngineRegistry.register(it)
        }

    // --- Hand-off --------------------------------------------------------------------------------

    @Test
    fun `a prepared upload is handed to the transport and claimed under its lease`() = runTest {
        val handler = TestUploadHandler(transport)
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")

        engine.drain()

        assertEquals(listOf(Launch("item-1", false, handler.request)), transport.launches)
        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.IN_FLIGHT, item.state)
        assertEquals(1_000L + RecordingTransport.LEASE, item.leaseUntilEpochMillis)
    }

    @Test
    fun `a re-hand after an expired lease tells the transport`() = runTest {
        val handler = TestUploadHandler(transport)
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        clock.millis += RecordingTransport.LEASE + 1
        engine.drain()

        assertEquals(listOf(false, true), transport.launches.map { it.isRehandOff })
    }

    @Test
    fun `a drop or park from preparation finishes the item without a hand-off`() = runTest {
        val dropping = TestUploadHandler(transport, type = "drop", prepare = { UploadPreparation.Drop("gone") })
        val parking = TestUploadHandler(transport, type = "park", prepare = { UploadPreparation.Park("stuck") })
        val engine: UploaderEngine = testEngine(store, listOf(dropping, parking), backgroundScope, clock = clock)
            .also { this@UploadHandlerTest.engine = it }
        engine.enqueue(dropping, "a")
        engine.enqueue(parking, "b")

        engine.drain()

        assertTrue(transport.launches.isEmpty())
        assertNull(store.find("item-1"))
        assertEquals(UploaderItemState.PARKED, store.find("item-2")?.state)
    }

    @Test
    fun `a transport that cannot enqueue fails only this attempt under the retry policy`() = runTest {
        transport.failLaunchWith = IllegalStateException("scheduler unavailable")
        val handler = TestUploadHandler(transport)
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")

        engine.drain()

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, item.state)
        assertEquals(1, item.attempts)
    }

    @Test
    fun `a transport with a non-positive lease parks the item instead of spinning`() = runTest {
        transport.lease = 0
        val handler = TestUploadHandler(transport)
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")

        engine.drain()

        assertEquals(UploaderItemState.PARKED, store.find("item-1")?.state)
        assertTrue(transport.launches.isEmpty())
    }

    // --- Preparation at run time -------------------------------------------------------------

    @Test
    fun `an attempt for an in-flight item is prepared afresh by the handler`() = runTest {
        var token = "first"
        val handler = TestUploadHandler(transport, prepare = { UploadPreparation.Proceed(request(token)) })
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        token = "fresh"
        val attempt: UploadAttempt = UploadGateway.prepareAttempt("item-1", WAIT)

        assertEquals(UploadAttempt.Ready(request("fresh")), attempt)
        assertTrue(handler.contexts.last().wasDetached)
    }

    @Test
    fun `nothing is owed for an item that is gone or not in flight`() = runTest {
        val handler = TestUploadHandler(transport)
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload") // pending, never drained

        assertEquals(UploadAttempt.NothingOwed, UploadGateway.prepareAttempt("item-1", WAIT))
        assertEquals(UploadAttempt.NothingOwed, UploadGateway.prepareAttempt("missing", WAIT))
    }

    @Test
    fun `a drop decided at run time removes the item and uploads nothing`() = runTest {
        var drop = false
        val handler = TestUploadHandler(transport, prepare = {
            if (drop) UploadPreparation.Drop("already on the server") else UploadPreparation.Proceed(request("t"))
        })
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        drop = true

        assertEquals(UploadAttempt.NothingOwed, UploadGateway.prepareAttempt("item-1", WAIT))
        assertNull(store.find("item-1"))
    }

    @Test
    fun `a preparation that throws at run time leaves the item in flight for the lease`() = runTest {
        var fail = false
        val handler = TestUploadHandler(transport, prepare = {
            if (fail) error("token service down") else UploadPreparation.Proceed(request("t"))
        })
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        fail = true

        assertEquals(UploadAttempt.NothingOwed, UploadGateway.prepareAttempt("item-1", WAIT))
        assertEquals(UploaderItemState.IN_FLIGHT, store.find("item-1")?.state)
    }

    @Test
    fun `without a registered engine the attempt is reported unavailable and not settled`() = runTest {
        assertEquals(UploadAttempt.EngineUnavailable, UploadGateway.prepareAttempt("item-1", WAIT))
        assertEquals(false, UploadGateway.complete("item-1", UploadResult.Completed(200), WAIT))
    }

    // --- Completion ----------------------------------------------------------------------------

    @Test
    fun `a delivered upload runs onDelivered before the row goes and onSettled after`() = runTest {
        val events = mutableListOf<String>()
        val handler = TestUploadHandler(
            transport,
            onDelivered = { events += "delivered, row present=${store.find("item-1") != null}" },
            onSettled = { _, result -> events += "settled $result, row present=${store.find("item-1") != null}" },
        )
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        assertTrue(UploadGateway.complete("item-1", UploadResult.Completed(201), WAIT))

        assertEquals(
            listOf("delivered, row present=true", "settled Delivered, row present=false"),
            events,
        )
        assertEquals(listOf<UploadResult>(UploadResult.Completed(201)), handler.classified)
    }

    @Test
    fun `classification decides the settlement and a thrown classify becomes a retry`() = runTest {
        val handler = TestUploadHandler(transport, classify = { error("bug in classify") })
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        UploadGateway.complete("item-1", UploadResult.Completed(500), WAIT)

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, item.state)
        assertEquals(1, item.attempts)
    }

    @Test
    fun `a failing hook changes nothing about the settlement`() = runTest {
        val handler = TestUploadHandler(
            transport,
            onDelivered = { error("local write failed") },
            onSettled = { _, _ -> error("cleanup failed") },
        )
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        UploadGateway.complete("item-1", UploadResult.Completed(200), WAIT)

        assertNull(store.find("item-1"), "the upload landed; a local failure must not keep it owed")
    }

    @Test
    fun `onSettled receives the attempts the item had when it was handed off`() = runTest {
        var seen: Int? = null
        val handler = TestUploadHandler(
            transport,
            classify = { SettleResult.Failed() },
            onSettled = { attempts, _ -> seen = attempts },
        )
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        UploadGateway.complete("item-1", UploadResult.TransportFailure("offline"), WAIT)

        assertEquals(0, seen)
    }

    @Test
    fun `a completion for an item whose handler is not an upload handler parks it`() = runTest {
        val plain = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 1_000) })
        val engine: UploaderEngine = startEngine(plain, backgroundScope)
        engine.enqueue(plain, "payload")
        engine.drain()

        UploadGateway.complete("item-1", UploadResult.Completed(200), WAIT)

        assertEquals(UploaderItemState.PARKED, store.find("item-1")?.state)
    }

    // --- Progress ------------------------------------------------------------------------------

    @Test
    fun `progress reaches the handler clamped and stops once the item settled`() = runTest {
        val handler = TestUploadHandler(transport)
        val engine: UploaderEngine = startEngine(handler, backgroundScope)
        engine.enqueue(handler, "payload")
        engine.drain()

        UploadGateway.progress("item-1", 0.5f)
        UploadGateway.progress("item-1", 1.5f)
        UploadGateway.complete("item-1", UploadResult.Completed(200), WAIT)
        UploadGateway.progress("item-1", 0.9f)

        assertEquals(listOf(0.5f, 1f), handler.progress)
    }

    private companion object {
        val WAIT = 10.milliseconds

        fun request(token: String): UploadRequest = UploadRequest(
            url = "https://example.com/upload",
            headers = mapOf("Authorization" to "Bearer $token"),
            fields = listOf(UploadField.Text("a", "b")),
        )
    }

    private data class Launch(val itemId: String, val isRehandOff: Boolean, val request: UploadRequest)

    private class RecordingTransport : UploadTransport {
        var lease: Long = LEASE
        var failLaunchWith: Throwable? = null
        val launches = mutableListOf<Launch>()

        override val leaseMillis: Long get() = lease

        override fun launch(itemId: String, request: UploadRequest) {
            error("UploadHandler must call the re-hand-aware overload")
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            failLaunchWith?.let { throw it }
            launches += Launch(itemId, isRehandOff, request)
        }

        override fun cancelAll() = Unit

        companion object {
            const val LEASE: Long = 60_000L
        }
    }

    private class TestUploadHandler(
        transport: UploadTransport,
        override val type: String = "upload",
        private val prepare: suspend (AttemptContext) -> UploadPreparation = { UploadPreparation.Proceed(request("t")) },
        private val classify: suspend (UploadResult) -> SettleResult = ::defaultUploadClassification,
        private val onDelivered: suspend () -> Unit = {},
        private val onSettled: suspend (Int, SettleResult) -> Unit = { _, _ -> },
    ) : UploadHandler<String>(transport) {

        val request: UploadRequest = request("t")
        val contexts = mutableListOf<AttemptContext>()
        val classified = mutableListOf<UploadResult>()
        val progress = mutableListOf<Float>()

        override val retryPolicy: RetryPolicy = FixedRetryPolicy()

        override fun encodePayload(payload: String): String = payload

        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            contexts += context
            return prepare(context)
        }

        override suspend fun classify(payload: String, result: UploadResult): SettleResult {
            classified += result
            return classify(result)
        }

        override suspend fun onUploadProgress(payload: String, fraction: Float) {
            progress += fraction
        }

        override suspend fun onDelivered(payload: String) = onDelivered()

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) = onSettled(attempts, result)
    }
}
