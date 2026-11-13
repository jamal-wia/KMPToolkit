package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A2 round 12, Q1: deterministic enumeration of interleavings of public engine operations.
 *
 * Every store call and every handler attempt is a suspension point that a [Pauser] can hold. A world starts one
 * operation, holds it at its k-th point, starts a second (held at its j-th point in the triples), runs a third to
 * its end, then releases the held ones in every order, one after the other. Operations: enqueue, enqueue with
 * REPLACE, enqueue with KEEP, cancel, cancelByTag, settle of a detached delivery, drain, close, trigger on a started
 * engine, and the expiry of a lease. There is no sleep and no real time: all of it runs on the test scheduler.
 *
 * Checked in every world, against the documented rules of docs 03 and 04 and the engine KDoc: no operation throws or
 * stays parked after everything is released (no hang), a withdrawn item never comes back, a plain item that was
 * enqueued and not withdrawn is run at least once (no lost row), an effect whose handler returned success is never
 * run again (no double delivery beyond the documented at-least-once rule), a recovery engine over the same store
 * empties the queue, and no job of a closed engine is left alive.
 */
class InterleavingsRound12AdversarialTest {

    private enum class Op { ENQUEUE, REPLACE, KEEP, CANCEL, TAG, SETTLE, DRAIN, CLOSE, TRIGGER, EXPIRE }

    private class Pauser {
        var calls: Int = 0
        val targets: MutableSet<Int> = mutableSetOf()
        val held: MutableList<CompletableDeferred<Unit>> = mutableListOf()

        suspend fun point() {
            val n: Int = ++calls
            if (n in targets) {
                val gate: CompletableDeferred<Unit> = CompletableDeferred()
                held += gate
                gate.await()
            }
        }
    }

    private class GatedStore(private val inner: TestUploaderStore, private val pauser: Pauser) : UploaderStore by inner {
        override suspend fun insertKeep(record: UploaderItem): KeepResult = inner.insertKeep(record).also { pauser.point() }
        override suspend fun insertReplace(record: UploaderItem): UploaderItem? = inner.insertReplace(record).also { pauser.point() }
        override suspend fun getAllActive(): List<UploaderItem> { pauser.point(); return inner.getAllActive() }
        override suspend fun getById(id: String): UploaderItem? { pauser.point(); return inner.getById(id) }
        override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            pauser.point()
            return inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim)
        }
        override suspend fun recordFailure(id: String, attempts: Int, nextRunAtEpochMillis: Long, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long, disposition: FailureDisposition): Boolean {
            pauser.point()
            return inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }
        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            pauser.point()
            return inner.park(id, lastError, expectedState, expectedClaim)
        }
        override suspend fun deleteById(id: String) { pauser.point(); inner.deleteById(id) }
        override suspend fun deleteByTag(tag: String): List<UploaderItem> { pauser.point(); return inner.deleteByTag(tag) }
    }

    private class Recorder {
        val started: MutableMap<String, Int> = mutableMapOf()
        val completed: MutableMap<String, Int> = mutableMapOf()
    }

    private var worlds: Int = 0
    private val failures: MutableList<String> = mutableListOf()
    private val scopes: MutableList<CoroutineScope> = mutableListOf()

    private suspend fun TestScope.guarded(label: String, started: Boolean, first: Op, second: Op, third: Op?, k: Int, j: Int, order: List<Int>): Triple<Boolean, Boolean, Boolean> =
        try {
            world(label, started, first, second, third, k, j, order)
        } catch (e: AssertionError) {
            failures += e.message ?: label
            Triple(true, true, true)
        } finally {
            // Every world ends here: nothing of it (a started engine's heartbeat included) may outlive it, or runTest
            // would advance virtual time forever at its end.
            scopes.forEach { it.coroutineContext[Job]!!.cancel() }
            scopes.clear()
        }

    private suspend fun TestScope.world(label: String, started: Boolean, first: Op, second: Op, third: Op?, k: Int, j: Int, order: List<Int>): Triple<Boolean, Boolean, Boolean> {
        worlds++
        val scheduler = testScheduler
        val pauser = Pauser()
        val store = TestUploaderStore()
        val clock = TestClock(1_000L)
        val rec = Recorder()
        val handlerPause: Pauser = pauser
        fun handler(): TestHandler = TestHandler(type = "r12.il", onExecute = { ctx, payload ->
            rec.started[payload] = (rec.started[payload] ?: 0) + 1
            handlerPause.point()
            if (payload == "d1" && !ctx.wasDetached) {
                AttemptResult.Detached(leaseMillis = 60_000L)
            } else {
                rec.completed[payload] = (rec.completed[payload] ?: 0) + 1
                AttemptResult.Success
            }
        })
        val engineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)).also { scopes += it }
        val opsScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)).also { scopes += it }
        val h1: TestHandler = handler()
        val engine: UploaderEngine = testEngine(GatedStore(store, pauser), listOf(h1), engineScope, clock = clock, idGenerator = SequentialIds("w")::next)
        // Setup, nothing is held yet: e1 pending (tag t, key k), d1 handed off and in flight under a lease.
        engine.enqueue(h1, "e1", uniqueKey = "k", tag = "t")!!
        val d1: String = engine.enqueue(h1, "d1")!!
        run {
            val setup = opsScope.launch { engine.drain() }
            scheduler.runCurrent()
            setup.join()
        }
        // The drain above ran e1 as well; put e1 back as a fresh pending row so it is owed again.
        rec.started.clear(); rec.completed.clear()
        val d1Item: UploaderItem = store.find(d1) ?: error("$label: d1 must be in flight after setup")
        assertEquals(UploaderItemState.IN_FLIGHT, d1Item.state, "$label: setup")
        val e1b: String = engine.enqueue(h1, "e1b", uniqueKey = "k2", tag = "t")!!
        if (started) engine.start()
        scheduler.runCurrent()
        pauser.calls = 0

        val results: MutableMap<String, String> = mutableMapOf()
        val withdrawn: MutableSet<String> = mutableSetOf()
        val enqueued: MutableSet<String> = mutableSetOf()
        var closed = false

        fun launchOp(op: Op, tag: String): Job = opsScope.launch {
            try {
                when (op) {
                    Op.ENQUEUE -> { if (engine.enqueue(h1, "n-$tag") != null) enqueued += "n-$tag" }
                    Op.REPLACE -> { if (engine.enqueue(h1, "rep-$tag", uniqueKey = "k2", conflictPolicy = ConflictPolicy.REPLACE) != null) enqueued += "rep-$tag" }
                    Op.KEEP -> { if (engine.enqueue(h1, "keep-$tag", uniqueKey = "k3", conflictPolicy = ConflictPolicy.KEEP) != null) enqueued += "keep-$tag" }
                    Op.CANCEL -> { engine.cancel(e1b); withdrawn += "e1b" }
                    Op.TAG -> { engine.cancelByTag("t"); withdrawn += "e1b" }
                    Op.SETTLE -> engine.settle(d1, d1Item.claim, SettleResult.Delivered)
                    Op.DRAIN -> engine.drain()
                    Op.CLOSE -> { engine.close(); closed = true }
                    Op.TRIGGER -> engine.trigger()
                    Op.EXPIRE -> clock.millis += 61_000L
                }
                results[tag] = "ok"
            } catch (t: Throwable) {
                results[tag] = "THREW ${t::class.simpleName}: ${t.message}"
            }
        }

        pauser.targets += k
        val a: Job = launchOp(first, "A:$first")
        scheduler.runCurrent()
        val aPaused: Boolean = pauser.held.size == 1
        var bPaused = false
        var cDone = false
        val jobs: MutableList<Job> = mutableListOf(a)
        if (aPaused || a.isCompleted.not()) {
            if (j > 0) pauser.targets += pauser.calls + j
            val b: Job = launchOp(second, "B:$second")
            jobs += b
            scheduler.runCurrent()
            bPaused = pauser.held.size == 2
            if (third != null) {
                val c: Job = launchOp(third, "C:$third")
                jobs += c
                scheduler.runCurrent()
                cDone = true
            }
        } else {
            return Triple(false, false, false)
        }
        // Release the held coroutines in the requested order, running the scheduler after each release.
        val heldNow: List<CompletableDeferred<Unit>> = pauser.held.toList()
        for (idx in order) {
            if (idx < heldNow.size) {
                heldNow[idx].complete(Unit)
                scheduler.runCurrent()
            }
        }
        // A released operation may reach the point another one was meant to be held at: release those too.
        repeat(20) {
            pauser.held.toList().forEach { it.complete(Unit) }
            scheduler.runCurrent()
        }
        scheduler.advanceTimeBy(120_000L)
        scheduler.runCurrent()

        val hung: List<String> = jobs.filter { !it.isCompleted }.map { it.toString() }
        assertTrue(hung.isEmpty(), "$label: operations still parked after release: $hung results=$results")
        assertTrue(results.values.all { it == "ok" }, "$label: an operation threw: $results")

        // Rules over the state engine 1 left behind.
        if ("e1b" in withdrawn) assertTrue(store.find(e1b) == null, "$label: a withdrawn item is still queued")
        engine.close()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(1_000L)
        scheduler.runCurrent()
        val leaked: List<Job> = engineScope.coroutineContext[Job]!!.children.toList()
        assertTrue(leaked.isEmpty(), "$label: jobs of the closed engine are still alive: $leaked")
        val before: Map<String, Int> = rec.started.toMap()

        // Recovery: a fresh engine over the same store after every lease has run out.
        clock.millis += 3_600_000L
        val recoveryScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)).also { scopes += it }
        val recHandlerCalls: Recorder = Recorder()
        val h2 = TestHandler(type = "r12.il", onExecute = { ctx, payload ->
            recHandlerCalls.started[payload] = (recHandlerCalls.started[payload] ?: 0) + 1
            AttemptResult.Success.also { recHandlerCalls.completed[payload] = (recHandlerCalls.completed[payload] ?: 0) + 1 }
        })
        val engine2: UploaderEngine = testEngine(store, listOf(h2), recoveryScope, clock = clock, idGenerator = SequentialIds("v")::next)
        val rj = opsScope.launch { engine2.drain(); engine2.drain() }
        scheduler.runCurrent()
        scheduler.advanceTimeBy(60_000L)
        scheduler.runCurrent()
        assertTrue(rj.isCompleted, "$label: the recovery drain hangs")
        assertTrue(store.items.isEmpty(), "$label: the recovery engine left rows behind: ${store.items} (results=$results)")
        engine2.close()
        scheduler.runCurrent()
        assertTrue(recoveryScope.coroutineContext[Job]!!.children.toList().isEmpty(), "$label: jobs of the recovery engine alive")

        for ((payload, n) in rec.completed) {
            val again: Int = recHandlerCalls.completed[payload] ?: 0
            if (payload != "d1") assertTrue(n + again <= 1, "$label: $payload succeeded $n times, then $again more in recovery (double delivery); started=$before")
        }
        for ((payload, n) in rec.started) {
            assertTrue(n + (recHandlerCalls.started[payload] ?: 0) <= 3, "$label: $payload started more than 3 times: $n")
        }
        if ("e1b" in withdrawn) {
            assertEquals(0, recHandlerCalls.started["e1b"] ?: 0, "$label: a withdrawn item came back in recovery")
        }
        for (payload in enqueued) {
            val overall: Int = (rec.completed[payload] ?: 0) + (recHandlerCalls.completed[payload] ?: 0)
            assertTrue(overall >= 1, "$label: $payload was enqueued, never withdrawn and never delivered (lost row); started=$before")
        }
        engineScope.coroutineContext[Job]!!.cancel()
        opsScope.coroutineContext[Job]!!.cancel()
        recoveryScope.coroutineContext[Job]!!.cancel()
        return Triple(aPaused, bPaused, cDone)
    }

    @Test
    fun `every pair of operations held at every suspension point leaves the queue consistent`() = runTest {
        var cases = 0
        for (started in listOf(false, true)) {
            for (a in Op.entries) for (b in Op.entries) {
                if (a == b && a != Op.ENQUEUE && a != Op.CANCEL) continue
                if (started && (a == Op.DRAIN || b == Op.DRAIN)) continue
                var k = 1
                while (k <= 40) {
                    val (aPaused, _, _) = guarded("pair started=$started $a@$k then $b", started, a, b, null, k, 0, listOf(0))
                    cases++
                    if (!aPaused) break
                    k++
                }
            }
        }
        println("INTERLEAVINGS pairs worlds=$cases failures=${failures.size}")
        failures.take(25).forEach { println("INTERLEAVINGS FAIL $it") }
        assertTrue(failures.isEmpty(), "pairs: ${failures.size} failing worlds, first: ${failures.firstOrNull()}")
        assertTrue(cases > 300, "the enumeration must not be vacuous: $cases")
    }

    @Test
    fun `every triple of operations with two held operations released in both orders leaves the queue consistent`() = runTest {
        val ops: List<Op> = listOf(Op.REPLACE, Op.KEEP, Op.ENQUEUE, Op.CANCEL, Op.TAG, Op.SETTLE, Op.DRAIN, Op.CLOSE, Op.EXPIRE)
        var cases = 0
        var bothHeld = 0
        for (a in ops) for (b in ops) for (c in ops) {
            if (a == b || b == c || a == c) continue
            for (k in 1..5) for (j in 1..2) for (order in listOf(listOf(0, 1), listOf(1, 0))) {
                val (aPaused, bPaused, _) = guarded("triple $a@$k $b@$j then $c order=$order", false, a, b, c, k, j, order)
                cases++
                if (aPaused && bPaused) bothHeld++
            }
        }
        println("INTERLEAVINGS triples worlds=$cases bothHeld=$bothHeld failures=${failures.size}")
        failures.take(25).forEach { println("INTERLEAVINGS FAIL $it") }
        assertTrue(failures.isEmpty(), "triples: ${failures.size} failing worlds, first: ${failures.firstOrNull()}")
        assertTrue(bothHeld > 700, "two operations must really be held together in many worlds: $bothHeld of $cases")
    }
}
