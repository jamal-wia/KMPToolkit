package io.github.jamal_wia.kmptoolkit.uploader

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 5 attacks on the multipart transfer through a real local socket server: replies the earlier rounds did not
 * script (early hints before the final answer, a silent close, a flood of headers, sloppy line endings, a missing
 * reason phrase) and the one property a re-upload depends on: a request is sent once. Every wait is bounded.
 */
class MultipartSocketRound5AdversarialTest {

    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val connections = AtomicInteger(0)

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun url(): String = "http://127.0.0.1:${server.localPort}/upload"

    /** Accepts every connection, reads each request to its final chunk, and lets [respond] answer. */
    private fun serve(respond: (Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        it.soTimeout = 15_000
                        val input = it.getInputStream()
                        val buffer = ByteArray(16 * 1024)
                        var tail = ""
                        while (!tail.endsWith("0\r\n\r\n")) {
                            val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                            if (read < 0) return@thread
                            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                        }
                        runCatching { respond(it) }
                    }
                }
            }
        }
    }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
    }

    private fun upload(readTimeoutMillis: Int = 4_000): MultipartOutcome = performMultipartUploadCapturing(
        UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))),
        connectTimeoutMillis = 4_000,
        readTimeoutMillis = readTimeoutMillis,
    )

    /**
     * An early hint (`103`) before the final answer on the same connection: the final status is the server's answer and
     * must reach the handler. Reading the 103 as the answer and failing the transfer would upload an accepted file
     * again. (Device stacks skip informational replies; this pins the JDK stack the host tests run on.)
     */
    @Test
    fun `a 103 early hint before the final 201 is skipped and the 201 is the answer`() {
        serve { it.send("HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\nHTTP/1.1 201 Created\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok") }

        val outcome: MultipartOutcome = upload()

        assertEquals(UploadResult.Completed(201), outcome.result, "the interim 103 was taken for the answer: ${outcome.result}")
        assertEquals("ok", outcome.response?.body()?.decodeToString())
    }

    /** A server that reads the whole request and closes without a byte must not make the transport send it twice. */
    @Test
    fun `a silent close after the request is one transport failure and one request`() {
        serve { /* closes without answering */ }

        val outcome: MultipartOutcome = upload()
        Thread.sleep(1_000) // a silent retry by the stack would connect again here

        assertIs<UploadResult.TransportFailure>(outcome.result)
        assertNull(outcome.response)
        assertEquals(1, connections.get(), "the request was sent ${connections.get()} times")
    }

    /** A flood of response headers neither throws nor hangs; the status stands. */
    @Test
    fun `thirty thousand response headers keep the status and never throw`() {
        serve { socket ->
            val headers = StringBuilder("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n")
            repeat(30_000) { headers.append("x-h$it: v$it\r\n") }
            headers.append("\r\n")
            socket.send(headers.toString())
        }

        val outcome: MultipartOutcome = upload()

        val result: UploadResult = outcome.result
        assertTrue(
            result is UploadResult.Completed && result.statusCode == 200 || result is UploadResult.TransportFailure,
            "unexpected result $result",
        )
        if (result is UploadResult.Completed) assertNotNull(outcome.response)
    }

    /** The same flood through the journal: whatever the capture kept is recorded, read back valid and within the entry bound. */
    @Test
    fun `a flood of response headers is journaled whole or cut but always readable`() {
        serve { socket ->
            val headers = StringBuilder("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n")
            repeat(30_000) { headers.append("x-h$it: v$it\r\n") }
            headers.append("\r\n")
            socket.send(headers.toString())
        }
        val outcome: MultipartOutcome = upload()
        val response: UploadResponse = outcome.response ?: return // a stack that refused the flood is also an answer
        val dir: java.io.File = java.nio.file.Files.createTempDirectory("r5-flood").toFile()
        try {
            val journal = OutcomeJournal(AndroidOutcomeStorage(java.io.File(dir, "outcomes"), java.io.File(dir, "partial")))
            assertTrue(journal.record("flood", response), "the journal refused a flooded answer")
            val entry: JournalEntry = journal.inspect("flood")
            assertIs<JournalEntry.Valid>(entry, "a recorded answer must read back valid, got $entry")
            assertEquals(200, entry.response.statusCode)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a status line without a reason phrase is still the answer`() {
        serve { it.send("HTTP/1.1 201\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") }

        assertEquals(UploadResult.Completed(201), upload().result)
    }

    @Test
    fun `bare line feeds instead of carriage returns still parse`() {
        serve { it.send("HTTP/1.1 202 Accepted\nContent-Length: 3\nConnection: close\n\nabc") }

        val outcome: MultipartOutcome = upload()

        assertEquals(UploadResult.Completed(202), outcome.result)
        assertEquals("abc", outcome.response?.body()?.decodeToString())
    }

    /** A length the server cannot honour: never a crash, and the attempt is classified by what arrived. */
    @Test
    fun `a negative content length is an answer or a transport failure, never a throw`() {
        serve { it.send("HTTP/1.1 200 OK\r\nContent-Length: -5\r\nConnection: close\r\n\r\nabc") }

        val outcome: MultipartOutcome = upload()

        val result: UploadResult = outcome.result
        assertTrue(result is UploadResult.Completed || result is UploadResult.TransportFailure, "unexpected result $result")
    }

    /** A reply of exactly the headers' end with a huge declared length and no body, then silence: bounded by the read timeout. */
    @Test
    fun `a huge declared length with no body is cut at the read timeout with the status kept`() {
        serve {
            it.send("HTTP/1.1 200 OK\r\nContent-Length: 9223372036854775807\r\n\r\n")
            Thread.sleep(6_000)
        }

        val startedAt: Long = System.nanoTime()
        val outcome: MultipartOutcome = upload(readTimeoutMillis = 1_500)
        val tookMillis: Long = (System.nanoTime() - startedAt) / 1_000_000L

        assertEquals(UploadResult.Completed(200), outcome.result)
        assertEquals(true, outcome.response?.isBodyTruncated, "a body that never arrived must be flagged")
        assertTrue(tookMillis < 5_000, "the transfer took $tookMillis ms")
    }
}
