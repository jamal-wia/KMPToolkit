package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Round 12 (mutants that survived the existing classes), derived from the contract:
 * - 04-api-reference: `elapsed` is the recorded time, "excluding paused time", republished while recording. The
 *   existing classes assert the value after a resume only through `stop()`; the value a screen shows while the
 *   recording runs again after a pause was never asserted (a ticker restarting from zero after a resume passed).
 * - 04-api-reference, `prepare`: the pre-flight checks are listed in an order and "the first failure ends the
 *   call"; a recorder that is denied the microphone and asked for a format it cannot encode must say
 *   `PermissionDenied`, not `UnsupportedFormat`.
 * - 03-guide / config: the intervals only have to be positive, so a nanosecond interval is legal and must neither
 *   hang nor publish a wrong value (virtual time: the platform delay rounds it up to a millisecond).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderLiveElapsedAndOrderR12Test {

    @Test
    fun `the elapsed flow keeps counting from the frozen value after a resume`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.timeSource += 4.seconds
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(4.seconds, fixture.recorder.elapsed.value)

        assertTrue(fixture.recorder.pause().isSuccess)
        fixture.timeSource += 10.seconds
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(4.seconds, fixture.recorder.elapsed.value, "paused time must not count")

        assertTrue(fixture.recorder.resume().isSuccess)
        fixture.timeSource += 6.seconds
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(10.seconds, fixture.recorder.elapsed.value, "the live timer after a resume lost the recorded time")
    }

    @Test
    fun `across many pauses and resumes the live elapsed value equals the recorded time and never goes back`() {
        val seed = 12_012L
        val random = Random(seed)
        runRecorderTest { fixture ->
            val seen: MutableList<Duration> = mutableListOf()
            backgroundScope.launch { fixture.recorder.elapsed.collect { seen += it } }
            runCurrent()
            fixture.recording()
            var recorded: Duration = Duration.ZERO
            repeat(12) { round: Int ->
                val segment: Duration = (random.nextInt(1, 40) * 100).milliseconds
                fixture.timeSource += segment
                recorded += segment
                advanceTimeBy(1.seconds)
                runCurrent()
                assertEquals(recorded, fixture.recorder.elapsed.value, "seed $seed round $round before the pause")
                assertTrue(fixture.recorder.pause().isSuccess, "seed $seed round $round")
                fixture.timeSource += (random.nextInt(1, 30) * 100).milliseconds
                advanceTimeBy(500.milliseconds)
                runCurrent()
                assertEquals(recorded, fixture.recorder.elapsed.value, "seed $seed round $round while paused")
                assertTrue(fixture.recorder.resume().isSuccess, "seed $seed round $round")
            }
            val finished: RecordedFile = requireNotNull(fixture.recorder.stop().getOrNull())
            assertEquals(recorded, finished.duration, "seed $seed")
            for (index: Int in 1 until seen.size) {
                assertTrue(seen[index] >= seen[index - 1], "seed $seed: the elapsed flow went back from ${seen[index - 1]} to ${seen[index]} at $index")
            }
        }
    }

    @Test
    fun `when every pre flight check would fail they are reported in the documented order`() = runRecorderTest { fixture ->
        val directory = "/data/app/r12"
        val explicit = "$directory/out.m4a"
        val engineFailure = IllegalStateException("engine refuses")
        fixture.engine.permissionGranted = false
        fixture.engine.unsupportedFormats = setOf(AudioFormat.M4A)
        fixture.fileSystem.unwritableDirectories += directory
        fixture.fileSystem.freeSpace = 0L
        fixture.engine.failures[RecorderOperation.PREPARE] = engineFailure

        assertEquals(RecorderError.PermissionDenied, fixture.recorder.prepare("relative/out.m4a").errorOrNull())
        fixture.engine.permissionGranted = true
        assertEquals(RecorderError.UnsupportedFormat(AudioFormat.M4A), fixture.recorder.prepare("relative/out.m4a").errorOrNull())
        fixture.engine.unsupportedFormats = emptySet()
        assertEquals(RecorderError.DirectoryNotWritable("relative/out.m4a"), fixture.recorder.prepare("relative/out.m4a").errorOrNull())
        assertEquals(RecorderError.DirectoryNotWritable(directory), fixture.recorder.prepare(explicit).errorOrNull())
        fixture.fileSystem.unwritableDirectories.clear()
        val storage: RecorderError? = fixture.recorder.prepare(explicit).errorOrNull()
        assertIs<RecorderError.InsufficientStorage>(storage)
        fixture.fileSystem.freeSpace = Long.MAX_VALUE
        val engine: RecorderError? = fixture.recorder.prepare(explicit).errorOrNull()
        assertIs<RecorderError.EngineFailure>(engine)
        assertSame(engineFailure, engine.cause)
        fixture.engine.failures.clear()
        assertEquals(RecorderResult.Success(explicit), fixture.recorder.prepare(explicit))
        assertEquals(RecorderState.Ready(explicit), fixture.recorder.state.value)
    }

    @Test
    fun `nanosecond intervals are legal and the recorder neither hangs nor publishes a wrong duration`() {
        val config = AudioRecorderConfig(durationUpdateInterval = 1.nanoseconds, levelUpdateInterval = 1.nanoseconds)
        runRecorderTest(config) { fixture ->
            val levels: MutableList<Float> = mutableListOf()
            backgroundScope.launch { fixture.recorder.levelSamples.collect { levels += it } }
            runCurrent()
            fixture.engine.defaultPeak = -20f
            fixture.recording()

            fixture.timeSource += 3.seconds
            advanceTimeBy(3.seconds)
            runCurrent()
            assertEquals(3.seconds, fixture.recorder.elapsed.value)
            assertTrue(levels.isNotEmpty() && levels.all { it in 0f..1f }, "levels out of range: ${levels.take(5)}")

            assertTrue(fixture.recorder.pause().isSuccess)
            assertTrue(fixture.recorder.resume().isSuccess)
            fixture.timeSource += 2.seconds
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(5.seconds, fixture.recorder.elapsed.value)
            assertEquals(RecorderResult.Success(RecordedFile(GENERATED_PATH, 5.seconds)), fixture.recorder.stop())
        }
    }
}
