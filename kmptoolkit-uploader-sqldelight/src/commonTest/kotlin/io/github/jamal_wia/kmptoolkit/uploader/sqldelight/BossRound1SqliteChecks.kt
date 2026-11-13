package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A1 final boss round 1 over real SQLite, run by the Android host and the iOS simulator alike: the 4.1.0 changes read
 * together (stored text forms, ids that need escaping, saturating time at the top of the Long range, close racing a
 * settle, rule P3's lease token), plus rows an earlier version wrote.
 */
internal class BossRound1SqliteChecks {
    /**
     * The seed of the random delays of the race round (a delay per round is drawn from `Random(SEED + round)`). It is
     * a constant because common test code has no system properties; to explore another interleaving edit it. Every
     * assertion message of the race quotes it.
     */
    private val seed: Long = 20261113L


    private val marker2: String = "﷐kmp2﷑"

    private suspend fun <T> real(millis: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(millis) { block() } }

    // ---------------------------------------------------------------------------------------------------------------
    // Shared upload fixtures.
    // ---------------------------------------------------------------------------------------------------------------

    private class Transport : UploadTransport {
        val events: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override val leaseMillis: Long = 15 * 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events.update { it + "launch:$itemId" }
        }

        override fun cancel(itemId: String) {
            events.update { it + "cancel:$itemId" }
        }

        fun launches(id: String): Int = events.value.count { it == "launch:$id" }
    }

    private class Uploading(transport: UploadTransport) : UploadHandler<String>(transport) {
        val settled: MutableStateFlow<List<Pair<String, SettleResult>>> = MutableStateFlow(emptyList())
        override val type: String = "boss1.upload"
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 60_000L)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("p", payload))))

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            settled.update { it + (payload to result) }
        }
    }

    /** An id no driver keeps as it is: a leading byte-order mark, a NUL and a lone surrogate. */
    private fun escapedId(n: Int): String = "﻿id-$n\u0000x\uD800"

    private fun engineOver(
        store: UploaderStore,
        storage: UploaderStorage,
        handler: UploaderHandler<*>,
        clock: MutableUploaderClock,
        scope: CoroutineScope,
        ids: Iterator<String>,
    ): UploaderEngine = createUploaderEngine(
        store = store,
        handlers = listOf(handler),
        scope = scope,
        transactionRunner = storage.transactionRunner,
        clock = clock,
        idGenerator = { ids.next() },
    )

    // ---------------------------------------------------------------------------------------------------------------
    // 2. Escaped ids, a clock at the top of the range, close() racing the delivery report.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Every round: an upload item whose id and tag need escaping is claimed at a clock 500 ms below Long.MAX_VALUE (its lease
     * saturates), then the transport's delivery report and close() run at once on real threads. Whatever the order, the
     * delivery commits exactly once, onSettled runs at most once, a report that found no engine leaves the row in flight
     * for the replacement engine, which settles it once, and nothing is launched twice.
     */
    suspend fun escapedUploadSettlesOnceWhileCloseRacesNearLongMax() {
        // timing-hygiene-ignore: TH3 name a unique database file name, not a scenario choice
        val name = "boss1-race-${Random.nextLong().toString(16)}"
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val clock = MutableUploaderClock(Long.MAX_VALUE - 500L)
        val transport = Transport()
        val handler = Uploading(transport)
        try {
            for (round in 0 until 24) {
                val id: String = escapedId(round)
                val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val first: UploaderEngine = engineOver(storage.store, storage, handler, clock, scope1, listOf(id).iterator())
                    UploaderEngineRegistry.register(first)
                    assertEquals(id, real(10_000) { first.enqueue(handler, "payload-$round", tag = "\u0000tag-$round") })
                    real(10_000) { first.drain() }
                    assertEquals(1, transport.launches(id), "round $round: setup launch (seed=$seed)")
                    val claimed: UploaderItem = assertNotNull(storage.store.getById(id))
                    assertEquals(UploaderItemState.IN_FLIGHT, claimed.state, "round $round (seed=$seed)")
                    assertEquals(Long.MAX_VALUE, claimed.leaseUntilEpochMillis, "round $round: the lease saturates (seed=$seed)")

                    val landed: Boolean = real(20_000) {
                        coroutineScope {
                            val report = async(Dispatchers.Default) {
                                if (round % 2 == 0) delay(Random(seed + round).nextLong(0, 3))
                                UploadGateway.complete(id, UploadResult.Completed(200), engineWait = Duration.ZERO)
                            }
                            val closer = async(Dispatchers.Default) {
                                if (round % 2 == 1) delay(Random(seed + round).nextLong(0, 3))
                                first.close()
                                true
                            }
                            awaitAll(report, closer)
                            report.await()
                        }
                    }
                    val after: UploaderItem? = storage.store.getById(id)
                    if (after == null) {
                        assertTrue(landed, "round $round: the row is gone but the report said not landed (seed=$seed)")
                    } else {
                        assertEquals(UploaderItemState.IN_FLIGHT, after.state, "round $round: a report that did not land changed the row (seed=$seed)")
                        assertEquals(claimed.claim, after.claim, "round $round (seed=$seed)")
                        val second: UploaderEngine = engineOver(storage.store, storage, handler, clock, scope2, emptyList<String>().iterator())
                        UploaderEngineRegistry.register(second)
                        real(10_000) { second.drain() }
                        assertEquals(1, transport.launches(id), "round $round: the replacement re-launched under a live lease (seed=$seed)")
                        assertTrue(real(10_000) { UploadGateway.complete(id, UploadResult.Completed(200), engineWait = Duration.ZERO) }, "round $round (seed=$seed)")
                        second.close()
                    }
                    assertNull(storage.store.getById(id), "round $round: the delivery never committed (seed=$seed)")
                    val settles: Int = handler.settled.value.count { it.first == "payload-$round" }
                    assertTrue(settles <= 1, "round $round: onSettled ran $settles times for one delivery (seed=$seed)")
                    assertEquals(1, transport.launches(id), "round $round: launched twice (seed=$seed)")
                } finally {
                    scope1.cancel()
                    scope2.cancel()
                    UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
                }
            }
            assertEquals(emptyList(), storage.store.getAllActive(), "the queue is not empty after every round settled (seed=$seed)")
        } finally {
            storage.close()
            deleteDatabaseFile(name)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. Rule P3 at the top of the range over SQLite, with an escaped id.
    // ---------------------------------------------------------------------------------------------------------------

    /** The first claim parks where a close() cannot interrupt it, then reports the closed caller without writing. */
    private class LateRefusedClaimStore(private val inner: UploaderStore) : UploaderStore by inner {
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val readsAfterGate: MutableStateFlow<Int> = MutableStateFlow(0)
        private val first: MutableStateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            if (first.compareAndSet(expect = true, update = false)) {
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                currentCoroutineContext().ensureActive()
            }
            return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
        }

        override suspend fun getById(id: String): UploaderItem? {
            if (gate.isCompleted) readsAfterGate.update { it + 1 }
            return inner.getById(id)
        }
    }

    /**
     * R6-09 and R7-01 together, over SQLite and with an id that needs escaping: the closed engine's refused claim unwinds
     * after its replacement claimed the same row at a saturated lease, and must leave that claim alone.
     */
    suspend fun lateReleaseSparesTheReplacementAtLongMaxWithAnEscapedId() {
        // timing-hygiene-ignore: TH3 name a unique database file name, not a scenario choice
        val name = "boss1-p3-${Random.nextLong().toString(16)}"
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val clock = MutableUploaderClock(Long.MAX_VALUE - 500L)
        val transport = Transport()
        val handler = Uploading(transport)
        val gated = LateRefusedClaimStore(storage.store)
        val id: String = escapedId(7)
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first: UploaderEngine = engineOver(gated, storage, handler, clock, scope1, listOf(id).iterator())
            assertEquals(id, real(10_000) { first.enqueue(handler, "x", uniqueKey = "\uDC00k", conflictPolicy = ConflictPolicy.KEEP) })
            first.start()
            real(10_000) { gated.entered.await() }
            first.close()

            val second: UploaderEngine = engineOver(storage.store, storage, handler, clock, scope2, emptyList<String>().iterator())
            real(10_000) { second.drain() }
            assertEquals(1, transport.launches(id), "the replacement claimed and launched")
            val claimed: UploaderItem = assertNotNull(storage.store.getById(id))
            assertEquals(UploaderItemState.IN_FLIGHT, claimed.state)
            assertNotEquals(Long.MAX_VALUE, claimed.leaseUntilEpochMillis, "two unresolved claims of one item got the same lease")

            gated.gate.complete(Unit)
            real(10_000) { gated.readsAfterGate.first { it >= 1 } } // the release read the row back
            delay(300) // and had the time to write, if it was going to
            val after: UploaderItem = assertNotNull(storage.store.getById(id))
            assertEquals(claimed, after, "the closed engine's late release changed the replacement's live claim")
            real(10_000) { second.drain() }
            assertEquals(1, transport.launches(id), "the upload was launched a second time")
            second.settle(id, after.claim, SettleResult.Delivered)
            assertNull(storage.store.getById(id))
            second.close()
        } finally {
            scope1.cancel()
            scope2.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 4. A plain handler's escaped item, seen by the gateway (D-23), over SQLite.
    // ---------------------------------------------------------------------------------------------------------------

    private class Plain : UploaderHandler<String> {
        override val type: String = "boss1.plain"
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Detached(60_000L)
    }

    /** D-23: the gateway leaves a plain handler's in-flight item exactly as it is, also when its id is stored escaped. */
    suspend fun gatewayLeavesAnEscapedPlainItemUntouched() {
        // timing-hygiene-ignore: TH3 name a unique database file name, not a scenario choice
        val name = "boss1-plain-${Random.nextLong().toString(16)}"
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val clock = MutableUploaderClock(1_000_000L)
        val handler = Plain()
        val id: String = escapedId(3)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine: UploaderEngine = engineOver(storage.store, storage, handler, clock, scope, listOf(id).iterator())
            UploaderEngineRegistry.register(engine)
            real(10_000) { engine.enqueue(handler, "plain") }
            real(10_000) { engine.drain() }
            val before: UploaderItem = assertNotNull(storage.store.getById(id))
            assertEquals(UploaderItemState.IN_FLIGHT, before.state)
            real(10_000) { UploadGateway.prepareAttempt(id, engineWait = Duration.ZERO) }
            assertTrue(real(10_000) { UploadGateway.complete(id, UploadResult.Completed(500), engineWait = Duration.ZERO) })
            assertTrue(real(10_000) { UploadGateway.complete(id, UploadResult.TransportFailure("x"), engineWait = Duration.ZERO) })
            assertEquals(before, storage.store.getById(id), "a gateway call changed a plain handler's row")
            engine.settle(id, before.claim, SettleResult.Delivered)
            assertNull(storage.store.getById(id))
            engine.close()
        } finally {
            scope.cancel()
            UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
            storage.close()
            deleteDatabaseFile(name)
        }
    }
}
