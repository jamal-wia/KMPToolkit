package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 5 (lifecycle chaos): the withdrawal inside a consumer transaction that rolls back. `Uploader.cancel` promises
 * that a cancelled plain attempt "recorded nothing" and so "simply runs on the next pass" once the transaction
 * restored its row. A handler that reacts to its cancellation by returning a verdict of its own (the common
 * `catch (e: IOException) { Retry(e) }` shape) must not change that.
 */
class UploaderEngineRollbackRound5AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    @Test
    fun `a withdrawn attempt that answers Retry to its cancellation leaves the restored row unspent`() = runTest(timeout = 30.seconds) {
        val store = TestUploaderStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handler = TestHandler(
            onExecute = { _, _ ->
                started.complete(Unit)
                try {
                    release.await()
                    AttemptResult.Success
                } catch (e: CancellationException) {
                    AttemptResult.Retry(RuntimeException("socket closed"))
                }
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        val id: String = engine.enqueue(handler, "x")!!
        val original: UploaderItem = store.find(id)!!
        val drain: Job = launch { engine.drain() }
        runCurrent()
        assertEquals(true, started.isCompleted)

        engine.cancel(id)
        store.insertKeep(original) // the enclosing transaction rolled back
        runCurrent()
        drain.join()

        assertEquals(original, store.find(id), "the cancelled attempt recorded nothing")
    }

    @Test
    fun `a withdrawn attempt that answers Park to its cancellation leaves the restored row queued`() = runTest(timeout = 30.seconds) {
        val store = TestUploaderStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handler = TestHandler(
            onExecute = { _, _ ->
                started.complete(Unit)
                try {
                    release.await()
                    AttemptResult.Success
                } catch (e: CancellationException) {
                    AttemptResult.Park("interrupted")
                }
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        val id: String = engine.enqueue(handler, "x")!!
        val original: UploaderItem = store.find(id)!!
        val drain: Job = launch { engine.drain() }
        runCurrent()
        assertEquals(true, started.isCompleted)

        engine.cancel(id)
        store.insertKeep(original)
        runCurrent()
        drain.join()

        assertEquals(original, store.find(id), "a cancelled attempt must not park the restored row")
    }

    @Test
    fun `a withdrawn attempt that answers Detached to its cancellation leaves the restored row unclaimed`() = runTest(timeout = 30.seconds) {
        val store = TestUploaderStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handler = TestHandler(
            onExecute = { _, _ ->
                started.complete(Unit)
                try {
                    release.await()
                    AttemptResult.Success
                } catch (e: CancellationException) {
                    AttemptResult.Detached(60_000L)
                }
            },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        val id: String = engine.enqueue(handler, "x")!!
        val original: UploaderItem = store.find(id)!!
        val drain: Job = launch { engine.drain() }
        runCurrent()
        assertEquals(true, started.isCompleted)

        engine.cancel(id)
        store.insertKeep(original)
        runCurrent()
        drain.join()

        assertEquals(original, store.find(id), "a cancelled attempt must not claim the restored row")
    }
}
