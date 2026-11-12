package io.github.jamal_wia.kmptoolkit.uploader

import com.sun.management.UnixOperatingSystemMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import org.junit.Assume.assumeTrue

/**
 * Round 6 (resource accounting): file descriptors and threads after the multipart transfer succeeded, was answered with
 * an error, was refused, was cut by the server, was cancelled mid-body and lost its source. Counted on the process
 * (`UnixOperatingSystemMXBean`), after a warm-up, over many repetitions of every outcome, so a single leaked descriptor
 * per outcome shows as dozens.
 */
class MultipartResourcesRound6AdversarialTest {

    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val respond = AtomicReference<(Socket) -> Unit> { }
    private val dir: File = Files.createTempDirectory("r6-res").toFile()
    private val source: File = File(dir, "big.bin").apply { writeBytes(ByteArray(3 * 1024 * 1024) { 5 }) }

    @AfterTest
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    // path-hygiene-ignore: PH2 guarded by the assumption at the start of each test that counts descriptors
    private fun openFds(): Long = (ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean).openFileDescriptorCount

    private fun url(): String = "http://127.0.0.1:${server.localPort}/upload"

    private fun serve() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    socket.use {
                        it.soTimeout = 10_000
                        val input = it.getInputStream()
                        val buffer = ByteArray(16 * 1024)
                        var tail = ""
                        while (!tail.endsWith("0\r\n\r\n")) {
                            val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                            if (read < 0) return@thread
                            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                        }
                        runCatching { respond.get()(it) }
                    }
                }
            }
        }
    }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
    }

    private fun request(withFile: Boolean): UploadRequest = UploadRequest(
        url(),
        fields = if (withFile) listOf(UploadField.File("f", "big.bin", "application/octet-stream", source.path)) else listOf(UploadField.Text("a", "b")),
    )

    private fun run(mode: String) {
        when (mode) {
            "ok" -> {
                respond.set { it.send("HTTP/1.1 201 Created\r\nContent-Length: 4\r\n\r\ndone") }
                performMultipartUploadCapturing(request(false), 4_000, 4_000)
            }
            "error-body" -> {
                respond.set { it.send("HTTP/1.1 500 Oops\r\nContent-Length: 4\r\n\r\nfail") }
                performMultipartUploadCapturing(request(false), 4_000, 4_000)
            }
            "silent-close" -> {
                respond.set { }
                performMultipartUploadCapturing(request(false), 4_000, 4_000)
            }
            "chunked" -> {
                respond.set { it.send("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n") }
                performMultipartUploadCapturing(request(false), 4_000, 4_000)
            }
            "cancel-mid-body" -> {
                respond.set { }
                var polls = 0
                try {
                    performMultipartUpload(request(true), 4_000, 4_000, isCancelled = { ++polls > 40 })
                } catch (e: CancellationException) {
                    // expected
                }
            }
            "source-vanished" -> {
                val gone = File(dir, "gone-${System.nanoTime()}.bin").apply { writeBytes(byteArrayOf(1)) }
                val req = UploadRequest(url(), fields = listOf(UploadField.File("f", "g.bin", "application/octet-stream", gone.path)))
                gone.delete()
                performMultipartUploadCapturing(req, 4_000, 4_000)
            }
            "refused" -> {
                val closed: Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
                performMultipartUploadCapturing(UploadRequest("http://127.0.0.1:$closed/x", fields = listOf(UploadField.Text("a", "b"))), 2_000, 2_000)
            }
            "server-reset-after-headers" -> {
                respond.set { it.send("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort") }
                performMultipartUploadCapturing(request(false), 4_000, 4_000)
            }
            else -> error(mode)
        }
    }

    @Test
    fun `descriptors and threads do not grow over many transfers of every outcome`() {
        // path-hygiene-ignore: PH2 the assumption itself: skips the test on a JVM without a Unix descriptor count
        assumeTrue(ManagementFactory.getOperatingSystemMXBean() is UnixOperatingSystemMXBean)
        serve()
        val modes: List<String> = listOf("ok", "error-body", "silent-close", "chunked", "cancel-mid-body", "source-vanished", "refused", "server-reset-after-headers")
        repeat(3) { modes.forEach(::run) } // warm-up: pools, class loading, the keep-alive timer
        Thread.sleep(1_500)
        val fdsBefore: Long = openFds()
        val threadsBefore: Int = Thread.getAllStackTraces().size
        repeat(25) { modes.forEach(::run) }
        Thread.sleep(2_500) // the server's handler threads and a stack's idle connections wind down
        val fdsAfter: Long = openFds()
        val threadsAfter: Int = Thread.getAllStackTraces().size
        assertTrue(fdsAfter - fdsBefore <= 8, "open descriptors grew from $fdsBefore to $fdsAfter over 200 transfers")
        assertTrue(threadsAfter - threadsBefore <= 8, "threads grew from $threadsBefore to $threadsAfter over 200 transfers")
    }

    @Test
    fun `a transfer through the watchdog releases its descriptors and its watcher thread`() {
        // path-hygiene-ignore: PH2 the assumption itself: skips the test on a JVM without a Unix descriptor count
        assumeTrue(ManagementFactory.getOperatingSystemMXBean() is UnixOperatingSystemMXBean)
        serve()
        respond.set { it.send("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n") }
        fun one() = kotlinx.coroutines.runBlocking {
            withWriteWatchdog(stallMillis = 5_000) { watch ->
                performMultipartUploadCapturing(request(true), 4_000, 4_000, watch = watch)
            }
        }
        repeat(3) { one() }
        Thread.sleep(1_500)
        val fdsBefore: Long = openFds()
        val threadsBefore: Int = Thread.getAllStackTraces().size
        repeat(30) { one() }
        Thread.sleep(2_500)
        assertTrue(openFds() - fdsBefore <= 8, "descriptors grew from $fdsBefore to ${openFds()} over 30 watched transfers")
        assertTrue(Thread.getAllStackTraces().size - threadsBefore <= 8, "threads grew over 30 watched transfers")
    }
}
