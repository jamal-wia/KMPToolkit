package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The handler transport's worker under process-level chaos: an engine that goes away between the server's answer
 * and the settlement, a store that refuses the settlement, two jobs for one item, a job stopped mid-body or in the
 * middle of the settlement, and item ids the journal cannot name. The server is a real loopback socket; every wait
 * is bounded and every engine and server is torn down in cleanUp.
 */
@RunWith(AndroidJUnit4::class)
class UploaderLifecycleChaosRound2AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply {
        receiveBufferSize = 4 * 1024
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }
    private val connections = AtomicInteger(0)
    private val accepted = CountDownLatch(1)
    private val held = mutableListOf<Socket>()
    private val engines = mutableListOf<UploaderEngine>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uploads = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempFiles = mutableListOf<File>()
    private val journalRoot: File get() = File(context.noBackupFilesDir, "kmptoolkit-uploader")
    private val config = UploadTransportConfig(uniqueWorkNamePrefix = "chaos_", workTag = "chaos")
    private val gate = CompletableDeferred<Unit>()
    private val outcomesDir: File get() = File(journalRoot, "outcomes")

    @BeforeTest
    fun clean() {
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
        gate.complete(Unit)
        journalRoot.walkTopDown().forEach { it.setWritable(true) }
        uploads.cancel()
        synchronized(held) { held.forEach { runCatching { it.close() } } }
        server.close()
        engines.forEach { runCatching { it.close() } }
        scope.cancel()
        tempFiles.forEach { it.delete() }
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    // --- loopback server -----------------------------------------------------------------------------------

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

    /** Answers every connection with [raw] after [beforeAnswer] (which runs once the whole request was read). */
    private fun serve(raw: String = CREATED, beforeAnswer: () -> Unit = {}) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        readRequest(it)
                        beforeAnswer()
                        runCatching {
                            it.getOutputStream().write(raw.encodeToByteArray())
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
        }
    }

    // --- engine and handler --------------------------------------------------------------------------------

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private inner class ChaosHandler(
        private val fields: () -> List<UploadField> = { emptyList() },
        private val onClassify: () -> Unit = {},
        private val limit: () -> Int = { UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES },
        private val onPrepare: (Int) -> Unit = {},
        private val onProgress: suspend (Float) -> Unit = {},
        private val onSettledHook: suspend () -> Unit = {},
        private val retry: RetryPolicy = ExponentialBackoffRetryPolicy(),
    ) : UploadHandler<String>(NoopTransport()) {
        override val type: String = "chaos"
        override val retryPolicy: RetryPolicy get() = retry
        override val maxResponseBodyBytes: Int get() = limit()
        private val prepared = AtomicInteger(0)
        val delivered = java.util.Collections.synchronizedList(mutableListOf<UploadResponse?>())
        val settled = AtomicInteger(0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            onPrepare(prepared.incrementAndGet())
            return UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = fields()))
        }

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            onClassify()
            return super.classify(payload, result, response)
        }

        override suspend fun onUploadProgress(payload: String, fraction: Float) = onProgress(fraction)

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            onSettledHook()
            settled.incrementAndGet()
        }
    }

    /** A store whose first delete (the settlement of a delivery) fails, as a full disk or a locked database would. */
    private class RefusingStore(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        @Volatile var failDeletes: Int = 0
        override suspend fun deleteById(id: String) {
            if (failDeletes > 0) {
                failDeletes--
                throw IllegalStateException("database is locked")
            }
            delegate.deleteById(id)
        }
    }

    /** A store whose delete suspends first, as a real database call does, so a cancellation lands on the settlement's write. */
    private class YieldingStore(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        override suspend fun deleteById(id: String) {
            kotlinx.coroutines.yield()
            delegate.deleteById(id)
        }
    }

    private fun startEngine(
        store: UploaderStore,
        handler: ChaosHandler,
        id: String = "item-1",
        register: Boolean = true,
        clock: UploaderClock = TestClock(),
    ): UploaderEngine {
        val started: UploaderEngine = testEngine(store, listOf(handler), scope, clock = clock, idGenerator = { id })
        engines += started
        if (register) UploaderEngineRegistry.register(started)
        runBlocking {
            withTimeout(10_000L) {
                started.enqueue(handler, "payload")
                started.drain() // hands off to the no-op transport, leaving the item in flight
            }
        }
        return started
    }

    private fun worker(id: String = "item-1", engineWaitMillis: Long = 100L): androidx.work.testing.TestListenableWorkerBuilder<UploadHandlerWorker> =
        TestListenableWorkerBuilder<UploadHandlerWorker>(context).setInputData(
            workDataOf(
                UploadHandlerWorker.ITEM_ID_KEY to id,
                UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to engineWaitMillis,
                UploadHandlerWorker.CONNECT_TIMEOUT_MILLIS_KEY to 5_000,
                UploadHandlerWorker.READ_TIMEOUT_MILLIS_KEY to 5_000,
            ),
        )

    private fun runWorker(id: String = "item-1"): ListenableWorker.Result =
        runBlocking { withTimeout(20_000L) { worker(id).build().doWork() } }

    private fun journalFiles(): List<File> =
        journalRoot.walkTopDown().filter { it.isFile }.toList()

    private fun sent() = UploadResponse(201, mapOf("Location" to "/jobs/9"), """{"job":"j9"}""".encodeToByteArray(), false)

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        runBlocking { withTimeout(15_000L) { while (!condition()) delay(20L) } }
    }


    /** A store whose delete throws a cancellation of its own (a scope or timeout inside the store), the worker not being stopped. */
    private class CancellingStore(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        @Volatile var cancelDeletes: Int = 0
        override suspend fun deleteById(id: String) {
            if (cancelDeletes > 0) {
                cancelDeletes--
                throw kotlin.coroutines.cancellation.CancellationException("the store's own scope was cancelled")
            }
            delegate.deleteById(id)
        }
    }

    /** A store whose delete never returns until [gate] opens: the commit a process dies in the middle of. */
    private class HangingStore(private val delegate: TestUploaderStore, private val gate: CompletableDeferred<Unit>, val entered: CountDownLatch) :
        UploaderStore by delegate {
        @Volatile var hang: Boolean = true
        override suspend fun deleteById(id: String) {
            if (hang) {
                entered.countDown()
                gate.await()
                return
            }
            delegate.deleteById(id)
        }
    }

    private fun file1k(): File = File(context.cacheDir, "r2-${System.nanoTime()}.bin").also {
        RandomAccessFile(it, "rw").use { f -> f.setLength(1024) }
        tempFiles += it
    }




    // R2-L5: stopped while waiting for an engine after the answer was journaled
    @Test
    fun `a job stopped while waiting for an engine keeps the answer and leaves the item lock free`() {
        lateinit var engineA: UploaderEngine
        serve(beforeAnswer = { UploaderEngineRegistry.unregister(engineA as DefaultUploaderEngine) })
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        engineA = startEngine(backing, handler)
        val running: Deferred<ListenableWorker.Result> = uploads.async { worker(engineWaitMillis = 60_000L).build().doWork() }
        awaitTrue("the answer is journaled") { journalFiles().isNotEmpty() }
        runBlocking { delay(300L) }
        running.cancel()
        runBlocking { withTimeout(5_000L) { running.join() } }
        assertEquals(1, journalFiles().size, "the stop must not lose the entry")
        UploaderEngineRegistry.register(engineA)
        val rerun: ListenableWorker.Result = runWorker()
        assertIs<ListenableWorker.Result.Success>(rerun)
        assertEquals(1, connections.get())
        assertEquals(1, handler.delivered.size)
        assertTrue(journalFiles().isEmpty())
    }

    // R2-L6: a corrupt or half-written entry for the very item the job is about to upload
    @Test
    fun `any damaged entry at the start of a job is dropped and the item uploads once`() {
        serve()
        val good: ByteArray = OutcomeCodec.encode(UploadResponse(201, emptyMap(), ByteArray(0), false), 1L)
        val damaged: List<Pair<String, ByteArray>> = listOf(
            "empty" to ByteArray(0),
            "three bytes" to byteArrayOf(0, 0, 0),
            "cut by one" to good.copyOf(good.size - 1),
            "extra tail" to good + byteArrayOf(9),
            "unknown version" to good.copyOf().also { it[3] = 99 },
            "flag 7" to good.copyOf().also { it[16] = 7 },
            "huge header count" to good.copyOf().also { it[17] = 0x7f },
        )
        outcomesDir.mkdirs()
        File(journalRoot, "outcomes-partial").mkdirs()
        damaged.forEachIndexed { index, (label, bytes) ->
            val id = "item-$index"
            File(outcomesDir, id).writeBytes(bytes)
            File(File(journalRoot, "outcomes-partial"), "$id.leftover").writeBytes(bytes)
            val handler = ChaosHandler()
            val engine: UploaderEngine = startEngine(TestUploaderStore(), handler, id = id)
            val result: ListenableWorker.Result = runWorker(id)
            assertIs<ListenableWorker.Result.Success>(result, label)
            assertEquals(index + 1, connections.get(), "$label: uploaded once more")
            assertEquals(1, handler.delivered.size, label)
            assertTrue(!File(outcomesDir, id).exists(), "$label: entry gone")
            engine.close()
        }
    }







    /** A store that stops the job (as WorkManager does) while the commit is being written. */
    private class StoppingStore(private val delegate: TestUploaderStore, private val job: CompletableDeferred<kotlinx.coroutines.Job>) :
        UploaderStore by delegate {
        override suspend fun deleteById(id: String) {
            if (job.isCompleted) job.await().cancel()
            delegate.deleteById(id)
        }
    }


    // R2-L11: replaying journaled answers with statuses the classifier never expects must not throw or loop
    @Test
    fun `journaled answers with unusual statuses replay without escaping the job`() {
        serve()
        val statuses: List<Int> = listOf(-5, 0, 99, 100, 199, 304, 600, 99_999, Int.MAX_VALUE, Int.MIN_VALUE)
        statuses.forEachIndexed { index, status ->
            val id = "item-$index"
            val journal = OutcomeJournal(AndroidOutcomeStorage.forContext(context))
            assertTrue(journal.record(id, UploadResponse(status, emptyMap(), ByteArray(0), false)))
            val handler = ChaosHandler()
            val engine: UploaderEngine = startEngine(TestUploaderStore(), handler, id = id)
            val result: ListenableWorker.Result = runWorker(id)
            assertIs<ListenableWorker.Result.Success>(result, "status $status")
            assertEquals(0, connections.get(), "status $status: replayed, not uploaded")
            assertTrue(!File(outcomesDir, id).exists(), "status $status: entry gone")
            engine.close()
        }
    }


    // R2-S1: a cancellation thrown by the store from inside the commit (the worker is not stopped)
    @Test
    fun `a cancellation the store throws inside the commit does not lose the answer`() {
        serve()
        val backing = TestUploaderStore()
        val store = CancellingStore(backing)
        val handler = ChaosHandler()
        startEngine(store, handler)
        store.cancelDeletes = 1
        runCatching { runBlocking { withTimeout(15_000L) { worker().build().doWork() } } }
        val rerun: ListenableWorker.Result = runWorker()
        assertIs<ListenableWorker.Result.Success>(rerun)
        assertEquals(1, connections.get(), "the answer was journaled before the failed commit; the re-run must replay it, not upload again")
        assertTrue(
            handler.delivered.size in 1..2,
            "onDelivered runs before the commit and may repeat after a commit that did not land " +
                "(UploadGateway.complete KDoc: onDelivered must tolerate running twice): ${handler.delivered.size}",
        )
        assertNull(backing.find("item-1"))
    }

    // R2-S2: an answer whose entry reads back as damaged right after it was written is not "handled"
    @Test
    fun `an entry that reads back damaged right after it was written is settled from memory`() {
        val mem = HashMap<String, ByteArray>()
        var corrupt = false
        val storage = object : OutcomeStorage {
            override fun write(itemId: String, bytes: ByteArray): Boolean { mem[itemId] = bytes; return true }
            override fun read(itemId: String): ByteArray? = mem[itemId]?.let { if (corrupt) it.copyOf(it.size / 2) else it }
            override fun delete(itemId: String) { mem.remove(itemId) }
            override fun itemIds(): List<String> = mem.keys.toList()
            override fun purgeLeftovers() = Unit
        }
        val journal = OutcomeJournal(storage)
        val response = UploadResponse(201, emptyMap(), "x".encodeToByteArray(), false)
        assertTrue(journal.record("a", response))
        corrupt = true
        var settledWith: UploadResponse? = null
        val landed: Boolean = runBlocking {
            withTimeout(5_000L) {
                journal.settleRecorded("a", response) { _, r, _, _ -> settledWith = r; JournalSettleOutcome.LANDED }
            }
        }
        assertTrue(landed)
        assertEquals(response, settledWith, "the answer was held in memory and never reached the engine")
    }

    // R2-S2: a clock step forward between the write and the settlement expires the worker's own fresh entry
    @Test
    fun `a clock that jumps past the retention between record and settle does not make the answer look handled`() {
        val mem = HashMap<String, ByteArray>()
        val storage = object : OutcomeStorage {
            override fun write(itemId: String, bytes: ByteArray): Boolean { mem[itemId] = bytes; return true }
            override fun read(itemId: String): ByteArray? = mem[itemId]
            override fun delete(itemId: String) { mem.remove(itemId) }
            override fun itemIds(): List<String> = mem.keys.toList()
            override fun purgeLeftovers() = Unit
        }
        var now = 1_000L
        val journal = OutcomeJournal(storage, clock = { now })
        val response = UploadResponse(201, emptyMap(), ByteArray(0), false)
        assertTrue(journal.record("a", response))
        now += OutcomeJournal.RETENTION_MILLIS + 1
        var settledWith: UploadResponse? = null
        runBlocking {
            withTimeout(5_000L) {
                journal.settleRecorded("a", response) { _, r, _, _ -> settledWith = r; JournalSettleOutcome.LANDED }
            }
        }
        assertEquals(response, settledWith, "the answer was held in memory and never reached the engine")
    }

    // R2-S3: an entry that exists but cannot be read at the start of a job is not "nothing owed"
    @Test
    fun `an entry that cannot be read at the start of a job does not make the job upload again`() {
        serve()
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)
        val journal = OutcomeJournal(AndroidOutcomeStorage.forContext(context))
        assertTrue(journal.record("item-1", UploadResponse(201, emptyMap(), "{}".encodeToByteArray(), false)))
        val entry = File(outcomesDir, "item-1")
        entry.setReadable(false)
        try {
            runCatching { runWorker() }
        } finally {
            entry.setReadable(true)
        }
        assertEquals(0, connections.get(), "the server's answer is on disk, merely unreadable right now: retry later, do not upload again")
    }

    @Test
    fun `an entry that stays unreadable for three runs is given up and the item uploads once`() {
        serve()
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)
        val journal = OutcomeJournal(AndroidOutcomeStorage.forContext(context))
        assertTrue(journal.record("item-1", UploadResponse(201, emptyMap(), "{}".encodeToByteArray(), false)))
        val entry = File(outcomesDir, "item-1")
        entry.setReadable(false)
        val result: ListenableWorker.Result = try {
            runBlocking { withTimeout(20_000L) { worker().setRunAttemptCount(3).build().doWork() } }
        } finally {
            entry.setReadable(true)
        }
        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(1, connections.get(), "given up after three runs: the file uploads (the documented at-least-once fallback)")
        assertEquals(1, handler.delivered.size)
        assertTrue(journalFiles().isEmpty(), "no journal file is left: ${journalFiles()}")
    }

    // R2-S4 (rewritten): the contract is that a journal entry must not replay into the next attempt
    @Test
    fun `an entry the journal could not delete after the commit does not replay into the next attempt`() {
        serve(raw = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        val backing = TestUploaderStore()
        val clock = TestClock(1_000L)
        val classified = AtomicInteger(0)
        val handler = ChaosHandler(
            onClassify = { if (classified.getAndIncrement() == 0) outcomesDir.setWritable(false) },
            retry = FixedRetryPolicy(delayMillis = 1_000L),
        )
        val started: UploaderEngine = startEngine(backing, handler, clock = clock)
        val first: ListenableWorker.Result = runWorker()
        outcomesDir.setWritable(true)
        assertIs<ListenableWorker.Result.Success>(first)
        assertEquals(1, journalFiles().size, "precondition: the delete after the commit could not remove the entry: ${journalFiles()}")
        assertEquals(1, backing.find("item-1")?.attempts)
        // The item becomes due and is handed off again: it is in flight at attempts 1.
        clock.millis += 3_600_000L
        runBlocking { withTimeout(10_000L) { started.drain() } }
        assertEquals(UploaderItemState.IN_FLIGHT, backing.find("item-1")?.state)

        val second: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(second)
        assertEquals(2, connections.get(), "the retry uploaded; the stale 503 did not stand in for it")
        assertEquals(2, backing.find("item-1")?.attempts)
        assertTrue(journalFiles().isEmpty(), "no journal file is left: ${journalFiles()}")
    }

    // R2-S6: stopped while the answer's body is still arriving: the status was received
    @Test
    fun `a job stopped while the answer body is still arriving keeps the received status`() {
        val socketHold = CountDownLatch(1)
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            synchronized(held) { held += socket }
            connections.incrementAndGet()
            readRequest(socket)
            runCatching {
                socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 100\r\nConnection: close\r\n\r\nab".encodeToByteArray())
                socket.getOutputStream().flush()
            }
            accepted.countDown()
            socketHold.await(20, TimeUnit.SECONDS)
        }
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)
        val running: Deferred<ListenableWorker.Result> = uploads.async { worker().build().doWork() }
        assertTrue(accepted.await(15, TimeUnit.SECONDS))
        runBlocking { delay(700L) }
        running.cancel()
        runBlocking { withTimeout(8_000L) { running.join() } }
        socketHold.countDown()
        assertTrue(
            journalFiles().isNotEmpty(),
            "the server answered 201 and the stop threw the received status away: the file uploads again",
        )
        val rerun: ListenableWorker.Result = runWorker()
        assertIs<ListenableWorker.Result.Success>(rerun)
        assertEquals(1, connections.get(), "the re-run replays the journaled status instead of uploading again")
        assertEquals(1, handler.delivered.size)
    }

    // R2-S7: a progress hook that ignores its cancellation must not hold the settlement
    @Test
    fun `a progress hook that ignores cancellation does not hold up the settlement`() {
        serve()
        val file: File = file1k()
        val handler = ChaosHandler(
            fields = { listOf(UploadField.File("f", "p.bin", "application/octet-stream", file.path)) },
            onProgress = { withContext(NonCancellable) { delay(20_000L) } },
        )
        startEngine(TestUploaderStore(), handler)
        uploads.async { worker().build().doWork() }
        runBlocking { withTimeout(10_000L) { delay(6_000L) } }
        assertEquals(1, handler.delivered.size, "2 s flush bound plus margin elapsed, the settlement is still behind the hook")
    }

    private companion object {
        const val CREATED: String = "HTTP/1.1 201 Created\r\nLocation: /jobs/1\r\nContent-Length: 12\r\nConnection: close\r\n\r\n{\"job\":\"j1\"}"
    }
}
