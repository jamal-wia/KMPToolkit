package io.github.jamal_wia.kmptoolkit.uploader

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 7 (real sockets, persisted-response fidelity): how the captured answer is framed. A body the server announced with
 * a length and did not deliver is flagged truncated whatever header names the encoding; the 1 MiB ceiling is exact; chunk
 * extensions and trailers do not leak into the body. Every wait is bounded.
 */
class MultipartResponseFramingRound7AdversarialTest {

    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun serve(respond: (Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
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

    private fun Socket.send(raw: ByteArray) {
        getOutputStream().write(raw)
        getOutputStream().flush()
    }

    private fun Socket.send(raw: String) = send(raw.toByteArray(Charsets.ISO_8859_1))

    private fun upload(): MultipartOutcome = performMultipartUploadCapturing(
        UploadRequest("http://127.0.0.1:${server.localPort}/upload", fields = listOf(UploadField.Text("a", "b"))),
        connectTimeoutMillis = 4_000,
        readTimeoutMillis = 4_000,
    )

    @Test
    fun `a body cut short under an identity content encoding is flagged truncated`() {
        serve { it.send("HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\nContent-Length: 100\r\nConnection: close\r\n\r\n" + "x".repeat(50)) }

        val response: UploadResponse = assertNotNull(upload().response)

        assertEquals(50, response.body().size)
        assertTrue(response.isBodyTruncated, "50 of the 100 announced bytes arrived and the body was reported complete")
    }

    @Test
    fun `a body cut short without any content encoding is flagged truncated`() {
        serve { it.send("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n" + "x".repeat(50)) }

        val response: UploadResponse = assertNotNull(upload().response)

        assertTrue(response.isBodyTruncated)
    }

    @Test
    fun `a body of exactly the ceiling is whole and one byte more is cut to the ceiling and flagged`() {
        for ((size, truncated) in listOf(MAX_RESPONSE_BODY_BYTES - 1 to false, MAX_RESPONSE_BODY_BYTES to false, MAX_RESPONSE_BODY_BYTES + 1 to true)) {
            val body = ByteArray(size) { (it % 253).toByte() }
            val head = "HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            val one = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
            thread(isDaemon = true) {
                runCatching {
                    one.accept().use { s ->
                        s.soTimeout = 15_000
                        val input = s.getInputStream()
                        val buffer = ByteArray(16 * 1024)
                        var tail = ""
                        while (!tail.endsWith("0\r\n\r\n")) {
                            val read: Int = input.read(buffer)
                            if (read < 0) return@thread
                            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                        }
                        s.send(head + body)
                    }
                }
            }
            try {
                val outcome: MultipartOutcome = performMultipartUploadCapturing(
                    UploadRequest("http://127.0.0.1:${one.localPort}/u", fields = listOf(UploadField.Text("a", "b"))),
                    connectTimeoutMillis = 4_000,
                    readTimeoutMillis = 8_000,
                )
                val response: UploadResponse = assertNotNull(outcome.response, "size $size")
                assertEquals(truncated, response.isBodyTruncated, "truncated flag for $size bytes")
                assertContentEquals(body.copyOf(minOf(size, MAX_RESPONSE_BODY_BYTES)), response.body(), "body for $size bytes")
            } finally {
                one.close()
            }
        }
    }

    @Test
    fun `chunk extensions and trailers never reach the body`() {
        serve {
            it.send(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: x-sum\r\nConnection: close\r\n\r\n" +
                    "5;ext=1\r\nhello\r\n3;a=\"b\"\r\n wo\r\n0\r\nx-sum: 1\r\n\r\n",
            )
        }

        val response: UploadResponse = assertNotNull(upload().response)

        assertEquals("hello wo", response.body().decodeToString())
        assertEquals(false, response.isBodyTruncated)
    }

    @Test
    fun `a chunked body cut inside a chunk is flagged truncated and keeps what arrived`() {
        serve { it.send("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n64\r\n" + "y".repeat(40)) }

        val response: UploadResponse = assertNotNull(upload().response)

        assertTrue(response.isBodyTruncated)
        assertTrue(response.body().size <= 40)
    }

    @Test
    fun `a header the server repeats keeps the order it arrived in`() {
        serve {
            it.send(
                "HTTP/1.1 200 OK\r\nX-Multi: a\r\nX-Other: 1\r\nX-Multi: b\r\nX-Multi: c\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            )
        }

        val response: UploadResponse = assertNotNull(upload().response)

        assertEquals("a, b, c", response.header("x-multi"), "the values of a repeated header were joined out of order")
        assertEquals("1", response.header("X-Other"))
    }
}
