package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * What the Android transport reads of the server's answer, against a real local HTTP server: the status,
 * the headers without the status line, the body from the error stream for a 4xx/5xx, the ceiling, and the
 * guarantee that a body that fails to read never turns a received status into a retryable failure.
 */
@RunWith(AndroidJUnit4::class)
class UploadResponseCaptureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket(0)
    private var engine: UploaderEngine? = null

    @AfterTest
    fun cleanUp() {
        server.close()
        engine?.close()
    }

    private fun request(): UploadRequest = UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList())

    /**
     * Ends a server's side of a connection the way a well-behaved server does: its answer is followed by a FIN and
     * whatever the client still sends is read and thrown away until the client closes. Closing a socket with unread
     * input makes the stack send a RST instead, and a RST may destroy an answer the client has not read yet — whether
     * it does is up to the stack and the timing, which made these tests fail now and then for no reason of the code
     * under test. A test that wants to see a reset says so itself.
     */
    private fun finishAnswer(socket: Socket) {
        runCatching {
            socket.shutdownOutput()
            socket.soTimeout = 2_000
            val sink = ByteArray(8 * 1024)
            val input = socket.getInputStream()
            while (input.read(sink) >= 0) { /* discard */ }
        }
    }

    /** Reads one request to the end of its chunked body, answers with [raw] and closes. */
    private fun answer(raw: ByteArray) {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val seen = StringBuilder()
                val buffer = ByteArray(4 * 1024)
                while (!seen.endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    seen.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                }
                socket.getOutputStream().write(raw)
                socket.getOutputStream().flush()
                finishAnswer(socket)
            }
        }
    }

    private fun http(status: String, headers: List<String>, body: ByteArray, declaredLength: Int = body.size): ByteArray =
        (listOf("HTTP/1.1 $status") + headers + listOf("Content-Length: $declaredLength", "Connection: close", "", ""))
            .joinToString("\r\n").encodeToByteArray() + body

    private fun capture(): MultipartOutcome = performMultipartUploadCapturing(request(), 5_000, 5_000)

    @Test
    fun `a 201 gives its status headers and body`() {
        answer(http("201 Created", listOf("Location: /files/9", "X-Request-Id: abc"), """{"id":9}""".encodeToByteArray()))

        val outcome: MultipartOutcome = capture()

        assertEquals(UploadResult.Completed(201), outcome.result)
        val response: UploadResponse = assertNotNull(outcome.response)
        assertEquals(201, response.statusCode)
        assertEquals("""{"id":9}""", response.body().decodeToString())
        assertEquals("/files/9", response.header("location"))
        assertEquals("abc", response.header("X-REQUEST-ID"))
        assertFalse(response.isBodyTruncated)
    }

    @Test
    fun `the status line is not a header`() {
        answer(http("200 OK", listOf("A: 1"), ByteArray(0)))

        val response: UploadResponse = assertNotNull(capture().response)

        assertTrue(response.headers.keys.none { it.startsWith("http/") || it.isEmpty() }, "got ${response.headers.keys}")
        assertEquals("1", response.header("a"))
    }

    @Test
    fun `repeated headers are joined`() {
        answer(http("200 OK", listOf("Vary: Accept", "Vary: Origin"), ByteArray(0)))

        val response: UploadResponse = assertNotNull(capture().response)

        assertEquals("Accept, Origin", response.header("vary"))
    }

    @Test
    fun `a 422 body is read from the error stream`() {
        answer(http("422 Unprocessable Entity", listOf("Content-Type: application/json"), """{"error":"name is blank"}""".encodeToByteArray()))

        val outcome: MultipartOutcome = capture()

        assertEquals(UploadResult.Completed(422), outcome.result)
        assertEquals("""{"error":"name is blank"}""", assertNotNull(outcome.response).body().decodeToString())
    }

    @Test
    fun `a 500 with no body has an empty body and not null`() {
        answer(http("500 Internal Server Error", emptyList(), ByteArray(0)))

        val response: UploadResponse = assertNotNull(capture().response)

        assertEquals(500, response.statusCode)
        assertContentEquals(ByteArray(0), response.body())
        assertFalse(response.isBodyTruncated)
    }

    @Test
    fun `a 204 is an empty body`() {
        answer("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".encodeToByteArray())

        val outcome: MultipartOutcome = capture()

        assertEquals(UploadResult.Completed(204), outcome.result)
        assertContentEquals(ByteArray(0), assertNotNull(outcome.response).body())
    }

    @Test
    fun `a body above the ceiling is cut to it and flagged`() {
        answer(http("200 OK", emptyList(), ByteArray(MAX_RESPONSE_BODY_BYTES + 500) { 7 }))

        val response: UploadResponse = assertNotNull(capture().response)

        assertEquals(MAX_RESPONSE_BODY_BYTES, response.body().size)
        assertTrue(response.isBodyTruncated)
    }

    @Test
    fun `a body of exactly the ceiling is not flagged`() {
        answer(http("200 OK", emptyList(), ByteArray(MAX_RESPONSE_BODY_BYTES)))

        val response: UploadResponse = assertNotNull(capture().response)

        assertEquals(MAX_RESPONSE_BODY_BYTES, response.body().size)
        assertFalse(response.isBodyTruncated)
    }

    @Test
    fun `a body cut short by the server keeps the received status and is flagged`() {
        // Declares 100 bytes, sends 10 and closes: reading the body fails, the 200 was still received.
        answer(http("200 OK", emptyList(), ByteArray(10), declaredLength = 100))

        val outcome: MultipartOutcome = capture()

        assertEquals(UploadResult.Completed(200), outcome.result, "an accepted upload must not read as a transport failure")
        assertTrue(assertNotNull(outcome.response).isBodyTruncated)
    }

    @Test
    fun `the bytes read before a body breaks are kept`() {
        answer(http("200 OK", emptyList(), "0123456789".encodeToByteArray(), declaredLength = 100))

        val response: UploadResponse = assertNotNull(capture().response)

        assertEquals("0123456789", response.body().decodeToString())
        assertTrue(response.isBodyTruncated)
    }

    @Test
    fun `a body that trickles in is cut at the total read deadline and the status still lands`() {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val seen = StringBuilder()
                val buffer = ByteArray(4 * 1024)
                while (!seen.endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    seen.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                }
                val out = socket.getOutputStream()
                runCatching {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    repeat(100) {
                        out.write(1)
                        out.flush()
                        Thread.sleep(50)
                    }
                }
            }
        }
        val startedAt: Long = System.currentTimeMillis()

        val outcome: MultipartOutcome = performMultipartUploadCapturing(request(), 5_000, readTimeoutMillis = 500)

        assertTrue(System.currentTimeMillis() - startedAt < 4_000, "the read must stop at the deadline, not follow the trickle")
        assertEquals(UploadResult.Completed(200), outcome.result)
        val response: UploadResponse = assertNotNull(outcome.response)
        assertTrue(response.isBodyTruncated)
        assertTrue(response.body().size < 100)
    }

    @Test
    fun `a server that rejects before the body ends still gives its status and a response`() {
        val source: java.io.File = java.io.File(context.cacheDir, "late.bin").apply { writeBytes(ByteArray(4 * 1024 * 1024) { 1 }) }
        try {
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    // Answers without reading the request, so the answer comes before the body ends; it then reads the
                    // rest and discards it, so that closing does not reset the connection under the answer.
                    socket.getOutputStream().write(http("413 Payload Too Large", emptyList(), "too big".encodeToByteArray()))
                    socket.getOutputStream().flush()
                    finishAnswer(socket)
                }
            }
            val outcome: MultipartOutcome = performMultipartUploadCapturing(
                UploadRequest(
                    url = "http://127.0.0.1:${server.localPort}/upload",
                    fields = listOf(UploadField.File("file", "late.bin", "application/octet-stream", source.path)),
                ),
                5_000,
                5_000,
            )

            // Whether the write fails first is up to the socket stack; either way a received 413 must
            // never be read as a retryable transport failure. The server drains the request, so the 413 is received.
            assertEquals(UploadResult.Completed(413), outcome.result)
            val response: UploadResponse = assertNotNull(outcome.response)
            assertEquals(413, response.statusCode)
            // Either the stack read the whole body, or the reset left it unreadable and that is flagged.
            val body: String = response.body().decodeToString()
            assertTrue(body == "too big" || (body.isEmpty() && response.isBodyTruncated), "body=$body truncated=${response.isBodyTruncated}")
        } finally {
            source.delete()
        }
    }

    @Test
    fun `the status-only variant reads no response`() {
        answer(http("201 Created", emptyList(), "ignored".encodeToByteArray()))

        val outcome: MultipartOutcome = performMultipartUploadCapturing(request(), 5_000, 5_000, captureResponse = false)

        assertEquals(UploadResult.Completed(201), outcome.result)
        assertNull(outcome.response)
    }

    @Test
    fun `a transport failure has no response`() {
        val unused = ServerSocket(0).use { it.localPort }

        val outcome: MultipartOutcome = performMultipartUploadCapturing(
            UploadRequest(url = "http://127.0.0.1:$unused/upload", fields = emptyList()),
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 2_000,
        )

        assertIs<UploadResult.TransportFailure>(outcome.result)
        assertNull(outcome.response)
    }

    // --- Through the worker and the engine ---------------------------------------------------------

    @Test
    fun `the worker delivers a 201 body to onDelivered`() = runTest {
        val handler = CapturingHandler()
        val store = TestUploaderStore()
        val started: UploaderEngine = start(store, handler, backgroundScope)
        started.enqueue(handler, "one")
        started.drain()
        answer(http("201 Created", listOf("Location: /jobs/5"), """{"job":5}""".encodeToByteArray()))

        val result: ListenableWorker.Result = runWorker("item-1")

        assertIs<ListenableWorker.Result.Success>(result)
        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertEquals("""{"job":5}""", delivered.body().decodeToString())
        assertEquals("/jobs/5", delivered.header("location"))
        assertNull(store.find("item-1"))
    }

    @Test
    fun `the worker hands a 422 body to the three-argument classify`() = runTest {
        val handler = CapturingHandler()
        val store = TestUploaderStore()
        val started: UploaderEngine = start(store, handler, backgroundScope)
        started.enqueue(handler, "one")
        started.drain()
        answer(http("422 Unprocessable Entity", emptyList(), "bad name".encodeToByteArray()))

        runWorker("item-1")

        val (result: UploadResult, response: UploadResponse?) = handler.classified.single()
        assertEquals(UploadResult.Completed(422), result)
        assertEquals("bad name", assertNotNull(response).body().decodeToString())
        assertTrue(handler.delivered.isEmpty())
    }

    @Test
    fun `the worker applies the handler's limit to the body it read`() = runTest {
        val handler = CapturingHandler(limit = 3)
        val started: UploaderEngine = start(TestUploaderStore(), handler, backgroundScope)
        started.enqueue(handler, "one")
        started.drain()
        answer(http("200 OK", emptyList(), "abcdefgh".encodeToByteArray()))

        runWorker("item-1")

        val delivered: UploadResponse = assertNotNull(handler.delivered.single())
        assertEquals("abc", delivered.body().decodeToString())
        assertTrue(delivered.isBodyTruncated)
    }

    @Test
    fun `the worker with an unreachable server reports a transport failure and no response`() = runTest {
        val handler = CapturingHandler(port = ServerSocket(0).use { it.localPort })
        val started: UploaderEngine = start(TestUploaderStore(), handler, backgroundScope)
        started.enqueue(handler, "one")
        started.drain()

        runWorker("item-1")

        val (result: UploadResult, response: UploadResponse?) = handler.classified.single()
        assertIs<UploadResult.TransportFailure>(result)
        assertNull(response)
    }

    private suspend fun runWorker(itemId: String): ListenableWorker.Result =
        TestListenableWorkerBuilder<UploadHandlerWorker>(context)
            .setInputData(workDataOf(UploadHandlerWorker.ITEM_ID_KEY to itemId))
            .build()
            .doWork()

    private fun start(store: TestUploaderStore, handler: UploaderHandler<*>, scope: CoroutineScope): UploaderEngine =
        testEngine(store, listOf(handler), scope).also {
            engine = it
            UploaderEngineRegistry.register(it)
        }

    private inner class CapturingHandler(
        private val limit: Int = UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES,
        private val port: Int = server.localPort,
    ) : UploadHandler<String>(NoopTransport()) {
        val classified = mutableListOf<Pair<UploadResult, UploadResponse?>>()
        val delivered = mutableListOf<UploadResponse?>()

        override val type: String = "capture"
        override val maxResponseBodyBytes: Int get() = limit
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:$port/upload", fields = emptyList()))

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            classified += result to response
            return defaultUploadClassification(result)
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }
}
