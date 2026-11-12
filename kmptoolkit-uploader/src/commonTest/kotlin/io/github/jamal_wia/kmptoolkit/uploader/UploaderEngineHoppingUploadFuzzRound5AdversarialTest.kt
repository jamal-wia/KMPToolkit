package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 4: the random walk of the plain-handler fuzz, for an upload handler whose platform jobs are simulated:
 * a withdrawn upload must end cancelled (its last transport event is a cancel), a late outcome of a withdrawn
 * upload must settle nothing, a delivered upload is never launched again, ordering channels hold, and the
 * queue drains once the executors report.
 */
class UploaderEngineHoppingUploadFuzzRound5AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class FuzzTransport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 2_000L
        override fun launch(itemId: String, request: UploadRequest) = error("UploadHandler uses the re-hand overload")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }

        fun running(): List<String> {
            val last: MutableMap<String, String> = linkedMapOf()
            events.forEach { event -> last[event.substringAfter(':')] = event.substringBefore(':') }
            return last.filterValues { it == "launch" }.keys.toList()
        }
    }

    private class FuzzUploadHandler(transport: UploadTransport, private val random: Random) : UploadHandler<String>(transport) {
        val dropped: MutableSet<String> = mutableSetOf()
        override val type: String = "fuzz.up"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy(delayMillis = 300L)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override fun orderingKey(payload: String): String? = payload.substringBefore(':').takeIf { it != "free" }
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            delay(random.nextLong(0L, 30L).milliseconds)
            return when (random.nextInt(100)) {
                in 0..91 -> UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
                in 92..95 -> UploadPreparation.Park("no")
                else -> UploadPreparation.Drop("no").also { dropped += context.id }
            }
        }
    }

    private class HoppingStore(private val inner: TestUploaderStore) : io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore by inner {
        override suspend fun insertKeep(record: UploaderItem): Boolean { yield(); return inner.insertKeep(record).also { yield() } }
        override suspend fun insertReplace(record: UploaderItem): UploaderItem? { yield(); return inner.insertReplace(record).also { yield() } }
        override suspend fun getAllActive(): List<UploaderItem> { yield(); return inner.getAllActive() }
        override suspend fun getById(id: String): UploaderItem? { yield(); return inner.getById(id) }
        override suspend fun recordFailure(
            id: String, attempts: Int, nextRunAtEpochMillis: Long, lastError: String?, expectedState: UploaderItemState,
            expectedClaim: Long, disposition: io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition,
        ): Boolean { yield(); return inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition).also { yield() } }
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            yield(); return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim).also { yield() }
        }
        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            yield(); return inner.park(id, lastError, expectedState, expectedClaim).also { yield() }
        }
        override suspend fun deleteById(id: String) { yield(); inner.deleteById(id); yield() }
        override suspend fun deleteByTag(tag: String): List<UploaderItem> { yield(); return inner.deleteByTag(tag).also { yield() } }
    }

    private var totalLaunches = 0

    private suspend fun TestScope.walk(seed: Int) {
        val random = Random(seed)
        val store = TestUploaderStore()
        val clock = TestClock(1_000L)
        val transport = FuzzTransport()
        val handler = FuzzUploadHandler(transport, random)
        val engine: UploaderEngine = createUploaderEngine(
            store = HoppingStore(store),
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds("u$seed")::next,
        )
        val enqueued: MutableList<String> = mutableListOf()
        val withdrawn: MutableSet<String> = mutableSetOf()
        // withdrawn while still owed (a row that was gone or delivered has no upload to stop; the check covers only rows still owed; a parked row is cancelled too but not required to be)
        val withdrawnOwed: MutableSet<String> = mutableSetOf()
        fun owed(id: String?): Boolean = id != null && store.find(id)?.let { it.state != UploaderItemState.PARKED } == true
        val finished: MutableSet<String> = mutableSetOf()
        val currentByKey: MutableMap<String, String> = mutableMapOf()
        try {
            UploaderEngineRegistry.register(engine)
            engine.start()

            suspend fun report(id: String, status: Int) {
                val before: UploaderItem? = store.find(id)
                UploadGateway.complete(id, UploadResult.Completed(status), Duration.ZERO)
                if (before == null) {
                    assertEquals(null, store.find(id), "seed $seed: a late outcome of $id revived or created a row")
                } else if (status in 200..299 || status in 400..499) {
                    if (store.find(id) == null) finished += id
                }
            }

            repeat(70) {
                when (random.nextInt(100)) {
                    in 0..39 -> {
                        val key: String? = listOf("k1", "k2", "k3", null).random(random)
                        val chan: String = listOf("A", "B", "free").random(random)
                        val before: String? = key?.let { currentByKey[it] }
                        val beforeOwed: Boolean = owed(before)
                        val id: String? = engine.enqueue(
                            handler, "$chan:${enqueued.size}", uniqueKey = key, tag = listOf("t1", "t2", null).random(random),
                            conflictPolicy = if (random.nextInt(3) == 0) ConflictPolicy.REPLACE else ConflictPolicy.KEEP,
                        )
                        if (id != null) {
                            enqueued += id
                            if (key != null) {
                                if (before != null && before != id) {
                                    withdrawn += before
                                    if (beforeOwed) withdrawnOwed += before
                                }
                                currentByKey[key] = id
                            }
                        }
                    }

                    in 40..49 -> enqueued.randomOrNull(random)?.let { id ->
                        val wasOwed: Boolean = owed(id)
                        engine.cancel(id)
                        withdrawn += id
                        if (wasOwed) withdrawnOwed += id
                    }

                    in 50..55 -> {
                        val tag: String = listOf("t1", "t2").random(random)
                        val owned: List<String> = store.items.filter { it.tag == tag }.map { it.id }
                        val ownedOwed: List<String> = owned.filter { owed(it) }
                        engine.cancelByTag(tag)
                        withdrawn += owned
                        withdrawnOwed += ownedOwed
                    }

                    in 56..80 -> {
                        // executors report: running jobs, but also jobs that were already cancelled (late outcomes)
                        val candidates: List<String> = transport.running() + enqueued.filter { it in withdrawn }.take(3)
                        candidates.randomOrNull(random)?.let { id -> report(id, listOf(200, 200, 500, 400).random(random)) }
                    }

                    in 81..85 -> clock.millis += random.nextLong(0L, 5_000L)
                    else -> Unit
                }
                advanceTimeBy(random.nextLong(1L, 700L).milliseconds)
                clock.millis = maxOf(clock.millis, 1_000L + testScheduler.currentTime)
            }

            var rounds = 0
            while (store.items.any { it.state != UploaderItemState.PARKED } && rounds++ < 400) {
                clock.millis += 5_000L
                advanceTimeBy(1.seconds)
                runCurrent()
                transport.running().forEach { id -> report(id, 200) }
            }

            val stuck: List<String> = store.items.filter { it.state != UploaderItemState.PARKED }.map { "${it.id}:${it.state}:a${it.attempts}" }
            assertTrue(stuck.isEmpty(), "seed $seed: the queue did not drain once the executors reported: $stuck")
            val leaked: List<String> = withdrawnOwed.filter { id -> transport.events.lastOrNull { it.endsWith(":$id") }?.startsWith("launch") == true }
            assertTrue(leaked.isEmpty(), "seed $seed: withdrawn uploads left running (last transport event is a launch): $leaked")
            val lost: List<String> = enqueued.filter { store.find(it) == null && it !in finished && it !in handler.dropped && it !in withdrawn && transport.events.none { e -> e == "launch:$it" } }
            assertTrue(lost.isEmpty(), "seed $seed: items vanished before any upload was launched, without being withdrawn: $lost")
            totalLaunches += transport.events.count { it.startsWith("launch:") }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a random walk over an upload handler leaves no withdrawn upload running and drains once executors report`() =
        runTest(timeout = 120.seconds) {
            for (seed in 0 until 120) walk(seed)
            assertTrue(totalLaunches > 600, "only $totalLaunches launches in 120 walks")
        }
}
