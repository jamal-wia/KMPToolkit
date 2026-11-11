package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderConfig
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.runner.RunWith

/**
 * Round 5 (lifecycle chaos): an engine closed at a random moment during a burst of upload hand-offs, against the
 * real SQLite storage, whose calls hop to the database thread and report the close's cancellation when they return
 * even though the write committed. A claim written in that window must not leave its item in flight with no
 * upload behind it: the engine that replaces the closed one has to start everything still owed.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageCloseClaimRound5AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val engines: MutableList<UploaderEngine> = mutableListOf()
    private val jobs: MutableList<Job> = mutableListOf()
    private val files: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        engines.forEach { it.close() }
        jobs.forEach { it.cancel() }
        storages.forEach { runCatching { it.close() } }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private class Transport : UploadTransport {
        override val leaseMillis: Long = 600_000L
        val events: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override fun launch(itemId: String, request: UploadRequest) {
            events.update { it + "launch:$itemId" }
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events.update { it + "launch:$itemId" }
        }

        override fun cancel(itemId: String) {
            events.update { it + "cancel:$itemId" }
        }
    }

    private object QuickPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 50L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 20L
    }

    private class SqlUploadHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "adv.r5.upload"
        override val retryPolicy: RetryPolicy = QuickPolicy
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
    }

    private fun open(): UploaderStorage {
        val name = "adv.a1r5.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    private fun engineOver(
        storage: UploaderStorage,
        handler: UploadHandler<*>,
        direct: Boolean = false,
        heartbeat: Duration = UploaderConfig.DEFAULT_HEARTBEAT_INTERVAL,
    ): UploaderEngine {
        val job = Job().also { jobs += it }
        return createUploaderEngine(
            store = storage.store,
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + job),
            transactionRunner = if (direct) io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner.Direct else storage.transactionRunner,
            config = UploaderConfig(heartbeatInterval = heartbeat),
        ).also { engines += it }
    }

    private companion object {
        /** Far more than a 100 ms heartbeat needs; the bound only stops a genuinely stranded item from hanging the test. */
        val WAKE_BOUND: Duration = 10.seconds
    }

    private fun realTest(seconds: Int = 90, body: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = (seconds + 10).seconds) { withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } } }

    @Test
    fun `an engine closed at a random moment during a burst of hand-offs leaves nothing owed that its replacement does not start`() = realTest(150) {
        var claimedWithoutLaunch = 0
        repeat(40) { iteration ->
            val storage: UploaderStorage = open()
            val transport = Transport()
            val handler = SqlUploadHandler(transport)
            val first: UploaderEngine = engineOver(storage, handler)
            val ids: List<String> = List(100) { first.enqueue(handler, "u$it")!! }
            first.start()
            delay(Random.nextLong(0, 25))
            first.close()
            // The replacement's heartbeat is the only wake-up it can get for a claim the closed engine releases after
            // the replacement's first pass already skipped the row as in flight under an unexpired lease (an engine
            // cannot signal another; see UploaderConfig.heartbeatInterval, the backstop for exactly a lost wake-up).
            // So the check waits for that bounded wake-up, with a short heartbeat, instead of a fixed second that
            // the contract never promised: what must hold is unchanged, that every owed item gets started.
            val second: UploaderEngine = engineOver(storage, handler, heartbeat = 100.milliseconds)
            second.start()
            fun launchedIds(): Set<String> =
                transport.events.value.filter { it.startsWith("launch:") }.map { it.removePrefix("launch:") }.toSet()
            withTimeoutOrNull(WAKE_BOUND) {
                while (ids.any { it !in launchedIds() }) delay(20)
            }
            val launched: Set<String> = launchedIds()
            val stranded: List<String> = ids.filter { it !in launched }
            claimedWithoutLaunch += stranded.size
            assertEquals(emptyList(), stranded, "iteration $iteration: ${stranded.size} owed items were never started by either engine")
            second.close()
        }
    }
}
