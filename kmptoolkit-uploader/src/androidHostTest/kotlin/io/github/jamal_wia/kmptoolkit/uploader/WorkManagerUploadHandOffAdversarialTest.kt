package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * A hand-off that reaches the handler transport while the item's previous `WorkManager` job is still finishing:
 * the running job serves it before it returns, a retrying job keeps its work spec, a finished job leaves no chain,
 * and a cancelled served retry leaves nothing behind. Every wait is a bounded poll or a bounded latch.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerUploadHandOffAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val connections = AtomicInteger(0)
    private val held = mutableListOf<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = UploadTransportConfig(
        uniqueWorkNamePrefix = "wm_ho_",
        workTag = "wm_ho",
        requiresNetwork = false,
        leaseMillis = 60_000L,
        engineWait = 200.milliseconds,
    )
    private var engine: UploaderEngine? = null
    private val journalRoot: File get() = File(context.noBackupFilesDir, "kmptoolkit-uploader")

    @BeforeTest
    fun setUp() {
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @AfterTest
    fun tearDown() {
        synchronized(held) { held.forEach { runCatching { it.close() } } }
        server.close()
        engine?.close()
        scope.cancel()
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    /** Accepts connections, reads each request fully, and lets [respond] answer connection number `n` (1-based). */
    private fun serve(respond: (n: Int, socket: Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                val n: Int = connections.incrementAndGet()
                synchronized(held) { held += socket }
                thread(isDaemon = true) {
                    socket.soTimeout = 20_000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(8192)
                    var tail = ""
                    while (!tail.endsWith("0\r\n\r\n")) {
                        val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                        if (read < 0) return@thread
                        tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                    }
                    runCatching { respond(n, socket) }
                }
            }
        }
    }

    private fun answer(socket: Socket, status: String) {
        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
        socket.getOutputStream().flush()
    }

    private fun workInfos(id: String): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(config.resolveUniqueWorkName(context.packageName, id)).get()

    private fun states(id: String): List<WorkInfo.State> = workInfos(id).map { it.state }

    private fun awaitTrue(timeoutMillis: Long = 15_000L, condition: () -> Boolean) {
        runBlocking { withTimeout(timeoutMillis) { while (!condition()) delay(20L) } }
    }

    private class ZeroBackoff : RetryPolicy {
        override val maxDelayMillis: Long = 1L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 0L
    }

    /** Counts hand-offs, and tells the test when the second one happened. */
    private class CountingTransport(private val inner: UploadTransport) : UploadTransport {
        val launches = AtomicInteger(0)
        val secondLaunch = CountDownLatch(1)
        override val leaseMillis: Long = inner.leaseMillis
        private fun counted() {
            if (launches.incrementAndGet() >= 2) secondLaunch.countDown()
        }
        override fun launch(itemId: String, request: UploadRequest) {
            inner.launch(itemId, request)
            counted()
        }
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            inner.launch(itemId, isRehandOff, request)
            counted()
        }
        override fun cancel(itemId: String) = inner.cancel(itemId)
    }

    /** Zero backoff; the first attempt's `onSettled` (inside the job) waits until the engine re-handed the retry. */
    private inner class RetryHandler(private val counting: CountingTransport) : UploadHandler<String>(counting) {
        override val type: String = "wm-ho"
        override val retryPolicy: RetryPolicy = ZeroBackoff()
        val delivered = AtomicInteger(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList()))
        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered.incrementAndGet()
        }
        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            if (attempts == 0) withContext(Dispatchers.IO) { counting.secondLaunch.await(5, TimeUnit.SECONDS) }
        }
    }

    private fun startEngine(handler: UploadHandler<String>): UploaderEngine {
        val started: UploaderEngine = createUploaderEngine(
            store = TestUploaderStore(),
            handlers = listOf(handler),
            scope = scope,
            idGenerator = SequentialIds()::next,
        )
        engine = started
        UploaderEngineRegistry.register(started)
        started.start()
        return started
    }

    @Test
    fun `a retry handed off while the job is still serving is uploaded by that job, not after the lease`() {
        serve { n, socket -> answer(socket, if (n == 1) "503 Service Unavailable" else "201 Created") }
        val transport = CountingTransport(createWorkManagerUploadHandlerTransport(context, config))
        val handler = RetryHandler(transport)
        val started: UploaderEngine = startEngine(handler)

        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        while (handler.delivered.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)

        assertEquals(1, handler.delivered.get(), "delivered after ${connections.get()} connections; the retry waited for its 60 s lease")
        assertEquals(2, connections.get())
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), states("item-1"))
    }

    @Test
    fun `a re-hand while the job waits in retry backoff keeps the same WorkSpec`() {
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val request = UploadRequest(url = "http://127.0.0.1:1/", fields = emptyList())

        // No engine is registered: the job returns retry and WorkManager keeps the same spec for its re-run.
        transport.launch("item-1", isRehandOff = false, request = request)
        awaitTrue { workInfos("item-1").singleOrNull()?.let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount >= 1 } == true }
        val before: WorkInfo = workInfos("item-1").single()

        transport.launch("item-1", isRehandOff = true, request = request)

        val after: WorkInfo = workInfos("item-1").single()
        assertEquals(before.id, after.id, "the retrying job is kept, not replaced")
        assertTrue(after.runAttemptCount >= before.runAttemptCount, "the run count did not reset")
    }

    @Test
    fun `hand-offs after the job finished leave one WorkSpec`() {
        serve { _, socket -> answer(socket, "201 Created") }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = object : UploadHandler<String>(transport) {
            override val type: String = "wm-ho-done"
            val delivered = AtomicInteger(0)
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
                UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList()))
            override suspend fun onDelivered(payload: String, response: UploadResponse?) {
                delivered.incrementAndGet()
            }
        }
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking { withTimeout(15_000L) { started.enqueue(handler, "payload"); started.drain() } }
        awaitTrue { handler.delivered.get() == 1 && states("item-1") == listOf(WorkInfo.State.SUCCEEDED) }

        repeat(5) { transport.launch("item-1", UploadRequest(url = "http://127.0.0.1:1/", fields = emptyList())) }
        runBlocking { delay(300L) }

        assertEquals(1, states("item-1").size, "no chain of jobs")
        assertEquals(1, connections.get())
        assertEquals(1, handler.delivered.get())
    }

    @Test
    fun `cancel during a served retry stops it and leaves no ledger entry`() {
        val secondRequestRead = CountDownLatch(1)
        serve { n, socket ->
            when (n) {
                1 -> answer(socket, "503 Service Unavailable")
                2 -> secondRequestRead.countDown() // never answered: the served retry is blocked on the server
                else -> answer(socket, "201 Created")
            }
        }
        val transport = CountingTransport(createWorkManagerUploadHandlerTransport(context, config))
        val handler = RetryHandler(transport)
        val started: UploaderEngine = startEngine(handler)
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        assertTrue(secondRequestRead.await(10, TimeUnit.SECONDS), "the second attempt reached the server")

        transport.cancel("item-1")

        awaitTrue { states("item-1").all { it == WorkInfo.State.CANCELLED } }
        awaitTrue { UploadHandOffLedger.shared.entry("item-1") == null }
        assertEquals(0, handler.delivered.get())
        assertTrue(journalRoot.walkTopDown().none { it.isFile }, "a cancelled item leaves no answer on disk")
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, UploadHandOffLedger.shared.policyForHandOff("item-1"))
        assertNull(UploadHandOffLedger.shared.entry("item-1"))

        // The next hand-off enqueues one job, which delivers.
        transport.launch("item-1", UploadRequest(url = "http://127.0.0.1:1/", fields = emptyList()))
        awaitTrue { handler.delivered.get() == 1 }
        assertEquals(1, states("item-1").size)
    }
}
