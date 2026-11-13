package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith

/**
 * The multipart body and the answer reading against odd inputs: an empty or vanished source, a huge body, field
 * names that carry quoting characters, and answers the JDK's streaming mode treats specially (a challenge, a
 * redirect, a trickled header). Every test talks to a real loopback server and is bounded by socket timeouts
 * and by joins on its own threads.
 */
@RunWith(AndroidJUnit4::class)
class MultipartBodyAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket(0)
    private val hits = AtomicInteger(0)
    private val seen = AtomicReference("")
    private val cleanup = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        server.close()
        cleanup.forEach { it.deleteRecursively() }
    }

    private fun url(): String = "http://127.0.0.1:${server.localPort}/upload"

    /** Reads one request to the end of its chunked body (remembering what it saw), then answers with [raw] and closes. */
    private fun answer(raw: String, afterRequest: (Socket) -> Unit = {}) {
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            hits.incrementAndGet()
            socket.use {
                it.soTimeout = 20_000
                val input = it.getInputStream()
                val text = StringBuilder()
                val buffer = ByteArray(64 * 1024)
                var tail = ""
                while (!tail.endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    val chunk = String(buffer, 0, read, Charsets.ISO_8859_1)
                    if (text.length < 2_000_000) text.append(chunk)
                    tail = (tail + chunk).takeLast(16)
                }
                seen.set(text.toString())
                afterRequest(it)
                it.getOutputStream().write(raw.encodeToByteArray())
                it.getOutputStream().flush()
            }
        }
    }

    private fun tempFile(name: String, bytes: Long): File =
        File(context.cacheDir, "mp-$name-${System.nanoTime()}").also {
            RandomAccessFile(it, "rw").use { file -> file.setLength(bytes) }
            cleanup += it
        }

    private fun upload(request: UploadRequest, readTimeoutMillis: Int = 5_000, isCancelled: () -> Boolean = { false }): MultipartOutcome =
        performMultipartUploadCapturing(request, 5_000, readTimeoutMillis, isCancelled = isCancelled)

    private val ok = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    @Test
    fun `a 302 redirect is the server's answer, not a transport failure that uploads the file again`() {
        answer("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(302), outcome.result, "a received status must reach the handler's classify")
    }

    @Test
    fun `a 307 redirect is the server's answer, not a transport failure that uploads the file again`() {
        answer("HTTP/1.1 307 Temporary Redirect\r\nLocation: /again\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(307), outcome.result, "a received status must reach the handler's classify")
    }

    @Test
    fun `an empty file part and a request with no part at all both complete`() {
        answer(ok)
        val empty: File = tempFile("empty", 0)
        val first: MultipartOutcome =
            upload(UploadRequest(url(), fields = listOf(UploadField.File("f", "e.bin", "application/octet-stream", empty.path))))
        assertEquals(UploadResult.Completed(201), first.result)
        assertTrue(seen.get().contains("filename=\"e.bin\""), "the empty part is still framed")

        answer(ok)
        val second: MultipartOutcome = upload(UploadRequest(url(), fields = emptyList()))
        assertEquals(UploadResult.Completed(201), second.result)
    }

    @Test
    fun `a source that vanishes between the pre-flight and the open is a transport failure, never a throw`() {
        answer(ok)
        val file: File = tempFile("vanish", 1024)
        val outcome: MultipartOutcome = upload(
            UploadRequest(url(), fields = listOf(UploadField.File("f", "v.bin", "application/octet-stream", file.path))),
            // First poll happens when the first boundary is written: after the pre-flight, before the file opens.
            isCancelled = { file.delete(); false },
        )

        assertIs<UploadResult.TransportFailure>(outcome.result)
    }

    @Test
    fun `a body of tens of megabytes streams through and the answer still lands`() {
        answer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        val file: File = tempFile("big", 48L * 1024 * 1024)

        val outcome: MultipartOutcome = upload(
            UploadRequest(
                url(),
                fields = listOf(
                    UploadField.Text("note", "x".repeat(8 * 1024 * 1024)),
                    UploadField.File("f", "big.bin", "application/octet-stream", file.path),
                ),
            ),
            readTimeoutMillis = 20_000,
        )

        assertEquals(UploadResult.Completed(200), outcome.result)
        assertEquals("ok", assertNotNull(outcome.response).body().decodeToString())
    }

    @Test
    fun `a PUT is sent as a PUT with the whole multipart body`() {
        answer(ok)
        val outcome: MultipartOutcome = upload(UploadRequest(url(), method = "PUT", fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(201), outcome.result)
        assertTrue(seen.get().startsWith("PUT /upload "), "request line was: ${seen.get().lineSequence().first()}")
        assertTrue(seen.get().contains("name=\"a\""))
    }

    @Test
    fun `a custom Content-Type header cannot replace the multipart boundary the body is framed with`() {
        answer(ok)
        val outcome: MultipartOutcome = upload(
            UploadRequest(url(), headers = mapOf("Content-Type" to "application/json"), fields = listOf(UploadField.Text("a", "b"))),
        )

        assertEquals(UploadResult.Completed(201), outcome.result)
        val head: String = seen.get().substringBefore("\r\n\r\n")
        assertTrue(head.contains("multipart/form-data; boundary=kmptoolkit-uploader-"), "headers were: $head")
        assertTrue(!head.contains("application/json"))
    }

    @Test
    fun `a chunked answer that ends without its terminator keeps the bytes it had and is flagged`() {
        answer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nhello\r\n5\r\nworld\r\n")

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(200), outcome.result)
        val response: UploadResponse = assertNotNull(outcome.response)
        assertEquals("helloworld", response.body().decodeToString())
        assertTrue(response.isBodyTruncated)
    }

    @Test
    fun `a server that promises a body and then goes silent is cut at the read timeout with the status kept`() {
        val hold = java.util.concurrent.CountDownLatch(1)
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                it.soTimeout = 20_000
                val input = it.getInputStream()
                val buffer = ByteArray(8192)
                var tail = ""
                while (!tail.endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) return@thread
                    tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                }
                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc".encodeToByteArray())
                it.getOutputStream().flush()
                hold.await(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val startedAt: Long = System.nanoTime()
        // Under the watchdog, as both workers run it: the transfer on its own has no deadline for the headers.
        val outcome: MultipartOutcome = runBlocking {
            withWriteWatchdog(stallMillis = 1_000) { watch ->
                withContext(Dispatchers.IO) {
                    performMultipartUploadCapturing(
                        UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))), 5_000, 1_000, watch = watch,
                    )
                }
            }
        }
        val tookMillis: Long = (System.nanoTime() - startedAt) / 1_000_000
        hold.countDown()

        assertEquals(UploadResult.Completed(200), outcome.result)
        val response: UploadResponse = assertNotNull(outcome.response)
        assertEquals("abc", response.body().decodeToString())
        assertTrue(response.isBodyTruncated)
        assertTrue(tookMillis < 4_000, "took $tookMillis ms against a 1000 ms read timeout")
    }

    @Test
    fun `an interim 100 Continue before the real answer is skipped`() {
        answer("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 Created\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(201), outcome.result)
        assertEquals("ok", assertNotNull(outcome.response).body().decodeToString())
    }

    @Test
    fun `a reply that is not an HTTP status is a transport failure, never a completed attempt`() {
        answer("garbage-reply-not-http\r\n\r\n")

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertIs<UploadResult.TransportFailure>(outcome.result, "a non-HTTP reply reached classify as ${outcome.result}")
        assertNull(outcome.response)
    }

    @Test
    fun `an HTTP 1_0 answer with no length is read to the end of the connection`() {
        answer("HTTP/1.0 200 OK\r\n\r\n" + "z".repeat(200_000))

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(200), outcome.result)
        assertEquals(200_000, assertNotNull(outcome.response).body().size)
    }

    @Test
    fun `a 401 that carries a challenge is the server's answer, not a transport failure`() {
        answer("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"x\"\r\nContent-Length: 5\r\nConnection: close\r\n\r\nnope!")

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(401), outcome.result, "a received status must reach the handler's classify")
        val response: UploadResponse = assertNotNull(outcome.response)
        // Either the body or an honest flag: a stack that drops the connection to retry a 401 cannot deliver the body.
        assertTrue(response.body().decodeToString() == "nope!" || response.isBodyTruncated, "an empty body that is not flagged says the server sent nothing")
    }

    @Test
    fun `a 304 that names the length of the entity it did not send is not a truncated body`() {
        answer("HTTP/1.1 304 Not Modified\r\nContent-Length: 1234\r\nConnection: close\r\n\r\n")

        val outcome: MultipartOutcome = upload(UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))))

        assertEquals(UploadResult.Completed(304), outcome.result)
        val response: UploadResponse = assertNotNull(outcome.response)
        assertEquals(0, response.body().size)
        assertTrue(!response.isBodyTruncated, "a response that carries no body by definition was flagged as cut short")
    }

    @Test
    fun `a server that trickles its status line header by header cannot hold the transfer far past the read timeout`() {
        val thread = thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                it.soTimeout = 20_000
                val input = it.getInputStream()
                val buffer = ByteArray(8 * 1024)
                var tail = ""
                while (!tail.endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                }
                val out = it.getOutputStream()
                runCatching {
                    out.write("HTTP/1.1 200 OK\r\n".encodeToByteArray()); out.flush()
                    // One header byte every 200 ms: no single read ever exceeds the 1 s read timeout.
                    repeat(40) { index -> out.write("X-Slow-$index: ".encodeToByteArray()); out.flush(); Thread.sleep(200) }
                    out.write("\r\n".encodeToByteArray()); out.flush()
                }
            }
        }
        val startedAt: Long = System.nanoTime()
        // Under the watchdog, as both workers run it: the transfer on its own has no deadline for the headers.
        val outcome: MultipartOutcome = runBlocking {
            withWriteWatchdog(stallMillis = 1_000) { watch ->
                withContext(Dispatchers.IO) {
                    performMultipartUploadCapturing(
                        UploadRequest(url(), fields = listOf(UploadField.Text("a", "b"))), 5_000, 1_000, watch = watch,
                    )
                }
            }
        }
        val tookMillis: Long = (System.nanoTime() - startedAt) / 1_000_000
        thread.join(15_000)

        assertTrue(tookMillis < 4_000, "the answer's headers trickled for $tookMillis ms against a 1000 ms read timeout (outcome ${outcome.result})")
    }

    @Test
    fun `field names and file names carrying quotes or line breaks cannot add headers or parts to the body`() {
        answer(ok)
        val file: File = tempFile("odd", 16)
        val outcome: MultipartOutcome = upload(
            UploadRequest(
                url(),
                fields = listOf(
                    UploadField.Text("a\"; x=\"y\r\nX-Injected: 1", "v"),
                    UploadField.File("f", "evil\"\r\nX-Injected-Too: 1\r\n\r\nsmuggled", "text/plain", file.path),
                ),
            ),
        )

        assertEquals(UploadResult.Completed(201), outcome.result)
        val body: String = seen.get()
        assertTrue(!body.contains("\r\nX-Injected: 1"), "a field name must not start a header line of its own")
        assertTrue(!body.contains("\r\nX-Injected-Too: 1"), "a file name must not start a header line of its own")
    }

    @Test
    fun `a directory named as the source is refused before the server is contacted`() {
        answer(ok)
        val dir: File = File(context.cacheDir, "mp-dir-${System.nanoTime()}").apply { mkdirs() }
        cleanup += dir

        val outcome: MultipartOutcome =
            upload(UploadRequest(url(), fields = listOf(UploadField.File("f", "f.bin", "application/octet-stream", dir.path))))

        assertIs<UploadResult.TransportFailure>(outcome.result)
        assertEquals(0, hits.get(), "an unreadable source is a pre-flight failure; no connection is opened for it")
    }

    private fun answerWithoutReading(raw: String) {
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                // Reads the request head (up to the blank line) so the client's header write has landed and
                // cannot race the close (a reset during the head write makes the JDK reconnect to a server that
                // no longer accepts); then answers and closes with the body still unread (ruling R-2).
                it.soTimeout = 20_000 // hang bound only
                val input = it.getInputStream()
                var tail = 0
                while (tail != 4) {
                    val b: Int = input.read()
                    if (b < 0) break
                    tail = when {
                        b == '\r'.code && (tail == 0 || tail == 2) -> tail + 1
                        b == '\n'.code && (tail == 1 || tail == 3) -> tail + 1
                        b == '\r'.code -> 1
                        else -> 0
                    }
                }
                it.getOutputStream().write(raw.encodeToByteArray())
                it.getOutputStream().flush()
            }
        }
    }

    private fun bigSourceRequest(): UploadRequest = UploadRequest(
        url(),
        fields = listOf(UploadField.File("f", "big.bin", "application/octet-stream", tempFile("early", 32L * 1024 * 1024).path)),
    )

    @Test
    fun `a redirect sent before the body ends is the server's answer, not a transport failure`() {
        answerWithoutReading("HTTP/1.1 301 Moved Permanently\r\nLocation: https://example.invalid/upload\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        val outcome: MultipartOutcome = upload(bigSourceRequest())

        assertEquals(UploadResult.Completed(301), outcome.result, "a received status must reach the handler's classify")
    }

    @Test
    fun `a success sent before the body ends is the server's answer, not a transport failure that uploads the file again`() {
        answerWithoutReading("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")

        val outcome: MultipartOutcome = upload(bigSourceRequest())

        assertEquals(UploadResult.Completed(200), outcome.result, "a received status must reach the handler's classify")
    }

    @Test
    fun `a source that fails mid-body never reaches the server as a complete request`() {
        val done = CountDownLatch(1)
        answer(ok, afterRequest = { done.countDown() })
        val file: File = tempFile("midbody", 1024)
        val outcome: MultipartOutcome = upload(
            UploadRequest(url(), fields = listOf(UploadField.File("f", "v.bin", "application/octet-stream", file.path))),
            isCancelled = { file.delete(); false },
        )

        assertIs<UploadResult.TransportFailure>(outcome.result)
        assertTrue(done.await(10, TimeUnit.SECONDS), "the server never saw the connection end")
        assertTrue(!seen.get().endsWith("0\r\n\r\n"), "a local failure must drop the connection, not send the final chunk")
    }
}
