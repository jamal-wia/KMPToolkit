package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith

/**
 * Round 6 R6-05b: pins P2 (a delivered Success is always recorded) on SQLite. An engine closed at a random moment
 * while delivering must never make its replacement repeat a delivered effect. The release tests of this round are in
 * [UploaderStorageClaimReleaseRound6AdversarialTest].
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageCloseDeliveryRound6AdversarialTest {

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
        val launches: MutableStateFlow<List<Boolean>> = MutableStateFlow(emptyList())
        override fun launch(itemId: String, request: UploadRequest) {
            launches.update { it + false }
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            launches.update { it + isRehandOff }
        }

        override fun cancel(itemId: String) = Unit
    }

    private object Quick : RetryPolicy {
        override val maxDelayMillis: Long = 50L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 20L
    }

    private class SqlUploadHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "adv.r6.upload"
        override val retryPolicy: RetryPolicy = Quick
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
    }

    private class CountingHandler : UploaderHandler<String> {
        override val type: String = "adv.r6.plain"
        override val retryPolicy: RetryPolicy = Quick
        val runs: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            runs.update { it + (payload to ((it[payload] ?: 0) + 1)) }
            return AttemptResult.Success
        }
    }

    /** The claim commits, the caller's cancellation is reported on return: what a dispatcher hop does. */
    private class HoppingClaimStore(private val inner: UploaderStore, private val onClaimed: () -> Unit) : UploaderStore by inner {
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            val claimed: Boolean = inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
            if (claimed) onClaimed()
            yield()
            return claimed
        }
    }

    private fun open(): UploaderStorage {
        val name = "adv.a1r6.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    private val ownerJobs: MutableMap<UploaderEngine, Job> = mutableMapOf()

    /** Waits until every coroutine of a closed engine has ended, so no attempt of it overlaps its replacement. */
    private suspend fun quiesce(closed: UploaderEngine) {
        withTimeout(10.seconds) { ownerJobs.getValue(closed).children.toList().forEach { it.join() } }
    }

    private fun engineOver(store: UploaderStore, handler: UploaderHandler<*>): UploaderEngine {
        val job = Job().also { jobs += it }
        return createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + job),
        ).also {
            engines += it
            ownerJobs[it] = job
        }
    }

    private fun realTest(seconds: Int = 60, body: suspend () -> Unit) =
        runTest(timeout = (seconds + 10).seconds) { withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } } }

    private suspend fun awaitRow(storage: UploaderStorage, id: String, what: String, ok: (UploaderItem?) -> Boolean): UploaderItem? {
        var row: UploaderItem? = storage.store.getById(id)
        var waited = 0
        while (!ok(row) && waited < 10_000) {
            delay(20)
            waited += 20
            row = storage.store.getById(id)
        }
        assertEquals(true, ok(row), "$what; row=$row")
        return row
    }

    @Test
    fun `an engine closed at a random moment while delivering never makes its replacement repeat a delivered effect`() = realTest(150) {
        repeat(40) { iteration ->
            val storage: UploaderStorage = open()
            val handler = CountingHandler()
            val first: UploaderEngine = engineOver(storage.store, handler)
            repeat(100) { first.enqueue(handler, "p$it") }
            first.start()
            delay(Random.nextLong(0, 15))
            first.close()
            quiesce(first)
            val second: UploaderEngine = engineOver(storage.store, handler)
            second.start()
            var waited = 0
            while (storage.store.getAllActive().isNotEmpty() && waited < 20_000) {
                delay(25)
                waited += 25
            }
            val repeated: Map<String, Int> = handler.runs.value.filterValues { it > 1 }
            assertEquals(emptyMap(), repeated, "iteration $iteration: delivered effects ran again; queue left=${storage.store.getAllActive().size}")
            assertEquals(100, handler.runs.value.size, "iteration $iteration: every effect ran")
            second.close()
        }
    }
}
