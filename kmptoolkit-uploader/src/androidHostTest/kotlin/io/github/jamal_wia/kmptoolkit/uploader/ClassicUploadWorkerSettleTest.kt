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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.runner.RunWith

/**
 * The classic [UploaderUploadWorker] after the server answered: the app's `classify` and the settle write
 * must never turn an upload that happened into an exception that loses the outcome — a lost outcome leaves
 * the row in flight, and the whole file uploads again once its lease runs out.
 */
@RunWith(AndroidJUnit4::class)
class ClassicUploadWorkerSettleTest {

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

    /** A store whose reads yield first (so a cancellation lands on them, as a real store's I/O would) and whose first delete fails. */
    private class FlakyStore(private val delegate: TestUploaderStore, var failNextDelete: Boolean = false) :
        UploaderStore by delegate {
        override suspend fun getById(id: String): UploaderItem? {
            yield()
            return delegate.getById(id)
        }

        override suspend fun deleteById(id: String) {
            if (failNextDelete) {
                failNextDelete = false
                throw IllegalStateException("disk full")
            }
            delegate.deleteById(id)
        }
    }

    private suspend fun inFlight(store: FlakyStore) {
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 60_000L) })
        val started: UploaderEngine = testEngine(store, listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain()
    }

    private fun worker(): androidx.work.testing.TestListenableWorkerBuilder<UploaderUploadWorker> =
        TestListenableWorkerBuilder<UploaderUploadWorker>(context).setInputData(
            UploadRequest(url = "http://127.0.0.1:${server.localPort}/upload", fields = emptyList()).toWorkData(
                itemId = "item-1",
                engineWaitMillis = 200L,
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 5_000,
            ),
        )

    @Test
    fun `a classify that throws settles the attempt as failed instead of escaping the job`() = runBlocking {
        serveCreated()
        val backing = TestUploaderStore()
        inFlight(FlakyStore(backing))
        UploadClassifierRegistry.register { throw IllegalStateException("unexpected status") }

        val result: ListenableWorker.Result = worker().build().doWork()

        assertIs<ListenableWorker.Result.Success>(result)
        assertEquals(1, connections.get())
        val row: UploaderItem = checkNotNull(backing.find("item-1"))
        assertEquals(UploaderItemState.PENDING, row.state, "the attempt is spent and queued for a retry, not left in flight")
        assertEquals(1, row.attempts)
    }

    @Test
    fun `a settle the store refuses is reported as a retry, not thrown`() = runBlocking {
        serveCreated()
        val backing = TestUploaderStore()
        val store = FlakyStore(backing, failNextDelete = true)
        inFlight(store)
        UploadClassifierRegistry.register { SettleResult.Delivered }

        val result: ListenableWorker.Result = worker().build().doWork()

        assertIs<ListenableWorker.Result.Retry>(result)
        assertEquals(UploaderItemState.IN_FLIGHT, backing.find("item-1")?.state)
    }

    @Test
    fun `a job stopped between the upload and the settle still records the outcome`() = runBlocking {
        serveCreated()
        val backing = TestUploaderStore()
        inFlight(FlakyStore(backing))
        lateinit var job: kotlinx.coroutines.Deferred<ListenableWorker.Result>
        // WorkManager stops the job right after the server answered, before the outcome is written.
        UploadClassifierRegistry.register {
            job.cancel(CancellationException("stopped by WorkManager"))
            SettleResult.Delivered
        }
        job = async(Dispatchers.Default, start = CoroutineStart.LAZY) { worker().build().doWork() }
        job.start()

        runCatching { job.await() }

        assertNull(backing.find("item-1"), "the delivered file's row is gone: the next run has nothing to upload")
        assertEquals(1, connections.get())
    }
}
