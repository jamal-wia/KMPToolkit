package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * The interleavings between the drain, a settle, an enqueue and a withdrawal that no ordering of test
 * statements can produce on its own. Each test pins one write between another caller's read and its
 * write through a one-shot hook on [TestUploaderStore], then asserts what the contract promises for
 * that interleaving:
 *
 * - a settled item is never revived by a claim made from a stale read, and a give-up never lets one
 *   more attempt out (`UploaderEngine.settle`, `GiveUpPolicy`);
 * - an enqueue racing the drain's "queue is empty" never loses its platform wake (`WakeScheduler`);
 * - a withdrawal cancels the upload of every row it removed, including one enqueued while it ran
 *   (`Uploader.cancelByTag`, `ConflictPolicy.REPLACE`).
 */
class UploaderRaceTest {

    private val store = TestUploaderStore()
    private val clock = TestClock(millis = 1_000L)
    private val transport = RecordingTransport()
    private var engine: UploaderEngine? = null

    @AfterTest
    fun closeEngine() {
        engine?.close()
    }

    private fun startEngine(
        scope: CoroutineScope,
        handlers: List<UploaderHandler<*>>,
        wakeScheduler: TestWakeScheduler = TestWakeScheduler(),
        constraints: List<TestConstraint> = emptyList(),
    ): UploaderEngine = testEngine(
        store = store,
        handlers = handlers,
        scope = scope,
        clock = clock,
        constraintProviders = constraints,
        wakeScheduler = wakeScheduler,
    ).also {
        engine = it
        UploaderEngineRegistry.register(it)
    }

    // --- A claim from a stale read --------------------------------------------------------------

    @Test
    fun `a park that lands between the drain's read and its re-hand claim is not revived`() = runTest {
        val handler = RaceUploadHandler(transport)
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!
        engine.drain()
        clock.millis += RecordingTransport.LEASE + 1 // the first executor went silent

        // The drain has read the expired claim; the first executor reports a permanent failure
        // before the drain's new claim is written.
        store.beforeNextMarkInFlight = { engine.settle(id, SettleResult.Park("permanent 400")) }
        engine.drain()

        assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "a parked item must stay parked")
        assertEquals(listOf(id), transport.launches, "a known-permanent failure must not be uploaded again")
    }

    @Test
    fun `a failure that lands between the drain's read and its re-hand claim keeps its backoff`() = runTest {
        val handler = RaceUploadHandler(transport)
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!
        engine.drain()
        clock.millis += RecordingTransport.LEASE + 1

        store.beforeNextMarkInFlight = { engine.settle(id, SettleResult.Failed(IllegalStateException("503"))) }
        engine.drain()

        val item: UploaderItem? = store.find(id)
        assertEquals(UploaderItemState.PENDING, item?.state, "the failure returned the item to the queue")
        assertEquals(1, item?.attempts)
        assertTrue(item!!.nextRunAtEpochMillis > clock.millis, "the backoff gate the failure set must hold")
        assertEquals(listOf(id), transport.launches, "a gated item must not be re-handed past its gate")
    }

    @Test
    fun `a park reported during a plain handler's hand-off is not overwritten by its claim`() = runTest {
        lateinit var engine: UploaderEngine
        val handler = TestHandler(
            onExecute = { context, _ ->
                // A fast executor finishes before execute() has even returned its Detached.
                engine.settle(context.id, SettleResult.Park("rejected"))
                AttemptResult.Detached(leaseMillis = 30_000L)
            },
        )
        engine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!

        engine.drain()

        assertEquals(UploaderItemState.PARKED, store.find(id)?.state)
        assertEquals(0L, store.find(id)?.leaseUntilEpochMillis)
    }

    // --- A failure from a stale read --------------------------------------------------------------

    @Test
    fun `a park reported while a plain handler runs inline is not overwritten by its failure`() = runTest {
        lateinit var engine: UploaderEngine
        val handler = TestHandler(
            onExecute = { context, _ ->
                // A detached executor of an earlier attempt reports a permanent failure while this
                // attempt is still running; this attempt then fails transiently.
                engine.settle(context.id, SettleResult.Park("permanent 400"))
                AttemptResult.Retry(IllegalStateException("timeout"))
            },
        )
        engine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!

        engine.drain()

        val item: UploaderItem? = store.find(id)
        assertEquals(UploaderItemState.PARKED, item?.state, "a parked item must stay parked")
        assertEquals("permanent 400", item?.lastError, "the park's reason must survive")
        assertEquals(0, item?.attempts, "a failure from a stale read must not spend the budget")
    }

    @Test
    fun `a late park landing while an expired claim runs inline again is not overwritten by its failure`() = runTest {
        lateinit var engine: UploaderEngine
        var runs = 0
        val handler = TestHandler(
            onExecute = { context, _ ->
                runs++
                if (runs == 1) {
                    AttemptResult.Detached(leaseMillis = 10_000L)
                } else {
                    // The first executor, silent past its lease, finally reports — during the re-run.
                    engine.settle(context.id, SettleResult.Park("permanent 400"))
                    AttemptResult.Retry(IllegalStateException("timeout"))
                }
            },
        )
        engine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!
        engine.drain()
        clock.millis += 10_001L

        engine.drain()

        assertEquals(2, runs, "precondition: the expired claim was run again")
        assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "a parked item must stay parked")
    }

    @Test
    fun `a park reported while an upload is being prepared is not overwritten by its preparation failure`() = runTest {
        lateinit var engine: UploaderEngine
        val handler = RaceUploadHandler(transport).apply {
            duringPrepare = { context -> engine.settle(context.id, SettleResult.Park("permanent 400")) }
            failPrepareWith = IllegalStateException("token refresh failed")
        }
        engine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!

        engine.drain()

        assertEquals(UploaderItemState.PARKED, store.find(id)?.state, "a parked item must stay parked")
        assertTrue(transport.launches.isEmpty())
    }

    // --- Give-up is one write ---------------------------------------------------------------------

    @Test
    fun `a failed settle that exhausts a park budget lets no further attempt out`() = runTest {
        val handler = RaceUploadHandler(
            transport,
            retryPolicy = FixedRetryPolicy(delayMillis = 0L, giveUp = GiveUpPolicy.ParkAfterAttempts(maxAttempts = 1)),
        )
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!
        engine.drain()

        // A drain pass runs the moment the failure is recorded, before any give-up could follow it.
        store.afterNextRecordFailure = { engine.drain() }
        engine.settle(id, SettleResult.Failed(IllegalStateException("500")))

        val item: UploaderItem? = store.find(id)
        assertEquals(UploaderItemState.PARKED, item?.state)
        assertEquals(1, item?.attempts, "the parked row records the attempt that exhausted the budget")
        assertEquals(listOf(id), transport.launches, "maxAttempts = 1 allows exactly one upload")
    }

    @Test
    fun `a failed settle that exhausts a drop budget lets no further attempt out`() = runTest {
        val handler = RaceUploadHandler(
            transport,
            retryPolicy = FixedRetryPolicy(delayMillis = 0L, giveUp = GiveUpPolicy.DropAfterAttempts(maxAttempts = 1)),
        )
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!
        engine.drain()

        store.afterNextRecordFailure = { engine.drain() }
        engine.settle(id, SettleResult.Failed(IllegalStateException("500")))

        assertNull(store.find(id))
        assertEquals(listOf(id), transport.launches, "maxAttempts = 1 allows exactly one upload")
    }

    @Test
    fun `a drain-side failure that exhausts the budget parks in the same write`() = runTest {
        val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 0L, giveUp = GiveUpPolicy.ParkAfterAttempts(1)))
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler))
        val id: String = engine.enqueue(handler, "payload")!!

        val seenAfterTheWrite: MutableList<UploaderItemState?> = mutableListOf()
        store.afterNextRecordFailure = { seenAfterTheWrite += store.find(id)?.state }
        engine.drain()

        assertEquals(UploaderItemState.PARKED, store.find(id)?.state)
        assertEquals(1, handler.attempts.size)
        assertEquals(
            listOf<UploaderItemState?>(UploaderItemState.PARKED),
            seenAfterTheWrite,
            "the row must never be observable as PENDING once its budget is spent",
        )
    }

    // --- The wake --------------------------------------------------------------------------------

    @Test
    fun `an enqueue landing between the drain's empty read and its wake cancel keeps the wake armed`() = runTest {
        val offline = TestConstraint("net", satisfied = false)
        val handler = TestHandler(constraintKeys = setOf("net"))
        val wake = TestWakeScheduler()
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler), wake, listOf(offline))

        // The drain has just seen an empty queue; the enqueue persists, arms its wake, and only then
        // does the drain act on its stale read.
        store.afterNextGetAllActive = { engine.enqueue(handler, "payload") }
        engine.drain()

        assertEquals(1, store.items.size, "the item is owed")
        assertTrue(wake.armed, "the platform wake must stay armed for an owed item")
    }

    // --- Withdrawal ------------------------------------------------------------------------------

    @Test
    fun `cancelByTag cancels the upload of an item enqueued and handed off while it ran`() = runTest {
        val handler = RaceUploadHandler(transport)
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler))
        var racer: String? = null

        store.beforeNextDeleteByTag = {
            racer = engine.enqueue(handler, "payload", tag = "session")
            engine.drain()
        }
        engine.cancelByTag("session")

        val racerId: String = racer!!
        assertEquals(listOf(racerId), transport.launches, "precondition: the racer was handed off")
        assertNull(store.find(racerId), "the wipe removed the racer's row")
        assertEquals(listOf(racerId), transport.cancels, "an upload whose row the wipe removed must be cancelled")
    }

    @Test
    fun `a REPLACE cancels the upload of an item enqueued and handed off while it ran`() = runTest {
        val handler = RaceUploadHandler(transport)
        val engine: UploaderEngine = startEngine(backgroundScope, listOf(handler))
        var racer: String? = null

        store.beforeNextInsertReplace = {
            racer = engine.enqueue(handler, "older", uniqueKey = "avatar")
            engine.drain()
        }
        val replacement: String = engine.enqueue(handler, "newer", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.REPLACE)!!

        val racerId: String = racer!!
        assertNull(store.find(racerId), "the replacement superseded the racer's row")
        assertEquals(UploaderItemState.PENDING, store.find(replacement)?.state)
        assertEquals(listOf(racerId), transport.cancels, "the superseded upload must be cancelled")
    }

    private class RecordingTransport : UploadTransport {
        val launches: MutableList<String> = mutableListOf()
        val cancels: MutableList<String> = mutableListOf()

        override val leaseMillis: Long = LEASE

        override fun launch(itemId: String, request: UploadRequest) {
            error("UploadHandler must call the re-hand-aware overload")
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            launches += itemId
        }

        override fun cancel(itemId: String) {
            cancels += itemId
        }

        companion object {
            const val LEASE: Long = 60_000L
        }
    }

    private class RaceUploadHandler(
        transport: UploadTransport,
        override val retryPolicy: RetryPolicy = FixedRetryPolicy(),
    ) : UploadHandler<String>(transport) {

        override val type: String = "race.upload"

        override fun encodePayload(payload: String): String = payload

        override fun decodePayload(raw: String): String = raw

        /** Runs inside [prepareUpload] before it answers — a settle landing while preparation suspends. */
        var duringPrepare: suspend (AttemptContext) -> Unit = {}

        /** Thrown by [prepareUpload] after [duringPrepare], when set. */
        var failPrepareWith: Throwable? = null

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            duringPrepare(context)
            failPrepareWith?.let { throw it }
            return UploadPreparation.Proceed(UploadRequest(url = "https://example.com/upload", fields = emptyList()))
        }

        override suspend fun classify(payload: String, result: UploadResult): SettleResult =
            defaultUploadClassification(result)
    }
}
