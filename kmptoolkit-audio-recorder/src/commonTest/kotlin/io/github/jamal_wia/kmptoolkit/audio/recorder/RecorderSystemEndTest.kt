package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * A recording the system ends on its own: what state the recorder reaches, with what file and
 * duration, and that it happens exactly once — whichever reason, and whichever state the recorder
 * was in. The engine's events are delivered by [FakeRecorderEngine.emit], synchronously, the way
 * a platform callback arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderSystemEndTest {

    /** Reasons the engine reports directly; [InterruptionReason.MicrophoneSilenced] is debounced. */
    private val directReasons: List<InterruptionReason> = listOf(
        InterruptionReason.AudioSessionInterrupted,
        InterruptionReason.StorageLow,
        InterruptionReason.EngineDied(),
        InterruptionReason.EngineDied(platformCode = 100),
    )

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun TestScope.collectStates(fixture: RecorderFixture): List<RecorderState> {
        val states: MutableList<RecorderState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.recorder.state.collect { states += it }
        }
        return states
    }

    // --- every reason from every state ---

    @Test
    fun `every reason ends a recording as an interrupted file with the elapsed time frozen`() {
        directReasons.forEach { reason: InterruptionReason ->
            runRecorderTest { fixture ->
                val path: String = fixture.recording()
                passTime(fixture, 3.seconds)
                val releasesBefore: Int = fixture.engine.releaseCount

                fixture.engine.emit(EngineEvent.Interrupted(reason))
                runCurrent()

                assertEquals(
                    RecorderState.Interrupted(RecordedFile(path, 3.seconds), reason),
                    fixture.recorder.state.value,
                    "reason $reason",
                )
                assertEquals(1, fixture.engine.calls.count { it == "stop" }, "finalized once")
                assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "engine released once")
                assertNull(fixture.engine.listener, "the listener must be removed")
                assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths, "the file is kept")

                passTime(fixture, 5.seconds)
                assertEquals(3.seconds, fixture.recorder.elapsed.value, "elapsed stays frozen")
            }
        }
    }

    @Test
    fun `every reason ends a paused recording with the paused duration`() {
        directReasons.forEach { reason: InterruptionReason ->
            runRecorderTest { fixture ->
                val path: String = fixture.recording()
                passTime(fixture, 2.seconds)
                fixture.recorder.pause()
                passTime(fixture, 10.seconds)
                val releasesBefore: Int = fixture.engine.releaseCount

                fixture.engine.emit(EngineEvent.Interrupted(reason))
                runCurrent()

                assertEquals(
                    RecorderState.Interrupted(RecordedFile(path, 2.seconds), reason),
                    fixture.recorder.state.value,
                    "reason $reason",
                )
                assertEquals(2.seconds, fixture.recorder.elapsed.value)
                assertEquals(1, fixture.engine.calls.count { it == "stop" })
                assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
                assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
            }
        }
    }

    @Test
    fun `every reason ends a prepared but unstarted recording as a lost recording with no file`() {
        directReasons.forEach { reason: InterruptionReason ->
            runRecorderTest { fixture ->
                val path: String = fixture.prepared()
                val releasesBefore: Int = fixture.engine.releaseCount

                fixture.engine.emit(EngineEvent.Interrupted(reason))
                runCurrent()

                assertEquals(
                    RecorderState.Failed(RecorderError.RecordingLost(reason), outputPath = null),
                    fixture.recorder.state.value,
                    "reason $reason",
                )
                assertEquals(0, fixture.engine.calls.count { it == "stop" }, "nothing to finalize")
                assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
                assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths, "the empty file goes")
            }
        }
    }

    @Test
    fun `the level drops to zero and the samples stop at the transition`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -10f
        val samples: MutableList<Float> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.recorder.levelSamples.collect { samples += it }
        }
        val levels: MutableList<Float> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.recorder.level.collect { levels += it }
        }
        fixture.recording()
        passTime(fixture, 1.seconds)
        assertTrue(samples.isNotEmpty() && fixture.recorder.level.value > 0f, "metering must be running")

        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
        runCurrent()
        val samplesAtTransition: Int = samples.size
        val peakCallsAtTransition: Int = fixture.engine.peakCalls

        passTime(fixture, 2.seconds)

        assertEquals(0f, fixture.recorder.level.value)
        assertEquals(0f, levels.last())
        assertEquals(samplesAtTransition, samples.size, "no sample after the transition")
        assertEquals(peakCallsAtTransition, fixture.engine.peakCalls, "the meter must stop sampling")
    }

    @Test
    fun `the ticker belongs to the session and stops with it`() = runRecorderTest { fixture ->
        fixture.recording()
        passTime(fixture, 1.seconds)

        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
        runCurrent()
        passTime(fixture, 4.seconds)

        assertEquals(1.seconds, fixture.recorder.elapsed.value)
    }

    // --- finalize failure ---

    @Test
    fun `a recording that cannot be finalized is a lost recording whose file is kept`() {
        directReasons.forEach { reason: InterruptionReason ->
            runRecorderTest { fixture ->
                val path: String = fixture.recording()
                passTime(fixture, 2.seconds)
                val cause = IllegalStateException("moov could not be written")
                fixture.engine.failures[RecorderOperation.STOP] = cause

                fixture.engine.emit(EngineEvent.Interrupted(reason))
                runCurrent()

                val state: RecorderState = fixture.recorder.state.value
                assertIs<RecorderState.Failed>(state)
                assertEquals(path, state.outputPath)
                val error: RecorderError = state.error
                assertIs<RecorderError.RecordingLost>(error)
                assertEquals(reason, error.reason)
                assertSame(cause, error.cause)
                assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
                assertEquals(2.seconds, fixture.recorder.elapsed.value)
            }
        }
    }

    @Test
    fun `a failed finalize still releases the engine exactly once`() = runRecorderTest { fixture ->
        fixture.recording()
        val releasesBefore: Int = fixture.engine.releaseCount
        fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("boom")

        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
        runCurrent()

        assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
    }

    // --- only the first event counts ---

    @Test
    fun `only the first event of a session counts`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        passTime(fixture, 1.seconds)
        val states: List<RecorderState> = collectStates(fixture)

        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
        runCurrent()
        fixture.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
        runCurrent()

        assertEquals(
            RecorderState.Interrupted(RecordedFile(path, 1.seconds), InterruptionReason.StorageLow),
            fixture.recorder.state.value,
        )
        assertEquals(1, fixture.engine.calls.count { it == "stop" })
        assertEquals(
            listOf(RecorderState.Recording(path), fixture.recorder.state.value),
            states,
            "exactly one transition out of Recording",
        )
    }

    @Test
    fun `an event queued behind the first one never changes the published reason`() {
        // A submit already queued when the engine's listener was detached still reaches the
        // recorder; whichever way round the two reasons are, the first one stands.
        val pairs: List<Pair<InterruptionReason, InterruptionReason>> = listOf(
            InterruptionReason.EngineDied() to InterruptionReason.AudioSessionInterrupted,
            InterruptionReason.StorageLow to InterruptionReason.EngineDied(),
            InterruptionReason.AudioSessionInterrupted to InterruptionReason.EngineDied(7),
        )
        pairs.forEach { (first: InterruptionReason, queued: InterruptionReason) ->
            runRecorderTest { fixture ->
                val path: String = fixture.recording()
                passTime(fixture, 1.seconds)
                // Lands while the finalization is running, the way a callback already in flight would.
                fixture.engine.onStop = {
                    fixture.engine.emitToStaleListener(EngineEvent.Interrupted(queued))
                }

                fixture.engine.emit(EngineEvent.Interrupted(first))
                runCurrent()

                assertEquals(
                    RecorderState.Interrupted(RecordedFile(path, 1.seconds), first),
                    fixture.recorder.state.value,
                    "first $first, queued $queued",
                )
                assertEquals(1, fixture.engine.calls.count { it == "stop" })
            }
        }
    }

    @Test
    fun `an event after the recorder was released changes nothing`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.release()
        val calls: List<String> = fixture.engine.calls.toList()

        fixture.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.StorageLow))
        runCurrent()

        assertEquals(RecorderState.Released, fixture.recorder.state.value)
        assertContentEquals(calls, fixture.engine.calls)
    }

    @Test
    fun `an event from an earlier session cannot end a later one even on the same path`() =
        runRecorderTest { fixture ->
            val path = "$DEFAULT_DIRECTORY/same.m4a"
            fixture.recording(path)
            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            runCurrent()
            assertIs<RecorderState.Interrupted>(fixture.recorder.state.value)
            fixture.recording(path)
            val during: RecorderState = fixture.recorder.state.value
            assertEquals(RecorderState.Recording(path), during)

            fixture.engine.emitToStaleListener(
                EngineEvent.Interrupted(InterruptionReason.StorageLow),
                index = 0,
            )
            runCurrent()

            assertEquals(during, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.calls.count { it == "stop" }, "only the first session finalized")
        }

    @Test
    fun `the listener is registered once per session and never again after release or a refused call`() =
        runRecorderTest { fixture ->
            fixture.recording()
            assertEquals(listOf(true), fixture.engine.listenerCalls)

            // Refused calls never touch the engine's listener.
            fixture.recorder.start()
            fixture.recorder.resume()
            assertEquals(listOf(true), fixture.engine.listenerCalls)

            fixture.recorder.release()
            fixture.recorder.start()
            fixture.recorder.pause()
            fixture.recorder.resume()
            fixture.recorder.prepare()
            fixture.recorder.stop()
            fixture.recorder.cancel()
            runCurrent()

            assertEquals(listOf(true, false), fixture.engine.listenerCalls, "removed on release, never set again")
        }

    @Test
    fun `listeners are only registered after a successful prepare`() = runRecorderTest { fixture ->
        fixture.engine.failures[RecorderOperation.PREPARE] = IllegalStateException("no codec")

        fixture.recorder.prepare()

        assertEquals(0, fixture.engine.listenerRegistrations)
        assertNull(fixture.engine.listener)
    }

    // --- stop and cancel win over a system event that arrives while they run ---

    @Test
    fun `an event during a stop is dropped and the stop result stands`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        passTime(fixture, 2.seconds)
        val states: List<RecorderState> = collectStates(fixture)
        fixture.engine.onStop = {
            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            fixture.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
        }

        val result: RecorderResult<RecordedFile> = fixture.recorder.stop()
        runCurrent()

        assertEquals(RecorderResult.Success(RecordedFile(path, 2.seconds)), result)
        assertEquals(RecorderState.Completed(RecordedFile(path, 2.seconds)), fixture.recorder.state.value)
        assertTrue(states.none { it is RecorderState.Interrupted || it is RecorderState.Failed })
    }

    @Test
    fun `an event during a failing stop does not replace the stop failure`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        val releasesBefore: Int = fixture.engine.releaseCount
        val cause = IllegalStateException("encoder died")
        fixture.engine.failures[RecorderOperation.STOP] = cause
        fixture.engine.onStop = {
            // Queued behind the stop: the listener is already detached, so this is the only way an
            // event can still reach the recorder now.
            fixture.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.StorageLow))
        }

        val result: RecorderResult<RecordedFile> = fixture.recorder.stop()
        runCurrent()

        val error: RecorderError? = result.errorOrNull()
        assertIs<RecorderError.EngineFailure>(error)
        assertSame(cause, error.cause)
        assertEquals(RecorderState.Failed(error, path), fixture.recorder.state.value)
        assertEquals(1, fixture.engine.calls.count { it == "stop" }, "the engine is finalized once")
        assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "and released once")
    }

    @Test
    fun `an event during a cancel is dropped and the file is deleted`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        val releasesBefore: Int = fixture.engine.releaseCount
        val states: List<RecorderState> = collectStates(fixture)
        fixture.engine.onStop = {
            fixture.engine.emitToStaleListener(
                EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted),
            )
        }

        assertEquals(RecorderResult.Success(Unit), fixture.recorder.cancel())
        runCurrent()

        assertEquals(RecorderState.Idle, fixture.recorder.state.value)
        assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        assertTrue(states.none { it is RecorderState.Interrupted || it is RecorderState.Failed })
        assertEquals(1, fixture.engine.calls.count { it == "stop" }, "the engine is stopped once")
        assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "and released once")
    }

    @Test
    fun `an event that arrives after a stop completed is not reported as a failure`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            fixture.recorder.stop()
            val completed: RecorderState = fixture.recorder.state.value

            fixture.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
            fixture.engine.emitToStaleListener(EngineEvent.InputSilenced(true))
            passTime(fixture, 2.seconds)

            assertEquals(RecorderState.Completed(RecordedFile(path, 1.seconds)), completed)
            assertEquals(completed, fixture.recorder.state.value)
        }

    @Test
    fun `an event that arrives after a cancel completed is not reported either`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.recorder.cancel()

            fixture.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
            runCurrent()

            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
        }

    @Test
    fun `a stop and a cancel never listen to the engine while they finalize`() =
        runRecorderTest { fixture ->
            fixture.recording()
            var listenerDuringStop: Any? = "unset"
            fixture.engine.onStop = { listenerDuringStop = fixture.engine.listener }

            fixture.recorder.stop()

            assertNull(listenerDuringStop, "a normal stop must never be reported as an event")
        }

    // --- release while the slow part runs ---

    @Test
    fun `release during a stop leaves the engine to the stop and drives it once`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 2.seconds)
            val releasesBefore: Int = fixture.engine.releaseCount
            fixture.engine.onStop = { fixture.recorder.release() }

            val result: RecorderResult<RecordedFile> = fixture.recorder.stop()
            runCurrent()

            assertEquals(RecorderResult.Success(RecordedFile(path, 2.seconds)), result)
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.calls.count { it == "stop" }, "engine.stop exactly once")
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "engine.release exactly once")
            assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `release during a failing stop still releases the engine once and stays released`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount
            fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("boom")
            fixture.engine.onStop = { fixture.recorder.release() }

            val result: RecorderResult<RecordedFile> = fixture.recorder.stop()

            assertIs<RecorderError.EngineFailure>(result.errorOrNull())
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.calls.count { it == "stop" })
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
        }

    @Test
    fun `release during a cancel drives the engine once and stays released`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount
            fixture.engine.onStop = { fixture.recorder.release() }

            fixture.recorder.cancel()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.calls.count { it == "stop" })
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `release while an event is being finalized drives the engine once and stays released`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount
            fixture.engine.onStop = { fixture.recorder.release() }

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(1, fixture.engine.calls.count { it == "stop" })
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount)
        }

    @Test
    fun `a stop that starts while an event is being finalized waits and returns the interrupted file`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 2.seconds)
            var late: Deferred<RecorderResult<RecordedFile>>? = null
            fixture.engine.onStop = {
                late = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { fixture.recorder.stop() }
            }

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()

            val expected = RecordedFile(path, 2.seconds)
            assertEquals(RecorderResult.Success(expected), late?.await())
            assertEquals(
                RecorderState.Interrupted(expected, InterruptionReason.StorageLow),
                fixture.recorder.state.value,
            )
            assertEquals(1, fixture.engine.calls.count { it == "stop" })
        }

    // --- operations on an interrupted or lost recording ---

    @Test
    fun `stop from interrupted returns the same recording and leaves the state alone`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 4.seconds)
            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
            runCurrent()
            val interrupted: RecorderState = fixture.recorder.state.value
            val states: List<RecorderState> = collectStates(fixture)
            val callsBefore: List<String> = fixture.engine.calls.toList()

            val result: RecorderResult<RecordedFile> = fixture.recorder.stop()

            assertEquals(RecorderResult.Success(RecordedFile(path, 4.seconds)), result)
            assertEquals(interrupted, fixture.recorder.state.value)
            assertEquals(listOf(interrupted), states, "no emission at all")
            assertContentEquals(callsBefore, fixture.engine.calls, "the engine is not touched again")
        }

    @Test
    fun `cancel from interrupted deletes the file and returns to idle`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        passTime(fixture, 4.seconds)
        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
        runCurrent()
        val releasesBefore: Int = fixture.engine.releaseCount

        assertEquals(RecorderResult.Success(Unit), fixture.recorder.cancel())

        assertEquals(RecorderState.Idle, fixture.recorder.state.value)
        assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
        assertEquals(releasesBefore, fixture.engine.releaseCount, "the engine was already released")
    }

    @Test
    fun `cancel from a lost recording deletes the kept file and returns to idle`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("boom")
            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()
            assertIs<RecorderError.RecordingLost>((fixture.recorder.state.value as RecorderState.Failed).error)

            assertEquals(RecorderResult.Success(Unit), fixture.recorder.cancel())

            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
        }

    @Test
    fun `cancel from a failed stop deletes the kept file`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("boom")
        fixture.recorder.stop()

        assertEquals(RecorderResult.Success(Unit), fixture.recorder.cancel())

        assertEquals(RecorderState.Idle, fixture.recorder.state.value)
        assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
    }

    @Test
    fun `prepare from interrupted starts a fresh recording and never deletes the interrupted file`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            runCurrent()
            assertIs<RecorderState.Interrupted>(fixture.recorder.state.value)

            val result: RecorderResult<String> = fixture.recorder.prepare("$DEFAULT_DIRECTORY/next.m4a")

            assertEquals(RecorderResult.Success("$DEFAULT_DIRECTORY/next.m4a"), result)
            assertEquals(RecorderState.Ready("$DEFAULT_DIRECTORY/next.m4a"), fixture.recorder.state.value)
            assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
            assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
        }

    @Test
    fun `prepare from a lost recording keeps its file as well`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("boom")
        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
        runCurrent()
        fixture.engine.failures.clear()

        fixture.recorder.prepare("$DEFAULT_DIRECTORY/next.m4a")

        assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
    }

    @Test
    fun `release from interrupted keeps the file`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
        runCurrent()

        fixture.recorder.release()

        assertEquals(RecorderState.Released, fixture.recorder.state.value)
        assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
    }

    @Test
    fun `an event reported synchronously from inside start lands on the recording state`() =
        runRecorderTest { fixture ->
            val path: String = fixture.prepared()
            fixture.engine.onStart = {
                fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            }

            assertEquals(RecorderResult.Success(Unit), fixture.recorder.start())
            runCurrent()

            assertEquals(
                RecorderState.Interrupted(
                    RecordedFile(path, Duration.ZERO),
                    InterruptionReason.AudioSessionInterrupted,
                ),
                fixture.recorder.state.value,
            )
        }

    @Test
    fun `a pause while an event is being finalized is refused with the published state`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            var pauseResult: RecorderResult<Unit>? = null
            fixture.engine.onStop = { pauseResult = fixture.recorder.pause() }

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()

            assertEquals(
                RecorderError.IllegalState(RecorderState.Recording(path), RecorderOperation.PAUSE),
                pauseResult?.errorOrNull(),
            )
            assertIs<RecorderState.Interrupted>(fixture.recorder.state.value)
        }

    @Test
    fun `a start while an event is being finalized is refused with the published state`() =
        runRecorderTest { fixture ->
            val path: String = fixture.prepared()
            var startResult: RecorderResult<Unit>? = null
            // A prepared recorder has nothing to stop; the finalization that is in flight here is
            // the Ready tail, which releases the engine.
            fixture.engine.onRelease = { startResult = fixture.recorder.start() }
            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()

            assertEquals(
                RecorderError.IllegalState(RecorderState.Ready(path), RecorderOperation.START),
                startResult?.errorOrNull(),
            )
            assertIs<RecorderState.Failed>(fixture.recorder.state.value)
            assertEquals(0, fixture.engine.calls.count { it == "start" }, "the engine is never started")
        }

    @Test
    fun `a resume while an event is being finalized is refused with the published state`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 2.seconds)
            fixture.recorder.pause()
            var resumeResult: RecorderResult<Unit>? = null
            fixture.engine.onStop = { resumeResult = fixture.recorder.resume() }

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()

            assertEquals(
                RecorderError.IllegalState(
                    RecorderState.Paused(path, 2.seconds),
                    RecorderOperation.RESUME,
                ),
                resumeResult?.errorOrNull(),
            )
            assertIs<RecorderState.Interrupted>(fixture.recorder.state.value)
            assertEquals(0, fixture.engine.calls.count { it == "resume" })
        }

    // --- release while a Ready-state event is being finalized ---

    @Test
    fun `release during the finalization of a prepared recorder stays released`() =
        runRecorderTest { fixture ->
            val path: String = fixture.prepared()
            val releasesBefore: Int = fixture.engine.releaseCount
            var released = false
            fixture.engine.onRelease = {
                if (!released) {
                    released = true
                    fixture.recorder.release()
                }
            }

            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            runCurrent()

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "released once, by the finalizer")
            assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths, "the empty file is deleted")
        }
}
