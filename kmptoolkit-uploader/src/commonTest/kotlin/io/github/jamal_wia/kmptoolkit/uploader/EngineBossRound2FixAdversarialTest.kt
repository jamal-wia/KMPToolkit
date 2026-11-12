package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * A1 boss round 2 fix, risk points of the boss2-S2 ruling: the manual drain's stop is a plain child job of the engine.
 * The scope's end reaches the drain as surely as close(), a drain nobody stops completes its stop job and leaves only
 * the alarm as a child, and a fact (a Success) still outlives the cancelled attempt (P2).
 */
class EngineBossRound2FixAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private fun answerAfterRelease(release: CompletableDeferred<Unit>, answer: AttemptResult): TestHandler =
        TestHandler(onExecute = { _, _ ->
            release.await()
            answer
        })

    private fun alwaysFailing(): TestHandler =
        TestHandler(type = "boss2fix.failing", onExecute = { _, _ -> AttemptResult.Retry(RuntimeException("offline")) })

    @Test
    fun `a manual drain with an alarm armed is stopped synchronously when the engine's parent scope is cancelled`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val release = CompletableDeferred<Unit>()
            val handler = answerAfterRelease(release, AttemptResult.Drop("no longer wanted"))
            val failing: TestHandler = alwaysFailing()
            val engine: UploaderEngine = testEngine(inner, listOf(handler, failing), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2fixa")::next)
            engine.enqueue(failing, "f")!!
            engine.drain() // the failing item backs off: an alarm is armed in the engine's scope
            val id: String = engine.enqueue(handler, "x")!!
            val drain: Job = launch { engine.drain() }
            runCurrent()
            assertEquals(1, handler.payloads.size, "setup: the attempt is running")
            release.complete(Unit) // the handler's answer is on its way
            parent.cancel() // the scope ends instead of close()
            runCurrent()
            drain.join()
            assertFalse(drain.isCancelled, "the drain's own caller was not cancelled, so the drain must not throw")
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "the drain dropped the row after its engine's scope was cancelled")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a manual drain with an alarm armed and no close ends normally and leaves the alarm as the only active child`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val ok = TestHandler(type = "boss2fix.ok")
            val failing: TestHandler = alwaysFailing()
            val engine: UploaderEngine = testEngine(inner, listOf(ok, failing), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2fixb")::next)
            engine.enqueue(failing, "f")!!
            engine.drain()
            val engineJob: Job = parent.children.single()
            assertEquals(1, engineJob.children.count { it.isActive }, "the stop job did not complete: more than the alarm is alive")
            // The completed stop job must not have cancelled anything: a further drain still delivers.
            val id: String = engine.enqueue(ok, "y")!!
            engine.drain()
            assertEquals(1, ok.payloads.size)
            assertNull(inner.find(id), "a later drain was cancelled by the earlier stop job")
            assertEquals(1, engineJob.children.count { it.isActive })
            assertFalse(engineJob.isCancelled)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a success answered right after close with an alarm armed still deletes the row`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val release = CompletableDeferred<Unit>()
            // A handler that has already done its work: its Success is answered even though close() cancelled it.
            val handler = TestHandler(onExecute = { _, _ ->
                withContext(NonCancellable) { release.await() }
                AttemptResult.Success
            })
            val failing: TestHandler = alwaysFailing()
            val engine: UploaderEngine = testEngine(inner, listOf(handler, failing), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2fixc")::next)
            engine.enqueue(failing, "f")!!
            engine.drain()
            val id: String = engine.enqueue(handler, "x")!!
            val drain: Job = launch { engine.drain() }
            runCurrent()
            assertEquals(1, handler.payloads.size, "setup: the attempt is running")
            release.complete(Unit)
            engine.close()
            runCurrent()
            drain.join()
            assertNull(inner.find(id), "a delivered effect lost its confirming delete because close() landed first")
        } finally {
            parent.cancel()
        }
    }
}
