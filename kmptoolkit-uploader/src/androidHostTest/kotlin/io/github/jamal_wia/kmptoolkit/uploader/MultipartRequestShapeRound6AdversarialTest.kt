package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 6 (docs versus code): what `UploadRequest` and docs 08 promise about the request the Android transfer sends:
 * the method the consumer named, the multipart body in wire order with every part intact (a file of any size and any
 * name, text with non-ASCII), the consumer's headers, and that the body is the exact concatenation the boundary frames.
 * A real socket reads the whole request; every wait is bounded.
 */
class MultipartRequestShapeRound6AdversarialTest {

    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val dir: File = Files.createTempDirectory("r6-shape").toFile()
    private val captured = AtomicReference<ByteArray>()
    private val arrived = CountDownLatch(1)

    @AfterTest
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun url(): String = "http://127.0.0.1:${server.localPort}/upload?x=1&y=%C3%A9"

    private fun serveOnce() {
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                it.soTimeout = 10_000
                val input = it.getInputStream()
                val all = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (!all.toString(Charsets.ISO_8859_1).endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    all.write(buffer, 0, read)
                }
                captured.set(all.toByteArray())
                arrived.countDown()
                it.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                it.getOutputStream().flush()
            }
        }
    }

    private fun wire(): String {
        assertTrue(arrived.await(10, TimeUnit.SECONDS), "the server never saw a request")
        return String(captured.get(), Charsets.ISO_8859_1)
    }

    /** The chunked framing removed: the payload bytes the server would reassemble. */
    private fun dechunked(raw: String): ByteArray {
        val body: ByteArray = raw.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < body.size) {
            var end: Int = i
            while (body[end] != '\r'.code.toByte()) end++
            val size: Int = String(body, i, end - i, Charsets.ISO_8859_1).trim().toInt(16)
            if (size == 0) break
            out.write(body, end + 2, size)
            i = end + 2 + size + 2
        }
        return out.toByteArray()
    }

    @Test
    fun `the method the consumer names is the method on the wire`() {
        serveOnce()
        val outcome = performMultipartUploadCapturing(
            UploadRequest(url(), method = "PUT", fields = listOf(UploadField.Text("a", "b"))),
            4_000,
            4_000,
        )
        assertEquals(UploadResult.Completed(204), outcome.result)
        val requestLine: String = wire().lineSequence().first()
        assertEquals("PUT /upload?x=1&y=%C3%A9 HTTP/1.1", requestLine)
    }

    @Test
    fun `the body is the parts in wire order, intact, framed by one boundary`() {
        val file = File(dir, "payload.bin").apply { writeBytes(ByteArray(70_000) { (it * 7).toByte() }) }
        val empty = File(dir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        serveOnce()
        val outcome = performMultipartUploadCapturing(
            UploadRequest(
                url(),
                headers = mapOf("X-Token" to "t-1", "content-type" to "application/json"),
                fields = listOf(
                    UploadField.Text("first", "caf\u00E9 \u65E5\u672C"),
                    UploadField.File("file", "weird name \u00e9 \"q\".bin", "application/octet-stream", file.path),
                    UploadField.Text("empty-text", ""),
                    UploadField.File("nothing", "empty.bin", "text/plain", empty.path),
                    UploadField.Text("last", "z"),
                ),
            ),
            4_000,
            4_000,
        )
        assertEquals(UploadResult.Completed(204), outcome.result)
        val raw: String = wire()
        val headerBlock: String = raw.substringBefore("\r\n\r\n").lowercase()
        assertTrue("x-token: t-1" in headerBlock, "the consumer's header is missing: $headerBlock")
        assertEquals(1, Regex("content-type:").findAll(headerBlock).count(), "the content type must be sent once: $headerBlock")
        assertTrue("multipart/form-data; boundary=" in headerBlock, "the multipart content type replaces the consumer's: $headerBlock")
        val payload: ByteArray = dechunked(raw)
        val boundary: String = Regex("boundary=([^\\r\\n]+)").find(raw)!!.groupValues[1]
        val text: String = String(payload, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--$boundary\r\n"), "the body must start with the boundary")
        assertTrue(text.endsWith("\r\n--$boundary--\r\n"), "the body must end with the closing boundary")
        assertEquals(6, Regex("--" + Regex.escape(boundary) + "(--)?\r\n").findAll(text).count(), "four parts and one closing boundary plus the fifth part")
        val order: List<Int> = listOf("name=\"first\"", "name=\"file\"", "name=\"empty-text\"", "name=\"nothing\"", "name=\"last\"").map { text.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), "the parts left the wire order: $order")
        val fileBytes: ByteArray = file.readBytes()
        val start: Int = text.indexOf("\r\n\r\n", text.indexOf("name=\"file\"")) + 4
        assertTrue(
            payload.copyOfRange(start, start + fileBytes.size).contentEquals(fileBytes) &&
                text.substring(start + fileBytes.size).startsWith("\r\n--$boundary"),
            "the file part is not the file",
        )
        assertTrue(String(payload, Charsets.UTF_8).contains("caf\u00E9 \u65E5\u672C"), "the text part lost its characters")
    }
}
