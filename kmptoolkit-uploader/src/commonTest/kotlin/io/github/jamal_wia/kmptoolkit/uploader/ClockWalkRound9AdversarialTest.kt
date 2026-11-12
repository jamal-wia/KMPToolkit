package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 9 (A2), environment hostility, clock: a wall clock that walks forwards and backwards (DST-sized steps, a day,
 * three days, forty days, a millisecond) while the engine drains. The oracle is the documented rule, written down
 * independently of the engine: a pending item runs when its gate is not in the future, or lies further ahead than
 * `clockAnomalyFactor` times the policy ceiling (a backwards jump); an in-flight upload is re-handed when its lease
 * has run out, or lies further ahead than the factor times the longest lease (24 h). Every other drain must leave the
 * item untouched, and a delivery happens exactly once whatever the clock did.
 */
class ClockWalkRound9AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private val steps: List<Long> = listOf(
        0L, 1L, -1L, 59_999L, 61_000L, 120_001L, 3_600_000L, -3_600_000L, 86_400_000L, -86_400_000L,
        3 * 86_400_000L, -3 * 86_400_000L, 40 * 86_400_000L, -40 * 86_400_000L,
    )

    @Test
    fun `a pending item runs exactly when its gate is due or a clock jump over a long random walk of the clock`() = runTest {
        val start = 1_700_000_000_000L
        val backoff = 60_000L
        val factor = UploaderConfig().clockAnomalyFactor
        for (seed in 1..40) {
            val random = Random(seed)
            val clock = TestClock(start)
            val store = TestUploaderStore()
            val failuresLeft: MutableMap<String, Int> = mutableMapOf()
            val handler = TestHandler(
                retryPolicy = FixedRetryPolicy(delayMillis = backoff),
                onExecute = { _, payload ->
                    val left: Int = failuresLeft.getValue(payload)
                    if (left > 0) {
                        failuresLeft[payload] = left - 1
                        AttemptResult.Retry()
                    } else {
                        AttemptResult.Success
                    }
                },
            )
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock)
            try {
                val payloads: List<String> = listOf("a", "b", "c", "d")
                val owed: MutableMap<String, Int> = mutableMapOf()
                for (p in payloads) {
                    owed[p] = random.nextInt(0, 5)
                    failuresLeft[p] = owed.getValue(p)
                    engine.enqueue(handler, p)
                }
                repeat(150) { step ->
                    clock.millis += steps[random.nextInt(steps.size)]
                    val now: Long = clock.millis
                    val due: Set<String> = store.items.filter { item ->
                        val ahead: Long = item.nextRunAtEpochMillis - now
                        ahead <= 0L || ahead > backoff * factor
                    }.map { it.payload }.toSet()
                    val before: Map<String, Int> = payloads.associateWith { p -> handler.payloads.count { it == p } }
                    engine.drain()
                    for (p in payloads) {
                        val ran: Int = handler.payloads.count { it == p } - before.getValue(p)
                        val shouldRun: Int = if (p in due) 1 else 0
                        assertEquals(shouldRun, ran, "seed $seed step $step clock $now: '$p' ran $ran times, expected $shouldRun")
                    }
                }
                // Let every item finish: the clock now only moves forwards.
                var guard = 0
                while (store.items.isNotEmpty() && guard++ < 20) {
                    clock.millis += backoff + 1
                    engine.drain()
                }
                assertTrue(store.items.isEmpty(), "seed $seed: items left after the walk: ${store.items.map { it.payload to it.attempts }}")
                for (p in payloads) {
                    assertEquals(owed.getValue(p) + 1, handler.payloads.count { it == p }, "seed $seed: '$p' was executed a wrong number of times in total")
                }
            } finally {
                engine.close()
            }
        }
    }

    private class LaunchCounter(override val leaseMillis: Long) : UploadTransport {
        val launches: MutableList<String> = mutableListOf()
        override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            launches += itemId
        }

        override fun cancel(itemId: String) = Unit
    }

    private class UploadingHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "r9.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
    }

    @Test
    fun `an in-flight upload is re-handed exactly when its lease ran out or is a clock jump over a long random walk of the clock`() = runTest {
        val start = 1_700_000_000_000L
        val lease = 3_600_000L
        val longestLease = 24L * 60 * 60 * 1000
        val factor = UploaderConfig().clockAnomalyFactor
        for (seed in 1..40) {
            val random = Random(seed)
            val clock = TestClock(start)
            val store = TestUploaderStore()
            val transport = LaunchCounter(lease)
            val handler = UploadingHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock)
            try {
                val id: String = engine.enqueue(handler, "x")!!
                engine.drain()
                assertEquals(1, transport.launches.size, "seed $seed: the first hand-off")
                repeat(150) { step ->
                    clock.millis += steps[random.nextInt(steps.size)]
                    val now: Long = clock.millis
                    val row: UploaderItem = store.find(id)!!
                    val ahead: Long = row.leaseUntilEpochMillis - now
                    val shouldRehand: Boolean = ahead <= 0L || ahead > longestLease * factor
                    val before: Int = transport.launches.size
                    engine.drain()
                    val handed: Int = transport.launches.size - before
                    assertEquals(if (shouldRehand) 1 else 0, handed, "seed $seed step $step clock $now: lease was ${ahead}ms ahead, re-hands: $handed")
                    val after: UploaderItem = store.find(id)!!
                    assertEquals(UploaderItemState.IN_FLIGHT, after.state, "seed $seed step $step: the item left flight without a settlement")
                    assertEquals(row.attempts, after.attempts, "seed $seed step $step: a re-hand must not charge an attempt")
                    if (handed == 1) {
                        assertTrue(after.leaseUntilEpochMillis - clock.millis <= longestLease, "seed $seed step $step: a lease beyond the 24 h ceiling was written")
                    }
                }
            } finally {
                engine.close()
            }
        }
    }
}
