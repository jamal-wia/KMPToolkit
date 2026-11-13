package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Round 9, lifecycle chaos and files integration on the recorder's fakes: a system end whose file is
 * gone, a state collector that releases the recorder from inside a transition, and a new recorder
 * that must not inherit anything from a released or failed predecessor.
 *
 * Derived from the contract: `RecordedFile` is "an existing, closed audio file", `Interrupted` is "a
 * complete, closed file", `Released` is terminal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderLifecycleFilesR9Test {

    private val directReasons: List<InterruptionReason> = listOf(
        InterruptionReason.AudioSessionInterrupted,
        InterruptionReason.StorageLow,
        InterruptionReason.EngineDied(),
    )

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    /** Releases the engine saw since its latest `prepare`; the prepare itself first releases the previous session. */
    private fun FakeRecorderEngine.releasesSincePrepare(): Int =
        calls.takeLastWhile { !it.startsWith("prepare(") }.count { it == "release" }

    @Test
    fun `a system end whose file vanished still releases the engine once and leaves a recorder that can be reused`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            fixture.fileSystem.notRegularFiles += path
            val releasesBefore: Int = fixture.engine.releaseCount

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()

            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "the native handle is released exactly once")
            assertEquals(null, fixture.engine.listener, "the listener is removed")
            // Whatever the state is, a new recording can follow and the old file is not deleted by it.
            fixture.fileSystem.notRegularFiles -= path
            val next: RecorderResult<String> = fixture.recorder.prepare("/data/app/next.m4a")
            assertEquals(RecorderResult.Success("/data/app/next.m4a"), next)
            assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths, "the earlier file belongs to the caller")
        }

    // --- Q1 and Q2: a collector that releases the recorder from inside a transition ---

    @Test
    fun `a collector that releases the recorder on the recording state leaves it released with the engine freed once`() =
        runRecorderTest { fixture ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.state.collect { state: RecorderState ->
                    if (state is RecorderState.Recording) fixture.recorder.release()
                }
            }
            fixture.prepared()

            fixture.recorder.start()
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.releasesSincePrepare(), "the engine is freed exactly once")
            assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
            assertTrue(fixture.scope.coroutineContext[Job]!!.isCancelled, "the ticker scope is cancelled")
            assertEquals(null, fixture.engine.listener)
            fixture.recorder.release()
            assertEquals(1, fixture.engine.releasesSincePrepare(), "a second release changes nothing")
        }

    @Test
    fun `a collector that releases the recorder on the ready state frees the prepared file and the engine once`() =
        runRecorderTest { fixture ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.state.collect { state: RecorderState ->
                    if (state is RecorderState.Ready) fixture.recorder.release()
                }
            }

            fixture.recorder.prepare()
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.releasesSincePrepare())
            assertEquals(listOf(GENERATED_PATH), fixture.fileSystem.deletedPaths, "an unused prepared file is removed")
        }

    @Test
    fun `a collector that releases the recorder on the paused state keeps the audio and frees the engine once`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.state.collect { state: RecorderState ->
                    if (state is RecorderState.Paused) fixture.recorder.release()
                }
            }
            passTime(fixture, 1.seconds)

            fixture.recorder.pause()
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.releasesSincePrepare())
            assertEquals(1, fixture.engine.calls.count { it == "stop" }, "the capture is stopped once")
            assertFalse(path in fixture.fileSystem.deletedPaths, "a paused recording is audio the user made")
        }

    // --- Q3: a new recorder owes nothing to a released or failed one ---

    @Test
    fun `the flows of a released recorder stay quiet while a new recorder on the same path records`() =
        runRecorderTest { first ->
            val explicit = "/data/app/shared-r9.m4a"
            first.recording(explicit)
            passTime(first, 2.seconds)
            val firstListener: (EngineEvent) -> Unit = checkNotNull(first.engine.listener)
            first.recorder.release()

            val second = RecorderFixture(AudioRecorderConfig(), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            try {
                second.recording(explicit)
                passTime(second, 3.seconds)
                // A callback of the first session that was already in flight when its recorder let go.
                firstListener(EngineEvent.Interrupted(InterruptionReason.StorageLow))
                runCurrent()

                assertEquals(RecorderState.Released, first.recorder.state.value)
                assertEquals(Duration.ZERO, first.recorder.elapsed.value, "the old elapsed does not move")
                assertEquals(0f, first.recorder.level.value)
                assertEquals(RecorderState.Recording(explicit), second.recorder.state.value)
                assertEquals(3.seconds, second.recorder.elapsed.value, "the new elapsed is its own")
                assertEquals(1, first.engine.releasesSincePrepare())
                assertEquals(0, second.engine.releasesSincePrepare())
                assertContentEquals(emptyList(), second.fileSystem.deletedPaths)
            } finally {
                second.recorder.release()
            }
        }

    @Test
    fun `a failed stop followed by a new prepare leaves no failed state behind and keeps the failed file`() =
        runRecorderTest { fixture ->
            val states: MutableList<RecorderState> = mutableListOf()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.state.collect { states += it }
            }
            val path: String = fixture.recording()
            fixture.fileSystem.notRegularFiles += path
            val failure = assertIs<RecorderResult.Failure>(fixture.recorder.stop())
            val failed: RecorderState = fixture.recorder.state.value
            assertEquals(RecorderState.Failed(failure.error, path), failed)
            fixture.fileSystem.notRegularFiles -= path

            val nextPath: String = fixture.prepared("/data/app/after-failure.m4a")
            runCurrent()

            assertEquals(RecorderState.Ready(nextPath), fixture.recorder.state.value)
            assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
            val afterReady: List<RecorderState> = states.dropWhile { it !is RecorderState.Ready || it.outputPath != nextPath }
            assertTrue(afterReady.none { it is RecorderState.Failed }, "an old failure leaked into the new session: $states")
            assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths, "the failed file is the caller's to clear")
            assertTrue(fixture.recorder.start().isSuccess)
            assertTrue(fixture.recorder.stop().isSuccess)
        }
}
