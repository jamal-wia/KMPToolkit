package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Round 10 (cross-platform differential): one scripted scenario through the real engine, the in-memory store and the
 * manual clock, written as a literal transcript. The same literal is compared on Android (JVM) and on iOS
 * (Kotlin/Native): any observable difference between the platforms fails one of the two runs.
 */
class EngineTranscriptRound10AdversarialTest {

    private class Boom(message: String) : Exception(message)

    private class Scripted(
        override val type: String,
        override val retryPolicy: RetryPolicy,
        private val detachedLog: MutableList<Pair<String, Long>>,
    ) : UploaderHandler<String> {
        override val schemaVersion: Int = 1
        override val constraintKeys: Set<String> = emptySet()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override fun orderingKey(payload: String): String? = payload.substringAfter("@", "").ifEmpty { null }
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            val script: String = payload.substringBefore("@")
            return when {
                script == "ok" -> AttemptResult.Success
                script == "retry" -> AttemptResult.Retry(Boom("retry ${context.attempts}"))
                script == "retry-null" -> AttemptResult.Retry(null)
                script == "retry-twice" -> if (context.attempts < 2) AttemptResult.Retry(IllegalStateException("early")) else AttemptResult.Success
                script == "park" -> AttemptResult.Park("parked by script")
                script == "drop" -> AttemptResult.Drop("dropped by script")
                script == "throw" -> throw Boom("throwné😀")
                script == "detach" -> {
                    detachedLog += context.id to context.claim
                    AttemptResult.Detached(5_000L)
                }
                else -> AttemptResult.Success
            }
        }
    }

    private fun render(item: UploaderItem): String =
        "${item.id}|${item.type}|${item.state}|a=${item.attempts}|next=${item.nextRunAtEpochMillis}|lease=${item.leaseUntilEpochMillis}|" +
            "claim=${item.claim}|key=${item.uniqueKey}|ord=${item.orderingKey}|tag=${item.tag}|err=${item.lastError}"

    @Test
    fun `a scripted engine scenario gives the same transcript on both platforms`() = runTest {
        val clock = MutableUploaderClock(1_700_000_000_000L)
        val store = InMemoryUploaderStore()
        val detached: MutableList<Pair<String, Long>> = mutableListOf()
        val parking = Scripted("t", ExponentialBackoffRetryPolicy(1_000L, 60_000L, 0.2, GiveUpPolicy.ParkAfterAttempts(4), Random(7)), detached)
        val dropping = Scripted("u", ExponentialBackoffRetryPolicy(500L, 8_000L, 0.0, GiveUpPolicy.DropAfterAttempts(2)), detached)
        var next = 0
        val engine = createUploaderEngine(
            store = store,
            handlers = listOf(parking, dropping),
            scope = backgroundScope,
            clock = clock,
            idGenerator = { "id-${++next}" },
        )
        val lines: MutableList<String> = mutableListOf()
        fun snap(label: String) {
            lines += "-- $label @${clock.nowEpochMillis()}"
            store.snapshot().forEach { lines += render(it) }
        }

        engine.enqueue(parking, "retry")
        engine.enqueue(parking, "retry-twice")
        engine.enqueue(parking, "retry-null", uniqueKey = "k1")
        engine.enqueue(parking, "park")
        engine.enqueue(parking, "throw", tag = "tg")
        engine.enqueue(parking, "detach")
        engine.enqueue(dropping, "retry")
        engine.enqueue(dropping, "drop")
        engine.enqueue(dropping, "ok", tag = "tg")
        engine.enqueue(parking, "ok@chain", uniqueKey = "dup")
        engine.enqueue(parking, "retry@chain", uniqueKey = "dup", conflictPolicy = ConflictPolicy.REPLACE)
        engine.enqueue(parking, "ok@chain2")
        snap("enqueued")

        val steps: List<Long> = listOf(0L, 300L, 700L, 1_500L, 3_000L, 6_000L, 12_000L, 30_000L, 61_000L, 120_000L)
        steps.forEachIndexed { index, advance ->
            engine.drain()
            snap("drain $index")
            if (index == 2) {
                detached.forEach { (id, claim) -> engine.settle(id, claim, SettleResult.Failed(Boom("settled late"))) }
                snap("settled")
            }
            if (index == 5) {
                engine.cancelByTag("tg")
                snap("cancelled tag")
            }
            clock.advanceBy(advance)
        }
        clock.advanceBy(Long.MAX_VALUE)
        snap("far future")
        engine.drain()
        snap("drain far future")
        clock.advanceBy(Long.MIN_VALUE)
        clock.advanceBy(Long.MIN_VALUE)
        snap("far past")
        engine.drain()
        snap("drain far past")
        engine.close()

        assertEquals(GOLDEN.trim(), lines.joinToString("\n"), "transcript:\n" + lines.joinToString("\n"))
    }

    private companion object {
        val GOLDEN: String = """
-- enqueued @1700000000000
id-1|t|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=null|err=null
id-2|t|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=null|err=null
id-3|t|PENDING|a=0|next=0|lease=0|claim=0|key=k1|ord=null|tag=null|err=null
id-4|t|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=null|err=null
id-5|t|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=tg|err=null
id-6|t|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=null|err=null
id-7|u|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=null|err=null
id-8|u|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=null|err=null
id-9|u|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=null|tag=tg|err=null
id-11|t|PENDING|a=0|next=0|lease=0|claim=0|key=dup|ord=chain|tag=null|err=null
id-12|t|PENDING|a=0|next=0|lease=0|claim=0|key=null|ord=chain2|tag=null|err=null
-- drain 0 @1700000000000
id-1|t|PENDING|a=1|next=1700000001183|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-2|t|PENDING|a=1|next=1700000001019|lease=0|claim=1|key=null|ord=null|tag=null|err=IllegalStateException: early
id-3|t|PENDING|a=1|next=1700000000945|lease=0|claim=1|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-5|t|PENDING|a=1|next=1700000001151|lease=0|claim=1|key=null|ord=null|tag=tg|err=Boom: throwné😀
id-6|t|IN_FLIGHT|a=0|next=0|lease=1700000005000|claim=1|key=null|ord=null|tag=null|err=null
id-7|u|PENDING|a=1|next=1700000000500|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-11|t|PENDING|a=1|next=1700000000809|lease=0|claim=1|key=dup|ord=chain|tag=null|err=Boom: retry 0
-- drain 1 @1700000000000
id-1|t|PENDING|a=1|next=1700000001183|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-2|t|PENDING|a=1|next=1700000001019|lease=0|claim=1|key=null|ord=null|tag=null|err=IllegalStateException: early
id-3|t|PENDING|a=1|next=1700000000945|lease=0|claim=1|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-5|t|PENDING|a=1|next=1700000001151|lease=0|claim=1|key=null|ord=null|tag=tg|err=Boom: throwné😀
id-6|t|IN_FLIGHT|a=0|next=0|lease=1700000005000|claim=1|key=null|ord=null|tag=null|err=null
id-7|u|PENDING|a=1|next=1700000000500|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-11|t|PENDING|a=1|next=1700000000809|lease=0|claim=1|key=dup|ord=chain|tag=null|err=Boom: retry 0
-- drain 2 @1700000000300
id-1|t|PENDING|a=1|next=1700000001183|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-2|t|PENDING|a=1|next=1700000001019|lease=0|claim=1|key=null|ord=null|tag=null|err=IllegalStateException: early
id-3|t|PENDING|a=1|next=1700000000945|lease=0|claim=1|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-5|t|PENDING|a=1|next=1700000001151|lease=0|claim=1|key=null|ord=null|tag=tg|err=Boom: throwné😀
id-6|t|IN_FLIGHT|a=0|next=0|lease=1700000005000|claim=1|key=null|ord=null|tag=null|err=null
id-7|u|PENDING|a=1|next=1700000000500|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-11|t|PENDING|a=1|next=1700000000809|lease=0|claim=1|key=dup|ord=chain|tag=null|err=Boom: retry 0
-- settled @1700000000300
id-1|t|PENDING|a=1|next=1700000001183|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-2|t|PENDING|a=1|next=1700000001019|lease=0|claim=1|key=null|ord=null|tag=null|err=IllegalStateException: early
id-3|t|PENDING|a=1|next=1700000000945|lease=0|claim=1|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-5|t|PENDING|a=1|next=1700000001151|lease=0|claim=1|key=null|ord=null|tag=tg|err=Boom: throwné😀
id-6|t|PENDING|a=1|next=1700000001279|lease=0|claim=2|key=null|ord=null|tag=null|err=Boom: settled late
id-7|u|PENDING|a=1|next=1700000000500|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-11|t|PENDING|a=1|next=1700000000809|lease=0|claim=1|key=dup|ord=chain|tag=null|err=Boom: retry 0
-- drain 3 @1700000001000
id-1|t|PENDING|a=1|next=1700000001183|lease=0|claim=1|key=null|ord=null|tag=null|err=Boom: retry 0
id-2|t|PENDING|a=1|next=1700000001019|lease=0|claim=1|key=null|ord=null|tag=null|err=IllegalStateException: early
id-3|t|PENDING|a=2|next=1700000003361|lease=0|claim=2|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-5|t|PENDING|a=1|next=1700000001151|lease=0|claim=1|key=null|ord=null|tag=tg|err=Boom: throwné😀
id-6|t|PENDING|a=1|next=1700000001279|lease=0|claim=2|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PENDING|a=2|next=1700000002762|lease=0|claim=2|key=dup|ord=chain|tag=null|err=Boom: retry 1
-- drain 4 @1700000002500
id-1|t|PENDING|a=2|next=1700000004408|lease=0|claim=2|key=null|ord=null|tag=null|err=Boom: retry 1
id-2|t|PENDING|a=2|next=1700000004721|lease=0|claim=2|key=null|ord=null|tag=null|err=IllegalStateException: early
id-3|t|PENDING|a=2|next=1700000003361|lease=0|claim=2|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-5|t|PENDING|a=2|next=1700000004217|lease=0|claim=2|key=null|ord=null|tag=tg|err=Boom: throwné😀
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000007500|claim=3|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PENDING|a=2|next=1700000002762|lease=0|claim=2|key=dup|ord=chain|tag=null|err=Boom: retry 1
-- drain 5 @1700000005500
id-1|t|PENDING|a=3|next=1700000009808|lease=0|claim=3|key=null|ord=null|tag=null|err=Boom: retry 2
id-3|t|PENDING|a=3|next=1700000010068|lease=0|claim=3|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-5|t|PENDING|a=3|next=1700000009828|lease=0|claim=3|key=null|ord=null|tag=tg|err=Boom: throwné😀
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000007500|claim=3|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PENDING|a=3|next=1700000009617|lease=0|claim=3|key=dup|ord=chain|tag=null|err=Boom: retry 2
-- cancelled tag @1700000005500
id-1|t|PENDING|a=3|next=1700000009808|lease=0|claim=3|key=null|ord=null|tag=null|err=Boom: retry 2
id-3|t|PENDING|a=3|next=1700000010068|lease=0|claim=3|key=k1|ord=null|tag=null|err=null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000007500|claim=3|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PENDING|a=3|next=1700000009617|lease=0|claim=3|key=dup|ord=chain|tag=null|err=Boom: retry 2
-- drain 6 @1700000011500
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000016500|claim=4|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
-- drain 7 @1700000023500
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000028500|claim=5|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
-- drain 8 @1700000053500
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000058500|claim=6|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
-- drain 9 @1700000114500
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000119500|claim=7|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
-- far future @9223372036854775807
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=1700000119500|claim=7|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
-- drain far future @9223372036854775807
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=9223372036854775807|claim=8|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
-- far past @-9223372036854775808
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=9223372036854775807|claim=8|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
-- drain far past @-9223372036854775808
id-1|t|PARKED|a=4|next=1700000018893|lease=0|claim=4|key=null|ord=null|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
id-3|t|PARKED|a=4|next=1700000019818|lease=0|claim=4|key=k1|ord=null|tag=null|err=gave up after 4 attempts; last error: null
id-4|t|PARKED|a=0|next=0|lease=0|claim=1|key=null|ord=null|tag=null|err=parked by script
id-6|t|IN_FLIGHT|a=1|next=1700000001279|lease=-9223372036854770808|claim=9|key=null|ord=null|tag=null|err=Boom: settled late
id-11|t|PARKED|a=4|next=1700000018888|lease=0|claim=4|key=dup|ord=chain|tag=null|err=gave up after 4 attempts; last error: Boom: retry 3
"""
    }
}
