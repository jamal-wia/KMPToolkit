package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * A2 round 11, Q2: every public call of the engine in every lifecycle state returns a typed result and never hangs.
 *
 * States: fresh (not started), started and idle, started with an attempt running, a tag wipe in progress (its store
 * write held), finished (queue drained), closed, closed twice, closed while an attempt ran. Calls: enqueue, observe,
 * cancel (known and unknown id), cancelByTag, trigger, drain, awaitDrained, settle, start and close. A hang is a call
 * that does not return inside 30 seconds of virtual time (the test clock runs ahead whenever everything is idle, so a
 * call parked on something that never happens is caught deterministically); any exception that is not documented is a
 * failure of the cell.
 */
class LifecycleMatrixRound11AdversarialTest {

    private enum class Stage { FRESH, STARTED_IDLE, RUNNING, WIPING, FINISHED, CLOSED, CLOSED_TWICE, CLOSED_WHILE_RUNNING }

    private class Rig(val store: TestUploaderStore, val engine: UploaderEngine, val gate: CompletableDeferred<Unit>) {
        val running: CompletableDeferred<Unit> = CompletableDeferred()
        val wipeHeld: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
    }

    private val engines: MutableList<UploaderEngine> = mutableListOf()
    private val gates: MutableList<CompletableDeferred<Unit>> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        gates.forEach { it.complete(Unit) }
        engines.forEach { it.close() }
    }

    private fun TestScope.rig(stage: Stage, n: Int): Rig {
        val store = TestUploaderStore()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        gates += gate
        val handler = TestHandler(
            type = "r11.life",
            onExecute = { _, payload ->
                if (payload.startsWith("block")) {
                    rigs[n]?.running?.complete(Unit)
                    gate.await()
                }
                AttemptResult.Success
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = TestClock(1_000L), idGenerator = SequentialIds("r11-$n")::next)
        engines += engine
        handlers[n] = handler
        return Rig(store, engine, gate).also { rigs[n] = it }
    }

    private val rigs: MutableMap<Int, Rig> = mutableMapOf()
    private val handlers: MutableMap<Int, TestHandler> = mutableMapOf()

    private suspend fun TestScope.prepare(stage: Stage, n: Int): Rig {
        val r: Rig = rig(stage, n)
        val handler: TestHandler = handlers.getValue(n)
        when (stage) {
            Stage.FRESH -> Unit
            Stage.STARTED_IDLE -> r.engine.start()
            Stage.RUNNING, Stage.CLOSED_WHILE_RUNNING -> {
                r.engine.enqueue(handler, "block-1")
                backgroundScope.launch { r.engine.drain() }
                withTimeout(30.seconds) { r.running.await() }
                if (stage == Stage.CLOSED_WHILE_RUNNING) r.engine.close()
            }

            Stage.WIPING -> {
                r.engine.enqueue(handler, "queued", tag = "wipe-tag")
                r.store.beforeNextDeleteByTag = {
                    r.wipeHeld.complete(Unit)
                    r.release.await()
                }
                backgroundScope.launch { r.engine.cancelByTag("wipe-tag") }
                withTimeout(30.seconds) { r.wipeHeld.await() }
            }

            Stage.FINISHED -> {
                r.engine.enqueue(handler, "done")
                r.engine.drain()
            }

            Stage.CLOSED -> r.engine.close()
            Stage.CLOSED_TWICE -> {
                r.engine.close()
                r.engine.close()
            }
        }
        return r
    }

    private class Call(val name: String, val run: suspend (Rig, TestHandler, CoroutineScope) -> Any?)

    private val calls: List<Call> = listOf(
        Call("enqueue") { r, h, _ -> r.engine.enqueue(h, "late") },
        Call("enqueue-with-key") { r, h, _ -> r.engine.enqueue(h, "late", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE) },
        Call("observe-first") { r, _, _ -> r.engine.observe("r11.life").first() },
        Call("cancel-unknown") { r, _, _ -> r.engine.cancel("no-such-item") },
        Call("cancel-first-item") { r, _, _ -> r.engine.cancel(r.store.items.firstOrNull()?.id ?: "none") },
        Call("cancelByTag") { r, _, _ -> r.engine.cancelByTag("wipe-tag") },
        Call("trigger") { r, _, _ -> r.engine.trigger() },
        Call("drain") { r, _, _ -> r.engine.drain() },
        Call("awaitDrained") { r, _, _ -> r.engine.awaitDrained(100.milliseconds) },
        Call("settle-unknown") { r, _, _ -> r.engine.settle("no-such-item", 0L, SettleResult.Delivered) },
        Call("settle-first-item") { r, _, _ ->
            val item: UploaderItem? = r.store.items.firstOrNull()
            r.engine.settle(item?.id ?: "none", item?.claim ?: 0L, SettleResult.Delivered)
        },
        Call("start") { r, _, _ -> r.engine.start() },
        Call("close") { r, _, _ -> r.engine.close() },
    )

    @Test
    fun `every public call in every lifecycle state returns and never hangs`() = runTest {
        var cell = 0
        val lines: MutableList<String> = mutableListOf()
        for (stage in Stage.entries) {
            for (call in calls) {
                val n: Int = cell++
                val r: Rig = prepare(stage, n)
                val handler: TestHandler = handlers.getValue(n)
                val outcome: String = try {
                    withTimeout(30.seconds) { call.run(r, handler, backgroundScope) }.let { "ok($it)" }
                } catch (e: TimeoutCancellationException) {
                    "HANG"
                } catch (e: CancellationException) {
                    "CANCELLED"
                } catch (e: Throwable) {
                    "THREW ${e::class.simpleName}: ${e.message}"
                }
                lines += "LIFECYCLE-TABLE $stage x ${call.name} -> $outcome"
                assertTrue(
                    outcome.startsWith("ok"),
                    "$stage x ${call.name}: expected a typed result, saw $outcome",
                )
                // Let everything that was held finish, so the cell leaves nothing running behind it.
                r.release.complete(Unit)
                r.gate.complete(Unit)
                r.engine.close()
            }
        }
        lines.forEach { println(it) }
        assertEquals(Stage.entries.size * calls.size, lines.size)
    }

    private suspend fun TestScope.twoEngines(drains: List<Int>): Map<String, Int> {
        val store = TestUploaderStore()
        val counts: MutableMap<String, Int> = mutableMapOf()
        val handler = TestHandler(type = "r11.two", onExecute = { _, payload ->
            counts[payload] = (counts[payload] ?: 0) + 1
            AttemptResult.Success
        })
        val a: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = TestClock(1_000L), idGenerator = SequentialIds("a")::next)
        val b: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = TestClock(1_000L), idGenerator = SequentialIds("b")::next)
        engines += a
        engines += b
        repeat(20) { i -> (if (i % 2 == 0) a else b).enqueue(handler, "p$i") }
        val jobs = drains.map { which -> launch { (if (which == 0) a else b).drain() } }
        withTimeout(30.seconds) { jobs.forEach { it.join() } }
        assertTrue(store.items.isEmpty(), "the queue is empty: ${store.items}")
        a.close()
        b.close()
        return counts
    }

    @Test
    fun `two engines over one store are the documented residual - nothing is lost and nothing hangs`() = runTest {
        // docs 07-custom-store: one store, one engine; DefaultUploaderEngine KDoc: two engines over one store stay the
        // P6 residual. This pins what that residual is: at-least-once (an item may run on both engines), never lost,
        // the queue ends empty, no call hangs, and a third overlapping drain on one of them changes none of it.
        for (drains in listOf(listOf(0, 1), listOf(0, 1, 0))) {
            val counts: Map<String, Int> = twoEngines(drains)
            assertEquals(20, counts.size, "every item ran at least once for $drains: $counts")
            assertTrue(counts.values.all { it in 1..drains.size }, "no item ran more than once per drain for $drains: $counts")
        }
    }

    @Test
    fun `an engine closed during an upload leaves the row owed and the next engine finishes it once`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(1_000L)
        val ran: MutableList<String> = mutableListOf()
        val started: CompletableDeferred<Unit> = CompletableDeferred()
        val first = TestHandler(type = "r11.close", onExecute = { _, payload ->
            ran += "first:$payload"
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            AttemptResult.Success
        })
        val a: UploaderEngine = testEngine(store, listOf(first), backgroundScope, clock = clock, idGenerator = SequentialIds("c")::next)
        engines += a
        a.enqueue(first, "x")
        val pass = launch { a.drain() }
        withTimeout(30.seconds) { started.await() }
        a.close()
        withTimeout(30.seconds) { pass.join() }
        val after: UploaderItem? = store.find("c-1")
        assertTrue(after != null, "the row is still owed after the close")
        val second = TestHandler(type = "r11.close", onExecute = { _, payload ->
            ran += "second:$payload"
            AttemptResult.Success
        })
        val b: UploaderEngine = testEngine(store, listOf(second), backgroundScope, clock = clock, idGenerator = SequentialIds("d")::next)
        engines += b
        clock.millis += 10 * 60 * 60 * 1000L
        b.drain()
        assertNull(store.find("c-1"), "the next engine delivered it: $ran")
        assertEquals(1, ran.count { it.startsWith("second:") }, "once by the next engine: $ran")
    }
}
