package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The outcome journal as the Android handler transport uses it: the worker journals a completed answer
 * before settling, replays a journaled one INSTEAD of uploading, and the transport's orphan sweep and
 * `cancel` clean up what no worker will collect.
 */
@RunWith(AndroidJUnit4::class)
class UploadOutcomeJournalWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket(0)
    private val connections = AtomicInteger(0)
    private var engine: UploaderEngine? = null
    private var engineScope: CoroutineScope? = null
    private val journalDir: File get() = File(context.noBackupFilesDir, "kmptoolkit-uploader")
    private val config = UploadTransportConfig(uniqueWorkNamePrefix = "journal_", workTag = "journal")

    private fun journal(clock: UploaderClock = UploaderClock.System): OutcomeJournal =
        OutcomeJournal(AndroidOutcomeStorage.forContext(context), clock)

    private fun entryFile(itemId: String) = File(journalDir, "outcomes/$itemId")

    @BeforeTest
    fun clean() {
        journalDir.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        engine?.close()
        engineScope?.cancel()
        journalDir.deleteRecursively()
    }

    /** Answers every connection with [raw], counting them. */
    private fun serve(raw: String) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                connections.incrementAndGet()
                socket.use {
                    val input = it.getInputStream()
                    val seen = StringBuilder()
                    val buffer = ByteArray(4 * 1024)
                    while (!seen.endsWith("0\r\n\r\n")) {
                        val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                        if (read < 0) break
                        seen.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                    }
                    it.getOutputStream().write(raw.encodeToByteArray())
                    it.getOutputStream().flush()
                }
            }
        }
    }

    private val created = "HTTP/1.1 201 Created\r\nLocation: /jobs/1\r\nContent-Length: 12\r\nConnection: close\r\n\r\n{\"job\":\"j1\"}"

    private fun handler(bodyLimit: Int = UploadHandler.DEFAULT_MAX_RESPONSE_BODY_BYTES, onPrepared: (Int) -> Unit = {}) =
        JournalHandler(server.localPort, bodyLimit, onPrepared)

    private suspend fun kotlinx.coroutines.test.TestScope.startEngine(handler: JournalHandler): UploaderEngine {
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), backgroundScope)
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain() // hands off to the recording transport, leaving item-1 in flight
        return started
    }

    private suspend fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<UploadHandlerWorker>(context)
            .setInputData(
                workDataOf(
                    UploadHandlerWorker.ITEM_ID_KEY to "item-1",
                    UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to 50L,
                ),
            )
            .build()
            .doWork()

    private fun sent() = UploadResponse(201, mapOf("Location" to "/jobs/9"), """{"job":"j9"}""".encodeToByteArray(), false)

    // --- Replay instead of upload -----------------------------------------------------------------------

    @Test
    fun `a journaled outcome is replayed instead of uploading, never alongside`() = runTest {
        serve(created)
        val handler: JournalHandler = handler()
        startEngine(handler)
        journal().record("item-1", sent())

        val result: ListenableWorker.Result = runWorker()

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(0, connections.get(), "the server was never contacted")
        assertEquals(1, handler.prepared.get(), "only the engine's hand-off prepared a request; the worker prepared none")
        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered, "the journaled response reaches onDelivered")
        assertTrue(!entryFile("item-1").exists(), "the entry is gone once settled")
    }

    @Test
    fun `an outcome that cannot reach the engine stays journaled and the next run replays it without uploading`() = runTest {
        serve(created)
        var started: UploaderEngine? = null
        val handler: JournalHandler = handler(onPrepared = { count -> if (count == 2) started?.let(UploaderEngineRegistry::unregister) })
        val live: UploaderEngine = startEngine(handler)
        started = live

        // The engine is gone between preparing the attempt and settling it, as in a process that is dying.
        assertIs<ListenableWorker.Result.Retry>(runWorker())

        assertEquals(1, connections.get())
        val kept: JournalEntry.Valid = assertIs(journal().inspect("item-1"))
        assertEquals(201, kept.response.statusCode)
        assertEquals("""{"job":"j1"}""", kept.response.body().decodeToString())
        assertTrue(handler.delivered.isEmpty())

        UploaderEngineRegistry.register(live)
        assertIs<ListenableWorker.Result.Success>(runWorker())

        assertEquals(1, connections.get(), "the second run replayed; it did not upload again")
        assertEquals(2, handler.prepared.get(), "hand-off and the first run prepared; the replay did not")
        assertEquals("""{"job":"j1"}""", handler.delivered.single()?.body()?.decodeToString())
        assertTrue(!entryFile("item-1").exists())
    }

    @Test
    fun `the journal keeps no more body than the handler asked for`() = runTest {
        serve(created)
        val handler: JournalHandler = handler(bodyLimit = 0)
        val live: UploaderEngine = startEngine(handler)
        // The engine goes away once the worker has asked for the limit, so the entry stays on disk.
        handler.onLimitRead = { UploaderEngineRegistry.unregister(live) }

        assertIs<ListenableWorker.Result.Retry>(runWorker())

        val kept: JournalEntry.Valid = assertIs(journal().inspect("item-1"))
        assertEquals(201, kept.response.statusCode)
        assertEquals(0, kept.response.body().size, "a handler that wants no body has none on disk")
        assertTrue(kept.response.isBodyTruncated)
    }

    @Test
    fun `a delivered upload leaves nothing on disk`() = runTest {
        serve(created)
        val handler: JournalHandler = handler()
        startEngine(handler)

        assertIs<ListenableWorker.Result.Success>(runWorker())

        assertEquals(1, handler.delivered.size)
        assertEquals(emptyList(), File(journalDir, "outcomes").list()?.toList().orEmpty())
    }

    @Test
    fun `an entry for an item that settled or is gone is discarded and nothing is delivered`() = runTest {
        val handler: JournalHandler = handler()
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), backgroundScope)
        engine = started
        UploaderEngineRegistry.register(started)
        journal().record("item-1", sent())

        assertIs<ListenableWorker.Result.Success>(runWorker())

        assertTrue(handler.delivered.isEmpty())
        assertEquals(0, connections.get())
        assertTrue(!entryFile("item-1").exists())
    }

    @Test
    fun `an entry of another format version is ignored and deleted and the upload proceeds`() = runTest {
        serve(created)
        val handler: JournalHandler = handler()
        startEngine(handler)
        val bytes: ByteArray = OutcomeCodec.encode(sent(), System.currentTimeMillis()).also { it[3] = 99 }
        entryFile("item-1").also { it.parentFile?.mkdirs() }.writeBytes(bytes)

        assertIs<ListenableWorker.Result.Success>(runWorker())

        assertEquals(1, connections.get(), "an unusable entry replaces nothing")
        assertEquals("""{"job":"j1"}""", handler.delivered.single()?.body()?.decodeToString(), "the live answer, not the old entry")
        assertTrue(!entryFile("item-1").exists())
    }

    @Test
    fun `an expired entry is not replayed and the upload proceeds`() = runTest {
        serve(created)
        val handler: JournalHandler = handler()
        startEngine(handler)
        journal(clock = { System.currentTimeMillis() - 8.days.inWholeMilliseconds }).record("item-1", sent())

        assertIs<ListenableWorker.Result.Success>(runWorker())

        assertEquals(1, connections.get())
        assertEquals("""{"job":"j1"}""", handler.delivered.single()?.body()?.decodeToString())
    }

    @Test
    fun `a journal that cannot be written leaves today's behaviour`() = runTest {
        serve(created)
        val handler: JournalHandler = handler()
        startEngine(handler)
        journalDir.parentFile?.mkdirs()
        journalDir.writeText("a file where the journal directory would go")

        assertIs<ListenableWorker.Result.Success>(runWorker())

        assertEquals(1, connections.get())
        assertEquals("""{"job":"j1"}""", handler.delivered.single()?.body()?.decodeToString())
    }

    // --- Transport: orphan sweep and cancel -------------------------------------------------------------

    @Test
    fun `cancel deletes the item's journaled outcome and no other`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        journal().record("item-1", sent())
        journal().record("item-2", sent())

        transport.cancel("item-1")

        assertTrue(!entryFile("item-1").exists())
        assertTrue(entryFile("item-2").exists())
    }

    @Test
    fun `creating the transport replays an orphaned outcome and deletes an expired one`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val handler: JournalHandler = handler()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineScope = scope
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking {
            started.enqueue(handler, "payload")
            started.drain()
        }
        journal().record("item-1", sent())
        journal(clock = { System.currentTimeMillis() - 8.days.inWholeMilliseconds }).record("stale", sent())

        createWorkManagerUploadHandlerTransport(context, config)

        runBlocking {
            withTimeout(10_000L) {
                while (handler.delivered.isEmpty() || entryFile("stale").exists() || entryFile("item-1").exists()) delay(20L)
            }
        }
        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered)
        assertEquals(0, connections.get())
    }

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class JournalHandler(private val port: Int, private val bodyLimit: Int, private val onPrepared: (Int) -> Unit) :
        UploadHandler<String>(NoopTransport()) {
        var onLimitRead: () -> Unit = {}
        override val maxResponseBodyBytes: Int get() = bodyLimit.also { onLimitRead() }
        override val type: String = "journal"
        val prepared = AtomicInteger(0)
        val delivered = java.util.Collections.synchronizedList(mutableListOf<UploadResponse?>())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            onPrepared(prepared.incrementAndGet())
            return UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:$port/upload", fields = emptyList()))
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }
}
