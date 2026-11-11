package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
 * A hand-off that reaches the classic `createWorkManagerUploadTransport` while the item's previous `WorkManager` job
 * is still finishing is not dropped (the job serves it before it returns, with its own request) and does not upload
 * twice (a duplicate of an attempt that was just settled finds nothing owed). Every wait is a bounded poll or a
 * bounded latch.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerClassicHandOffAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val requestLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val held = mutableListOf<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = UploadTransportConfig(
        uniqueWorkNamePrefix = "wm_classic_ho_",
        workTag = "wm_classic_ho",
        requiresNetwork = false,
        leaseMillis = 60_000L,
        engineWait = 200.milliseconds,
    )
    private var engine: UploaderEngine? = null

    @BeforeTest
    fun setUp() {
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
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    /** Reads each request fully, records its request line, and lets [respond] answer connection number `n`. */
    private fun serve(respond: (n: Int, socket: Socket) -> Unit) {
        val connections = AtomicInteger(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                val n: Int = connections.incrementAndGet()
                synchronized(held) { held += socket }
                thread(isDaemon = true) {
                    socket.soTimeout = 20_000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(8192)
                    var seen = ""
                    while (!seen.endsWith("0\r\n\r\n")) {
                        val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                        if (read < 0) return@thread
                        seen += String(buffer, 0, read, Charsets.ISO_8859_1)
                    }
                    requestLines += seen.lineSequence().first()
                    runCatching { respond(n, socket) }
                }
            }
        }
    }

    private fun answer(socket: Socket, status: String) {
        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
        socket.getOutputStream().flush()
    }

    private fun states(id: String): List<WorkInfo.State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(config.resolveUniqueWorkName(context.packageName, id)).get().map { it.state }

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

    /** A classic handler: it hands each attempt to the transport with the attempt number in the URL. */
    private inner class ClassicHandler(private val transport: UploadTransport) : UploaderHandler<String> {
        override val type: String = "wm-classic-ho"
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ZeroBackoff()
        override val constraintKeys: Set<String> = emptySet()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            transport.launch(
                context.id,
                context.wasDetached,
                UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload?attempt=${context.attempts}", fields = emptyList()),
            )
            return AttemptResult.Detached(transport.leaseMillis)
        }
    }

    /** A store that runs [afterCommit] right after the first removal of a row, inside the committing job. */
    private class HoldingStore(private val inner: TestUploaderStore, var afterCommit: (suspend () -> Unit)?) :
        UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            inner.deleteById(id)
            afterCommit?.let { hook ->
                afterCommit = null
                hook()
            }
        }
    }

    private fun startEngine(store: UploaderStore, handler: UploaderHandler<String>): UploaderEngine {
        val started: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = scope,
            // A short heartbeat is what re-hands a retry while the first job is held in its settlement.
            config = UploaderConfig(heartbeatInterval = 50.milliseconds),
            idGenerator = SequentialIds()::next,
        )
        engine = started
        UploaderEngineRegistry.register(started)
        started.start()
        return started
    }

    @Test
    fun `a retry handed off while the classic job is finishing is uploaded by that job, not after the lease`() {
        serve { n, socket -> answer(socket, if (n == 1) "503 Service Unavailable" else "201 Created") }
        val transport = CountingTransport(createWorkManagerUploadTransport(context, config))
        val handler = ClassicHandler(transport)
        val rows = TestUploaderStore()
        // The first job is held right after it recorded the retry, until the engine re-handed it.
        rows.afterNextRecordFailure = { withContext(Dispatchers.IO) { transport.secondLaunch.await(5, TimeUnit.SECONDS) } }
        val started: UploaderEngine = startEngine(rows, handler)

        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        val deadline: Long = System.nanoTime() + 8_000_000_000L
        while (rows.find("item-1") != null && System.nanoTime() < deadline) Thread.sleep(20)

        assertNull(rows.find("item-1"), "the retry was dropped and waited for its 60 s lease; requests=$requestLines")
        assertEquals(2, requestLines.size, "one upload per attempt: $requestLines")
        assertTrue(requestLines[0].contains("attempt=0"), requestLines[0])
        assertTrue(requestLines[1].contains("attempt=1"), "the retry is uploaded with its own, newest request: ${requestLines[1]}")
        awaitTrue { states("item-1") == listOf(WorkInfo.State.SUCCEEDED) }
    }

    /**
     * Guard of "no double run": the serve-again path must not upload for a hand-off that only repeats the attempt
     * the job has just delivered (a lease re-hand that lands while the job commits).
     */
    @Test
    fun `a duplicate hand-off landing while the classic job commits a delivery uploads nothing`() {
        serve { _, socket -> answer(socket, "201 Created") }
        val transport = CountingTransport(createWorkManagerUploadTransport(context, config))
        val handler = ClassicHandler(transport)
        val committed = CountDownLatch(1)
        val duplicateSent = CountDownLatch(1)
        val rows = TestUploaderStore()
        val store = HoldingStore(rows) {
            committed.countDown()
            withContext(Dispatchers.IO) { duplicateSent.await(5, TimeUnit.SECONDS) }
        }
        val started: UploaderEngine = startEngine(store, handler)

        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        assertTrue(committed.await(10, TimeUnit.SECONDS), "the job committed the delivery")
        // The row is gone and the job still holds WorkManager's slot: the duplicate is absorbed by the job.
        transport.launch(
            "item-1",
            isRehandOff = true,
            request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload?duplicate", fields = emptyList()),
        )
        duplicateSent.countDown()

        awaitTrue { states("item-1") == listOf(WorkInfo.State.SUCCEEDED) }
        runBlocking { delay(500L) }
        assertEquals(1, requestLines.size, "the duplicate of a delivered attempt uploaded again: $requestLines")
        assertNull(rows.find("item-1"))
        assertEquals(1, states("item-1").size, "no chain of jobs")
    }
}
