package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Concurrency storms on the classic WorkManager transport with real executors and a real local socket server:
 * many items on one channel, a lease re-hand while the item's own job is alive, a tag cancellation racing hand-offs,
 * and hand-offs from outside any attempt while the item retries. Every wait is bounded.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerClassicStormRound5AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val held = mutableListOf<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: UploaderEngine? = null

    /** `item` and `attempt` of every request line, in arrival order. */
    private val requests: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())

    /** `item` and `attempts` of every `execute` call (the engine's own hand-offs, lease re-hands included). */
    private val executes: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())

    private fun config(lease: Long = 60_000L): UploadTransportConfig = UploadTransportConfig(
        uniqueWorkNamePrefix = "wm_r5_storm_",
        workTag = TAG,
        requiresNetwork = false,
        leaseMillis = lease,
        engineWait = 3_000.milliseconds,
    )

    @BeforeTest
    fun setUp() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
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

    private fun serve(respond: (item: String, attempt: Int, socket: Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                synchronized(held) { held += socket }
                thread(isDaemon = true) {
                    socket.soTimeout = 30_000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(8192)
                    var seen = ""
                    while (!seen.endsWith("0\r\n\r\n")) {
                        val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                        if (read < 0) return@thread
                        seen += String(buffer, 0, read, Charsets.ISO_8859_1)
                    }
                    val query: String = seen.lineSequence().first().substringAfter("?").substringBefore(" ")
                    val item: String = query.substringAfter("item=").substringBefore("&")
                    val attempt: Int = query.substringAfter("attempt=").toInt()
                    requests += item to attempt
                    runCatching { respond(item, attempt, socket) }
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

    private inner class PlainHandler(private val transport: UploadTransport) : UploaderHandler<String> {
        override val type: String = "wm-classic-storm"
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ZeroBackoff()
        override val constraintKeys: Set<String> = emptySet()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            executes += context.id to context.attempts
            transport.launch(context.id, requestFor(context.id, context.attempts))
            return AttemptResult.Detached(transport.leaseMillis)
        }
    }

    private fun requestFor(id: String, attempt: Int): UploadRequest =
        UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload?item=$id&attempt=$attempt", fields = emptyList())

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

    private fun awaitTrue(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out after $timeoutMillis ms waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun attemptsOf(id: String): List<Int> = synchronized(requests) { requests.filter { it.first == id }.map { it.second } }

    private fun workStates(id: String, cfg: UploadTransportConfig): List<WorkInfo.State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(cfg.resolveUniqueWorkName(context.packageName, id)).get().map { it.state }

    private fun noLiveWork(id: String, cfg: UploadTransportConfig): Boolean =
        workStates(id, cfg).none { it == WorkInfo.State.RUNNING || it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }

    /** Twelve items share the one channel; every one fails [failures] times and then delivers: no item lost, none uploaded twice for one attempt. */
    private fun manyItems(items: Int, failures: Int) {
        serve { _, attempt, socket -> answer(socket, if (attempt < failures) "503 Service Unavailable" else "201 Created") }
        val cfg: UploadTransportConfig = config()
        val rows = SerializedStore()
        val handler = PlainHandler(createWorkManagerUploadTransport(context, cfg))
        val started: UploaderEngine = startEngine(rows, handler)
        val ids: List<String> = (1..items).map { "item-$it" }
        runBlocking { withTimeout(10_000L) { repeat(items) { started.enqueue(handler, "payload-$it") } } }

        awaitTrue(60_000L, "all $items items delivered") { ids.all { rows.find(it) == null } }
        Thread.sleep(600) // a stray extra upload would land here

        for (id in ids) {
            assertEquals((0..failures).toList(), attemptsOf(id), "$id was not uploaded exactly once per attempt")
        }
        for (id in ids) {
            assertEquals(0, UploadHandOffLedger.shared.entry(id)?.activeJobs ?: 0, "$id: a job is still counted")
            awaitTrue(5_000L, "$id: no live work") { noLiveWork(id, cfg) }
        }
    }

    @Test
    fun `twelve items on one channel each fail once and deliver, uploaded once per attempt`() = manyItems(items = 12, failures = 1)

    @Test
    fun `eight items on one channel each fail three times in a row and deliver, uploaded once per attempt`() =
        manyItems(items = 8, failures = 3)

    @Test
    fun `ten items on one channel each fail twenty times in a row and deliver, uploaded once per attempt`() =
        manyItems(items = 10, failures = 20)

    /**
     * A storm of cancellations by tag while ten items retry: each cancel kills the jobs that exist, the leases re-hand
     * the items, the cancels stop, and every item still ends delivered with nothing left counted.
     */
    @Test
    fun `a storm of tag cancellations over ten retrying items loses none of them`() {
        serve { _, attempt, socket -> answer(socket, if (attempt < 8) "503 Service Unavailable" else "201 Created") }
        val cfg: UploadTransportConfig = config(lease = 700L)
        val rows = SerializedStore()
        val handler = PlainHandler(createWorkManagerUploadTransport(context, cfg))
        val started: UploaderEngine = startEngine(rows, handler)
        val ids: List<String> = (1..10).map { "item-$it" }
        val stop = AtomicBoolean(false)
        val canceller = thread {
            val random = Random(3)
            while (!stop.get()) {
                runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(TAG).result.get() }
                Thread.sleep(random.nextLong(2, 25))
            }
        }
        runBlocking { withTimeout(10_000L) { repeat(10) { started.enqueue(handler, "payload-$it") } } }
        Thread.sleep(1_500)
        stop.set(true)
        canceller.join(10_000L)

        awaitTrue(60_000L, "all items delivered after the cancellation storm") { ids.all { rows.find(it) == null } }
        Thread.sleep(500)
        for (id in ids) {
            assertEquals(0, UploadHandOffLedger.shared.entry(id)?.activeJobs ?: 0, "$id: a cancelled job is still counted")
            awaitTrue(8_000L, "$id: no live work") { noLiveWork(id, cfg) }
        }
    }

    /**
     * The lease expires while the item's own job is alive and waiting for a slow answer: the engine re-hands the item
     * (through `execute`, stamped with the attempt it is running). The job must absorb it. A 503 at the end charges the
     * attempt once, and the stale re-hand must not be uploaded in place of, or in addition to, the retry's own request.
     */
    @Test
    fun `a lease re-hand while the job still waits for a slow failure uploads the retry once and the stale re-hand never`() {
        serve { _, attempt, socket ->
            if (attempt == 0) Thread.sleep(3_500)
            answer(socket, if (attempt == 0) "503 Service Unavailable" else "201 Created")
        }
        val cfg: UploadTransportConfig = config(lease = 1_200L)
        val rows = SerializedStore()
        val handler = PlainHandler(createWorkManagerUploadTransport(context, cfg))
        val started: UploaderEngine = startEngine(rows, handler)
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }

        awaitTrue(30_000L, "the item delivered") { rows.find("item-1") == null }
        Thread.sleep(800)

        assertTrue(
            synchronized(executes) { executes.count { it == "item-1" to 0 } } >= 2,
            "the lease never re-handed the item, so the case is vacuous: $executes",
        )
        assertEquals(listOf(0, 1), attemptsOf("item-1"), "the stale lease re-hand was uploaded, or the retry was not")
        assertEquals(0, UploadHandOffLedger.shared.entry("item-1")?.activeJobs ?: 0)
    }

    @Test
    fun `a lease re-hand while the job still waits for a slow delivery uploads nothing more`() {
        serve { _, _, socket ->
            Thread.sleep(3_500)
            answer(socket, "201 Created")
        }
        val cfg: UploadTransportConfig = config(lease = 1_200L)
        val rows = SerializedStore()
        val handler = PlainHandler(createWorkManagerUploadTransport(context, cfg))
        val started: UploaderEngine = startEngine(rows, handler)
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }

        awaitTrue(30_000L, "the item delivered") { rows.find("item-1") == null }
        Thread.sleep(1_500) // a re-hand that slipped past the job would upload again here

        assertTrue(
            synchronized(executes) { executes.count { it == "item-1" to 0 } } >= 2,
            "the lease never re-handed the item, so the case is vacuous: $executes",
        )
        assertEquals(listOf(0), attemptsOf("item-1"), "the file was uploaded more than once")
    }

    /**
     * An application-wide cancellation by tag (a logout, say) lands while items are in flight with a job each. The
     * engine still owes them: the lease re-hands them, so every item ends delivered, and no job is left counted.
     */
    @Test
    fun `a tag cancellation in the middle of the uploads loses no item - the lease re-hands them`() {
        val slowFirst = AtomicBoolean(true)
        serve { item, attempt, socket ->
            if (attempt == 0 && slowFirst.get()) Thread.sleep(1_500)
            answer(socket, "201 Created")
        }
        val cfg: UploadTransportConfig = config(lease = 1_500L)
        val rows = SerializedStore()
        val handler = PlainHandler(createWorkManagerUploadTransport(context, cfg))
        val started: UploaderEngine = startEngine(rows, handler)
        val ids: List<String> = (1..6).map { "item-$it" }
        runBlocking { withTimeout(10_000L) { repeat(6) { started.enqueue(handler, "payload-$it") } } }
        awaitTrue(10_000L, "every item on the wire") { synchronized(requests) { requests.map { it.first }.toSet().size } == 6 }
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG).result.get()
        slowFirst.set(false)

        awaitTrue(40_000L, "all items delivered after the cancellation") { ids.all { rows.find(it) == null } }
        Thread.sleep(500)
        for (id in ids) {
            assertTrue(attemptsOf(id).size in 1..4, "$id was uploaded ${attemptsOf(id).size} times: ${attemptsOf(id)}")
            assertEquals(0, UploadHandOffLedger.shared.entry(id)?.activeJobs ?: 0, "$id: a cancelled job is still counted")
        }
    }

    /** Withdrawals racing a retry storm on eight items at once: nothing runs and nothing uploads after them. */
    @Test
    fun `withdrawing eight items at random moments of a retry storm leaves no job and no further upload`() {
        serve { _, _, socket -> answer(socket, "503 Service Unavailable") }
        val cfg: UploadTransportConfig = config()
        val rows = SerializedStore()
        val handler = PlainHandler(createWorkManagerUploadTransport(context, cfg))
        val started: UploaderEngine = startEngine(rows, handler)
        val random = Random(5)
        repeat(3) { round ->
            val ids: List<String> = runBlocking { withTimeout(10_000L) { (1..8).map { started.enqueue(handler, "payload-$round-$it") } } }
                .map { checkNotNull(it) { "the item was not enqueued" } }
            val names: List<String> = synchronized(requests) { requests.map { it.first }.distinct() }
            // Withdraw each item at its own random moment, from its own thread.
            val delays: List<Long> = ids.map { random.nextLong(0, 150) }
            val workers = ids.zip(delays).map { (id, delay) ->
                thread {
                    Thread.sleep(delay)
                    runBlocking { withTimeout(10_000L) { started.cancel(id) } }
                }
            }
            workers.forEach { it.join(20_000L) }
            check(workers.none { it.isAlive }) { "round $round: a withdrawal did not return" }
            val atCancel: Int = requests.size
            Thread.sleep(800)
            val after: Int = requests.size
            val late: Map<String, Int> = synchronized(requests) { requests.drop(atCancel).groupingBy { it.first }.eachCount() }
            assertTrue(
                after - atCancel <= ids.size * 3,
                "round $round: $after - $atCancel uploads started after every withdrawal returned; per item: $late; this round's ids=$ids; earlier=$names",
            )
            val settledAt: Int = after
            Thread.sleep(500)
            assertEquals(settledAt, requests.size, "round $round: uploads kept starting after the items were withdrawn")
            for (id in ids) {
                assertNull(rows.find(id), "round $round: $id still has a row after its withdrawal")
                assertEquals(0, UploadHandOffLedger.shared.entry(id)?.activeJobs ?: 0, "round $round: a job of $id is still counted")
                awaitTrue(8_000L, "round $round: $id has no live work") { noLiveWork(id, cfg) }
            }
        }
    }

    /**
     * A wrapper launches the transport from its own thread, outside any attempt (unstamped), over and over while the
     * item retries and finally delivers. The item must still end delivered, and nothing may stay counted afterwards.
     */
    @Test
    fun `unstamped launches hammered from outside the attempts never strand the item or a job`() {
        serve { _, attempt, socket -> answer(socket, if (attempt < 6) "503 Service Unavailable" else "201 Created") }
        val cfg: UploadTransportConfig = config()
        val rows = SerializedStore()
        val transport: UploadTransport = createWorkManagerUploadTransport(context, cfg)
        val handler = PlainHandler(transport)
        val started: UploaderEngine = startEngine(rows, handler)
        val stop = AtomicBoolean(false)
        val launched = AtomicInteger(0)
        val hammer = thread {
            val random = Random(11)
            while (!stop.get()) {
                transport.launch("item-1", requestFor("item-1", 99))
                launched.incrementAndGet()
                Thread.sleep(random.nextLong(1, 6))
            }
        }
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }

        awaitTrue(60_000L, "the item delivered despite the launches") { rows.find("item-1") == null }
        stop.set(true)
        hammer.join(5_000L)
        // Launches made after the delivery are fresh uploads of a gone item (the caller asked): let them end.
        Thread.sleep(1_500)
        awaitTrue(20_000L, "no live work left") { noLiveWork("item-1", cfg) }

        assertTrue(launched.get() > 0)
        assertEquals(0, UploadHandOffLedger.shared.entry("item-1")?.activeJobs ?: 0, "a job is still counted")
        assertNull(rows.find("item-1"))
    }

    /**
     * [TestUploaderStore] is a single-threaded fake: its read-modify-write steps are not atomic, so a withdrawal and a
     * failure write racing on real threads can resurrect a deleted row (a harness artefact seen in the first run of
     * this class). Every store call goes through one lock here, the atomicity a real store gives.
     */
    private class SerializedStore(private val inner: TestUploaderStore = TestUploaderStore()) : UploaderStore {
        private val lock = kotlinx.coroutines.sync.Mutex()

        fun find(id: String): UploaderItem? = inner.find(id)

        override suspend fun insertKeep(record: UploaderItem): Boolean = lock.withLock { inner.insertKeep(record) }
        override suspend fun insertReplace(record: UploaderItem): UploaderItem? = lock.withLock { inner.insertReplace(record) }
        override suspend fun getAllActive(): List<UploaderItem> = lock.withLock { inner.getAllActive() }
        override suspend fun getById(id: String): UploaderItem? = lock.withLock { inner.getById(id) }
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean = lock.withLock {
            inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }

        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean = lock.withLock { inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim) }

        override suspend fun park(
            id: String,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean = lock.withLock { inner.park(id, lastError, expectedState, expectedClaim) }

        override suspend fun deleteById(id: String) = lock.withLock { inner.deleteById(id) }
        override suspend fun deleteByTag(tag: String): List<UploaderItem> = lock.withLock { inner.deleteByTag(tag) }
        override fun observeByType(type: String): kotlinx.coroutines.flow.Flow<List<UploaderItem>> = inner.observeByType(type)
        override suspend fun clearAll() = lock.withLock { inner.clearAll() }
    }

    private companion object {
        const val TAG: String = "wm_r5_storm"
    }
}
