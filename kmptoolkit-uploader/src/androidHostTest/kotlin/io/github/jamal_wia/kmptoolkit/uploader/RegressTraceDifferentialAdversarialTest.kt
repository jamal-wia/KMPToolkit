package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression differential (A1 regress round): a seeded random walk over the public operations of the engine
 * that writes a full observable trace (every operation and its outcome, every attempt the handler saw, every store
 * write with its result, a snapshot of the rows after every step, the platform wake) to the directory named by the
 * environment variable CT_TRACE_DIR. The same source compiles against the engine before and after the component split;
 * the traces of the two engines are compared with diff. Without the variable the walks still run and only assert that
 * the quiet world drains. It touches no member that changed between the two engines (the store wrapper does not
 * override the keep insert).
 */
class RegressTraceDifferentialAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class SpyStore(
        private val inner: TestUploaderStore,
        private val random: Random,
        private val log: MutableList<String>,
    ) : UploaderStore by inner {
        var failing: Boolean = false
        var failDeletes: Boolean = true
        var reads: Int = 0
        var slow: Random? = null

        private suspend fun pause() {
            slow?.let { delay(it.nextLong(0L, 12L).milliseconds) }
        }

        private fun roll(): Int = if (failing && random.nextInt(100) < 10) (if (random.nextBoolean()) 1 else 2) else 0

        private suspend fun <T> guarded(label: String, call: suspend () -> T): T {
            pause()
            val verdict: Int = roll()
            if (verdict == 1) {
                log += "store:$label:FAIL-BEFORE"
                throw IllegalStateException("database is locked")
            }
            val result: T = call()
            pause()
            log += "store:$label=${result.toString().take(60)}"
            if (verdict == 2) {
                log += "store:$label:FAIL-AFTER"
                throw IllegalStateException("committed then dropped")
            }
            return result
        }

        override suspend fun insertReplace(record: UploaderItem): UploaderItem? =
            guarded("insertReplace(${record.id})") { inner.insertReplace(record) }.also { log += "store:superseded=${it?.id}" }

        override suspend fun getAllActive(): List<UploaderItem> {
            reads++
            pause()
            if (slow != null) log += "read:getAllActive"
            return inner.getAllActive()
        }

        override suspend fun getById(id: String): UploaderItem? {
            reads++
            pause()
            if (slow != null) log += "read:getById($id)"
            return inner.getById(id)
        }

        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean = guarded("recordFailure($id,a$attempts,$disposition)") {
            inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }

        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
            guarded("markInFlight($id,c$expectedClaim)") { inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim) }

        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
            guarded("park($id,c$expectedClaim)") { inner.park(id, lastError, expectedState, expectedClaim) }

        override suspend fun deleteById(id: String) =
            if (failDeletes) guarded("deleteById($id)") { inner.deleteById(id) } else { pause(); inner.deleteById(id).also { log += "store:deleteById($id)=ok" } }
        override suspend fun deleteByTag(tag: String): List<UploaderItem> =
            guarded("deleteByTag($tag)") { inner.deleteByTag(tag) }
    }

    private fun snapshot(store: TestUploaderStore): String =
        // path-hygiene-ignore: PH4 the '/' is the field delimiter of a diagnostic snapshot string ('attempts' only contains the substring 'temp'), not a path join
        store.items.sortedBy { it.id }.joinToString(" ") { "${it.id}/${it.state.name.take(2)}/a${it.attempts}/c${it.claim}/n${it.nextRunAtEpochMillis}/l${it.leaseUntilEpochMillis}" }

    private suspend fun TestScope.walk(seed: Int, out: StringBuilder?, chaos: Boolean, flaky: Boolean, keepOverParked: Boolean, failDeletes: Boolean = true, quietSettle: Boolean = false) {
        val store = TestUploaderStore()
        val clock = TestClock(1_000L)
        val random = Random(seed)
        val log: MutableList<String> = mutableListOf()
        val spy = SpyStore(store, Random(seed * 31 + 7), log).also { it.failing = flaky; it.failDeletes = failDeletes }
        val net = TestConstraint("net", satisfied = true)
        val enqueued: MutableList<String> = mutableListOf()
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 300L),
            constraintKeys = setOf("net"),
            ordering = { payload -> payload.substringBefore(':').takeIf { it != "free" } },
            onExecute = { context, payload ->
                val gateAtStart: Long = store.find(context.id)?.nextRunAtEpochMillis ?: 0L
                if (!context.wasDetached && gateAtStart - clock.millis > 6L) log += "EARLY:${context.id}:gate=$gateAtStart:now=${clock.millis}"
                log += "start@${clock.millis}/${testScheduler.currentTime}:${context.id}:a${context.attempts}:c${context.claim}:d${context.wasDetached}:$payload"
                delay(random.nextLong(0L, 40L).milliseconds)
                val r: AttemptResult = when (random.nextInt(100)) {
                    in 0..54 -> AttemptResult.Success
                    in 55..79 -> AttemptResult.Retry(IllegalStateException("flaky"))
                    in 80..86 -> AttemptResult.Park("no")
                    in 87..91 -> AttemptResult.Drop("no")
                    else -> AttemptResult.Detached(random.nextLong(200L, 4_000L))
                }
                log += "answer:${context.id}:${r::class.simpleName}"
                r
            },
        )
        val ids = SequentialIds("s$seed-reg")
        val wake = TestWakeScheduler()
        fun newEngine(): UploaderEngine = createUploaderEngine(
            store = spy,
            handlers = listOf(handler),
            scope = backgroundScope,
            constraintProviders = listOf(net),
            clock = clock,
            wakeScheduler = wake,
            idGenerator = ids::next,
            logger = object : Logger {
                override val tag: String = "ct"
                override fun isLoggable(level: LogLevel): Boolean = true
                override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
                    log += "log:${level.name}:${message().take(110)}"
                }
            },
        )
        var engine: UploaderEngine = newEngine()
        var skew = 0L
        var step = 0
        var lastReads = 0
        fun flush(label: String) {
            if (out == null) { log.clear(); return }
            val dr: Int = spy.reads - lastReads; lastReads = spy.reads
            out.append("s$seed.${step++} dReads=$dr now=${clock.millis}/${testScheduler.currentTime} $label | wake=${wake.armed}/${wake.scheduleCount}/${wake.cancelCount} | ${snapshot(store)}\n")
            for (l in log) out.append("    $l\n")
            log.clear()
        }
        try {
            engine.start()
            repeat(80) {
                val label: String
                when (random.nextInt(100)) {
                    in 0..44 -> {
                        val key: String? = listOf("k1", "k2", "k3", null).random(random)
                        val chan: String = listOf("A", "B", "free").random(random)
                        val replace: Boolean = random.nextInt(3) == 0
                        val tag: String? = listOf("t1", "t2", null).random(random)
                        val parkedKey: Boolean = key != null && store.items.any { it.uniqueKey == key && it.state == UploaderItemState.PARKED }
                        val policy: ConflictPolicy = if (replace || (parkedKey && !keepOverParked)) ConflictPolicy.REPLACE else ConflictPolicy.KEEP
                        val res: String = try {
                            val id: String? = engine.enqueue(handler, "$chan:${enqueued.size}", uniqueKey = key, tag = tag, conflictPolicy = policy)
                            if (id != null) enqueued += id
                            id.toString()
                        } catch (e: IllegalStateException) {
                            if (!flaky) throw e
                            "THROW"
                        }
                        label = "enqueue($key,$chan,$tag,$policy)=$res"
                    }
                    in 45..54 -> {
                        val id: String? = enqueued.randomOrNull(random)
                        val res: String = try { id?.let { engine.cancel(it) }; "ok" } catch (e: IllegalStateException) { if (!flaky) throw e; "THROW" }
                        label = "cancel($id)=$res"
                    }
                    in 55..60 -> {
                        val tag: String = listOf("t1", "t2").random(random)
                        val res: String = try { engine.cancelByTag(tag); "ok" } catch (e: IllegalStateException) { if (!flaky) throw e; "THROW" }
                        label = "cancelByTag($tag)=$res"
                    }
                    in 61..70 -> {
                        val row: UploaderItem? = store.items.filter { it.state == UploaderItemState.IN_FLIGHT }.randomOrNull(random)
                        val result: SettleResult = when (random.nextInt(4)) {
                            0 -> SettleResult.Delivered
                            1 -> SettleResult.Drop("x")
                            2 -> SettleResult.Park("x")
                            else -> SettleResult.Failed(null)
                        }
                        val res: String = try { val was: Boolean = spy.failing; if (quietSettle) spy.failing = false; try { row?.let { engine.settle(it.id, it.claim, result) } } finally { spy.failing = was }; "ok" } catch (e: IllegalStateException) { if (!flaky) throw e; "THROW" }
                        label = "settle(${row?.id},c${row?.claim},${result::class.simpleName})=$res"
                    }
                    in 71..76 -> { val d: Long = random.nextLong(0L, 6_000L); skew += d; label = "skew+$d" }
                    in 77..82 -> if (chaos) { val d: Long = random.nextLong(0L, 30_000L); skew = maxOf(-40_000L, skew - d); label = "skew-$d" } else label = "noop"
                    in 83..86 -> if (chaos) {
                        engine.close()
                        engine = newEngine()
                        engine.start()
                        label = "restart"
                    } else label = "noop"
                    in 87..92 -> { val v: Boolean = random.nextBoolean(); net.set(v); label = "net=$v" }
                    in 93..95 -> {
                        val res: String = try { engine.drain(); "ok" } catch (e: IllegalStateException) { if (!flaky) throw e; "THROW" }
                        label = "drain=$res"
                    }
                    else -> label = "idle"
                }
                var left: Long = random.nextLong(1L, 700L)
                while (left > 0L) {
                    val slice: Long = minOf(left, 5L)
                    advanceTimeBy(slice.milliseconds)
                    clock.millis = 1_000L + testScheduler.currentTime + skew
                    left -= slice
                }
                flush(label)
            }
            spy.failing = false
            net.set(true)
            var rounds = 0
            while (store.items.any { it.state != UploaderItemState.PARKED } && rounds++ < 400) {
                skew += 5_000L
                clock.millis = 1_000L + testScheduler.currentTime + skew
                if (System.getenv("CT_COARSE") == "1") {
                    advanceTimeBy(1.seconds)
                } else repeat(20) {
                    advanceTimeBy(50.milliseconds)
                    clock.millis = 1_000L + testScheduler.currentTime + skew
                }
                runCurrent()
                store.items.filter { it.state == UploaderItemState.IN_FLIGHT && rounds % 3 == 0 }.forEach { row ->
                    engine.settle(row.id, row.claim, SettleResult.Delivered)
                }
                flush("quiet$rounds")
            }
            out?.append("s$seed.end reads=${spy.reads} deleted=${store.deletedIds.size}\n")
            assertTrue(store.items.none { it.state != UploaderItemState.PARKED }, "seed $seed did not drain")
        } finally {
            engine.close()
        }
    }

    private fun run(name: String, seeds: IntRange, chaos: Boolean, flaky: Boolean, keepOverParked: Boolean, failDeletes: Boolean = true, quietSettle: Boolean = false) = runTest(timeout = 300.seconds) {
        val dir: String? = System.getenv("CT_TRACE_DIR")
        val out: StringBuilder? = dir?.let { StringBuilder() }
        for (seed in seeds) walk(seed, out, chaos, flaky, keepOverParked, failDeletes, quietSettle)
        if (dir != null) File(dir).also { it.mkdirs() }.resolve("$name.txt").writeText(out.toString())
    }

    /** Runs [block] as its own coroutine, cancels it after [cancelAfterMs] of virtual time (if still running) and reports how it ended. */
    private suspend fun TestScope.cancellable(cancelAfterMs: Long, block: suspend () -> Any?): String {
        val job = kotlinx.coroutines.CompletableDeferred<String>()
        val worker = backgroundScope.launch {
            job.complete(
                try { block().toString() } catch (e: kotlinx.coroutines.CancellationException) { job.complete("CANCELLED"); throw e } catch (e: Throwable) { "THROW:${e::class.simpleName}" },
            )
        }
        var waited = 0L
        while (waited < cancelAfterMs && !job.isCompleted) { advanceTimeBy(1.milliseconds); waited++ }
        if (!job.isCompleted) worker.cancel()
        var cap = 0
        while (!job.isCompleted && cap++ < 3_000) advanceTimeBy(1.milliseconds)
        return if (job.isCompleted) job.await() else "HANG"
    }

    private suspend fun TestScope.walkSlow(seed: Int, out: StringBuilder?, closes: Boolean) {
        val store = TestUploaderStore()
        val clock = TestClock(1_000L)
        val random = Random(seed)
        val log: MutableList<String> = mutableListOf()
        val spy = SpyStore(store, Random(seed * 31 + 7), log).also { it.slow = Random(seed * 17 + 3) }
        val enqueued: MutableList<String> = mutableListOf()
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 300L),
            ordering = { payload -> payload.substringBefore(':').takeIf { it != "free" } },
            onExecute = { context, payload ->
                log += "start@${clock.millis}:${context.id}:a${context.attempts}:c${context.claim}:d${context.wasDetached}:$payload"
                delay(random.nextLong(0L, 40L).milliseconds)
                val r: AttemptResult = when (random.nextInt(100)) {
                    in 0..54 -> AttemptResult.Success
                    in 55..79 -> AttemptResult.Retry(IllegalStateException("flaky"))
                    in 80..86 -> AttemptResult.Park("no")
                    in 87..91 -> AttemptResult.Drop("no")
                    else -> AttemptResult.Detached(random.nextLong(200L, 4_000L))
                }
                log += "answer:${context.id}:${r::class.simpleName}"
                r
            },
        )
        val ids = SequentialIds("s$seed-slow")
        val wake = TestWakeScheduler()
        fun newEngine(): UploaderEngine = createUploaderEngine(
            store = spy, handlers = listOf(handler), scope = backgroundScope, clock = clock, wakeScheduler = wake, idGenerator = ids::next,
        )
        var engine: UploaderEngine = newEngine()
        var skew = 0L
        var step = 0
        var open = true
        fun flush(label: String) {
            if (out == null) { log.clear(); return }
            out.append("s$seed.${step++} reads=${spy.reads} t=${testScheduler.currentTime} $label | ${snapshot(store)}\n")
            for (l in log) out.append("    $l\n")
            log.clear()
        }
        try {
            engine.start()
            repeat(70) {
                val cancelAfter: Long = random.nextLong(0L, 40L)
                val label: String
                when (random.nextInt(100)) {
                    in 0..39 -> {
                        val key: String? = listOf("k1", "k2", null).random(random)
                        val replace: Boolean = random.nextInt(3) == 0
                        val tag: String? = listOf("t1", "t2", null).random(random)
                        val chan: String = listOf("A", "B", "free").random(random)
                        val res: String = cancellable(cancelAfter) {
                            val id: String? = engine.enqueue(handler, "$chan:${enqueued.size}", uniqueKey = key, tag = tag, conflictPolicy = if (replace) ConflictPolicy.REPLACE else ConflictPolicy.KEEP)
                            if (id != null) enqueued += id
                            id
                        }
                        label = "enqueue($key,$chan,$tag,$replace)=$res"
                    }
                    in 40..54 -> { val id: String? = enqueued.randomOrNull(random); label = "cancel($id)=" + cancellable(cancelAfter) { id?.let { engine.cancel(it) } } }
                    in 55..62 -> { val tag: String = listOf("t1", "t2").random(random); label = "cancelByTag($tag)=" + cancellable(cancelAfter) { engine.cancelByTag(tag) } }
                    in 63..78 -> {
                        val row: UploaderItem? = store.items.filter { it.state == UploaderItemState.IN_FLIGHT }.randomOrNull(random)
                        val result: SettleResult = when (random.nextInt(4)) { 0 -> SettleResult.Delivered; 1 -> SettleResult.Drop("x"); 2 -> SettleResult.Park("x"); else -> SettleResult.Failed(null) }
                        label = "settle(${row?.id},${result::class.simpleName})=" + cancellable(100_000L) { row?.let { engine.settle(it.id, it.claim, result) } }
                    }
                    in 79..86 -> { val d: Long = random.nextLong(0L, 5_000L); skew += d; label = "skew+$d" }
                    in 87..93 -> if (closes && open) {
                        engine.close(); open = false
                        val a: String = cancellable(50) { engine.enqueue(handler, "free:x", uniqueKey = null, tag = null, conflictPolicy = ConflictPolicy.KEEP) }
                        val b: String = cancellable(50) { engine.cancelByTag("t1") }
                        val c: String = cancellable(50) { engine.drain() }
                        val d: String = cancellable(50) { engine.awaitDrained(50.milliseconds) }
                        val e: String = cancellable(50) { store.items.firstOrNull { it.state == UploaderItemState.IN_FLIGHT }?.let { engine.settle(it.id, it.claim, SettleResult.Delivered) } }
                        engine.trigger()
                        label = "closed enqueue=$a cancelByTag=$b drain=$c awaitDrained=$d settle=$e"
                    } else if (!open) { engine = newEngine(); engine.start(); open = true; label = "reopen" } else label = "noop"
                    else -> label = "idle"
                }
                var left: Long = random.nextLong(1L, 300L)
                while (left > 0L) { val sl: Long = minOf(left, 5L); advanceTimeBy(sl.milliseconds); clock.millis = 1_000L + testScheduler.currentTime + skew; left -= sl }
                flush(label)
            }
            if (!open) { engine = newEngine(); engine.start() }
            var rounds = 0
            while (store.items.any { it.state != UploaderItemState.PARKED } && rounds++ < 400) {
                skew += 5_000L
                repeat(20) { advanceTimeBy(50.milliseconds); clock.millis = 1_000L + testScheduler.currentTime + skew }
                store.items.filter { it.state == UploaderItemState.IN_FLIGHT && rounds % 3 == 0 }.forEach { engine.settle(it.id, it.claim, SettleResult.Delivered) }
                flush("quiet$rounds")
            }
            assertTrue(store.items.none { it.state != UploaderItemState.PARKED }, "slow seed $seed did not drain")
        } finally {
            engine.close()
        }
    }

    private fun runSlow(name: String, seeds: IntRange, closes: Boolean) = runTest(timeout = 600.seconds) {
        val dir: String? = System.getenv("CT_TRACE_DIR")
        val out: StringBuilder? = dir?.let { StringBuilder() }
        for (seed in seeds) walkSlow(seed, out, closes)
        if (dir != null) File(dir).also { it.mkdirs() }.resolve("$name.txt").writeText(out.toString())
    }

    @Test
    fun slowStoreWithCancelledCallers() = runSlow("slowcancel", 7_000 until 7_200, closes = false)

    @Test
    fun slowStoreWithCancelledCallersAndClosedEngineCalls() = runSlow("slowclose", 8_000 until 8_200, closes = true)

    @Test
    fun cleanWalk() = run("clean", 0 until 300, chaos = false, flaky = false, keepOverParked = false)

    @Test
    fun chaosWalk() = run("chaos", 1_000 until 1_300, chaos = true, flaky = false, keepOverParked = false)

    @Test
    fun keepOverParkedWalk() = run("keepparked", 3_000 until 3_300, chaos = true, flaky = false, keepOverParked = true)

    @Test
    fun flakyWalk() = run("flaky", 2_000 until 2_300, chaos = true, flaky = true, keepOverParked = false)

    @Test
    fun flakyWalkWithoutFailingDeletes() =
        run("flakynodelete", 4_000 until 4_300, chaos = true, flaky = true, keepOverParked = true, failDeletes = false)

    @Test
    fun flakyWalkWithQuietSettles() =
        run("flakyquiet", 5_000 until 5_300, chaos = true, flaky = true, keepOverParked = true, failDeletes = true, quietSettle = true)

    @Test
    fun flakyWalkWithQuietSettlesNoDeleteFailures() =
        run("flakyquietnd", 6_000 until 6_300, chaos = true, flaky = true, keepOverParked = true, failDeletes = false, quietSettle = true)
}
