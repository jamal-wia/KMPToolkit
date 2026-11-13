package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Boss round 1 of unit C: the stale-epoch fences of the silence debounce and of the elapsed ticker
 * (r12 survivors M20 and M35), and the listener residue after a re-prepare from Ready (R11-04).
 *
 * The interleaving the fences guard against is a debounce or tick whose `submit` is queued behind a
 * lock holder that ends the silence or pauses first. It is produced deterministically on one thread:
 * the meter's `peakDbfs()` runs under the lock, so a hook inside it queues an engine event (the
 * holder's own submits are deferred to the end of its action), then advances the virtual clock so the
 * debounce or the ticker resumes and queues its own submit behind it. No real thread, no sleep.
 */
@OptIn(ExperimentalTime::class)
class RecorderStaleEpochBoss1Test {

    private class Rig(scope: TestScope, config: AudioRecorderConfig, silenceDebounce: Duration) {
        val engine: FakeRecorderEngine = FakeRecorderEngine()
        val fileSystem: FakeRecordingFileSystem = FakeRecordingFileSystem()
        val timeSource: TestTimeSource = TestTimeSource()
        val recorder: DefaultAudioRecorder = DefaultAudioRecorder(
            engine = engine,
            fileSystem = fileSystem,
            config = config,
            workerContext = StandardTestDispatcher(scope.testScheduler),
            epochClock = EpochClock { FIXED_EPOCH_MILLIS },
            fileNameToken = FileNameToken { FIXED_FILE_NAME_TOKEN },
            timeSource = timeSource,
            silenceDebounce = silenceDebounce,
        )
    }

    private val config: AudioRecorderConfig = AudioRecorderConfig(
        durationUpdateInterval = 200.milliseconds,
        levelUpdateInterval = 100.milliseconds,
    )

    @Test
    fun `a debounce that fires behind an unsilence queued first does not end the recording`() = runTest {
        val rig = Rig(this, config, silenceDebounce = 500.milliseconds)
        try {
            assertTrue(rig.recorder.prepare(null).isSuccess)
            assertTrue(rig.recorder.start().isSuccess)
            val watcher: Job = backgroundScope.launch { rig.recorder.level.collect { } }
            runCurrent()
            rig.engine.emit(EngineEvent.InputSilenced(true))
            runCurrent()
            var fired = false
            rig.engine.onPeak = {
                rig.engine.onPeak = {}
                fired = true
                // Under the lock: the unsilence is queued first, then the debounce resumes and queues
                // its own submit behind it, carrying the epoch the unsilence is about to retire.
                rig.engine.emit(EngineEvent.InputSilenced(false))
                testScheduler.advanceTimeBy(600.milliseconds)
            }
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            watcher.cancel()

            assertTrue(fired, "the meter sample with the hook never ran")
            assertIs<RecorderState.Recording>(
                rig.recorder.state.value,
                "a silence that ended before its debounce ran must not end the recording",
            )
            assertEquals(emptyList(), rig.engine.calls.filter { call: String -> call == "stop" })
        } finally {
            rig.recorder.release()
        }
    }

    @Test
    fun `a tick computed before a pause and queued behind it does not overwrite the frozen elapsed`() = runTest {
        val rig = Rig(this, config, silenceDebounce = 500.milliseconds)
        try {
            assertTrue(rig.recorder.prepare(null).isSuccess)
            assertTrue(rig.recorder.start().isSuccess)
            val watcher: Job = backgroundScope.launch { rig.recorder.level.collect { } }
            runCurrent()
            var fired = false
            rig.engine.onPeak = {
                rig.engine.onPeak = {}
                fired = true
                // The ticker resumes inside the lock holder's action and queues a tick of 130 ms.
                rig.timeSource += 130.milliseconds
                testScheduler.advanceTimeBy(150.milliseconds)
                // Then a pause lands (re-entrant, inline) and freezes elapsed at 150 ms.
                rig.timeSource += 20.milliseconds
                check(rig.recorder.pause().isSuccess) { "pause failed" }
            }
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            watcher.cancel()

            assertTrue(fired, "the meter sample with the hook never ran")
            val paused: RecorderState.Paused = assertIs(rig.recorder.state.value)
            assertEquals(150.milliseconds, paused.elapsed)
            assertEquals(
                paused.elapsed,
                rig.recorder.elapsed.value,
                "a tick computed before the pause overwrote the elapsed the pause froze",
            )
        } finally {
            rig.recorder.release()
        }
    }

    @Test
    fun `events through the listener of a session replaced by a re-prepare from Ready change nothing`() = runTest {
        val rig = Rig(this, config, silenceDebounce = 100.milliseconds)
        try {
            val first: String = requireNotNull(rig.recorder.prepare("/data/app/a.m4a").getOrNull())
            val staleIndex: Int = rig.engine.registeredListeners.lastIndex
            assertTrue(staleIndex >= 0, "prepare registered no listener")
            val second: String = requireNotNull(rig.recorder.prepare("/data/app/b.m4a").getOrNull())
            runCurrent()
            val staleEvents: List<EngineEvent> = listOf(
                EngineEvent.InputSilenced(true),
                EngineEvent.Interrupted(InterruptionReason.StorageLow),
                EngineEvent.Interrupted(InterruptionReason.MicrophoneSilenced),
                EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted),
                EngineEvent.Interrupted(InterruptionReason.EngineDied(platformCode = 100)),
            )

            fun storm() {
                for (event: EngineEvent in staleEvents) rig.engine.emitToStaleListener(event, staleIndex)
            }

            storm()
            runCurrent()
            assertEquals(RecorderState.Ready(second), rig.recorder.state.value)

            assertTrue(rig.recorder.start().isSuccess)
            storm()
            advanceTimeBy(300.milliseconds)
            runCurrent()
            assertIs<RecorderState.Recording>(rig.recorder.state.value, "a stale listener ended the recording")

            assertTrue(rig.recorder.pause().isSuccess)
            storm()
            runCurrent()
            assertIs<RecorderState.Paused>(rig.recorder.state.value, "a stale listener ended the paused recording")
            assertEquals(emptyList(), rig.engine.calls.filter { call: String -> call == "stop" })
            assertTrue(first != second)

            // The current listener still works: the same event through it ends the session.
            assertTrue(rig.recorder.resume().isSuccess)
            rig.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertIs<RecorderState.Interrupted>(rig.recorder.state.value)
        } finally {
            rig.recorder.release()
        }
    }

    @Test
    fun `an event through the listener of a session whose start failed leaves Failed untouched`() = runTest {
        val rig = Rig(this, config, silenceDebounce = 100.milliseconds)
        try {
            assertTrue(rig.recorder.prepare("/data/app/a.m4a").isSuccess)
            rig.engine.failures[RecorderOperation.START] = IllegalStateException("boom")
            assertTrue(!rig.recorder.start().isSuccess)
            val failed: RecorderState = rig.recorder.state.value
            assertIs<RecorderState.Failed>(failed)
            val releasesBefore: Int = rig.engine.releaseCount
            val callsBefore: List<String> = rig.engine.calls.toList()

            rig.engine.emitToStaleListener(EngineEvent.InputSilenced(true))
            rig.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            advanceTimeBy(300.milliseconds)
            runCurrent()

            assertEquals(failed, rig.recorder.state.value)
            assertEquals(releasesBefore, rig.engine.releaseCount)
            assertEquals(callsBefore, rig.engine.calls.toList())
        } finally {
            rig.recorder.release()
        }
    }
}
