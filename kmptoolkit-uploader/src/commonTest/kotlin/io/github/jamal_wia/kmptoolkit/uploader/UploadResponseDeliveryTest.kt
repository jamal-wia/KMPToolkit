package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * What an [UploadHandler] sees of the server's answer, against a real engine: the response in
 * [UploadHandler.classify] and [UploadHandler.onDelivered] for every status, the limit and its
 * truncation, and 3.0 handlers that know nothing of any of it.
 */
class UploadResponseDeliveryTest {

    private val store = TestUploaderStore()
    private var engine: UploaderEngine? = null

    @AfterTest
    fun closeEngine() {
        engine?.close()
    }

    private suspend fun enqueued(handler: UploadHandler<String>, scope: CoroutineScope) {
        val started: UploaderEngine = testEngine(store, listOf(handler), scope, clock = TestClock(1_000L))
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain()
    }

    // --- The response reaches the hooks -----------------------------------------------------------

    @Test
    fun `a 2xx body and headers reach onDelivered and classify`() = runTest {
        val handler = ResponseHandler()
        enqueued(handler, backgroundScope)

        val sent = UploadResponse(201, mapOf("Location" to "/files/9"), """{"id":9}""".encodeToByteArray(), false)
        assertTrue(UploadGateway.complete("item-1", sent, WAIT))

        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertEquals(201, delivered.statusCode)
        assertEquals("""{"id":9}""", delivered.body().decodeToString())
        assertEquals("/files/9", delivered.header("LOCATION"))
        assertEquals(sent, assertNotNull(handler.classified.single().second))
        assertNull(store.find("item-1"))
    }

    @Test
    fun `a 4xx body reaches the three-argument classify with a Completed result of that code`() = runTest {
        val handler = ResponseHandler(classifier = { _, response ->
            SettleResult.Drop("rejected: ${response?.body()?.decodeToString()}")
        })
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(422, emptyMap(), "name is blank".encodeToByteArray(), false), WAIT)

        val (result: UploadResult, response: UploadResponse?) = handler.classified.single()
        assertEquals(UploadResult.Completed(422), result)
        assertEquals("name is blank", response?.body()?.decodeToString())
        assertTrue(handler.delivered.isEmpty(), "a drop is not a delivery")
        assertNull(store.find("item-1"))
    }

    @Test
    fun `the result is always built from the response so they cannot disagree`() = runTest {
        val handler = ResponseHandler()
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(503, emptyMap(), ByteArray(0), false), WAIT)

        assertEquals(UploadResult.Completed(503), handler.classified.single().first)
        assertEquals(UploaderItemState.PENDING, store.find("item-1")?.state, "a 5xx is retried by default")
    }

    @Test
    fun `a 204 delivers an empty array and not null`() = runTest {
        val handler = ResponseHandler()
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(204, emptyMap(), ByteArray(0), false), WAIT)

        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertContentEquals(ByteArray(0), delivered.body())
        assertFalse(delivered.isBodyTruncated)
    }

    @Test
    fun `a transport failure reaches classify with no response and never onDelivered`() = runTest {
        val handler = ResponseHandler()
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResult.TransportFailure("offline"), WAIT)

        val (result: UploadResult, response: UploadResponse?) = handler.classified.single()
        assertIs<UploadResult.TransportFailure>(result)
        assertNull(response)
        assertTrue(handler.delivered.isEmpty())
    }

    @Test
    fun `a status-only completion from an older custom transport reaches the hooks with a null response`() = runTest {
        val handler = ResponseHandler()
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResult.Completed(200), WAIT)

        assertNull(handler.classified.single().second)
        assertEquals(listOf<UploadResponse?>(null), handler.delivered)
    }

    @Test
    fun `a classify that throws on a malformed body settles the item as failed and keeps it owed`() = runTest {
        val handler = ResponseHandler(classifier = { _, response ->
            error("cannot parse ${response?.body()?.size} bytes")
        })
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), "<<not json".encodeToByteArray(), false), WAIT)

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, item.state)
        assertEquals(1, item.attempts)
        assertTrue(handler.delivered.isEmpty())
    }

    @Test
    fun `an exception from onDelivered does not stop the settlement`() = runTest {
        val handler = ResponseHandler(deliveredHook = { error("disk full") })
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), ByteArray(1), false), WAIT)

        assertNull(store.find("item-1"))
    }

    @Test
    fun `completing an unknown id with a response is a no-op that reports true`() = runTest {
        val handler = ResponseHandler()
        enqueued(handler, backgroundScope)

        assertTrue(UploadGateway.complete("missing", UploadResponse(200, emptyMap(), ByteArray(0), false), WAIT))

        assertTrue(handler.classified.isEmpty())
        assertTrue(handler.delivered.isEmpty())
    }

    @Test
    fun `without a registered engine a response completion reports false`() = runTest {
        assertFalse(UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), ByteArray(0), false), WAIT))
    }

    // --- The limit ----------------------------------------------------------------------------------

    @Test
    fun `the default limit is 64 KiB`() {
        assertEquals(65_536, UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES)
        assertEquals(65_536, ResponseHandler().maxResponseBodyBytes)
    }

    @Test
    fun `a body over the handler limit is cut and flagged and the item is still delivered`() = runTest {
        val handler = ResponseHandler(limit = 4)
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), ByteArray(10) { it.toByte() }, false), WAIT)

        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertContentEquals(byteArrayOf(0, 1, 2, 3), delivered.body())
        assertTrue(delivered.isBodyTruncated)
        assertNull(store.find("item-1"), "truncation never causes a retry")
        assertEquals(1, handler.classified.size)
    }

    @Test
    fun `a getter that throws falls back to the default limit and never costs the upload`() = runTest {
        val handler = ResponseHandler(limitGetter = { error("config not initialised") })
        enqueued(handler, backgroundScope)

        val landed: Boolean = UploadGateway.complete(
            "item-1",
            UploadResponse(200, emptyMap(), ByteArray(UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES + 10), false),
            WAIT,
        )

        assertTrue(landed, "a failing getter must not read as a store failure that re-uploads")
        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertEquals(UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES, delivered.body().size)
        assertTrue(delivered.isBodyTruncated)
        assertNull(store.find("item-1"))
    }

    @Test
    fun `a body exactly at the limit is not truncated`() = runTest {
        val handler = ResponseHandler(limit = 4)
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), ByteArray(4), false), WAIT)

        assertFalse(assertNotNull(handler.delivered.single()).isBodyTruncated)
    }

    @Test
    fun `limit zero captures no body but keeps status and headers and says it truncated`() = runTest {
        val handler = ResponseHandler(limit = 0)
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(202, mapOf("Location" to "/jobs/1"), ByteArray(5), false), WAIT)

        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertEquals(0, delivered.body().size)
        assertTrue(delivered.isBodyTruncated)
        assertEquals(202, delivered.statusCode)
        assertEquals("/jobs/1", delivered.header("location"))
    }

    @Test
    fun `limit zero with no body is not truncated`() = runTest {
        val handler = ResponseHandler(limit = 0)
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(204, emptyMap(), ByteArray(0), false), WAIT)

        assertFalse(assertNotNull(handler.delivered.single()).isBodyTruncated)
    }

    @Test
    fun `a negative limit counts as zero`() = runTest {
        val handler = ResponseHandler(limit = -5)
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), ByteArray(3), false), WAIT)

        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertEquals(0, delivered.body().size)
        assertTrue(delivered.isBodyTruncated)
    }

    @Test
    fun `a limit above the one MiB ceiling is clamped to it`() = runTest {
        val handler = ResponseHandler(limit = Int.MAX_VALUE)
        enqueued(handler, backgroundScope)
        val ceiling: Int = 1024 * 1024

        UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), ByteArray(ceiling + 10), false), WAIT)

        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertEquals(ceiling, delivered.body().size)
        assertTrue(delivered.isBodyTruncated)
    }

    @Test
    fun `classify sees the same trimmed body as onDelivered`() = runTest {
        val handler = ResponseHandler(limit = 2)
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(200, emptyMap(), ByteArray(9), false), WAIT)

        assertEquals(2, assertNotNull(handler.classified.single().second).body().size)
        assertTrue(assertNotNull(handler.classified.single().second).isBodyTruncated)
    }

    // --- Handlers written for 3.0 --------------------------------------------------------------------

    @Test
    fun `an old two-argument classify and one-argument onDelivered are still called`() = runTest {
        val handler = LegacyHandler()
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResponse(200, mapOf("a" to "b"), ByteArray(3), false), WAIT)

        assertEquals(listOf<UploadResult>(UploadResult.Completed(200)), handler.classified)
        assertEquals(1, handler.deliveredCalls)
        assertNull(store.find("item-1"))
    }

    @Test
    fun `an old handler still works for a status-only completion`() = runTest {
        val handler = LegacyHandler()
        enqueued(handler, backgroundScope)

        UploadGateway.complete("item-1", UploadResult.Completed(200), WAIT)

        assertEquals(1, handler.classified.size)
        assertEquals(1, handler.deliveredCalls)
    }

    @Test
    fun `a handler that overrides no classify uses the default classification`() = runTest {
        val delivering = BareHandler(type = "bare-ok")
        val rejecting = BareHandler(type = "bare-bad")
        val started: UploaderEngine = testEngine(store, listOf(delivering, rejecting), backgroundScope, clock = TestClock(1_000L))
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(delivering, "a")
        started.enqueue(rejecting, "b")
        started.drain()

        UploadGateway.complete("item-1", UploadResponse(201, emptyMap(), ByteArray(0), false), WAIT)
        UploadGateway.complete("item-2", UploadResponse(404, emptyMap(), ByteArray(0), false), WAIT)

        assertNull(store.find("item-1"), "a 2xx is delivered")
        assertNull(store.find("item-2"), "a 4xx is dropped")
    }

    // --- Doubles ------------------------------------------------------------------------------------

    private companion object {
        val WAIT = 10.milliseconds
    }

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private abstract class BaseHandler(override val type: String) : UploadHandler<String>(NoopTransport()) {
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "https://example.com/u", fields = emptyList()))
    }

    private class ResponseHandler(
        private val limit: Int = DEFAULT_MAX_RESPONSE_BODY_BYTES,
        private val limitGetter: (() -> Int)? = null,
        private val classifier: (UploadResult, UploadResponse?) -> SettleResult = { result, _ ->
            defaultUploadClassification(result)
        },
        private val deliveredHook: () -> Unit = {},
    ) : BaseHandler("response") {

        val classified = mutableListOf<Pair<UploadResult, UploadResponse?>>()
        val delivered = mutableListOf<UploadResponse?>()

        override val maxResponseBodyBytes: Int get() = limitGetter?.invoke() ?: limit

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            classified += result to response
            return classifier(result, response)
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
            deliveredHook()
        }
    }

    /** Written for 3.0: knows only the two-argument classify and the one-argument onDelivered. */
    private class LegacyHandler : BaseHandler("legacy") {
        val classified = mutableListOf<UploadResult>()
        var deliveredCalls = 0

        override suspend fun classify(payload: String, result: UploadResult): SettleResult {
            classified += result
            return defaultUploadClassification(result)
        }

        override suspend fun onDelivered(payload: String) {
            deliveredCalls++
        }
    }

    private class BareHandler(type: String) : BaseHandler(type)
}
