package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * [AudioRecorder.levelSamples]: the stream a waveform is drawn from. Unlike [AudioRecorder.level]
 * it must deliver every sample the meter takes, repeats included, because a waveform that stops
 * growing whenever the user is silent is the bug this stream exists to avoid.
 *
 * The collectors run on the recorder's own virtual-time scheduler, so "one interval later" is
 * exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderLevelSamplesTest {

    /** A running collector of [AudioRecorder.levelSamples] and everything it has received. */
    private class SampleWatcher(val job: Job, val seen: List<Float>)

    private fun TestScope.watchSamples(recorder: AudioRecorder): SampleWatcher {
        val seen: MutableList<Float> = mutableListOf()
        val job: Job = backgroundScope.launch { recorder.levelSamples.collect { seen += it } }
        runCurrent()
        return SampleWatcher(job, seen)
    }

    private fun TestScope.watchLevel(recorder: AudioRecorder): Job {
        val job: Job = backgroundScope.launch { recorder.level.collect { } }
        runCurrent()
        return job
    }

    /** Lets one [AudioRecorderConfig.levelUpdateInterval] pass and runs everything due by then. */
    private fun TestScope.tick(count: Int = 1) {
        repeat(count) {
            advanceTimeBy(AudioRecorderConfig().levelUpdateInterval)
            runCurrent()
        }
    }

    @Test
    fun `silence keeps arriving as one zero sample per interval`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = Float.NEGATIVE_INFINITY
        fixture.recording()
        val watcher: SampleWatcher = watchSamples(fixture.recorder)

        tick(5)

        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), watcher.seen)
    }

    @Test
    fun `a clamped full scale level repeats too`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = 3f
        fixture.recording()
        val watcher: SampleWatcher = watchSamples(fixture.recorder)

        tick(4)

        assertEquals(listOf(1f, 1f, 1f, 1f), watcher.seen)
    }

    @Test
    fun `the samples carry the same values as level`() = runRecorderTest { fixture ->
        fixture.engine.peakSamples += listOf(
            -10f, // the priming call, discarded
            -25f, Float.NEGATIVE_INFINITY, 0f, -37.5f, -50f,
        )
        fixture.recording()
        val samples: SampleWatcher = watchSamples(fixture.recorder)

        val levelAfterEachTick: MutableList<Float> = mutableListOf()
        repeat(5) {
            tick()
            levelAfterEachTick += fixture.recorder.level.value
        }

        assertEquals(listOf(0.5f, 0f, 1f, 0.25f, 0f), samples.seen)
        assertEquals(levelAfterEachTick, samples.seen)
    }

    @Test
    fun `nothing is taken until a sample collector arrives`() = runRecorderTest { fixture ->
        fixture.recording()

        tick(20)

        assertEquals(0, fixture.engine.peakCalls)
    }

    @Test
    fun `collecting only the samples drives metering`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        fixture.recording()
        val watcher: SampleWatcher = watchSamples(fixture.recorder)

        tick(3)

        assertEquals(4, fixture.engine.peakCalls, "one priming call and three samples")
        assertEquals(listOf(0.5f, 0.5f, 0.5f), watcher.seen)
        assertEquals(0.5f, fixture.recorder.level.value, "level follows without being collected")
    }

    @Test
    fun `collecting both shares one meter`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        fixture.recording()
        watchSamples(fixture.recorder)
        watchLevel(fixture.recorder)

        tick(3)

        assertEquals(4, fixture.engine.peakCalls, "the same calls as with a single collector")
    }

    @Test
    fun `the meter keeps running while either collector remains`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        fixture.recording()
        val samples: SampleWatcher = watchSamples(fixture.recorder)
        val levelJob: Job = watchLevel(fixture.recorder)

        samples.job.cancel()
        tick(2)
        assertEquals(0.5f, fixture.recorder.level.value)

        levelJob.cancel()
        runCurrent()
        val callsAfterLeaving: Int = fixture.engine.peakCalls
        tick(10)

        assertEquals(callsAfterLeaving, fixture.engine.peakCalls)
        assertEquals(0f, fixture.recorder.level.value)
    }

    @Test
    fun `the last sample collector leaving stops the sampling`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        fixture.recording()
        val watcher: SampleWatcher = watchSamples(fixture.recorder)
        tick(2)

        watcher.job.cancel()
        runCurrent()
        val callsAfterLeaving: Int = fixture.engine.peakCalls
        tick(10)

        assertEquals(callsAfterLeaving, fixture.engine.peakCalls)
        assertEquals(2, watcher.seen.size)
    }

    @Test
    fun `nothing is emitted before recording starts`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        val watcher: SampleWatcher = watchSamples(fixture.recorder)
        fixture.prepared()

        tick(5)

        assertTrue(watcher.seen.isEmpty())
        assertEquals(0, fixture.engine.peakCalls)
    }

    @Test
    fun `nothing is emitted while paused and samples resume after resume`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.recording()
            val watcher: SampleWatcher = watchSamples(fixture.recorder)
            tick(2)

            fixture.recorder.pause()
            tick(20)

            assertEquals(2, watcher.seen.size, "a waveform pauses with the recording")

            fixture.recorder.resume()
            tick(3)

            assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f), watcher.seen)
        }

    @Test
    fun `nothing is emitted after stop`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        fixture.recording()
        val watcher: SampleWatcher = watchSamples(fixture.recorder)
        tick(2)

        fixture.recorder.stop()
        tick(20)

        assertEquals(2, watcher.seen.size)
    }

    @Test
    fun `nothing is emitted after cancel`() = runRecorderTest { fixture ->
        fixture.engine.defaultPeak = -25f
        fixture.recording()
        val watcher: SampleWatcher = watchSamples(fixture.recorder)
        tick(2)

        fixture.recorder.cancel()
        tick(20)

        assertEquals(2, watcher.seen.size)
    }

    @Test
    fun `nothing is emitted after release and the stream does not complete`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.recording()
            val watcher: SampleWatcher = watchSamples(fixture.recorder)
            tick(2)

            fixture.recorder.release()
            tick(20)

            assertEquals(2, watcher.seen.size)
            assertTrue(watcher.job.isActive, "the stream never completes")
        }

    @Test
    fun `a collector receives only the samples taken after it subscribed`() =
        runRecorderTest { fixture ->
            fixture.engine.peakSamples += listOf(-10f, -25f, -25f, -25f, 0f, 0f)
            fixture.recording()
            val early: SampleWatcher = watchSamples(fixture.recorder)
            tick(3)

            val late: SampleWatcher = watchSamples(fixture.recorder)
            tick(2)

            assertEquals(listOf(0.5f, 0.5f, 0.5f, 1f, 1f), early.seen)
            assertEquals(listOf(1f, 1f), late.seen, "no replay of what came before")
        }

    @Test
    fun `a failing pause keeps the stream going because the recording keeps running`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -25f
            fixture.engine.failures[RecorderOperation.PAUSE] = IllegalStateException("refused")
            fixture.recording()
            val watcher: SampleWatcher = watchSamples(fixture.recorder)
            tick(2)

            fixture.recorder.pause()
            tick(2)

            assertEquals(4, watcher.seen.size)
        }

    @Test
    fun `an engine that cannot answer produces no sample`() = runRecorderTest { fixture ->
        fixture.engine.peakSamples += listOf(-10f, -25f, null, -25f)
        fixture.recording()
        val watcher: SampleWatcher = watchSamples(fixture.recorder)

        tick(3)

        assertEquals(listOf(0.5f, 0.5f), watcher.seen)
    }

    @Test
    fun `a pause landing while a sample is being taken drops that sample from the stream`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val watcher: SampleWatcher = watchSamples(fixture.recorder)
            fixture.engine.onPeak = {
                fixture.engine.onPeak = {}
                fixture.recorder.pause()
            }
            fixture.engine.peakSamples += listOf(-10f, 0f)

            tick()
            val seenAtPause: Int = watcher.seen.size
            tick(10)

            assertEquals(0, seenAtPause, "the sample taken after the pause cancelled it is dropped")
            assertEquals(seenAtPause, watcher.seen.size)
            assertEquals(0f, fixture.recorder.level.value)
        }
}
