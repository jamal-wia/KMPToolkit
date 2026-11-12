package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 9 (A2), environment hostility, connectivity: a peer that resets (RST, not FIN) at every step of an upload, one
 * that streams a header line without end, and many large uploads in parallel. Each outcome is a documented one (a
 * completed answer whose body is flagged when it was cut, or a transport failure), never a throw and never a hang, a
 * reset never makes the transport send the request again, and the memory a transfer holds does not grow with the body.
 * Every wait is bounded.
 */
class MultipartConnectionFlapsRound9AdversarialTest {

    private val servers: MutableList<ServerSocket> = mutableListOf()
    private val tempFiles: MutableList<File> = mutableListOf()

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        tempFiles.forEach { it.delete() }
    }

    private fun newServer(): ServerSocket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }.also { servers += it }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
    }

    private fun Socket.reset() {
        setSoLinger(true, 0)
        close()
    }

    /** Reads the request until the chunked body's closing chunk, or [maxBytes] when it should answer early. */
    private fun Socket.readRequest(untilEnd: Boolean = true, headBytes: Int = 1) {
        val input = getInputStream()
        val buffer = ByteArray(16 * 1024)
        var tail = ""
        var total = 0L
        while (true) {
            val read: Int = input.read(buffer)
            if (read < 0) return
            total += read
            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
            if (!untilEnd && total >= headBytes) return
            if (untilEnd && tail.endsWith("0\r\n\r\n")) return
        }
    }

    private fun serveOnce(accepted: AtomicInteger = AtomicInteger(), respond: (Socket) -> Unit): ServerSocket {
        val server: ServerSocket = newServer()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                accepted.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching { socket.soTimeout = 20_000; respond(socket) }
                    runCatching { socket.close() }
                }
            }
        }
        return server
    }

    private fun upload(server: ServerSocket, request: UploadRequest? = null, readTimeout: Int = 4_000): MultipartOutcome =
        performMultipartUploadCapturing(
            request ?: UploadRequest("http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.Text("a", "b"))),
            connectTimeoutMillis = 4_000,
            readTimeoutMillis = readTimeout,
        )

    @Test
    fun `a reset after the first bytes of a body is a cut answer or a transport failure, never a whole body that is short`() {
        val full = 4_000
        repeat(30) { run ->
            val server: ServerSocket = serveOnce { s ->
                s.readRequest()
                s.send("HTTP/1.1 200 OK\r\nContent-Length: $full\r\nX-Run: $run\r\n\r\n" + "y".repeat(1_500))
                Thread.sleep(run % 3L)
                s.reset()
            }
            val outcome: MultipartOutcome = upload(server)
            when (val result: UploadResult = outcome.result) {
                is UploadResult.Completed -> {
                    assertEquals(200, result.statusCode)
                    val response: UploadResponse = outcome.response!!
                    val body: ByteArray = response.body()
                    assertTrue(body.size <= 1_500, "run $run: ${body.size} bytes of a body the server reset after 1500")
                    assertTrue(response.isBodyTruncated, "run $run: a body reset at ${body.size} of $full bytes was reported whole")
                }
                is UploadResult.TransportFailure -> assertEquals(null, outcome.response, "run $run: a failure with a response")
                else -> error("run $run: unexpected $result")
            }
            server.close()
        }
    }

    @Test
    fun `a reset right after the request is one transport failure and the request is not sent again`() {
        repeat(10) { run ->
            val accepted = AtomicInteger()
            val server: ServerSocket = serveOnce(accepted) { s ->
                s.readRequest()
                s.reset()
            }
            val outcome: MultipartOutcome = upload(server)
            assertTrue(outcome.result is UploadResult.TransportFailure, "run $run: $outcome")
            assertEquals(null, outcome.response)
            Thread.sleep(150)
            assertEquals(1, accepted.get(), "run $run: the transport sent the request again after a reset")
            server.close()
        }
    }

    @Test
    fun `a reset before the request is read is one transport failure and one connection`() {
        val accepted = AtomicInteger()
        val server: ServerSocket = serveOnce(accepted) { s -> s.reset() }
        val outcome: MultipartOutcome = upload(server)
        assertTrue(outcome.result is UploadResult.TransportFailure, "$outcome")
        Thread.sleep(150)
        assertEquals(1, accepted.get())
    }

    @Test
    fun `an early rejection followed by a reset while a large body is still being written ends in bounded time and never throws`() {
        val big: File = Files.createTempFile("r9-big", ".bin").toFile().also { tempFiles += it; RandomAccessFile(it, "rw").use { f -> f.setLength(24L * 1024 * 1024) } }
        repeat(5) { run ->
            val server: ServerSocket = serveOnce { s ->
                s.readRequest(untilEnd = false, headBytes = 200)
                s.send("HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                Thread.sleep(run * 5L)
                s.reset()
            }
            val started: Long = System.nanoTime()
            val outcome: MultipartOutcome = upload(server, UploadRequest("http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.File("f", "big.bin", "application/octet-stream", big.path))), readTimeout = 5_000)
            val tookMillis: Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(tookMillis < 20_000, "run $run: $tookMillis ms")
            when (val result: UploadResult = outcome.result) {
                is UploadResult.Completed -> assertEquals(413, result.statusCode, "run $run")
                is UploadResult.TransportFailure -> assertEquals(null, outcome.response)
                else -> error("run $run: unexpected $result")
            }
            server.close()
        }
    }

    @Test
    fun `a header line that never ends is cut by the stack as a transport failure without exhausting the heap`() {
        val server: ServerSocket = serveOnce { s ->
            s.readRequest()
            s.send("HTTP/1.1 200 OK\r\nX-Endless: ")
            val chunk = "a".repeat(8 * 1024)
            var sent = 0L
            while (sent < 96L * 1024 * 1024) {
                s.send(chunk)
                sent += chunk.length
            }
        }
        val before: Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val started: Long = System.nanoTime()
        val outcome: MultipartOutcome = upload(server, readTimeout = 6_000)
        val tookMillis: Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(tookMillis < 25_000, "$tookMillis ms to give up on an endless header")
        assertTrue(outcome.result is UploadResult.TransportFailure, "an endless header produced $outcome")
        System.gc()
        val growth: Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - before
        assertTrue(growth < 200L * 1024 * 1024, "the transfer retained ${growth / 1024 / 1024} MiB after an endless header")
    }

    @Test
    fun `many large uploads in parallel are streamed, so the heap does not grow with the bodies and every answer lands`() {
        val parallel = 16
        val size: Long = 32L * 1024 * 1024
        val big: File = Files.createTempFile("r9-par", ".bin").toFile().also { tempFiles += it; RandomAccessFile(it, "rw").use { f -> f.setLength(size) } }
        val received = AtomicLong()
        val server: ServerSocket = serveOnce { s ->
            val input = s.getInputStream()
            val buffer = ByteArray(64 * 1024)
            var tail = ""
            while (true) {
                val read: Int = input.read(buffer)
                if (read < 0) return@serveOnce
                received.addAndGet(read.toLong())
                tail = (tail + String(buffer, maxOf(0, read - 16), minOf(read, 16), Charsets.ISO_8859_1)).takeLast(16)
                if (tail.endsWith("0\r\n\r\n")) break
            }
            s.send("HTTP/1.1 201 Created\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        }
        System.gc()
        val baseline: Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val peak = AtomicLong(baseline)
        val sampling = AtomicBoolean(true)
        val sampler: Thread = thread(isDaemon = true) {
            while (sampling.get()) {
                peak.accumulateAndGet(Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }) { a, b -> maxOf(a, b) }
                Thread.sleep(20)
            }
        }
        val done = CountDownLatch(parallel)
        val completed = AtomicInteger()
        val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        repeat(parallel) {
            thread(isDaemon = true) {
                try {
                    val outcome: MultipartOutcome = performMultipartUploadCapturing(
                        UploadRequest("http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.File("f", "big.bin", "application/octet-stream", big.path))),
                        connectTimeoutMillis = 10_000,
                        readTimeoutMillis = 30_000,
                    )
                    val result: UploadResult = outcome.result
                    if (result is UploadResult.Completed && result.statusCode == 201 && outcome.response?.body()?.decodeToString() == "ok") {
                        completed.incrementAndGet()
                    } else {
                        failures += outcome.toString()
                    }
                } catch (e: Throwable) {
                    failures += e.toString()
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(120, TimeUnit.SECONDS), "the parallel uploads did not finish; ${completed.get()} of $parallel landed")
        sampling.set(false)
        sampler.join(2_000)
        assertEquals(emptyList(), failures.take(3), "an upload did not land")
        assertEquals(parallel, completed.get())
        assertTrue(received.get() >= parallel * size, "the server received only ${received.get()} bytes")
        val growth: Long = peak.get() - baseline
        assertTrue(growth < 160L * 1024 * 1024, "the heap peaked ${growth / 1024 / 1024} MiB above its start for $parallel x ${size / 1024 / 1024} MiB bodies: the body is not streamed")
    }
}
