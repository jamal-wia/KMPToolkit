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
class UploadHandlerWorkerChaosAdversarialTest {

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

    @BeforeTest
    fun clean() {
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
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
    ) : UploadHandler<String>(NoopTransport()) {
        override val type: String = "chaos"
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
    ): UploaderEngine {
        val started: UploaderEngine = testEngine(store, listOf(handler), scope, idGenerator = { id })
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

    // --- attacks -------------------------------------------------------------------------------------------

    @Test
    fun `an engine torn down between the answer and the settlement hands the answer to the next process once`() {
        val release = CountDownLatch(1)
        serve(beforeAnswer = { accepted.countDown(); release.await(15, TimeUnit.SECONDS) })
        val backing = TestUploaderStore()
        val handlerA = ChaosHandler()
        val engineA: UploaderEngine = startEngine(backing, handlerA)
        val running: Deferred<ListenableWorker.Result> = uploads.async { worker().build().doWork() }
        assertTrue(accepted.await(10, TimeUnit.SECONDS), "the worker reached the server")

        // The process dies: the engine and its registry slot are gone before the answer arrives.
        engineA.close()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        release.countDown()
        val first: ListenableWorker.Result = runBlocking { withTimeout(20_000L) { running.await() } }

        assertIs<ListenableWorker.Result.Retry>(first)
        assertEquals(1, journalFiles().size, "the answer waits on disk for whoever comes next")

        // The next process: a new engine over the same store, and a new transport that sweeps orphans.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val handlerB = ChaosHandler()
        val engineB: UploaderEngine = testEngine(backing, listOf(handlerB), scope, idGenerator = { "item-2" })
        engines += engineB
        UploaderEngineRegistry.register(engineB)
        createWorkManagerUploadHandlerTransport(context, config)

        awaitTrue("the orphan is replayed") { handlerB.delivered.isNotEmpty() && journalFiles().isEmpty() }
        assertEquals(1, connections.get(), "uploaded once")
        assertEquals(1, handlerB.delivered.size, "delivered once")
        assertEquals(0, handlerA.delivered.size)
        assertNull(backing.find("item-1"))
    }

    @Test
    fun `a re-delivered job after the process died finds the answer and uploads nothing`() {
        val release = CountDownLatch(1)
        serve(beforeAnswer = { accepted.countDown(); release.await(15, TimeUnit.SECONDS) })
        val backing = TestUploaderStore()
        val engineA: UploaderEngine = startEngine(backing, ChaosHandler())
        val running: Deferred<ListenableWorker.Result> = uploads.async { worker().build().doWork() }
        assertTrue(accepted.await(10, TimeUnit.SECONDS))
        engineA.close()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        release.countDown()
        runBlocking { withTimeout(20_000L) { running.await() } }

        val handlerB = ChaosHandler()
        val engineB: UploaderEngine = testEngine(backing, listOf(handlerB), scope, idGenerator = { "item-2" })
        engines += engineB
        UploaderEngineRegistry.register(engineB)
        val second: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(second)
        assertEquals(1, connections.get())
        assertEquals(1, handlerB.delivered.size)
        assertTrue(journalFiles().isEmpty())
    }

    @Test
    fun `two jobs for one item at once settle it once and leave nothing on disk`() {
        serve(beforeAnswer = { Thread.sleep(300) })
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)

        val results: List<ListenableWorker.Result> = runBlocking {
            withTimeout(30_000L) {
                listOf(uploads.async { worker().build().doWork() }, uploads.async { worker().build().doWork() }).awaitAll()
            }
        }

        results.forEach { assertIs<ListenableWorker.Result.Success>(it) }
        assertEquals(1, handler.delivered.size, "the second job's settlement finds the item gone")
        assertEquals(1, handler.settled.get())
        assertNull(backing.find("item-1"))
        assertTrue(journalFiles().isEmpty())
    }

    @Test
    fun `a job that starts after the item settled uploads nothing, however many re-deliveries come`() {
        serve()
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)
        assertIs<ListenableWorker.Result.Success>(runWorker())

        repeat(10) { assertIs<ListenableWorker.Result.Success>(runWorker()) }

        assertEquals(1, connections.get())
        assertEquals(1, handler.delivered.size)
    }

    @Test
    fun `twenty transports created from four threads replay one orphaned answer once`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)
        OutcomeJournal(AndroidOutcomeStorage.forContext(context)).record("item-1", sent())

        val threads: List<Thread> = List(4) {
            thread { repeat(5) { createWorkManagerUploadHandlerTransport(context, config) } }
        }
        threads.forEach { it.join(20_000) }
        awaitTrue("the orphan is replayed") { handler.delivered.isNotEmpty() && journalFiles().isEmpty() }
        runBlocking { delay(500L) }

        assertEquals(1, handler.delivered.size, "replayed exactly once")
        assertEquals(1, handler.settled.get())
        assertEquals(0, connections.get())
    }

    @Test
    fun `a job cancelled while the peer stalls the body frees its thread and closes the socket`() {
        val source: File = File(context.cacheDir, "stall-${System.nanoTime()}.bin").also {
            RandomAccessFile(it, "rw").use { file -> file.setLength(64L * 1024 * 1024) }
            tempFiles += it
        }
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            synchronized(held) { held += socket }
            accepted.countDown()
        }
        val backing = TestUploaderStore()
        val handler = ChaosHandler(fields = { listOf(UploadField.File("f", "s.bin", "application/octet-stream", source.path)) })
        startEngine(backing, handler)
        val running: Deferred<ListenableWorker.Result> = uploads.async { worker().build().doWork() }
        assertTrue(accepted.await(10, TimeUnit.SECONDS))
        Thread.sleep(1_500) // the send buffers fill and the body write blocks

        running.cancel()
        val ended: Boolean = runBlocking { withTimeout(10_000L) { running.join(); true } }

        assertTrue(ended)
        val stuck: List<String> = Thread.getAllStackTraces().filterValues { frames ->
            frames.any { it.className.contains("performMultipart") || it.methodName.contains("writeMultipartBody") } &&
                frames.any { it.methodName.contains("socketWrite") || it.className.contains("NioSocketImpl") }
        }.keys.map { it.name }
        assertTrue(stuck.isEmpty(), "threads still blocked writing the body: $stuck")
        val peer: Socket = synchronized(held) { held.single() }
        peer.soTimeout = 5_000
        val closed: Boolean = runCatching {
            val buffer = ByteArray(64 * 1024)
            while (peer.getInputStream().read(buffer) >= 0) continue
            true
        }.getOrDefault(true) // a reset also means the sender dropped the connection
        assertTrue(closed, "the sender kept the connection open")
        assertEquals(UploaderItemState.IN_FLIGHT, backing.find("item-1")?.state)
        assertTrue(journalFiles().isEmpty())
        assertEquals(0, handler.delivered.size)
    }

    @Test
    fun `an item id the journal cannot name is still delivered once and writes nothing to disk`() {
        listOf("an id with spaces", "x".repeat(250), "weird\u0001id").forEach { id ->
            connections.set(0)
            serve()
            val backing = TestUploaderStore()
            val handler = ChaosHandler()
            startEngine(backing, handler, id = id)

            val result: ListenableWorker.Result = runWorker(id)

            assertIs<ListenableWorker.Result.Success>(result, "id of length ${id.length}")
            assertEquals(1, handler.delivered.size, "id of length ${id.length}")
            assertNull(backing.find(id))
            assertTrue(journalFiles().isEmpty(), "id of length ${id.length}")
            engines.last().close()
            UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        }
    }

    @Test
    fun `a server that closes without answering is a retryable failure and journals nothing`() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                connections.incrementAndGet()
                socket.use { readRequest(it) }
            }
        }
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)

        val result: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(result, "the failure landed in the engine, which owns the retry")
        assertEquals(UploaderItemState.PENDING, backing.find("item-1")?.state)
        assertEquals(1, backing.find("item-1")?.attempts)
        assertTrue(journalFiles().isEmpty())
        assertEquals(0, handler.delivered.size)
    }

    /** Runs the died-before-settling flow with a 500-byte body and a handler limit of [limit]; returns what the next process delivered. */
    private fun answerReplayedAfterDeath(limit: Int): UploadResponse? {
        val release = CountDownLatch(1)
        serve(
            raw = "HTTP/1.1 200 OK\r\nContent-Length: 500\r\nConnection: close\r\n\r\n" + "x".repeat(500),
            beforeAnswer = { accepted.countDown(); release.await(15, TimeUnit.SECONDS) },
        )
        val backing = TestUploaderStore()
        val engineA: UploaderEngine = startEngine(backing, ChaosHandler(limit = { limit }))
        val running: Deferred<ListenableWorker.Result> = uploads.async { worker().build().doWork() }
        assertTrue(accepted.await(10, TimeUnit.SECONDS))
        engineA.close()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        release.countDown()
        assertIs<ListenableWorker.Result.Retry>(runBlocking { withTimeout(20_000L) { running.await() } })
        val handlerB = ChaosHandler(limit = { limit })
        val engineB: UploaderEngine = testEngine(backing, listOf(handlerB), scope, idGenerator = { "item-2" })
        engines += engineB
        UploaderEngineRegistry.register(engineB)
        assertIs<ListenableWorker.Result.Success>(runWorker())
        assertEquals(1, handlerB.delivered.size)
        return handlerB.delivered.single()
    }

    @Test
    fun `an answer longer than the handler's limit reaches the next process cut to it and flagged`() {
        val response: UploadResponse = checkNotNull(answerReplayedAfterDeath(limit = 10))

        assertEquals(10, response.body().size)
        assertTrue(response.isBodyTruncated)
    }

    @Test
    fun `a handler that wants no body gets none from a replayed answer either`() {
        val response: UploadResponse = checkNotNull(answerReplayedAfterDeath(limit = 0))

        assertEquals(0, response.body().size)
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `a handler whose body limit getter throws still gets its delivery, once`() {
        serve()
        val backing = TestUploaderStore()
        val handler = ChaosHandler(limit = { error("no limit today") })
        startEngine(backing, handler)

        val result: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(1, handler.delivered.size)
        assertEquals(1, connections.get())
        assertTrue(journalFiles().isEmpty())
    }

    @Test
    fun `a prepareUpload that throws when the job runs spends one attempt and uploads nothing`() {
        serve()
        val backing = TestUploaderStore()
        val handler = ChaosHandler(onPrepare = { call -> if (call >= 2) error("token store unavailable") })
        startEngine(backing, handler)

        val result: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(0, connections.get())
        assertEquals(UploaderItemState.PENDING, backing.find("item-1")?.state)
        assertEquals(1, backing.find("item-1")?.attempts)
    }

    @Test
    fun `thirty uploads in a row leave no file descriptor or thread behind`() {
        serve()
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        val started: UploaderEngine = testEngine(backing, listOf(handler), scope)
        engines += started
        UploaderEngineRegistry.register(started)
        runBlocking {
            withTimeout(20_000L) {
                repeat(30) { started.enqueue(handler, "payload-$it") }
                started.drain()
            }
        }
        fun descriptors(): Int = File("/dev/fd").list()?.size ?: -1
        runWorker("item-1") // warm up pools and class loading
        val fdBefore: Int = descriptors()
        val threadsBefore: Int = Thread.getAllStackTraces().size

        for (index in 2..30) assertIs<ListenableWorker.Result.Success>(runWorker("item-$index"))
        Thread.sleep(300)

        assertEquals(30, handler.delivered.size)
        assertTrue(descriptors() - fdBefore <= 8, "descriptors grew from $fdBefore to ${descriptors()}")
        assertTrue(Thread.getAllStackTraces().size - threadsBefore <= 8, "threads grew from $threadsBefore to ${Thread.getAllStackTraces().size}")
        assertTrue(journalFiles().isEmpty())
    }

    @Test
    fun `a job cancelled while the server never answers is freed promptly, not after the read timeout`() {
        val reached = CountDownLatch(1)
        val hold = CountDownLatch(1)
        serve(raw = CREATED, beforeAnswer = { reached.countDown(); hold.await(20, TimeUnit.SECONDS) })
        val backing = TestUploaderStore()
        val handler = ChaosHandler()
        startEngine(backing, handler)
        val running: Deferred<ListenableWorker.Result> = uploads.async { worker().build().doWork() }
        assertTrue(reached.await(10, TimeUnit.SECONDS), "the request was fully sent")
        Thread.sleep(300)

        val cancelledAt: Long = System.nanoTime()
        running.cancel()
        runBlocking { withTimeout(12_000L) { running.join() } }
        val tookMillis: Long = (System.nanoTime() - cancelledAt) / 1_000_000
        hold.countDown()

        assertTrue(tookMillis < 3_000, "the cancelled job lingered $tookMillis ms waiting for an answer (read timeout 5000 ms)")
        assertEquals(0, handler.delivered.size)
        assertTrue(journalFiles().isEmpty())
    }

    @Test
    fun `a settlement the store refuses after the answer was journaled does not make the next run upload again`() {
        serve()
        val backing = TestUploaderStore()
        val store = RefusingStore(backing)
        val handler = ChaosHandler()
        startEngine(store, handler)
        store.failDeletes = 1

        val first: ListenableWorker.Result = runWorker()
        assertIs<ListenableWorker.Result.Retry>(first, "the outcome did not land: the job asks for a re-run")
        assertEquals(UploaderItemState.IN_FLIGHT, backing.find("item-1")?.state)

        val second: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(second)
        assertEquals(1, connections.get(), "the answer the first run journaled is replayed; the file is not uploaded again")
        assertNull(backing.find("item-1"))
    }

    @Test
    fun `a job stopped in the middle of the settlement does not make its re-run upload the file again`() {
        serve()
        val backing = TestUploaderStore()
        val store = YieldingStore(backing)
        lateinit var job: Deferred<ListenableWorker.Result>
        val stopOnce = AtomicInteger(0)
        val handler = ChaosHandler(onClassify = {
            // WorkManager stops the job while the engine is classifying the answer.
            if (stopOnce.getAndIncrement() == 0) job.cancel()
        })
        startEngine(store, handler)
        job = uploads.async(start = CoroutineStart.LAZY) { worker().build().doWork() }
        job.start()
        runCatching { runBlocking { withTimeout(20_000L) { job.await() } } }

        val rerun: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(rerun)
        assertEquals(1, connections.get(), "the re-run replays")
        assertEquals(1, handler.delivered.size)
        assertTrue(journalFiles().isEmpty())
    }

    private companion object {
        const val CREATED: String = "HTTP/1.1 201 Created\r\nLocation: /jobs/1\r\nContent-Length: 12\r\nConnection: close\r\n\r\n{\"job\":\"j1\"}"
    }
}
