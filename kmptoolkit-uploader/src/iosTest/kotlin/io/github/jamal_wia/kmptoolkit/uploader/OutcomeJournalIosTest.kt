package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID

/**
 * The outcome journal as the iOS background transport uses it: files in a backup-excluded directory, a
 * hand-off or relaunch replaying a journaled outcome INSTEAD of uploading, the creation-time sweep, and
 * `cancel` deleting the entry.
 */
@OptIn(ExperimentalForeignApi::class)
class OutcomeJournalIosTest {

    private val dir: String = NSTemporaryDirectory() + "/kmptoolkit-journal-test-" + NSUUID().UUIDString() + "/outcomes"
    private val storage = IosOutcomeStorage(dir)
    private var engine: UploaderEngine? = null
    private var engineScope: CoroutineScope? = null

    @BeforeTest
    fun clear() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
        engine?.close()
        engineScope?.cancel()
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }

    private fun journal(clock: UploaderClock = UploaderClock.System) = OutcomeJournal(storage, clock)

    private fun sent() = UploadResponse(201, mapOf("Location" to "/jobs/9"), """{"job":"j9"}""".encodeToByteArray(), false)

    private fun transport(prefix: String = "kmptoolkit.test.journal.${NSUUID().UUIDString()}.") =
        BackgroundSessionUploadTransport(prefix, BackgroundUploadConfig(sessionIdentifierPrefix = prefix), NoopLogger, journal())

    /** A handler whose request can reach no server: a transport that does upload shows up in [classified] as a failure. */
    private suspend fun startEngine(handler: Recorder): UploaderEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineScope = scope
        val started: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scope, idGenerator = SequentialIds("jrn")::next)
        engine = started
        UploaderEngineRegistry.register(started)
        started.enqueue(handler, "payload")
        started.drain() // jrn-1 in flight
        return started
    }

    private suspend fun until(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(15_000L) { while (!condition()) delay(20L) }
        }
    }

    private val noServer = UploadRequest(url = "", fields = emptyList())

    // --- Files ------------------------------------------------------------------------------------------

    @Test
    fun `write read list overwrite and delete`() {
        assertTrue(storage.write("b", byteArrayOf(1, 2)))
        assertTrue(storage.write("a", ByteArray(0)))
        assertTrue(storage.write("b", byteArrayOf(9)))

        assertContentEquals(byteArrayOf(9), storage.read("b"))
        assertContentEquals(ByteArray(0), storage.read("a"))
        assertEquals(listOf("a", "b"), storage.itemIds())

        storage.delete("a")
        storage.delete("missing")
        assertNull(storage.read("a"))
        assertEquals(listOf("b"), storage.itemIds())
    }

    @Test
    fun `an entry round-trips through the journal on disk`() {
        assertTrue(journal().record("jrn-1", sent()))

        assertEquals(sent(), assertIs<JournalEntry.Valid>(journal().inspect("jrn-1")).response)
    }

    @Test
    fun `the directory is excluded from backup`() {
        storage.write("a", byteArrayOf(1))

        val values: Map<Any?, *> = NSURL.fileURLWithPath(dir).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
            ?: emptyMap<Any?, Any?>()
        assertEquals(true, values[NSURLIsExcludedFromBackupKey])
    }

    @Test
    fun `a journal that cannot be written reports it`() {
        val blocked = IosOutcomeStorage("/nonexistent-root-for-test/outcomes")

        assertFalse(OutcomeJournal(blocked).record("jrn-1", sent()))
    }

    // --- Transport --------------------------------------------------------------------------------------

    @Test
    fun `a hand-off with a journaled outcome replays it and uploads nothing`() = runTest {
        val handler = Recorder()
        startEngine(handler)
        // The creation-time sweep finds nothing yet; waiting it out keeps it from racing the hand-off, which
        // is what this test is about.
        val transport: BackgroundSessionUploadTransport = transport()
        withContext(Dispatchers.Default) { delay(500L) }
        journal().record("jrn-1", sent())

        transport.launch("jrn-1", isRehandOff = true, request = noServer)
        until { handler.delivered.isNotEmpty() }

        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered)
        assertTrue(handler.classified.none { it is UploadResult.TransportFailure }, "an upload attempt would have failed here: ${handler.classified}")
        assertEquals(JournalEntry.Absent, journal().inspect("jrn-1"))
    }

    @Test
    fun `a relaunch with a journaled outcome replays it and completes the system handler`() = runTest {
        val handler = Recorder()
        startEngine(handler)
        val transport: BackgroundSessionUploadTransport = transport()
        withContext(Dispatchers.Default) { delay(500L) }
        journal().record("jrn-1", sent())
        var done = 0

        transport.handleRelaunchEvents("jrn-1") { done++ }
        until { done > 0 && handler.delivered.isNotEmpty() }

        assertEquals(1, done)
        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered)
        assertEquals(JournalEntry.Absent, journal().inspect("jrn-1"))
    }

    @Test
    fun `creating the transport replays an orphaned outcome and deletes an expired one`() = runTest {
        val handler = Recorder()
        startEngine(handler)
        journal().record("jrn-1", sent())
        journal(clock = { currentEpochMillis() - 8.days.inWholeMilliseconds }).record("stale", sent())

        transport()
        until { handler.delivered.isNotEmpty() && storage.itemIds().isEmpty() }

        assertEquals(listOf<UploadResponse?>(sent()), handler.delivered)
    }

    @Test
    fun `an entry for an item that is gone settles nothing and is deleted`() = runTest {
        val handler = Recorder()
        startEngine(handler)
        journal().record("unknown-item", sent())

        transport()
        until { storage.itemIds().isEmpty() }

        assertTrue(handler.delivered.isEmpty())
    }

    @Test
    fun `an entry of another format version is ignored and deleted`() = runTest {
        val bytes: ByteArray = OutcomeCodec.encode(sent(), currentEpochMillis()).also { it[3] = 99 }
        storage.write("jrn-1", bytes)

        assertEquals(JournalEntry.Discarded, journal().inspect("jrn-1"))
        assertNull(storage.read("jrn-1"))
    }

    @Test
    fun `cancel deletes the item's journaled outcome and no other`() {
        journal().record("jrn-1", sent())
        journal().record("jrn-2", sent())

        transport().cancel("jrn-1")

        assertNull(storage.read("jrn-1"))
        assertTrue(storage.read("jrn-2") != null)
    }

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class Recorder : UploadHandler<String>(NoopTransport()) {
        override val type: String = "journal"
        val delivered = mutableListOf<UploadResponse?>()
        val classified = mutableListOf<UploadResult>()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "", fields = emptyList()))

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            classified += result
            return super.classify(payload, result, response)
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }
}
