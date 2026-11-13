package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The platform drain worker as WorkManager delivers it after a process restart: the OS may get there before
 * the app has built an engine, may deliver the same job again, may stop it mid-wait, and may find an engine
 * whose owner has already gone away.
 *
 * Hazard: a wake job that waits a whole drain budget on an engine that can no longer drain, or that leaves the
 * engine unusable after being stopped.
 */
@RunWith(AndroidJUnit4::class)
class UploaderDrainWorkerAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val engines: MutableList<UploaderEngine> = mutableListOf()

    @BeforeTest
    fun clean() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    @AfterTest
    fun cleanUp() {
        engines.forEach { it.close() }
        job.cancel()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private fun worker(engineWaitMillis: Long, drainBudgetMillis: Long): UploaderDrainWorker =
        TestListenableWorkerBuilder<UploaderDrainWorker>(context)
            .setInputData(
                workDataOf(
                    UploaderDrainWorker.ENGINE_WAIT_MILLIS_KEY to engineWaitMillis,
                    UploaderDrainWorker.DRAIN_BUDGET_MILLIS_KEY to drainBudgetMillis,
                ),
            )
            .build()

    private fun UploaderDrainWorker.runToEnd(): ListenableWorker.Result = startWork().get(30, TimeUnit.SECONDS)

    private fun engineOver(
        store: TestUploaderStore,
        handler: TestHandler,
        parent: Job = job,
    ): UploaderEngine = testEngine(
        store = store,
        handlers = listOf(handler),
        scope = CoroutineScope(Dispatchers.Default + parent),
        clock = UploaderClock.System,
    ).also { engines += it }

    @Test
    fun `with no engine the worker asks for a retry after its engine wait`() {
        val started: Long = System.nanoTime()
        val result = worker(engineWaitMillis = 300, drainBudgetMillis = 5_000).runToEnd()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000, "the worker waited longer than its engine wait")
    }

    @Test
    fun `an engine registered after the worker started is found within its engine wait and drains`() {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine = engineOver(store, handler)
        runBlocking { engine.enqueue(handler, "owed-from-the-last-process") }

        val pending = worker(engineWaitMillis = 5_000, drainBudgetMillis = 10_000).startWork()
        // timing-hygiene-ignore: TH1 inject the application bootstrap is late: the OS started the worker first
        Thread.sleep(300)
        engine.start()
        UploaderEngineRegistry.register(engine)

        assertEquals(ListenableWorker.Result.success(), pending.get(30, TimeUnit.SECONDS))
        assertEquals(emptyList(), store.items)
        assertEquals(listOf("owed-from-the-last-process"), handler.payloads)
    }

    @Test
    fun `eight re-delivered workers against one live engine all succeed and each item is handed off once`() {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine = engineOver(store, handler)
        runBlocking { repeat(20) { engine.enqueue(handler, "p$it") } }
        engine.start()
        UploaderEngineRegistry.register(engine)

        val futures = List(8) { worker(engineWaitMillis = 2_000, drainBudgetMillis = 10_000).startWork() }

        futures.forEach { assertEquals(ListenableWorker.Result.success(), it.get(30, TimeUnit.SECONDS)) }
        assertEquals(emptyList(), store.items)
        assertEquals(20, handler.payloads.size, "an item was run more than once: ${handler.payloads}")
        assertEquals(20, handler.payloads.toSet().size)
    }

    @Test
    fun `a worker stopped mid wait leaves the engine draining and a re-delivered worker succeeds`() {
        val store = TestUploaderStore()
        val gate = CompletableDeferred<Unit>()
        val executing = CompletableDeferred<Unit>()
        val handler = TestHandler(onExecute = { _, _ ->
            executing.complete(Unit)
            gate.await()
            AttemptResult.Success
        })
        val engine = engineOver(store, handler)
        runBlocking { engine.enqueue(handler, "blocked") }
        engine.start()
        UploaderEngineRegistry.register(engine)

        val first = worker(engineWaitMillis = 2_000, drainBudgetMillis = 20_000).startWork()
        // The engine is draining: its handler is inside the blocked attempt the first worker waits on.
        runBlocking { withTimeout(10_000) { executing.await() } }
        first.cancel(true) // WorkManager stops the job: constraints lost, or the platform deadline hit
        // timing-hygiene-ignore: TH1 inject grace for the stop to reach the worker; no event of the worker is observable
        Thread.sleep(200)
        gate.complete(Unit)

        val second = worker(engineWaitMillis = 2_000, drainBudgetMillis = 10_000).startWork()
        assertEquals(ListenableWorker.Result.success(), second.get(30, TimeUnit.SECONDS))
        assertEquals(emptyList(), store.items)
        runBlocking {
            engine.enqueue(handler, "after")
            withTimeout(10_000) { while (store.items.isNotEmpty()) delay(10) }
        }
        assertEquals(listOf("blocked", "after"), handler.payloads)
    }

    @Test
    fun `an engine whose owning scope was cancelled is not waited on for the whole drain budget`() {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val parent = Job()
        val engine = engineOver(store, handler, parent)
        engine.start()
        UploaderEngineRegistry.register(engine)
        // No event tells that the engine's loop is already running; a cancel that reaches it before or after its first
        // pass leaves the same end state (a dead owner), which is what the worker is asked about below.
        parent.cancel() // the owner is gone; nothing will ever drain through this engine again
        // Every coroutine of the owner has ended, so nothing can drain the item enqueued next.
        runBlocking { withTimeout(10_000) { parent.join() } }
        runBlocking { engine.enqueue(handler, "stranded") } // still persists: the store is independent
        assertEquals(1, store.items.size)

        val started: Long = System.nanoTime()
        val result = worker(engineWaitMillis = 300, drainBudgetMillis = 6_000).runToEnd()
        val elapsedMillis: Long = (System.nanoTime() - started) / 1_000_000

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(
            elapsedMillis < 3_000,
            "the worker sat ${elapsedMillis}ms on an engine that cannot drain (drain budget 6000ms) instead of treating it as absent",
        )
    }
}
