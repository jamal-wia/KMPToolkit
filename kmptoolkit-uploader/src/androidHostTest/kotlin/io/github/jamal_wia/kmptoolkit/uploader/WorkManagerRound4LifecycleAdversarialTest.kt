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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 4 (lifecycle chaos): the real transport, the real WorkManager test driver and a real loopback server. A job
 * the system stops mid-transfer, a long run of zero-backoff retries (row growth, lease waits), and a withdrawal racing
 * the retry storm. Every wait is a bounded poll; every loop is capped.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerRound4LifecycleAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val connections = AtomicInteger(0)
    private val connectionTimes = CopyOnWriteArrayList<Long>()
    private val held = mutableListOf<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: UploaderEngine? = null
    private val journalRoot: File get() = File(context.noBackupFilesDir, "kmptoolkit-uploader")

    private fun config(requiresNetwork: Boolean, leaseMillis: Long): UploadTransportConfig = UploadTransportConfig(
        uniqueWorkNamePrefix = "wm_r4_",
        workTag = "wm_r4",
        requiresNetwork = requiresNetwork,
        leaseMillis = leaseMillis,
        engineWait = 200.milliseconds,
    )

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

    private fun serve(respond: (n: Int, socket: Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                val n: Int = connections.incrementAndGet()
                connectionTimes += System.nanoTime()
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

    private fun infos(id: String, config: UploadTransportConfig): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(config.resolveUniqueWorkName(context.packageName, id)).get()

    private fun awaitTrue(timeoutMillis: Long = 15_000L, what: String = "condition", condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private class ZeroBackoff : RetryPolicy {
        override val maxDelayMillis: Long = 1L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 0L
    }

    private inner class Handler(transport: UploadTransport, override val type: String = "wm-r4") : UploadHandler<String>(transport) {
        override val retryPolicy: RetryPolicy = ZeroBackoff()
        val delivered = AtomicInteger(0)
        val settled = AtomicInteger(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList()))
        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered.incrementAndGet()
        }
        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settled.incrementAndGet()
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
    fun `a job the system stops mid-transfer is re-run once constraints return, uploads again once and leaves no ledger entry`() {
        val config: UploadTransportConfig = config(requiresNetwork = true, leaseMillis = 120_000L)
        val firstRequestRead = CountDownLatch(1)
        serve { n, socket ->
            if (n == 1) firstRequestRead.countDown() else answer(socket, "201 Created")
        }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = Handler(transport)
        val started: UploaderEngine = startEngine(handler)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        awaitTrue(what = "the work spec") { infos("item-1", config).isNotEmpty() }
        val workId = infos("item-1", config).single().id
        driver.setAllConstraintsMet(workId)
        assertTrue(firstRequestRead.await(10, TimeUnit.SECONDS), "the first transfer reached the server")

        driver.stopRunningWorkWithReason(workId, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY) // the system stops the running job
        awaitTrue(what = "the job to unwind") { UploadHandOffLedger.shared.entry("item-1") == null }
        assertEquals(WorkInfo.State.ENQUEUED, infos("item-1", config).single().state, "a stopped job waits for its constraints")
        assertEquals(0, handler.delivered.get())

        driver.setAllConstraintsMet(workId) // constraints return: the same work spec runs again
        awaitTrue(what = "the delivery") { handler.delivered.get() == 1 }

        assertEquals(1, handler.delivered.get())
        assertEquals(2, connections.get(), "the stopped transfer plus exactly one re-upload")
        awaitTrue(what = "the job to finish") { infos("item-1", config).single().state == WorkInfo.State.SUCCEEDED }
        awaitTrue(what = "the ledger to settle") { UploadHandOffLedger.shared.entry("item-1")?.activeJobs ?: 0 == 0 }
    }

    @Test
    fun `a lease re-hand that arrives while the system has the job stopped joins it and the item uploads once more, not twice`() {
        val config: UploadTransportConfig = config(requiresNetwork = true, leaseMillis = 120_000L)
        val firstRequestRead = CountDownLatch(1)
        serve { n, socket ->
            if (n == 1) firstRequestRead.countDown() else answer(socket, "201 Created")
        }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = Handler(transport)
        val started: UploaderEngine = startEngine(handler)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        awaitTrue(what = "the work spec") { infos("item-1", config).isNotEmpty() }
        val workId = infos("item-1", config).single().id
        driver.setAllConstraintsMet(workId)
        assertTrue(firstRequestRead.await(10, TimeUnit.SECONDS))
        driver.stopRunningWorkWithReason(workId, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        awaitTrue(what = "the job to unwind") { UploadHandOffLedger.shared.entry("item-1") == null }

        // The engine's lease re-hand (and an impatient duplicate) arrive while the job is stopped.
        val request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList())
        repeat(3) { transport.launch("item-1", isRehandOff = true, request = request) }
        assertEquals(listOf(workId), infos("item-1", config).map { it.id }, "the stopped work spec is joined, not replaced")
        driver.setAllConstraintsMet(workId)
        awaitTrue(what = "the delivery") { handler.delivered.get() == 1 }
        Thread.sleep(300)

        assertEquals(2, connections.get())
        assertEquals(1, handler.delivered.get())
        assertEquals(1, infos("item-1", config).size)
    }

    @Test
    fun `sixty zero-backoff retries in a row leave one work spec, one delivery and no growth in threads`() {
        val config: UploadTransportConfig = config(requiresNetwork = false, leaseMillis = 3_000L)
        serve { n, socket -> answer(socket, if (n <= 60) "503 Service Unavailable" else "201 Created") }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = Handler(transport)
        val started: UploaderEngine = startEngine(handler)
        val threadsBefore: Int = Thread.activeCount()

        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        awaitTrue(timeoutMillis = 120_000L, what = "delivery after 60 retries (connections so far: ${connections.get()})") {
            handler.delivered.get() == 1
        }

        val waits: Int = connectionTimes.zipWithNext().count { (a, b) -> (b - a) > 2_000_000_000L }
        println("zero-backoff retry run: ${connections.get()} connections, $waits waited out a lease of 3 s")
        assertEquals(61, connections.get())
        assertEquals(1, handler.delivered.get())
        awaitTrue(what = "the last job") { infos("item-1", config).single().state == WorkInfo.State.SUCCEEDED }
        assertEquals(1, infos("item-1", config).size, "no chain of work specs")
        assertTrue(Thread.activeCount() - threadsBefore < 30, "threads grew from $threadsBefore to ${Thread.activeCount()}")
        awaitTrue(what = "the ledger to drain") { UploadHandOffLedger.shared.entry("item-1")?.activeJobs ?: 0 == 0 }
    }

    @Test
    fun `withdrawing an item in the middle of a retry storm leaves no job running and no further upload`() {
        val config: UploadTransportConfig = config(requiresNetwork = false, leaseMillis = 2_000L)
        serve { _, socket -> answer(socket, "503 Service Unavailable") }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = Handler(transport)
        val started: UploaderEngine = startEngine(handler)
        val random = java.util.Random(7)

        repeat(10) { round ->
            val id = "item-${round + 1}"
            runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload-$round") } }
            val before: Int = connections.get()
            awaitTrue(timeoutMillis = 20_000L, what = "the storm to start for $id") { connections.get() > before + 2 }
            Thread.sleep(random.nextInt(40).toLong())

            runBlocking { withTimeout(10_000L) { started.cancel(id) } }

            val atCancel: Int = connections.get()
            awaitTrue(timeoutMillis = 20_000L, what = "no work of $id to run or wait") {
                infos(id, config).none { !it.state.isFinished }
            }
            Thread.sleep(250)
            val settledAt: Int = connections.get()
            Thread.sleep(250)
            assertTrue(settledAt - atCancel <= 3, "round $round: ${settledAt - atCancel} uploads started after the withdrawal returned")
            assertEquals(settledAt, connections.get(), "round $round: uploads kept starting after the item was withdrawn")
            assertEquals(0, UploadHandOffLedger.shared.entry(id)?.activeJobs ?: 0, "round $round: a job of the withdrawn item is still counted")
        }
        assertEquals(0, handler.delivered.get())
    }
}
