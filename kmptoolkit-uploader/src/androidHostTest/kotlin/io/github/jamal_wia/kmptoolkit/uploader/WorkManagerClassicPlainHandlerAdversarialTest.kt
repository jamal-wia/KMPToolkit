package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The documented use of `createWorkManagerUploadTransport`: a plain handler calls the PUBLIC `launch` from `execute`,
 * and the job runs on `WorkManager`'s own (asynchronous) executor, so it can settle or look at the row before the
 * engine has written the claim that follows `execute`. Every wait is bounded.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerClassicPlainHandlerAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val requestLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val held = mutableListOf<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = UploadTransportConfig(
        uniqueWorkNamePrefix = "wm_classic_plain_",
        workTag = "wm_classic_plain",
        requiresNetwork = false,
        leaseMillis = 60_000L,
        engineWait = 3_000.milliseconds,
    )
    private var engine: UploaderEngine? = null

    @BeforeTest
    fun setUp() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        // WorkManager's own executor: the job runs concurrently with the engine, as on a device.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
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

    private class ZeroBackoff : RetryPolicy {
        override val maxDelayMillis: Long = 1L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 0L
    }

    private class CountingTransport(private val inner: UploadTransport) : UploadTransport {
        val launches = AtomicInteger(0)
        val secondLaunch = CountDownLatch(1)
        override val leaseMillis: Long = inner.leaseMillis
        override fun launch(itemId: String, request: UploadRequest) {
            inner.launch(itemId, request)
            if (launches.incrementAndGet() >= 2) secondLaunch.countDown()
        }
        override fun cancel(itemId: String) = inner.cancel(itemId)
    }

    /** A plain handler: `execute` calls the public `launch`, then [afterLaunch] runs before `execute` returns. */
    private inner class PlainHandler(
        private val transport: UploadTransport,
        private val afterLaunch: suspend (attempts: Int) -> Unit = {},
    ) : UploaderHandler<String> {
        override val type: String = "wm-classic-plain"
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ZeroBackoff()
        override val constraintKeys: Set<String> = emptySet()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            transport.launch(
                context.id,
                UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload?attempt=${context.attempts}", fields = emptyList()),
            )
            afterLaunch(context.attempts)
            return AttemptResult.Detached(transport.leaseMillis)
        }
    }

    private fun startEngine(store: UploaderStore, handler: UploaderHandler<String>): UploaderEngine {
        val started: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = scope,
            config = UploaderConfig(heartbeatInterval = 50.milliseconds),
            idGenerator = SequentialIds()::next,
        )
        engine = started
        UploaderEngineRegistry.register(started)
        started.start()
        return started
    }

    private fun attemptsSeen(): List<String> = requestLines.map { it.substringAfter("?").substringBefore(" ") }

    private fun runUntilDelivered(rows: TestUploaderStore, started: UploaderEngine, handler: UploaderHandler<String>) {
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        // The caller asserts the outcome with its own message, so a miss of the bound is not thrown here.
        pollUntil(12_000L) { rows.find("item-1") == null }
    }

    /**
     * The job's Failed settles while `execute` has not returned yet (the claim is not written). The settle must
     * wait for the attempt to end and then count the failure: the retry follows at once, not after the 60 s lease.
     */
    @Test
    fun `a fast job's failure is counted and retried, not dropped as stale and left to the lease`() {
        val answered = CountDownLatch(1)
        serve { n, socket ->
            answer(socket, if (n == 1) "503 Service Unavailable" else "201 Created")
            if (n == 1) answered.countDown()
        }
        val rows = TestUploaderStore()
        val handler = PlainHandler(createWorkManagerUploadTransport(context, config)) { attempts ->
            // Hold `execute` open until the first job has its answer and has had time to reach its settle.
            // timing-hygiene-ignore: TH1 inject a precondition made by latency: the job must be suspended in its settle before execute returns; not observable without a production seam
            if (attempts == 0) withContext(Dispatchers.IO) { answered.await(5, TimeUnit.SECONDS); Thread.sleep(700) }
        }
        val started: UploaderEngine = startEngine(rows, handler)

        runUntilDelivered(rows, started, handler)

        assertNull(rows.find("item-1"), "the failure was dropped as stale and the item waits for its lease; requests=${attemptsSeen()}")
        assertEquals(listOf("attempt=0", "attempt=1"), attemptsSeen())
    }

    /**
     * A retry's hand-off is counted while the first job still closes its iteration, before the engine marked the
     * row in flight. The job must wait for that mark and serve the hand-off, not finish and leave the retry to the lease.
     */
    @Test
    fun `a retry handed off before its claim is written is served by the finishing job`() {
        serve { n, socket -> answer(socket, if (n == 1) "503 Service Unavailable" else "201 Created") }
        val rows = TestUploaderStore()
        val counting = CountingTransport(createWorkManagerUploadTransport(context, config))
        val handler = PlainHandler(counting) { attempts ->
            // The retry has handed off; its claim is written only after this returns.
            // timing-hygiene-ignore: TH1 inject a precondition made by latency: the retry's hand-off must be counted while its claim is not yet written; not observable without a production seam
            if (attempts == 1) withContext(Dispatchers.IO) { Thread.sleep(700) }
        }
        // The first job is held inside its settlement commit until the retry's hand-off was counted.
        rows.afterNextRecordFailure = { withContext(Dispatchers.IO) { counting.secondLaunch.await(5, TimeUnit.SECONDS) } }
        val started: UploaderEngine = startEngine(rows, handler)

        runUntilDelivered(rows, started, handler)

        assertNull(rows.find("item-1"), "the retry was lost and waits for its lease; requests=${attemptsSeen()}")
        assertEquals(listOf("attempt=0", "attempt=1"), attemptsSeen())
    }

    /**
     * The PUBLIC `launch` a plain handler calls from `execute` is stamped with the attempt the engine is running, so
     * the job can tell a late duplicate of an older attempt from the retry's own hand-off (the worker serves a
     * stamped hand-off only when the row is in flight at that attempt).
     */
    @Test
    fun `a hand-off through the public launch carries the attempt the engine is running`() {
        val release = CountDownLatch(1)
        serve { _, socket ->
            release.await(10, TimeUnit.SECONDS)
            answer(socket, "201 Created")
        }
        val rows = TestUploaderStore()
        val transport: UploadTransport = createWorkManagerUploadTransport(context, config)
        var stampSeen: Int? = -1
        val handler = PlainHandler(transport) { attempts ->
            if (attempts == 0) {
                withContext(Dispatchers.IO) {
                    // The first job is serving (it holds the server's answer): a second public launch is counted for it.
                    // A miss of the bound is not thrown here: the stamp assertion below reports it.
                    pollUntil(5_000L) { (UploadHandOffLedger.shared.entry("item-1")?.activeJobs ?: 0) >= 1 }
                    transport.launch(
                        "item-1",
                        UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload?second", fields = emptyList()),
                    )
                    stampSeen = UploadHandOffLedger.shared.latestHandOff("item-1")?.attempt
                    release.countDown()
                }
            }
        }
        val started: UploaderEngine = startEngine(rows, handler)

        runUntilDelivered(rows, started, handler)

        assertEquals(0, stampSeen, "the public launch was not stamped with the running attempt")
    }
}
