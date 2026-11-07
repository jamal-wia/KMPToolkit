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

    private companion object {
        const val CREATED: String = "HTTP/1.1 201 Created\r\nLocation: /jobs/1\r\nContent-Length: 12\r\nConnection: close\r\n\r\n{\"job\":\"j1\"}"
    }
}
