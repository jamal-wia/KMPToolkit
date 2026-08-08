package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The promise the suspending signatures make: **an operation that can touch the filesystem
 * suspends, and does its work on the factory's `coroutineContext` rather than on the caller's
 * thread.** A `suspend` keyword over work that still blocks the caller would be worse than no
 * keyword at all, so these assert the dispatch actually happens.
 *
 * [AudioRecorder.release] is the documented exception and is asserted to stay inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderDispatchTest {

    /**
     * Wraps a dispatcher to count how many blocks were handed to it.
     *
     * [Delay] has to be delegated as well, even though nothing here inspects it: without it `delay`
     * does not recognise the wrapper as a test dispatcher and silently falls back to the real
     * clock, which would leave the elapsed ticker running in wall-clock time and make every
     * virtual-time assertion in this suite meaningless. Resuming from a delay goes through [Delay]
     * rather than [dispatch], so it is deliberately not counted.
     */
    @OptIn(InternalCoroutinesApi::class)
    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher(), Delay {

        var dispatches: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }

        override fun scheduleResumeAfterDelay(
            timeMillis: Long,
            continuation: CancellableContinuation<Unit>,
        ) {
            (delegate as Delay).scheduleResumeAfterDelay(timeMillis, continuation)
        }
    }

    private fun runDispatchTest(
        block: suspend TestScope.(RecorderFixture, CountingDispatcher) -> Unit,
    ) = runTest {
        val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val fixture = RecorderFixture(AudioRecorderConfig(), dispatcher)
        try {
            block(fixture, dispatcher)
        } finally {
            fixture.recorder.release()
        }
    }

    @Test
    fun `prepare does its filesystem work on the injected context`() =
        runDispatchTest { fixture, dispatcher ->
            val before: Int = dispatcher.dispatches

            fixture.recorder.prepare()

            assertTrue(
                dispatcher.dispatches > before,
                "opening the directory and the file must not run on the caller's thread",
            )
        }

    @Test
    fun `stop finalizes the container on the injected context`() =
        runDispatchTest { fixture, dispatcher ->
            fixture.recording()
            val before: Int = dispatcher.dispatches

            fixture.recorder.stop()

            assertTrue(
                dispatcher.dispatches > before,
                "writing the moov atom is the slowest call in the module and must be dispatched",
            )
        }

    @Test
    fun `cancel deletes the partial file on the injected context`() =
        runDispatchTest { fixture, dispatcher ->
            fixture.recording()
            val before: Int = dispatcher.dispatches

            fixture.recorder.cancel()

            assertTrue(dispatcher.dispatches > before)
        }

    @Test
    fun `the elapsed ticker runs on the injected context`() =
        runDispatchTest { fixture, dispatcher ->
            assertSame(
                dispatcher,
                fixture.scope.coroutineContext[ContinuationInterceptor],
                "the ticker's scope must be built from the context the factory was given",
            )

            // And it really is the ticker that scope runs: the injected context is what makes the
            // tick land on virtual time rather than the wall clock.
            fixture.recording()
            fixture.timeSource += 1.seconds
            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(1.seconds, fixture.recorder.elapsed.value)
        }

    @Test
    fun `release does its teardown inline because a teardown path cannot launch a coroutine`() =
        runDispatchTest { fixture, _ ->
            fixture.recording()
            runCurrent()
            val callsBefore: List<String> = fixture.engine.calls.toList()

            fixture.recorder.release()

            // Asserted without pumping the scheduler: release is not suspending, so by the time it
            // returns the native recorder must already be stopped and freed. Anything it had handed
            // to the worker context would still be sitting in the queue right now, and the caller —
            // an onCleared or a deinit — would be gone before it ran.
            assertContentEquals(
                callsBefore + listOf("stop", "release"),
                fixture.engine.calls,
                "the handle must be freed by the time release() returns, not eventually",
            )
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
        }

    @Test
    fun `stop has not touched the engine until the worker context runs it`() =
        runDispatchTest { fixture, _ ->
            fixture.recording()
            runCurrent()
            val callsBefore: List<String> = fixture.engine.calls.toList()

            val stopping: Job = launch { fixture.recorder.stop() }
            // The launch itself has not started yet, let alone reached the engine.
            assertContentEquals(callsBefore, fixture.engine.calls)

            stopping.join()

            assertTrue("stop" in fixture.engine.calls.drop(callsBefore.size))
        }

    @Test
    fun `the operations that only move recorder state do not dispatch at all`() =
        runDispatchTest { fixture, dispatcher ->
            fixture.prepared()
            runCurrent()
            val beforeStart: Int = dispatcher.dispatches

            // Each of start and resume launches the ticker, which is exactly one dispatch. Neither
            // pause nor either of them does any filesystem work, so nothing else may be dispatched.
            fixture.recorder.start()
            assertEquals(beforeStart + 1, dispatcher.dispatches, "start launched the ticker")

            fixture.recorder.pause()
            assertEquals(beforeStart + 1, dispatcher.dispatches, "pause is a pure state flip")

            fixture.recorder.resume()
            assertEquals(beforeStart + 2, dispatcher.dispatches, "resume relaunched the ticker")

            fixture.recorder.pause()
            assertEquals(beforeStart + 2, dispatcher.dispatches, "pause is a pure state flip")
        }
}
