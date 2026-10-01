package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [AudioRecorder.level]: that it reports the engine's peak mapped onto `0f..1f`, that it does so
 * only while recording *and* watched, and that it is back at `0f` — with no stale bar left behind —
 * in every other circumstance.
 *
 * The collector in these tests runs on the same virtual-time scheduler as the recorder, so "one
 * interval later" is exact and no real clock is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderLevelTest {

    /** A running collector of [AudioRecorder.level] and everything it has seen so far. */
    private class LevelWatcher(val job: Job, val seen: List<Float>)

    private fun TestScope.watchLevel(recorder: AudioRecorder): LevelWatcher {
        val seen: MutableList<Float> = mutableListOf()
        val job: Job = backgroundScope.launch { recorder.level.collect { seen += it } }
        runCurrent()
        return LevelWatcher(job, seen)
    }

    /** Lets one [AudioRecorderConfig.levelUpdateInterval] pass and runs everything due by then. */
    private fun TestScope.tick(count: Int = 1) {
        repeat(count) {
            advanceTimeBy(AudioRecorderConfig().levelUpdateInterval)
            runCurrent()
        }
    }

    @Test
    fun `level is zero before anything is recorded`() = runRecorderTest { fixture ->
        assertEquals(0f, fixture.recorder.level.value)

        fixture.prepared()

        assertEquals(0f, fixture.recorder.level.value)
    }

    @Test
    fun `a recorder nobody meters does no metering work`() = runRecorderTest { fixture ->
        fixture.recording()

        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(0, fixture.engine.peakCalls)
    }

    @Test
    fun `reading the value without collecting does not start metering`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = 0f
            fixture.recording()

            repeat(20) {
                tick()
                assertEquals(0f, fixture.recorder.level.value)
            }

            assertEquals(0, fixture.engine.peakCalls)
        }

    @Test
    fun `a collector makes the recorder publish the peak mapped onto the unit range`() =
        runRecorderTest { fixture ->
            // The first sample is the priming call and must be thrown away, however loud.
            fixture.engine.peakSamples += listOf(0f, -25f, Float.NEGATIVE_INFINITY, 0f, -50f, -37.5f)
            fixture.recording()
            watchLevel(fixture.recorder)

            assertEquals(1, fixture.engine.peakCalls, "only the priming call so far")
            assertEquals(0f, fixture.recorder.level.value, "the priming sample is discarded")

            tick()
            assertEquals(0.5f, fixture.recorder.level.value, "halfway between the floor and 0 dBFS")
            tick()
            assertEquals(0f, fixture.recorder.level.value, "digital silence")
            tick()
            assertEquals(1f, fixture.recorder.level.value, "full scale")
            tick()
            assertEquals(0f, fixture.recorder.level.value, "exactly the floor")
            tick()
            assertEquals(0.25f, fixture.recorder.level.value, "a quarter of the way up")
        }

    @Test
    fun `a value is published once per level interval and not before`() {
        val config = AudioRecorderConfig(levelUpdateInterval = 200.milliseconds)
        runRecorderTest(config) { fixture ->
            fixture.engine.peakSamples += listOf(-50f, -25f)
            fixture.recording()
            watchLevel(fixture.recorder)

            advanceTimeBy(199.milliseconds)
            runCurrent()

            assertEquals(1, fixture.engine.peakCalls)
            assertEquals(0f, fixture.recorder.level.value)

            advanceTimeBy(2.milliseconds)
            runCurrent()

            assertEquals(2, fixture.engine.peakCalls)
            assertEquals(0.5f, fixture.recorder.level.value)
        }
    }

    @Test
    fun `the configured floor decides where the bottom of the meter is`() {
        val config = AudioRecorderConfig(levelFloorDbfs = -20f)
        runRecorderTest(config) { fixture ->
            fixture.engine.peakSamples += listOf(0f, -30f, -10f)
            fixture.recording()
            watchLevel(fixture.recorder)

            tick()
            assertEquals(0f, fixture.recorder.level.value, "below the raised floor")
            tick()
            assertEquals(0.5f, fixture.recorder.level.value)
        }
    }

    @Test
    fun `an engine that cannot answer leaves the previous value in place`() =
        runRecorderTest { fixture ->
            fixture.engine.peakSamples += listOf(-10f, -25f, null, null, -50f)
            fixture.recording()
            val watcher: LevelWatcher = watchLevel(fixture.recorder)

            tick()
            assertEquals(0.5f, fixture.recorder.level.value)

            tick(2)
            assertEquals(0.5f, fixture.recorder.level.value, "no answer publishes nothing")
            assertEquals(listOf(0f, 0.5f), watcher.seen)

            tick()
            assertEquals(0f, fixture.recorder.level.value, "answering again resumes publishing")
        }

    @Test
    fun `pause drops the level to zero at once and stops asking the engine`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.recording()
            watchLevel(fixture.recorder)
            tick(2)
            assertEquals(0.5f, fixture.recorder.level.value)

            fixture.recorder.pause()

            assertEquals(0f, fixture.recorder.level.value, "no stale bar while paused")
            runCurrent()
            val callsWhenPaused: Int = fixture.engine.peakCalls

            tick(40)

            assertEquals(callsWhenPaused, fixture.engine.peakCalls)
            assertEquals(0f, fixture.recorder.level.value)
        }

    @Test
    fun `resume starts metering again with a fresh priming call`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        fixture.recording()
        watchLevel(fixture.recorder)
        tick()
        fixture.recorder.pause()
        runCurrent()
        val callsWhenPaused: Int = fixture.engine.peakCalls

        fixture.recorder.resume()
        runCurrent()

        assertEquals(callsWhenPaused + 1, fixture.engine.peakCalls, "priming call on resume")
        assertEquals(0f, fixture.recorder.level.value, "nothing published before an interval")

        tick()

        assertEquals(callsWhenPaused + 2, fixture.engine.peakCalls)
        assertEquals(0.5f, fixture.recorder.level.value)
    }

    @Test
    fun `a collector that arrives while paused starts no metering until resume`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.recording()
            fixture.recorder.pause()

            watchLevel(fixture.recorder)
            tick(5)

            assertEquals(0, fixture.engine.peakCalls)

            fixture.recorder.resume()
            tick()

            assertEquals(0.5f, fixture.recorder.level.value)
        }

    @Test
    fun `stop returns the level to zero and ends metering`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = 0f
        fixture.recording()
        watchLevel(fixture.recorder)
        tick()
        assertEquals(1f, fixture.recorder.level.value)

        fixture.recorder.stop()
        runCurrent()
        val callsAfterStop: Int = fixture.engine.peakCalls
        tick(10)

        assertEquals(0f, fixture.recorder.level.value)
        assertEquals(callsAfterStop, fixture.engine.peakCalls)
    }

    @Test
    fun `stop from paused keeps the level at zero`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = 0f
        fixture.recording()
        watchLevel(fixture.recorder)
        tick()
        fixture.recorder.pause()

        fixture.recorder.stop()
        tick(5)

        assertEquals(0f, fixture.recorder.level.value)
    }

    @Test
    fun `cancel returns the level to zero and ends metering`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = 0f
        fixture.recording()
        watchLevel(fixture.recorder)
        tick()
        assertEquals(1f, fixture.recorder.level.value)

        fixture.recorder.cancel()
        runCurrent()
        val callsAfterCancel: Int = fixture.engine.peakCalls
        tick(10)

        assertEquals(0f, fixture.recorder.level.value)
        assertEquals(callsAfterCancel, fixture.engine.peakCalls)
    }

    @Test
    fun `a failed stop leaves no level behind`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = 0f
        fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("encoder died")
        fixture.recording()
        watchLevel(fixture.recorder)
        tick()

        fixture.recorder.stop()
        tick(5)

        assertTrue(fixture.recorder.state.value is RecorderState.Failed)
        assertEquals(0f, fixture.recorder.level.value)
    }

    @Test
    fun `release zeroes the level for good and nothing is published afterwards`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = 0f
            fixture.recording()
            val watcher: LevelWatcher = watchLevel(fixture.recorder)
            tick()
            assertEquals(1f, fixture.recorder.level.value)

            fixture.recorder.release()
            runCurrent()
            val callsAfterRelease: Int = fixture.engine.peakCalls
            val seenAfterRelease: List<Float> = watcher.seen.toList()
            tick(20)

            assertEquals(0f, fixture.recorder.level.value)
            assertEquals(callsAfterRelease, fixture.engine.peakCalls)
            assertEquals(seenAfterRelease, watcher.seen)
        }

    @Test
    fun `the last collector leaving zeroes the level and stops the sampling`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.recording()
            val watcher: LevelWatcher = watchLevel(fixture.recorder)
            tick(2)
            assertEquals(0.5f, fixture.recorder.level.value)

            watcher.job.cancel()
            runCurrent()
            val callsAfterLeaving: Int = fixture.engine.peakCalls

            assertEquals(0f, fixture.recorder.level.value)
            tick(20)
            assertEquals(callsAfterLeaving, fixture.engine.peakCalls)
        }

    @Test
    fun `a collector arriving after another left starts metering again`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.recording()
            val first: LevelWatcher = watchLevel(fixture.recorder)
            tick()
            first.job.cancel()
            runCurrent()
            val callsAfterLeaving: Int = fixture.engine.peakCalls

            watchLevel(fixture.recorder)

            assertEquals(callsAfterLeaving + 1, fixture.engine.peakCalls, "priming call")
            tick()
            assertEquals(0.5f, fixture.recorder.level.value)
        }

    @Test
    fun `one collector leaving does not stop metering for another`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.recording()
            val leaving: LevelWatcher = watchLevel(fixture.recorder)
            watchLevel(fixture.recorder)

            leaving.job.cancel()
            tick(2)

            assertEquals(0.5f, fixture.recorder.level.value)
        }

    @Test
    fun `a pause that lands while a sample is being taken leaves no stale bar`() =
        runRecorderTest { fixture ->
            fixture.recording()
            watchLevel(fixture.recorder)
            // The transition runs from inside the engine call, so the sample it then returns is
            // loud and is computed after the pause already cancelled the meter.
            fixture.engine.onPeak = {
                fixture.engine.onPeak = {}
                fixture.recorder.pause()
            }
            fixture.engine.peakSamples += listOf(-10f, 0f)

            tick()

            assertEquals(
                RecorderState.Paused(GENERATED_PATH, fixture.recorder.elapsed.value),
                fixture.recorder.state.value,
            )
            assertEquals(0f, fixture.recorder.level.value, "the loud late sample must be dropped")
        }

    @Test
    fun `repeated lifecycles leave no extra jobs in the recorder's scope`() =
        runRecorderTest { fixture ->
            fixture.recording()
            watchLevel(fixture.recorder)
            val baseline: Int = fixture.scope.childCount()
            assertTrue(baseline > 0, "something must be running while recording")

            repeat(5) {
                fixture.recorder.pause()
                fixture.recorder.resume()
                fixture.recorder.pause()
                fixture.recorder.resume()
                runCurrent()
                assertEquals(baseline, fixture.scope.childCount(), "while recording")

                fixture.recorder.stop()
                runCurrent()
                assertEquals(0, fixture.scope.childCount(), "after stop")

                fixture.recording(outputPath = "/data/app/cycle_$it.m4a")
                runCurrent()
                assertEquals(baseline, fixture.scope.childCount(), "recording again")
            }
        }

    @Test
    fun `two recorders meter independently`() = runTest {
        val first = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
        val second = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
        try {
            first.engine.defaultPeak = -25f
            second.engine.defaultPeak = 0f
            first.recording()
            second.recording()
            watchLevel(first.recorder)
            watchLevel(second.recorder)

            tick()

            assertEquals(0.5f, first.recorder.level.value)
            assertEquals(1f, second.recorder.level.value)

            second.recorder.pause()

            assertEquals(0.5f, first.recorder.level.value, "pausing one must not touch the other")
            assertEquals(0f, second.recorder.level.value)
        } finally {
            first.recorder.release()
            second.recorder.release()
        }
    }

    @Test
    fun `a pause the engine refuses keeps metering because the recording keeps running`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.engine.failures[RecorderOperation.PAUSE] = IllegalStateException("not supported")
            fixture.recording()
            watchLevel(fixture.recorder)
            tick()

            val result: RecorderResult<Unit> = fixture.recorder.pause()

            assertTrue(result.errorOrNull() is RecorderError.EngineFailure)
            assertEquals(RecorderState.Recording(GENERATED_PATH), fixture.recorder.state.value)
            assertEquals(0.5f, fixture.recorder.level.value, "the bar must not be cleared")
            fixture.engine.peakSamples += -50f

            tick()

            assertEquals(0f, fixture.recorder.level.value, "and it keeps following the input")
        }

    private fun CoroutineScope.childCount(): Int =
        coroutineContext[Job]!!.children.count()
}
