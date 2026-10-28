package io.github.jamal_wia.kmptoolkit.uploader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import org.junit.runner.RunWith

/**
 * The transfer under both `WorkManager` workers stops when its job is cancelled. The write is blocking,
 * so without polling it would send the whole body and only the outcome would be thrown away — the
 * server would have the upload anyway, and the item, still owed, would upload again.
 */
@RunWith(AndroidJUnit4::class)
class MultipartUploadCancelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: File = File(context.cacheDir, "recording.bin").apply { writeBytes(ByteArray(SOURCE_BYTES) { 1 }) }
    private val server = ServerSocket(0)

    @AfterTest
    fun cleanUp() {
        server.close()
        source.delete()
    }

    @Test
    fun `a transfer cancelled mid-body throws and never sends the end of the body`() {
        val received: ByteArrayOutputStream = acceptAndRecord()
        val polls = AtomicInteger()

        assertFailsWith<CancellationException> {
            performMultipartUpload(
                request = request(),
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 5_000,
                isCancelled = { polls.incrementAndGet() > CANCEL_AFTER_POLLS },
            )
        }

        val body: String = awaitClosed(received)
        assertFalse("0\r\n\r\n" in body, "the chunked body must not be terminated")
        assertFalse("--\r\n" in body.substringAfter("filename="), "the multipart body must not be closed")
        assertTrue(body.length < SOURCE_BYTES, "only part of the file was sent, was ${body.length} bytes")
    }

    @Test
    fun `a transfer that is never cancelled sends the whole body`() {
        val received: ByteArrayOutputStream = acceptAndRecord(respond = true)

        val result: UploadResult = performMultipartUpload(
            request = request(),
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 5_000,
            isCancelled = { false },
        )

        assertIs<UploadResult.Completed>(result)
        assertEquals(200, result.statusCode)
        assertTrue("0\r\n\r\n" in awaitClosed(received))
    }

    private fun request(): UploadRequest = UploadRequest(
        url = "http://127.0.0.1:${server.localPort}/upload",
        fields = listOf(UploadField.File("file", "recording.bin", "application/octet-stream", source.path)),
    )

    /**
     * Records everything the client sends. With [respond], answers 200 once the chunked body ends, as a
     * server does; otherwise only reads until the client drops the connection.
     */
    private fun acceptAndRecord(respond: Boolean = false): ByteArrayOutputStream {
        val received = ByteArrayOutputStream()
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    synchronized(received) { received.write(buffer, 0, read) }
                    if (respond && synchronized(received) { received.toString(Charsets.ISO_8859_1.name()) }.endsWith("0\r\n\r\n")) {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().flush()
                        break
                    }
                }
            }
            synchronized(received) { received.write(CLOSED_MARK.toByteArray()) }
        }
        return received
    }

    private fun awaitClosed(received: ByteArrayOutputStream): String {
        repeat(500) {
            val text: String = synchronized(received) { received.toString(Charsets.ISO_8859_1.name()) }
            if (text.endsWith(CLOSED_MARK)) return text.removeSuffix(CLOSED_MARK)
            Thread.sleep(10)
        }
        error("the server never saw the connection close")
    }

    private companion object {
        const val SOURCE_BYTES: Int = 2 * 1024 * 1024
        const val CANCEL_AFTER_POLLS: Int = 20
        const val CLOSED_MARK: String = "\u0000<closed>"
    }
}
