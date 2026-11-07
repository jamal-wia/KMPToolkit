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
 * The handler transport driven through a real (test) `WorkManager`: the job is instantiated by WorkManager's own
 * factory, a duplicate hand-off is absorbed, and `cancel` reaches a job whose body write is blocked on a peer that
 * stopped reading. Waits are bounded polls on the real clock.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerUploadIntegrationAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket().apply {
        receiveBufferSize = 4 * 1024
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }
    private val connections = AtomicInteger(0)
    private val held = mutableListOf<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempFiles = mutableListOf<File>()
    private val config = UploadTransportConfig(uniqueWorkNamePrefix = "wm_it_", workTag = "wm_it", requiresNetwork = false)
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
        synchronized(held) { held.forEach { runCatching { it.close() } } }
        server.close()
        engine?.close()
        scope.cancel()
        tempFiles.forEach { it.delete() }
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private inner class WmHandler(transport: UploadTransport, private val fields: List<UploadField>) : UploadHandler<String>(transport) {
        override val type: String = "wm"
        val delivered = java.util.Collections.synchronizedList(mutableListOf<UploadResponse?>())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = fields))

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }

    private fun states(id: String): List<WorkInfo.State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(config.resolveUniqueWorkName(context.packageName, id)).get().map { it.state }

    private fun awaitTrue(condition: () -> Boolean) {
        runBlocking { withTimeout(15_000L) { while (!condition()) delay(20L) } }
    }

    @Test
    fun `a hand-off through WorkManager uploads once and twenty duplicate hand-offs of the live job add nothing`() {
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
                            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                        }
                        it.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    }
                }
            }
        }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = WmHandler(transport, emptyList())
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)

        runBlocking {
            withTimeout(15_000L) {
                started.enqueue(handler, "payload")
                started.drain()
            }
        }
        repeat(20) { transport.launch("item-1", UploadRequest(url = "http://127.0.0.1:1/", fields = emptyList())) }
        awaitTrue { handler.delivered.isNotEmpty() }
        runBlocking { delay(300L) }

        assertEquals(1, connections.get())
        assertEquals(1, handler.delivered.size)
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), states("item-1"))
    }

    @Test
    fun `cancelling through the transport ends a job stuck writing to a peer that stopped reading`() {
        val source: File = File(context.cacheDir, "wm-stall-${System.nanoTime()}.bin").also {
            RandomAccessFile(it, "rw").use { file -> file.setLength(64L * 1024 * 1024) }
            tempFiles += it
        }
        val accepted = CountDownLatch(1)
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            synchronized(held) { held += socket }
            accepted.countDown()
        }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = WmHandler(transport, listOf(UploadField.File("f", "s.bin", "application/octet-stream", source.path)))
        val store = TestUploaderStore()
        val started: UploaderEngine = testEngine(store, listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking {
            withTimeout(15_000L) {
                started.enqueue(handler, "payload")
                started.drain()
            }
        }
        assertTrue(accepted.await(10, TimeUnit.SECONDS), "the job reached the server")
        Thread.sleep(1_500)

        transport.cancel("item-1")

        awaitTrue { states("item-1").all { it == WorkInfo.State.CANCELLED } }
        awaitTrue {
            Thread.getAllStackTraces().filterValues { frames ->
                frames.any { it.methodName.contains("writeMultipartBody") || it.className.contains("performMultipart") }
            }.isEmpty()
        }
        assertEquals(0, handler.delivered.size)
    }

    private class ZeroBackoff : RetryPolicy {
        override val maxDelayMillis: Long = 1L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 0L
    }

    @Test
    fun `a retry with no backoff is re-handed to a finishing job and not left in flight until the lease runs out`() {
        val answers = AtomicInteger(0)
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
                            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                        }
                        val status = if (answers.incrementAndGet() <= 5) "503 Service Unavailable" else "201 Created"
                        it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    }
                }
            }
        }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = object : UploadHandler<String>(transport) {
            override val type: String = "wm-retry"
            override val retryPolicy: RetryPolicy = ZeroBackoff()
            val delivered = java.util.concurrent.atomic.AtomicInteger(0)
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
                UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList()))
            override suspend fun onDelivered(payload: String, response: UploadResponse?) {
                delivered.incrementAndGet()
            }
        }
        val started: UploaderEngine = createUploaderEngine(
            store = TestUploaderStore(),
            handlers = listOf(handler),
            scope = scope,
        )
        engine = started
        UploaderEngineRegistry.register(started)
        started.start()

        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload") } }
        val deadline: Long = System.nanoTime() + 12_000_000_000L
        while (handler.delivered.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)

        assertEquals(1, handler.delivered.get(), "delivered after ${answers.get()} answers; the retry was parked until its lease expired")
        assertEquals(6, connections.get())
    }

    @Test
    fun `cancelling while the server is about to answer leaves no journaled answer behind`() {
        val release = CountDownLatch(1)
        val requestRead = CountDownLatch(1)
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            synchronized(held) { held += socket }
            socket.soTimeout = 20_000
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            var tail = ""
            while (!tail.endsWith("0\r\n\r\n")) {
                val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (read < 0) return@thread
                tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
            }
            requestRead.countDown()
            release.await(15, TimeUnit.SECONDS)
            runCatching {
                socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                socket.getOutputStream().flush()
            }
        }
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)
        val handler = WmHandler(transport, emptyList())
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking { withTimeout(15_000L) { started.enqueue(handler, "payload"); started.drain() } }
        assertTrue(requestRead.await(10, TimeUnit.SECONDS))

        transport.cancel("item-1")
        release.countDown()
        awaitTrue { states("item-1").all { it == WorkInfo.State.CANCELLED } }
        runBlocking { delay(500L) }

        assertEquals(0, handler.delivered.size, "a cancelled item delivers nothing")
        assertTrue(journalRoot.walkTopDown().none { it.isFile }, "a cancelled item leaves no answer on disk")
    }
}
