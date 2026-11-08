package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Many jobs and re-deliveries for one item, and many items at once, against the real worker, journal and engine over
 * loopback sockets. Every wait is bounded, every engine and server is closed in cleanUp, no test idles a live engine.
 */
@RunWith(AndroidJUnit4::class)
class UploaderConcurrencyStormRound3AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val connections = AtomicInteger(0)
    private val engines = mutableListOf<UploaderEngine>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uploads = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempFiles = mutableListOf<File>()
    private val journalRoot: File get() = File(context.noBackupFilesDir, "kmptoolkit-uploader")
    private val outcomesDir: File get() = File(journalRoot, "outcomes")

    @BeforeTest
    fun clean() {
        journalRoot.deleteRecursively()
        // The journal directories exist before the first write, so these tests are not about their creation race.
        outcomesDir.mkdirs()
        File(journalRoot, "outcomes-partial").mkdirs()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
        journalRoot.walkTopDown().forEach { it.setReadable(true); it.setWritable(true) }
        uploads.cancel()
        server.close()
        engines.forEach { runCatching { it.close() } }
        scope.cancel()
        tempFiles.forEach { it.delete() }
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private fun readRequest(socket: Socket) {
        socket.soTimeout = 20_000
        val input = socket.getInputStream()
        val buffer = ByteArray(16 * 1024)
        var tail = ""
        while (!tail.endsWith("0\r\n\r\n")) {
            val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
            if (read < 0) return
            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
        }
    }

    /** Answers connection number n with `raw(n)` after the whole request was read and [beforeAnswer] ran. */
    private fun serve(beforeAnswer: () -> Unit = {}, raw: (Int) -> String = { CREATED }) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                val number: Int = connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        readRequest(it)
                        beforeAnswer()
                        runCatching {
                            it.getOutputStream().write(raw(number).encodeToByteArray())
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
        }
    }

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private inner class StormHandler(
        private val port: () -> Int = { server.localPort },
        private val fields: () -> List<UploadField> = { emptyList() },
        private val onClassify: suspend () -> Unit = {},
        private val onProgress: suspend (Float) -> Unit = {},
        private val onPrepared: suspend (Int) -> Unit = {},
        private val prepared: AtomicInteger = AtomicInteger(0),
        private val retry: RetryPolicy = ExponentialBackoffRetryPolicy(),
    ) : UploadHandler<String>(NoopTransport()) {
        override val type: String = "storm"
        override val retryPolicy: RetryPolicy get() = retry
        val delivered = AtomicInteger(0)
        val settled = AtomicInteger(0)
        @Volatile var rowProbe: (String) -> UploaderItem? = { null }
        val rowAtDelivered = java.util.Collections.synchronizedList(mutableListOf<UploaderItem?>())
        val rowAtSettled = java.util.Collections.synchronizedList(mutableListOf<UploaderItem?>())
        val settledResults = java.util.Collections.synchronizedList(mutableListOf<SettleResult>())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            val request = UploadRequest(url = "http://127.0.0.1:${port()}/upload", fields = fields())
            onPrepared(prepared.incrementAndGet())
            return UploadPreparation.Proceed(request)
        }

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            onClassify()
            return super.classify(payload, result, response)
        }

        override suspend fun onUploadProgress(payload: String, fraction: Float) = onProgress(fraction)
        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            rowAtDelivered += rowProbe(payload.removePrefix("payload-"))
            delivered.incrementAndGet()
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            rowAtSettled += rowProbe(payload.removePrefix("payload-"))
            settledResults += result
            settled.incrementAndGet()
        }
    }

    private fun startEngine(store: UploaderStore, handler: StormHandler, ids: List<String> = listOf("item-1")): UploaderEngine {
        val iterator = ids.iterator()
        val started: UploaderEngine = testEngine(LockedStore(store), listOf(handler), scope, idGenerator = { iterator.next() })
        engines += started
        UploaderEngineRegistry.register(started)
        runBlocking {
            withTimeout(10_000L) {
                ids.forEach { started.enqueue(handler, "payload-$it") }
                started.drain()
            }
        }
        return started
    }

    private fun worker(id: String, runAttemptCount: Int = 0, engineWaitMillis: Long = 200L) =
        TestListenableWorkerBuilder<UploadHandlerWorker>(context).setInputData(
            workDataOf(
                UploadHandlerWorker.ITEM_ID_KEY to id,
                UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to engineWaitMillis,
                UploadHandlerWorker.CONNECT_TIMEOUT_MILLIS_KEY to 5_000,
                UploadHandlerWorker.READ_TIMEOUT_MILLIS_KEY to 5_000,
            ),
        ).setRunAttemptCount(runAttemptCount).build()

    private fun runMany(count: Int, id: String = "item-1"): List<ListenableWorker.Result> = runBlocking {
        withTimeout(30_000L) { (1..count).map { uploads.async { worker(id).doWork() } }.awaitAll() }
    }

    private fun journalFiles(): List<File> = journalRoot.walkTopDown().filter { it.isFile }.toList()

    private fun file(size: Int): File = File(context.cacheDir, "r3-${System.nanoTime()}.bin").also {
        RandomAccessFile(it, "rw").use { f -> f.setLength(size.toLong()) }
        tempFiles += it
    }

    // R3-01: eight jobs for one item all upload (unique work cannot be assumed across a stop): one delivery, one settle.
    @Test
    fun `eight concurrent jobs for one item deliver once and settle once`() {
        serve(beforeAnswer = { Thread.sleep(40L) })
        val store = TestUploaderStore()
        val handler = StormHandler()
        startEngine(store, handler)
        val results = runMany(8)
        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(1, handler.delivered.get(), "onDelivered once")
        assertEquals(1, handler.settled.get(), "onSettled once")
        assertNull(store.find("item-1"))
        assertTrue(journalFiles().isEmpty(), "no entry left: ${journalFiles()}")
    }

    // R3-02: eight jobs all get a 500 for the same attempt: one charge, one settle.
    @Test
    fun `eight concurrent jobs that all got a 500 charge the attempt once`() {
        serve(beforeAnswer = { Thread.sleep(40L) }, raw = { INTERNAL_ERROR })
        val store = TestUploaderStore()
        val handler = StormHandler()
        startEngine(store, handler)
        runMany(8).forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(1, store.find("item-1")?.attempts, "one attempt, one charge")
        assertEquals(1, handler.settled.get(), "onSettled once for one settlement")
        assertTrue(journalFiles().isEmpty(), "no entry left: ${journalFiles()}")
    }



    // R3-05: re-delivery of one journaled answer to many jobs at once.
    @Test
    fun `eight jobs replaying one journaled delivery settle it once`() {
        serve()
        val store = TestUploaderStore()
        val handler = StormHandler()
        startEngine(store, handler)
        outcomesDir.mkdirs()
        File(outcomesDir, "item-1").writeBytes(
            OutcomeCodec.encode(UploadResponse(201, emptyMap(), ByteArray(0), false), System.currentTimeMillis(), 0),
        )
        runMany(8).forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(0, connections.get())
        assertEquals(1, handler.delivered.get())
        assertEquals(1, handler.settled.get())
        assertTrue(journalFiles().isEmpty())
    }

    // R3-06: re-delivery of one journaled failure to many jobs at once.
    @Test
    fun `eight jobs replaying one journaled 500 charge it once`() {
        serve()
        val store = TestUploaderStore()
        val handler = StormHandler()
        startEngine(store, handler)
        outcomesDir.mkdirs()
        File(outcomesDir, "item-1").writeBytes(
            OutcomeCodec.encode(UploadResponse(500, emptyMap(), ByteArray(0), false), System.currentTimeMillis(), 0),
        )
        runMany(8).forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(0, connections.get())
        assertEquals(1, store.find("item-1")?.attempts)
        assertEquals(1, handler.settled.get())
        assertTrue(journalFiles().isEmpty())
    }


    // R3-08: stop storm at random moments with a store whose commit yields, then one clean job.
    @Test
    fun `random stops of many jobs never lose the delivery and never run onSettled twice`() {
        serve(beforeAnswer = { Thread.sleep(Random.nextLong(0, 40)) })
        val store = YieldingStore(TestUploaderStore())
        val handler = StormHandler()
        startEngine(store, handler)
        val random = Random(7)
        runBlocking {
            withTimeout(60_000L) {
                repeat(10) {
                    val jobs: List<Deferred<ListenableWorker.Result>> = (1..4).map { uploads.async { worker("item-1").doWork() } }
                    delay(random.nextLong(0, 60))
                    jobs.forEach { if (random.nextBoolean()) it.cancel() }
                    jobs.forEach { runCatching { withTimeout(10_000L) { it.join() } } }
                }
            }
        }
        val last = runBlocking { withTimeout(20_000L) { worker("item-1").doWork() } }
        assertIs<ListenableWorker.Result.Success>(last)
        assertNull(store.find("item-1"), "delivered")
        assertTrue(handler.delivered.get() >= 1)
        assertTrue(handler.settled.get() <= 1, "onSettled at most once, was ${handler.settled.get()}")
        assertTrue(journalFiles().isEmpty(), "no entry left: ${journalFiles()}")
    }

    // R3-09: many items, cooperative progress hooks that never return: every job still settles within the bound.
    @Test
    fun `eight items with never returning progress hooks all settle within the bound`() {
        serve()
        val ids = (1..8).map { "p-$it" }
        val handler = StormHandler(
            fields = { listOf(UploadField.File("f", "f.bin", "application/octet-stream", file(300_000).path)) },
            onProgress = { delay(60_000L) },
        )
        startEngine(TestUploaderStore(), handler, ids)
        val results = runBlocking {
            withTimeout(15_000L) { ids.map { id -> uploads.async { worker(id).doWork() } }.awaitAll() }
        }
        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(8, handler.delivered.get())
    }



    // W1 (D-59, P10): eight jobs for one item in one process run one after the other: the file is uploaded once.
    @Test
    fun `eight concurrent jobs for one item upload it once`() {
        serve(beforeAnswer = { Thread.sleep(40L) })
        val store = TestUploaderStore()
        val handler = StormHandler()
        startEngine(store, handler)
        val results = runMany(8)
        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(1, connections.get(), "one connection: the other seven jobs waited and found nothing owed")
        assertEquals(1, handler.delivered.get(), "onDelivered once")
        assertEquals(1, handler.settled.get(), "onSettled once")
        assertNull(store.find("item-1"))
        assertTrue(journalFiles().isEmpty(), "no entry left: ${journalFiles()}")
    }

    // R3-03: the same without an answer: every job reports a transport failure for the one attempt.
    @Test
    fun `eight concurrent transport failures of one attempt run onSettled once`() {
        val deadPort: Int = ServerSocket(0).use { it.localPort }
        val arrived = AtomicInteger(0)
        val handler = StormHandler(
            port = { deadPort },
            onClassify = {
                arrived.incrementAndGet()
                withTimeoutOrNull(1_000L) { while (arrived.get() < 8) delay(5L) }
            },
        )
        val store = TestUploaderStore()
        startEngine(store, handler)
        runMany(8).forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(1, store.find("item-1")?.attempts, "one attempt, one charge")
        assertEquals(1, handler.settled.get(), "the settlement landed once; onSettled is 'after the row was committed'")
    }

    // R3-10: as R3-09 but the hook blocks its thread, for more jobs than Default has threads.
    @Test
    fun `more items than threads with blocking progress hooks still settle within the bound`() {
        serve()
        val count: Int = Runtime.getRuntime().availableProcessors() + 2
        val ids = (1..count).map { "b-$it" }
        val handler = StormHandler(
            fields = { listOf(UploadField.File("f", "f.bin", "application/octet-stream", file(300_000).path)) },
            onProgress = { Thread.sleep(5_000L) },
        )
        startEngine(TestUploaderStore(), handler, ids)
        val startedAt = System.nanoTime()
        val delivered = runBlocking {
            withContext(Dispatchers.IO) {
                val jobs = ids.map { id -> async(Dispatchers.IO) { uploads.async { worker(id).doWork() }.await() } }
                withTimeout(25_000L) { jobs.awaitAll() }
            }
        }
        val tookMillis: Long = (System.nanoTime() - startedAt) / 1_000_000L
        delivered.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertTrue(tookMillis < 4_000L, "a progress hook must not hold up settlements: took $tookMillis ms for $count items")
    }

    // R3-22: another job settled the item between this job's preparation and the start of its transfer.
    @Test
    fun `a job whose item settled between preparation and transfer uploads nothing`() {
        serve()
        val store = TestUploaderStore()
        val handler = StormHandler(onPrepared = { call -> if (call == 2) store.deleteById("item-1") })
        startEngine(store, handler)
        val result = runBlocking { withTimeout(20_000L) { worker("item-1").doWork() } }
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(0, connections.get(), "the item is gone: its file must not be uploaded again")
    }

    // R3-12: the per-item lock table does not keep entries of cancelled waiters.
    @Test
    fun `cancelled waiters leave no entry in the item lock table`() {
        runBlocking {
            withTimeout(30_000L) {
                val random = Random(11)
                val jobs = (1..400).map { index ->
                    uploads.launch { OutcomeReplayLocks.withItem("lock-${index % 7}") { delay(3L) } }
                }
                delay(5L)
                jobs.forEach { if (random.nextInt(3) == 0) it.cancel() }
                jobs.forEach { it.join() }
            }
        }
        assertEquals(0, OutcomeReplayLocks.activeKeys, "lock table must be empty when nobody holds or waits")
    }

    // R3-14: the creation sweep and one job per item race over thirty journaled answers.
    @Test
    fun `the creation sweep racing thirty jobs settles every journaled delivery once`() {
        serve()
        val ids = (1..30).map { "sw-$it" }
        val store = TestUploaderStore()
        val handler = StormHandler()
        startEngine(store, handler, ids)
        ids.forEach {
            File(outcomesDir, it).writeBytes(
                OutcomeCodec.encode(UploadResponse(201, emptyMap(), ByteArray(0), false), System.currentTimeMillis(), 0),
            )
        }
        val results = runBlocking {
            withTimeout(30_000L) {
                val jobs = ids.map { id -> uploads.async { worker(id).doWork() } }
                // The sweep starts inside the constructor, on its own scope.
                uploads.async { WorkManagerUploadHandlerTransport(context, UploadTransportConfig(), io.github.jamal_wia.kmptoolkit.logging.NoopLogger) }
                jobs.awaitAll()
            }
        }
        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        runBlocking { withTimeout(10_000L) { while (journalFiles().isNotEmpty()) delay(20L) } }
        assertEquals(0, connections.get())
        assertEquals(30, handler.delivered.get(), "each answer is delivered exactly once")
        assertEquals(30, handler.settled.get())
        assertTrue(store.items.isEmpty(), "rows left: ${store.items.map { it.id + "/" + it.state + "/a" + it.attempts }}")
    }

    // R3-15: five items, three jobs each, random stops, a store whose commit fails or leaks its own cancellation at random.
    @Test
    fun `random stops and a flaky store over five items end with every item delivered and nothing left`() {
        serve(beforeAnswer = { Thread.sleep(Random.nextLong(0, 30)) })
        val ids = (1..5).map { "fl-$it" }
        val random = Random(23)
        val store = FlakyStore(TestUploaderStore(), random)
        val handler = StormHandler()
        startEngine(store, handler, ids)
        runBlocking {
            withTimeout(90_000L) {
                repeat(6) {
                    val jobs: List<Deferred<ListenableWorker.Result>> =
                        ids.flatMap { id -> (1..3).map { uploads.async { worker(id).doWork() } } }
                    delay(random.nextLong(0, 50))
                    jobs.forEach { if (random.nextInt(3) == 0) it.cancel() }
                    jobs.forEach { runCatching { withTimeout(15_000L) { it.join() } } }
                }
                store.calm()
                var rounds = 0
                while (ids.any { store.inner.find(it) != null } && rounds++ < 10) {
                    ids.forEach { id -> uploads.async { worker(id).doWork() }.await() }
                }
            }
        }
        ids.forEach { assertNull(store.inner.find(it), "$it delivered") }
        assertTrue(handler.settled.get() <= ids.size, "onSettled at most once per item, was ${handler.settled.get()}")
        assertTrue(journalFiles().isEmpty(), "no entry left: ${journalFiles()}")
    }

    /** A journal storage in memory, so the interleaving of two writers is exactly the one the test spells out. */
    private class MapStorage : OutcomeStorage {
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        override fun write(itemId: String, bytes: ByteArray): Boolean { files[itemId] = bytes; return true }
        override fun read(itemId: String): ByteArray? = files[itemId]
        override fun delete(itemId: String) { files.remove(itemId) }
        override fun itemIds(): List<String> = files.keys.sorted()
        override fun purgeLeftovers() = Unit
    }


    /** The shared test store loses updates under concurrent writers (read-modify-write on one flow): serialise them. */
    private class LockedStore(private val inner: UploaderStore) : UploaderStore by inner {
        private val lock = kotlinx.coroutines.sync.Mutex()
        override suspend fun insertKeep(record: UploaderItem): Boolean = lock.withLock { inner.insertKeep(record) }
        override suspend fun insertReplace(record: UploaderItem): UploaderItem? = lock.withLock { inner.insertReplace(record) }
        override suspend fun deleteById(id: String) = lock.withLock { inner.deleteById(id) }
        override suspend fun recordFailure(
            id: String, attempts: Int, nextRunAtEpochMillis: Long, lastError: String?,
            expectedState: UploaderItemState, expectedClaim: Long, disposition: FailureDisposition,
        ): Boolean = lock.withLock {
            inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
            lock.withLock { inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim) }
        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
            lock.withLock { inner.park(id, lastError, expectedState, expectedClaim) }
    }

    private class FlakyStore(val inner: TestUploaderStore, private val random: Random) : UploaderStore by inner {
        @Volatile private var calm = false
        fun calm() { calm = true }
        override suspend fun deleteById(id: String) {
            kotlinx.coroutines.yield()
            if (!calm) {
                when (random.nextInt(4)) {
                    0 -> throw IllegalStateException("database is locked")
                    1 -> throw kotlin.coroutines.cancellation.CancellationException("the store's own scope")
                }
            }
            inner.deleteById(id)
        }
    }

    private fun sleepyDrip() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching {
                        readRequest(socket)
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\n".encodeToByteArray())
                        while (!socket.isClosed) {
                            out.write('X'.code)
                            out.flush()
                            Thread.sleep(100L)
                        }
                    }
                    runCatching { socket.close() }
                }
            }
        }
    }

    // R3-11b: the watchdog bound itself, whatever the outcome type: 24 transfers on dripped headers all end quickly.
    @Test
    fun `many connections with dripped headers all end within the watchdog bound`() {
        sleepyDrip()
        val request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/u", fields = emptyList())
        val startedAt = System.nanoTime()
        runBlocking {
            withTimeout(20_000L) {
                (1..24).map {
                    async(Dispatchers.IO) {
                        withWriteWatchdog(400) { watch ->
                            withContext(Dispatchers.IO) { performMultipartUploadCapturing(request, 2_000, 400, watch = watch) }
                        }
                    }
                }.awaitAll()
            }
        }
        val tookMillis: Long = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(tookMillis < 5_000L, "took $tookMillis ms")
    }


    // R3-17: the hooks' contract in every route: onDelivered sees the row, onSettled does not (delivery) or sees the charge (failure).
    @Test
    fun `hooks see the row as documented on the journaled and replayed routes`() {
        serve()
        val store = TestUploaderStore()
        val handler = StormHandler()
        handler.rowProbe = { store.find(it) }
        startEngine(store, handler, listOf("item-1", "item-2", "item-3"))
        // item-1: a live upload, journaled. item-2: a replayed delivery. item-3: a replayed 500.
        File(outcomesDir, "item-2").writeBytes(OutcomeCodec.encode(UploadResponse(201, emptyMap(), ByteArray(0), false), System.currentTimeMillis(), 0))
        File(outcomesDir, "item-3").writeBytes(OutcomeCodec.encode(UploadResponse(500, emptyMap(), ByteArray(0), false), System.currentTimeMillis(), 0))
        listOf("item-1", "item-2", "item-3").forEach { id ->
            assertIs<ListenableWorker.Result.Success>(runBlocking { withTimeout(20_000L) { worker(id).doWork() } })
        }
        assertEquals(2, handler.rowAtDelivered.size)
        handler.rowAtDelivered.forEach { assertTrue(it != null, "onDelivered runs before the row is removed") }
        assertEquals(3, handler.rowAtSettled.size)
        val deliveredSettled = handler.rowAtSettled.filterIndexed { index, _ -> handler.settledResults[index] is SettleResult.Delivered }
        deliveredSettled.forEach { assertNull(it, "onSettled of a delivery runs after the row is gone") }
        val failedSettled = handler.rowAtSettled.filterIndexed { index, _ -> handler.settledResults[index] is SettleResult.Failed }
        assertEquals(1, failedSettled.size)
        assertEquals(1, failedSettled.single()?.attempts, "the row is already updated when onSettled runs")
    }

    // R3-18: the engine goes away and comes back (a new one on the same rows) while six jobs hold answers.
    @Test
    fun `an engine swapped under six waiting jobs settles the delivery once`() {
        val release = java.util.concurrent.CountDownLatch(1)
        serve(beforeAnswer = { release.await(10, java.util.concurrent.TimeUnit.SECONDS) })
        val backing = TestUploaderStore()
        val first = StormHandler()
        val engineA = startEngine(backing, first)
        val jobs = (1..6).map { uploads.async { worker("item-1", engineWaitMillis = 20_000L).doWork() } }
        runBlocking { withTimeout(10_000L) { while (connections.get() < 1) delay(10L) } }
        UploaderEngineRegistry.unregister(engineA as DefaultUploaderEngine)
        release.countDown()
        runBlocking { delay(300L) }
        val second = StormHandler()
        val engineB = testEngine(LockedStore(backing), listOf(second), scope)
        engines += engineB
        UploaderEngineRegistry.register(engineB)
        val results = runBlocking { withTimeout(30_000L) { jobs.awaitAll() } }
        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(1, connections.get(), "one upload; the other five jobs waited and found nothing owed")
        assertEquals(1, second.delivered.get(), "one delivery through the second engine")
        assertEquals(0, first.delivered.get())
        assertNull(backing.find("item-1"))
        assertTrue(journalFiles().isEmpty(), "no entry left: ${journalFiles()}")
    }

    // R3-19: registry churn while eight jobs settle.
    @Test
    fun `registry churn while eight jobs settle loses nothing`() {
        serve(beforeAnswer = { Thread.sleep(30L) })
        val backing = TestUploaderStore()
        val handler = StormHandler()
        val engine = startEngine(backing, handler)
        val churn = thread(isDaemon = true) {
            repeat(300) {
                UploaderEngineRegistry.unregister(engine as DefaultUploaderEngine)
                Thread.sleep(2L)
                UploaderEngineRegistry.register(engine)
                Thread.sleep(2L)
            }
        }
        val jobs = (1..8).map { uploads.async { worker("item-1", engineWaitMillis = 15_000L).doWork() } }
        val results = runBlocking { withTimeout(40_000L) { jobs.awaitAll() } }
        churn.join(10_000L)
        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(1, handler.delivered.get())
        assertNull(backing.find("item-1"))
        assertTrue(journalFiles().isEmpty())
    }

    // R3-20: sixty sequential jobs with progress, drops and an answer each leave no threads behind.
    @Test
    fun `sixty sequential jobs do not leak threads`() {
        serve()
        val ids = (1..60).map { "t-$it" }
        val handler = StormHandler(fields = { listOf(UploadField.File("f", "f.bin", "application/octet-stream", file(50_000).path)) })
        startEngine(TestUploaderStore(), handler, ids)
        runBlocking { withTimeout(20_000L) { ids.take(5).forEach { worker(it).doWork() } } }
        val before: Int = Thread.getAllStackTraces().size
        runBlocking { withTimeout(60_000L) { ids.drop(5).forEach { assertIs<ListenableWorker.Result.Success>(worker(it).doWork()) } } }
        runBlocking { delay(1_500L) }
        val after: Int = Thread.getAllStackTraces().size
        assertTrue(after - before < 20, "threads grew from $before to $after over 55 jobs")
        assertEquals(60, handler.delivered.get())
    }

    // R3-21: 24 connections whose body drips one byte per 100 ms are each cut at the total read deadline, status kept.
    @Test
    fun `many dripping bodies are all cut at the read deadline with their status kept`() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    runCatching {
                        readRequest(socket)
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".encodeToByteArray())
                        while (!socket.isClosed) { out.write('b'.code); out.flush(); Thread.sleep(100L) }
                    }
                    runCatching { socket.close() }
                }
            }
        }
        val request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/u", fields = emptyList())
        val startedAt = System.nanoTime()
        val outcomes: List<MultipartOutcome> = runBlocking {
            withTimeout(20_000L) {
                (1..24).map {
                    async(Dispatchers.IO) {
                        withWriteWatchdog(500) { watch ->
                            withContext(Dispatchers.IO) { performMultipartUploadCapturing(request, 2_000, 500, watch = watch) }
                        }
                    }
                }.awaitAll()
            }
        }
        val tookMillis: Long = (System.nanoTime() - startedAt) / 1_000_000L
        outcomes.forEach {
            assertEquals(UploadResult.Completed(200), it.result)
            assertTrue(it.response?.isBodyTruncated == true, "a body that did not finish is flagged")
        }
        assertTrue(tookMillis < 4_000L, "took $tookMillis ms")
    }



    private class YieldingStore(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        override suspend fun deleteById(id: String) {
            kotlinx.coroutines.yield()
            delegate.deleteById(id)
        }

        fun find(id: String): UploaderItem? = delegate.find(id)
    }

    private companion object {
        const val CREATED = "HTTP/1.1 201 Created\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
        const val INTERNAL_ERROR = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 2\r\nConnection: close\r\n\r\nno"
    }
}
