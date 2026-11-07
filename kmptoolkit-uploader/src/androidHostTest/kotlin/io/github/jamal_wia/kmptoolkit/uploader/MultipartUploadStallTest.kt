package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The body write is blocking and `HttpURLConnection` has no write timeout: a peer that accepts the connection
 * and never reads pins the thread until the kernel gives up. A cancelled job, and a write with no progress,
 * must still end it promptly.
 */
@RunWith(AndroidJUnit4::class)
class MultipartUploadStallTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: File = File(context.cacheDir, "stall.bin").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(SOURCE_BYTES) } }
    // A tiny receive buffer, set before binding, so a reader that never reads fills the path quickly.
    private val server = ServerSocket().apply {
        receiveBufferSize = 4 * 1024
        bind(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
    }
    private val accepted = AtomicBoolean(false)
    private val held = mutableListOf<Socket>()

    @AfterTest
    fun cleanUp() {
        held.forEach { runCatching { it.close() } }
        server.close()
        source.delete()
    }

    /** Accepts one connection and never reads from it, so the sender's buffers fill and its write blocks. */
    private fun acceptAndNeverRead() {
        Thread {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
            synchronized(held) { held += socket }
            accepted.set(true)
        }.apply { isDaemon = true }.start()
    }

    private fun request() = UploadRequest(
        url = "http://127.0.0.1:${server.localPort}/upload",
        fields = listOf(UploadField.File(name = "file", path = source.path, fileName = "stall.bin", contentType = "application/octet-stream")),
    )

    @Test
    fun `cancelling the job ends a write blocked on a peer that never reads`() = runBlocking<Unit> {
        acceptAndNeverRead()
        val job = launch(Dispatchers.Default) {
            withWriteWatchdog(stallMillis = 60_000) { watch ->
                withContext(Dispatchers.IO) {
                    performMultipartUpload(request(), 5_000, 60_000, watch = watch)
                }
            }
        }
        withTimeout(10_000) { while (!accepted.get()) delay(20) }
        delay(1_000) // the buffers are full by now and the write is blocked

        job.cancel(CancellationException("stopped"))

        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a write that makes no progress for the stall limit is dropped as a transport failure`() = runBlocking<Unit> {
        acceptAndNeverRead()

        val result: UploadResult = withTimeout(15_000) {
            withWriteWatchdog(stallMillis = 1_000) { watch ->
                withContext(Dispatchers.IO) {
                    performMultipartUpload(request(), 5_000, 60_000, watch = watch)
                }
            }
        }

        assertIs<UploadResult.TransportFailure>(result)
    }

    private companion object {
        /** A sparse file, far beyond any socket buffer, so a reader that never reads blocks the writer. */
        const val SOURCE_BYTES: Long = 4L * 1024 * 1024 * 1024
    }
}
