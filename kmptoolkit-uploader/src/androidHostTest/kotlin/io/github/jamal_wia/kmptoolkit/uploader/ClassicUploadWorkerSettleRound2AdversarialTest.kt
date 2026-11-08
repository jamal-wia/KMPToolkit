package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/** The classic worker's settle after the server answered, under a store that is slower than the engine wait. */
@RunWith(AndroidJUnit4::class)
class ClassicUploadWorkerSettleRound2AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = ServerSocket(0)
    private val connections = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: UploaderEngine? = null

    @BeforeTest
    fun clean() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        engine?.close()
        scope.cancel()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private fun serveCreated() {
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
                    it.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    it.getOutputStream().flush()
                }
            }
        }
    }

    /** A store whose delete takes [delayMillis], as a commit on a busy database does. */
    private class SlowStore(private val delegate: TestUploaderStore, private val delayMillis: Long) : UploaderStore by delegate {
        override suspend fun deleteById(id: String) {
            delay(delayMillis)
            delegate.deleteById(id)
        }
    }

    @Test
    fun `a settle that takes longer than the engine wait is not abandoned and the file does not upload again`() {
        serveCreated()
        val backing = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 60_000L) })
        val started: UploaderEngine = testEngine(SlowStore(backing, 1_500L), listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload"); started.drain() } }
        UploadClassifierRegistry.register { SettleResult.Delivered }
        val result: ListenableWorker.Result = runBlocking {
            withTimeout(15_000L) {
                TestListenableWorkerBuilder<UploaderUploadWorker>(context).setInputData(
                    UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList()).toWorkData(
                        itemId = "item-1",
                        engineWaitMillis = 300L,
                        connectTimeoutMillis = 5_000,
                        readTimeoutMillis = 5_000,
                    ),
                ).build().doWork()
            }
        }
        assertIs<ListenableWorker.Result.Success>(result, "the engine was there at once; only the commit was slow")
        assertNull(backing.find("item-1"))
        assertEquals(1, connections.get())
    }
}
