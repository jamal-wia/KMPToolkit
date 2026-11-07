package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
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
 * A logging sink that throws, handed to the Android transport. The transport's own work — enqueueing a job,
 * replaying an orphaned outcome — must not depend on the sink behaving, and a throw from it must not escape on a
 * thread nobody handles.
 */
@RunWith(AndroidJUnit4::class)
class UploadTransportLoggerAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = UploadTransportConfig(uniqueWorkNamePrefix = "logger_", workTag = "logger", requiresNetwork = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val journalRoot: File get() = File(context.noBackupFilesDir, "kmptoolkit-uploader")
    private var engine: UploaderEngine? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val uncaught = ConcurrentLinkedQueue<Throwable>()

    private class ThrowingSink : Logger {
        override val tag: String = "throwing"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            throw IllegalStateException("the crash reporter is not initialised")
        }
    }

    @BeforeTest
    fun setUp() {
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught += failure }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        engine?.close()
        scope.cancel()
        journalRoot.deleteRecursively()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @Test
    fun `a hand-off still enqueues its job when the logger throws`() {
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config, ThrowingSink())

        runCatching { transport.launch("item-1", UploadRequest(url = "http://127.0.0.1:1/", fields = emptyList())) }

        val jobs = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(config.resolveUniqueWorkName(context.packageName, "item-1")).get()
        assertEquals(1, jobs.size, "the job was not enqueued because a log line threw")
    }

    @Test
    fun `an orphaned outcome is still replayed and no exception escapes when the logger throws`() {
        val delivered = java.util.concurrent.atomic.AtomicInteger(0)
        val handler = object : UploadHandler<String>(object : UploadTransport {
            override val leaseMillis: Long = 60_000L
            override fun launch(itemId: String, request: UploadRequest) = Unit
            override fun cancel(itemId: String) = Unit
        }) {
            override val type: String = "logger"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
                UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:1/", fields = emptyList()))

            override suspend fun onDelivered(payload: String, response: UploadResponse?) {
                delivered.incrementAndGet()
            }
        }
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scope)
        engine = started
        UploaderEngineRegistry.register(started)
        runBlocking { withTimeout(10_000L) { started.enqueue(handler, "payload"); started.drain() } }
        OutcomeJournal(AndroidOutcomeStorage.forContext(context))
            .record("item-1", UploadResponse(201, emptyMap(), ByteArray(0), false))

        createWorkManagerUploadHandlerTransport(context, config, ThrowingSink())
        runBlocking { withTimeout(10_000L) { while (delivered.get() == 0 && uncaught.isEmpty()) delay(20L) } }
        runBlocking { delay(300L) }

        assertTrue(uncaught.isEmpty(), "a log line's exception escaped on a background thread: $uncaught")
        assertEquals(1, delivered.get(), "the orphaned outcome was not replayed")
    }
}
