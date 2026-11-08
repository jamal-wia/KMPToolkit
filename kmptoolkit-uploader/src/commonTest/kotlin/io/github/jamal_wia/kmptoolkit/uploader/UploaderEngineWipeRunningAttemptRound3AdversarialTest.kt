package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Round 3: a logout wipe while a plain handler's attempt is running (the attempt is the engine's own coroutine). */
class UploaderEngineWipeRunningAttemptRound3AdversarialTest {

    @Test
    fun `a wipe stops the running attempt of a withdrawn plain item and lets the next account's item through`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var sawCancellation = false
        val running = CompletableDeferred<Unit>()
        val handler = TestHandler(
            onExecute = { _, payload ->
                if (payload == "account-a") {
                    running.complete(Unit)
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        sawCancellation = true
                        throw e
                    }
                }
                AttemptResult.Success
            },
        )
        val store = TestUploaderStore()
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = TestClock(1_000L),
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            engine.enqueue(handler, "account-a", tag = "a")
            runCurrent()
            assertTrue(running.isCompleted, "precondition: the attempt is running")

            engine.cancelByTag("a")
            engine.enqueue(handler, "account-b", tag = "b")
            advanceTimeBy(2.seconds)

            assertTrue(sawCancellation, "the withdrawn item's attempt kept running under the signed-out account")
            assertEquals(listOf("account-a", "account-b"), handler.payloads, "the next account's item never started")
        } finally {
            gate.complete(Unit)
            engine.close()
        }
    }
}
