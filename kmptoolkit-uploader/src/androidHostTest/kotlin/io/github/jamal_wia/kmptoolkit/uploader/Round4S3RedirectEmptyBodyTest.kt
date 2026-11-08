package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class Round4S3RedirectEmptyBodyTest {

    private val cleanup = mutableListOf<File>()
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { runCatching { it.close() } }
        cleanup.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("uploader-r4-").toFile().also { cleanup += it }

    private fun answerWith(raw: String): ServerSocket {
        val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
        servers += server
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                it.soTimeout = 20_000
                val input = it.getInputStream()
                val buffer = ByteArray(8192)
                var tail = ""
                while (!tail.endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                }
                it.getOutputStream().write(raw.encodeToByteArray())
                it.getOutputStream().flush()
            }
        }
        return server
    }

    @Test
    fun `a redirect that sends no body is not a truncated body`() {
        val flagged = mutableListOf<String>()
        for (status in listOf("300 Multiple Choices", "301 Moved Permanently", "302 Found", "303 See Other", "307 Temporary Redirect", "308 Permanent Redirect")) {
            val server: ServerSocket = answerWith("HTTP/1.1 $status\r\nLocation: /elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            val request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.Text("a", "b")))

            val outcome: MultipartOutcome = performMultipartUploadCapturing(request, 5_000, 5_000)

            val response: UploadResponse = outcome.response ?: fail("$status: no response captured")
            assertEquals(0, response.body().size, "$status: no body was sent")
            if (response.isBodyTruncated) flagged += status
        }
        assertEquals(emptyList(), flagged, "the server sent an empty body and the response says the body was cut")
    }

    /**
     * Guard of the conservative side, no red-before state: the length is unknown and the stack withholds the
     * stream, so a body may have been sent and the honest answer is "could not read it completely".
     */
    @Test
    fun `a redirect whose body length is unknown and withheld is flagged truncated`() {
        val server: ServerSocket = answerWith("HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nConnection: close\r\n\r\nmoved")
        val request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.Text("a", "b")))

        val outcome: MultipartOutcome = performMultipartUploadCapturing(request, 5_000, 5_000)

        val response: UploadResponse = outcome.response ?: fail("no response captured")
        assertEquals(302, response.statusCode)
        assertTrue(response.isBodyTruncated, "the stack withheld a body whose length is unknown")
    }

    @Test
    fun `a redirect that sends a body keeps it or says it lost it`() {
        val server: ServerSocket = answerWith("HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 5\r\nConnection: close\r\n\r\nmoved")
        val request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.Text("a", "b")))

        val outcome: MultipartOutcome = performMultipartUploadCapturing(request, 5_000, 5_000)

        val response: UploadResponse = outcome.response ?: fail("no response captured")
        assertTrue(response.body().decodeToString() == "moved" || response.isBodyTruncated, "the five bytes were neither kept nor reported lost")
    }
}
