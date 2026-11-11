package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.runner.RunWith

/**
 * Many transfers at once: sixteen uploads of which half are cancelled at random moments, and eight handler jobs
 * whose progress must never arrive after their own settlement. Real threads and sockets; every wait is bounded.
 */
@RunWith(AndroidJUnit4::class)
class UploaderWorkerScaleAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket(0, 128)
    private val uploads = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempFiles = mutableListOf<File>()
    private val completedRequests = AtomicInteger(0)
    private var engine: UploaderEngine? = null
    private val journalRoot: File get() = File(context.noBackupFilesDir, "kmptoolkit-uploader")

    @BeforeTest
    fun setUp() {
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun tearDown() {
        uploads.cancel()
        server.close()
        engine?.close()
        tempFiles.forEach { it.delete() }
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private fun serveAll(delayMillis: Long = 0L) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    socket.use {
                        it.soTimeout = 30_000
                        val input = it.getInputStream()
                        val buffer = ByteArray(64 * 1024)
                        var tail = ""
                        while (!tail.endsWith("0\r\n\r\n")) {
                            val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                            if (read < 0) return@thread
                            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                        }
                        completedRequests.incrementAndGet()
                        if (delayMillis > 0) Thread.sleep(delayMillis)
                        runCatching {
                            it.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        }
                    }
                }
            }
        }
    }

    private fun source(megabytes: Int): File =
        File(context.cacheDir, "scale-${System.nanoTime()}.bin").also {
            RandomAccessFile(it, "rw").use { f -> f.setLength(megabytes * 1024L * 1024L) }
            tempFiles += it
        }

    // Open descriptors of this process; skips the test where the host cannot count them (Windows).
    private fun descriptors(): Long {
        val count: Long? = TestFs.openDescriptorCount()
        Assume.assumeTrue("this host cannot count open file descriptors", count != null)
        return count!!
    }

    @Test
    fun `sixteen uploads with half of them cancelled at random moments free every thread and socket and finish the rest`() {
        serveAll(delayMillis = 200)
        val file: File = source(24)
        val request = UploadRequest("http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.File("f", "f.bin", "application/octet-stream", file.path)))
        runBlocking { performWarmUp(request) }
        val fdBefore: Long = descriptors()
        val random = Random(42)

        val jobs: List<Deferred<MultipartOutcome>> = List(16) {
            uploads.async {
                withWriteWatchdog(5_000) { watch ->
                    withContext(Dispatchers.IO) {
                        val transfer = coroutineContext.job
                        performMultipartUploadCapturing(request, 5_000, 5_000, isCancelled = { !transfer.isActive }, watch = watch)
                    }
                }
            }
        }
        val cancelled: List<Deferred<MultipartOutcome>> = jobs.filterIndexed { index, _ -> index % 2 == 0 }
        cancelled.forEach { job ->
            Thread.sleep(random.nextLong(0, 30))
            job.cancel()
        }
        val survivors: List<Deferred<MultipartOutcome>> = jobs.filterIndexed { index, _ -> index % 2 == 1 }
        val results: List<MultipartOutcome> = runBlocking { withTimeout(60_000L) { survivors.awaitAll() } }
        runBlocking { withTimeout(30_000L) { cancelled.forEach { runCatching { it.await() }.onFailure { e -> assertTrue(e is CancellationException, "unexpected $e") } } } }
        Thread.sleep(500)

        results.forEach { assertEquals(UploadResult.Completed(201), it.result) }
        val stuck: List<String> = Thread.getAllStackTraces().filterValues { frames ->
            frames.any { it.methodName.contains("writeMultipartBody") || it.className.contains("performMultipart") }
        }.keys.map { it.name }
        assertTrue(stuck.isEmpty(), "threads still inside a transfer: $stuck")
        assertTrue(descriptors() - fdBefore <= 12, "descriptors grew from $fdBefore to ${descriptors()}")
    }

    private suspend fun performWarmUp(request: UploadRequest) {
        withContext(Dispatchers.IO) { performMultipartUploadCapturing(request.copy(fields = emptyList()), 5_000, 5_000) }
    }

    private class ScaleHandler(private val port: Int, private val file: File, private val clock: AtomicLong) :
        UploadHandler<String>(object : UploadTransport {
            override val leaseMillis: Long = 60_000L
            override fun launch(itemId: String, request: UploadRequest) = Unit
            override fun cancel(itemId: String) = Unit
        }) {
        override val type: String = "scale"
        val progressAt = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()
        val deliveredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(
                UploadRequest("http://127.0.0.1:$port/u", fields = listOf(UploadField.File("f", "f.bin", "application/octet-stream", file.path))),
            )

        override suspend fun onUploadProgress(payload: String, fraction: Float) {
            progressAt.getOrPut(payload) { java.util.Collections.synchronizedList(mutableListOf()) }.add(clock.incrementAndGet())
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            deliveredAt[payload] = clock.incrementAndGet()
        }
    }

    @Test
    fun `progress of eight concurrent jobs never reaches a handler after that item's own delivery`() {
        serveAll()
        val handler = ScaleHandler(server.localPort, source(6), AtomicLong())
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking {
            withTimeout(20_000L) {
                repeat(8) { started.enqueue(handler, "p-${it + 1}") }
                started.drain()
            }
        }

        val results: List<ListenableWorker.Result> = runBlocking {
            withTimeout(60_000L) {
                (1..8).map { index ->
                    uploads.async {
                        TestListenableWorkerBuilder<UploadHandlerWorker>(context).setInputData(
                            workDataOf(
                                UploadHandlerWorker.ITEM_ID_KEY to "item-$index",
                                UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to 1_000L,
                                UploadHandlerWorker.CONNECT_TIMEOUT_MILLIS_KEY to 5_000,
                                UploadHandlerWorker.READ_TIMEOUT_MILLIS_KEY to 10_000,
                            ),
                        ).build().doWork()
                    }
                }.awaitAll()
            }
        }

        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(8, handler.deliveredAt.size)
        handler.deliveredAt.forEach { (payload, deliveredAt) ->
            val late: List<Long> = handler.progressAt[payload].orEmpty().filter { it > deliveredAt }
            assertTrue(late.isEmpty(), "$payload got progress after its delivery: $late (delivered at $deliveredAt)")
            assertTrue(handler.progressAt[payload].orEmpty().isNotEmpty(), "$payload reported no progress at all")
        }
        runBlocking { delay(100L) }
    }
}
