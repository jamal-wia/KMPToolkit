package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 6 of the breaker for unit G, Q1 (uploader): the public engine (`createUploaderEngine`, here through the
 * module's own `testEngine` fixture that only supplies a store and a scope) with the public WorkManager handler transport
 * and a real loopback server, uploading a real source file, with the outcome journal (the only user of kmptoolkit-files)
 * on the Robolectric `noBackupFilesDir`. The seam that does not exist: no public parameter takes a FileAccess or a journal
 * directory handle, so the journal's file layer cannot be faulted from outside; the disk-full path is modelled by making
 * the journal's `outcomes` directory refuse new entries (a write that fails, which docs 05 says "leaves the behaviour of 3.0"),
 * and the vanished destination by deleting the whole journal tree while the request is in flight.
 */
@RunWith(AndroidJUnit4::class)
class FullEngineOverRealFilesR6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server: ServerSocket = ServerSocket().apply {
        receiveBufferSize = 4 * 1024
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }
    private val connections: AtomicInteger = AtomicInteger(0)
    private val sequence: AtomicInteger = AtomicInteger(0)
    private val received: AtomicLong = AtomicLong(0)
    private val held: MutableList<Socket> = mutableListOf()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempFiles: MutableList<File> = mutableListOf()
    private val restorers: MutableList<AutoCloseable> = mutableListOf()
    private val config: UploadTransportConfig = UploadTransportConfig(uniqueWorkNamePrefix = "r6_", workTag = "r6", requiresNetwork = false)
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
        restorers.asReversed().forEach { runCatching { it.close() } }
        synchronized(held) { held.forEach { runCatching { it.close() } } }
        server.close()
        engine?.close()
        scope.cancel()
        tempFiles.forEach { it.delete() }
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private inner class Handler(transport: UploadTransport, private val fields: List<UploadField>) : UploadHandler<String>(transport) {
        override val type: String = "r6"
        val delivered: MutableList<UploadResponse?> = java.util.Collections.synchronizedList(mutableListOf())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = fields))

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }

    /** A server that reads one whole multipart request, runs [beforeAnswer], and answers 201. */
    private fun answeringServer(beforeAnswer: () -> Unit = {}) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        it.soTimeout = 20_000
                        val input = it.getInputStream()
                        val buffer = ByteArray(8192)
                        var tail = ""
                        while (!tail.endsWith("0\r\n\r\n")) {
                            val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                            if (read < 0) return@thread
                            received.addAndGet(read.toLong())
                            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                        }
                        beforeAnswer()
                        it.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    }
                }
            }
        }
    }

    private fun source(bytes: Long): File = File(context.cacheDir, "r6-src-${sequence.incrementAndGet()}.bin").also {
        RandomAccessFile(it, "rw").use { file -> file.setLength(bytes) }
        tempFiles += it
    }

    private fun start(fields: List<UploadField>): Pair<UploadTransport, Handler> {
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = Handler(transport, fields)
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking {
            withTimeout(15_000L) {
                started.enqueue(handler, "payload")
                started.drain()
            }
        }
        return transport to handler
    }

    private fun states(id: String): List<WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(config.resolveUniqueWorkName(context.packageName, id)).get().map { it.state }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        runBlocking {
            withTimeout(20_000L) { while (!condition()) delay(20L) }
        }.also { assertTrue(condition(), what) }
    }

    /** Every file under the journal tree that is a temporary file of a write, plus any file left once the item settled. */
    private fun journalFiles(): List<String> =
        journalRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(journalRoot).path }.sorted().toList()

    @Test
    fun `happy path uploads the real file once leaves the source untouched and the journal empty`() {
        answeringServer()
        val file: File = source(300_000)
        val before: Long = file.length()
        val (_, handler) = start(listOf(UploadField.File("f", "s.bin", "application/octet-stream", file.path)))
        awaitTrue("delivered") { handler.delivered.isNotEmpty() }
        awaitTrue("job finished") { states("item-1").all { it == WorkInfo.State.SUCCEEDED } }
        assertEquals(1, handler.delivered.size)
        assertEquals(1, connections.get())
        assertTrue(received.get() >= before, "the server received ${received.get()} bytes of a $before byte file")
        assertTrue(file.exists() && file.length() == before, "the source file was touched")
        awaitTrue("journal settled") { journalFiles().isEmpty() }
    }

    @Test
    fun `a cancelled upload ends the job leaves the source file and the journal without litter`() {
        val file: File = source(64L * 1024 * 1024)
        val accepted = CountDownLatch(1)
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            synchronized(held) { held += socket }
            accepted.countDown()
        }
        val (transport, handler) = start(listOf(UploadField.File("f", "s.bin", "application/octet-stream", file.path)))
        assertTrue(accepted.await(10, TimeUnit.SECONDS), "the job reached the server")
        transport.cancel("item-1")
        awaitTrue("cancelled") { states("item-1").all { it == WorkInfo.State.CANCELLED } }
        assertEquals(0, handler.delivered.size)
        assertTrue(file.exists() && file.length() == 64L * 1024 * 1024, "cancel must never touch the source file")
        assertEquals(emptyList(), journalFiles(), "journal litter after a cancel")
    }

    @Test
    fun `a journal that refuses new entries does not stop the delivery and leaves no temporary file`() {
        answeringServer()
        val outcomes = File(journalRoot, "outcomes").also { it.mkdirs() }
        File(journalRoot, "outcomes-partial").mkdirs()
        restorers += TestFs.makeUnwritable(outcomes)
        val file: File = source(50_000)
        val (_, handler) = start(listOf(UploadField.File("f", "s.bin", "application/octet-stream", file.path)))
        awaitTrue("delivered") { handler.delivered.isNotEmpty() }
        assertEquals(1, handler.delivered.size)
        assertEquals(1, connections.get(), "a failed journal write must not cause a second upload")
        assertTrue(file.exists())
        assertEquals(emptyList(), journalFiles().filter { it.endsWith(".tmp") || it.contains("partial") }, "temporary files of the refused write")
    }

    @Test
    fun `a journal tree that vanishes while the request is in flight still delivers once and leaves no litter`() {
        answeringServer { journalRoot.deleteRecursively() }
        val file: File = source(50_000)
        val (_, handler) = start(listOf(UploadField.File("f", "s.bin", "application/octet-stream", file.path)))
        awaitTrue("delivered") { handler.delivered.isNotEmpty() }
        awaitTrue("job finished") { states("item-1").all { it == WorkInfo.State.SUCCEEDED } }
        assertEquals(1, handler.delivered.size)
        assertEquals(1, connections.get())
        awaitTrue("journal settled") { journalFiles().isEmpty() }
    }

    @Test
    fun `a source file that vanished before the upload is a typed failure without a server contact`() {
        answeringServer()
        val file: File = source(10_000)
        assertTrue(file.delete())
        val (_, handler) = start(listOf(UploadField.File("f", "s.bin", "application/octet-stream", file.path)))
        awaitTrue("the job settled into a non running state") { states("item-1").none { it == WorkInfo.State.RUNNING } }
        assertEquals(0, connections.get(), "a missing source must not reach the server")
        assertFalse(handler.delivered.isNotEmpty(), "a missing source was reported as delivered")
        assertEquals(emptyList(), journalFiles(), "journal litter after a transport failure")
    }
}
