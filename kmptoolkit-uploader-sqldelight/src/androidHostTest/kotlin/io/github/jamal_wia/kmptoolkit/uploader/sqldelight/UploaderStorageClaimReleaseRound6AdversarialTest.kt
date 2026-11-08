package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
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
 * Round 6 (re-sweep of round 5 on real SQLite): the release of an interrupted claim must give the row back as the
 * pass read it (attempts, error text, gate, lease). The close-during-delivery fuzz found in the same pass is the suspect
 * in adversarial/A1/suspects/r6-R6-05b-*.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageClaimReleaseRound6AdversarialTest {

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

    private fun engineOver(store: UploaderStore, handler: UploaderHandler<*>): UploaderEngine {
        val job = Job().also { jobs += it }
        return createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + job),
        ).also { engines += it }
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
    fun `a released pending claim gives the row back with its attempts error text and gate as read`() = realTest {
        val storage: UploaderStorage = open()
        val transport = Transport()
        val handler = SqlUploadHandler(transport)
        val seed: UploaderEngine = engineOver(storage.store, handler)
        val id: String = seed.enqueue(handler, "x")!!
        seed.close()
        assertEquals(true, storage.store.recordFailure(id, 2, 1L, "boom", UploaderItemState.PENDING, 0L, FailureDisposition.RETRY))
        val before: UploaderItem = assertNotNull(storage.store.getById(id))
        assertEquals(2, before.attempts)

        lateinit var closing: UploaderEngine
        val second: UploaderEngine = engineOver(HoppingClaimStore(storage.store) { closing.close() }, handler)
        closing = second
        second.start()
        val after: UploaderItem? = awaitRow(storage, id, "the claim was released") { it != null && it.state == UploaderItemState.PENDING && it.claim > before.claim }
        assertEquals(before.copy(claim = after!!.claim), after, "everything but the claim is as the pass read it")
        assertEquals(emptyList(), transport.launches.value, "the closed engine launched nothing")

        val third: UploaderEngine = engineOver(storage.store, handler)
        third.start()
        withTimeout(10.seconds) { transport.launches.first { it.isNotEmpty() } }
        assertEquals(listOf(false), transport.launches.value, "a plain pending row is a first-time hand-off")
    }

    @Test
    fun `a released re-hand of an expired lease stays a re-hand for the next engine`() = realTest {
        val storage: UploaderStorage = open()
        val transport = Transport()
        val handler = SqlUploadHandler(transport)
        val seed: UploaderEngine = engineOver(storage.store, handler)
        val id: String = seed.enqueue(handler, "x")!!
        seed.close()
        assertEquals(true, storage.store.markInFlight(id, 1_000L, UploaderItemState.PENDING, 0L))
        val before: UploaderItem = assertNotNull(storage.store.getById(id))

        lateinit var closing: UploaderEngine
        val second: UploaderEngine = engineOver(HoppingClaimStore(storage.store) { closing.close() }, handler)
        closing = second
        second.start()
        val after: UploaderItem? = awaitRow(storage, id, "the claim was released") { it != null && it.claim > before.claim }
        assertEquals(before.copy(claim = after!!.claim), after, "still in flight under the old, expired lease")
        assertEquals(emptyList(), transport.launches.value)

        val third: UploaderEngine = engineOver(storage.store, handler)
        third.start()
        withTimeout(10.seconds) { transport.launches.first { it.isNotEmpty() } }
        assertEquals(listOf(true), transport.launches.value, "the next engine knows an earlier executor may have run")
    }
}
