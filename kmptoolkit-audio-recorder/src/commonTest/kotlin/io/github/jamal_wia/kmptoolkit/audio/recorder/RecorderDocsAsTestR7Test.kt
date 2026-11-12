package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Round 7 (docs as a test): every observable-behaviour sentence of the recorder guide, the getting
 * started page and the API misuse notes, run against the real state machine on the fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderDocsAsTestR7Test {

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `pausing for a minute in the middle of a ten second recording still produces ten seconds`() =
        runRecorderTest { fixture ->
            fixture.recording()
            passTime(fixture, 10.seconds)
            assertTrue(fixture.recorder.pause().isSuccess)
            passTime(fixture, 1.minutes)
            assertTrue(fixture.recorder.resume().isSuccess)

            val file: RecordedFile? = fixture.recorder.stop().getOrNull()

            assertEquals(10.seconds, file?.duration)
        }

    @Test
    fun `a pause the platform refuses keeps the recording running and reports an engine failure`() =
        runRecorderTest { fixture ->
            val boom = IllegalStateException("pause not supported")
            val path: String = fixture.recording()
            fixture.engine.failures[RecorderOperation.PAUSE] = boom

            val result: RecorderResult<Unit> = fixture.recorder.pause()

            assertEquals(RecorderResult.Failure(RecorderError.EngineFailure(RecorderOperation.PAUSE, boom)), result)
            assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
            passTime(fixture, 2.seconds)
            assertEquals(2.seconds, fixture.recorder.elapsed.value, "the recording must keep ticking after a refused pause")
        }

    @Test
    fun `a second stop after a normal stop is illegal and carries the completed state`() = runRecorderTest { fixture ->
        fixture.recording()
        passTime(fixture, 3.seconds)
        val first: RecordedFile = requireNotNull(fixture.recorder.stop().getOrNull())

        val second: RecorderResult<RecordedFile> = fixture.recorder.stop()

        assertEquals(
            RecorderResult.Failure(
                RecorderError.IllegalState(RecorderState.Completed(first), RecorderOperation.STOP),
            ),
            second,
        )
        assertEquals(1, fixture.engine.calls.count { it == "stop" })
    }

    @Test
    fun `elapsed holds the final duration after stop until the next prepare`() = runRecorderTest { fixture ->
        fixture.recording()
        passTime(fixture, 3.seconds)
        fixture.recorder.stop()
        passTime(fixture, 5.seconds)
        assertEquals(3.seconds, fixture.recorder.elapsed.value)

        fixture.recorder.prepare()

        assertEquals(Duration.ZERO, fixture.recorder.elapsed.value)
    }

    @Test
    fun `start from paused is refused and touches nothing`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        passTime(fixture, 1.seconds)
        fixture.recorder.pause()
        val paused: RecorderState = fixture.recorder.state.value
        val callsBefore: List<String> = fixture.engine.calls.toList()

        val result: RecorderResult<Unit> = fixture.recorder.start()

        assertEquals(RecorderResult.Failure(RecorderError.IllegalState(paused, RecorderOperation.START)), result)
        assertEquals(paused, fixture.recorder.state.value)
        assertEquals(callsBefore, fixture.engine.calls)
        assertTrue(fixture.fileSystem.deletedPaths.isEmpty(), "$path must stay")
    }

    @Test
    fun `cancel from ready deletes the file and release from ready deletes it too but release from the others keeps it`() =
        runRecorderTest { fixture ->
            val ready: String = fixture.prepared()
            fixture.recorder.cancel()
            assertEquals(listOf(ready), fixture.fileSystem.deletedPaths)
            assertEquals(RecorderState.Idle, fixture.recorder.state.value)
        }

    @Test
    fun `release from ready deletes the empty file`() = runRecorderTest { fixture ->
        val ready: String = fixture.prepared()
        fixture.recorder.release()
        assertEquals(listOf(ready), fixture.fileSystem.deletedPaths)
    }

    @Test
    fun `release keeps the file from recording paused and completed`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.release()
        assertTrue(fixture.fileSystem.deletedPaths.isEmpty(), "release from Recording must keep the file")
    }

    @Test
    fun `release keeps the file from paused`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.pause()
        fixture.recorder.release()
        assertTrue(fixture.fileSystem.deletedPaths.isEmpty(), "release from Paused must keep the file")
    }

    @Test
    fun `release keeps the file from completed`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.stop()
        fixture.recorder.release()
        assertTrue(fixture.fileSystem.deletedPaths.isEmpty(), "release from Completed must keep the file")
    }

    @Test
    fun `every pre flight refusal is reported and published as the same error object`() = runRecorderTest { fixture ->
        fixture.engine.permissionGranted = false
        val denied: RecorderError? = fixture.recorder.prepare().errorOrNull()
        assertSame(denied, (fixture.recorder.state.value as RecorderState.Failed).error)
        assertEquals(RecorderError.PermissionDenied, denied)

        fixture.engine.permissionGranted = true
        fixture.engine.unsupportedFormats = setOf(AudioFormat.M4A)
        assertEquals(RecorderError.UnsupportedFormat(AudioFormat.M4A), fixture.recorder.prepare().errorOrNull())

        fixture.engine.unsupportedFormats = emptySet()
        fixture.fileSystem.freeSpace = 1L
        assertEquals(
            RecorderError.InsufficientStorage(DEFAULT_DIRECTORY, 8L * 1024 * 1024, 1L),
            fixture.recorder.prepare().errorOrNull(),
        )

        fixture.fileSystem.freeSpace = Long.MAX_VALUE
        fixture.fileSystem.unwritableDirectories += DEFAULT_DIRECTORY
        assertEquals(RecorderError.DirectoryNotWritable(DEFAULT_DIRECTORY), fixture.recorder.prepare().errorOrNull())

        fixture.fileSystem.unwritableDirectories.clear()
        val boom = RuntimeException("microphone busy")
        fixture.engine.failures[RecorderOperation.PREPARE] = boom
        val engineFailure: RecorderError? = fixture.recorder.prepare().errorOrNull()
        assertEquals(RecorderError.EngineFailure(RecorderOperation.PREPARE, boom), engineFailure)
        assertSame(boom, (engineFailure as RecorderError.EngineFailure).cause)

        fixture.engine.failures.clear()
        assertEquals(GENERATED_PATH, fixture.recorder.prepare().getOrNull(), "an EngineFailure is not fatal: prepare again")
    }

    @Test
    fun `a relative explicit path and a blank one are refused as an unwritable directory without touching the engine`() =
        runRecorderTest { fixture ->
            val relative: RecorderError? = fixture.recorder.prepare("take-1.m4a").errorOrNull()
            assertEquals(RecorderError.DirectoryNotWritable("take-1.m4a"), relative)
            val blank: RecorderError? = fixture.recorder.prepare("   ").errorOrNull()
            assertEquals(RecorderError.DirectoryNotWritable("   "), blank)
            val root: RecorderError? = fixture.recorder.prepare("/").errorOrNull()
            assertIs<RecorderError.DirectoryNotWritable>(root)
            assertTrue(fixture.engine.calls.none { it.startsWith("prepare(") }, "engine saw ${fixture.engine.calls}")
        }

    @Test
    fun `a failed stop leaves a failed state with the file and cancel then deletes it`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        val boom = IllegalStateException("moov atom")
        fixture.engine.failures[RecorderOperation.STOP] = boom

        val result: RecorderResult<RecordedFile> = fixture.recorder.stop()

        assertEquals(RecorderError.EngineFailure(RecorderOperation.STOP, boom), result.errorOrNull())
        assertEquals(
            RecorderState.Failed(RecorderError.EngineFailure(RecorderOperation.STOP, boom), path),
            fixture.recorder.state.value,
        )
        assertTrue(fixture.recorder.cancel().isSuccess)
        assertEquals(listOf(path), fixture.fileSystem.deletedPaths)
        assertEquals(RecorderState.Idle, fixture.recorder.state.value)
    }

    @Test
    fun `cancel from a failed state with no path stays illegal`() = runRecorderTest { fixture ->
        fixture.engine.permissionGranted = false
        fixture.recorder.prepare()
        val failed: RecorderState = fixture.recorder.state.value

        val result: RecorderResult<Unit> = fixture.recorder.cancel()

        assertEquals(RecorderResult.Failure(RecorderError.IllegalState(failed, RecorderOperation.CANCEL)), result)
        assertEquals(failed, fixture.recorder.state.value)
    }

    @Test
    fun `nothing is measured until somebody collects and reading the value does not count`() =
        runRecorderTest { fixture ->
            fixture.recording()
            passTime(fixture, 1.seconds)
            assertEquals(0f, fixture.recorder.level.value)
            passTime(fixture, 1.seconds)
            assertEquals(0, fixture.engine.peakCalls, "the meter read the microphone with no collector")

            val collector: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.levelSamples.collect { }
            }
            passTime(fixture, 500.milliseconds)
            assertTrue(fixture.engine.peakCalls > 0, "a levelSamples collector must start the meter")

            collector.cancel()
            runCurrent()
            passTime(fixture, 200.milliseconds)
            val settled: Int = fixture.engine.peakCalls
            passTime(fixture, 2.seconds)
            assertEquals(settled, fixture.engine.peakCalls, "the meter kept sampling after the last collector left")
            assertEquals(0f, fixture.recorder.level.value)
        }

    @Test
    fun `a collector that falls more than sixty four samples behind loses the oldest and keeps the newest`() =
        runRecorderTest { fixture ->
            val dbfs: List<Float> = List(130) { index: Int -> -50f + 0.35f * index }
            dbfs.forEach { fixture.engine.peakSamples.addLast(it) }
            fixture.engine.defaultPeak = dbfs.last()
            val hold = CompletableDeferred<Unit>()
            val received: MutableList<Float> = mutableListOf()
            fixture.recording()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.levelSamples.collect { sample: Float ->
                    received += sample
                    hold.await()
                }
            }
            runCurrent()

            repeat(140) { passTime(fixture, 50.milliseconds) }
            hold.complete(Unit)
            runCurrent()

            assertTrue(fixture.engine.peakCalls > 100, "peak calls ${fixture.engine.peakCalls}")
            assertTrue(received.size <= 65, "a slow collector keeps at most 1 + 64 samples, got ${received.size}")
            assertEquals(normalizedLevel(dbfs.last(), -50f), received.last())
        }

    @Test
    fun `a nested directory name resolves below the app private base and a prefix renames the file`() =
        runRecorderTest(
            AudioRecorderConfig(storage = RecordingStorage(directoryName = "audio/notes", fileNamePrefix = "note")),
        ) { fixture ->
            val path: String = fixture.prepared()

            assertEquals("/data/app/audio/notes/note_1700000000000_token.m4a", path)
            assertTrue("/data/app/audio/notes" in fixture.fileSystem.createdDirectories)
        }

    private fun TestScope.freshRecorder(epochClock: EpochClock): DefaultAudioRecorder = DefaultAudioRecorder(
        engine = FakeRecorderEngine(),
        fileSystem = FakeRecordingFileSystem(),
        config = AudioRecorderConfig(),
        workerContext = StandardTestDispatcher(testScheduler),
        epochClock = epochClock,
    )

    @Test
    fun `the generated token is 32 random hex characters and two recorders in the same millisecond never share a file`() =
        runRecorderTest { _ ->
            val names: MutableSet<String> = mutableSetOf()
            repeat(50) {
                val recorder: DefaultAudioRecorder = freshRecorder(EpochClock { FIXED_EPOCH_MILLIS })
                try {
                    val name: String = requireNotNull(recorder.prepare().getOrNull()).substringAfterLast('/')
                    assertTrue(
                        Regex("recording_1700000000000_[0-9a-f]{32}\\.m4a").matches(name),
                        "unexpected generated name $name",
                    )
                    names += name
                } finally {
                    recorder.release()
                }
            }
            assertEquals(50, names.size, "tokens collided")
        }

    @Test
    fun `a wall clock that steps backwards never makes two recordings share a file`() = runRecorderTest { _ ->
        var now: Long = 1_000L
        val names: MutableSet<String> = mutableSetOf()
        repeat(20) {
            val recorder: DefaultAudioRecorder = freshRecorder(EpochClock { now-- })
            try {
                names += requireNotNull(recorder.prepare().getOrNull())
            } finally {
                recorder.release()
            }
        }
        assertEquals(20, names.size)
    }

    @Test
    fun `a negative or zero config argument is refused with an illegal argument exception`() {
        val bad: List<() -> Any> = listOf(
            { AudioRecorderConfig(sampleRate = 0) },
            { AudioRecorderConfig(sampleRate = -1) },
            { AudioRecorderConfig(sampleRate = Int.MIN_VALUE) },
            { AudioRecorderConfig(channelCount = 0) },
            { AudioRecorderConfig(channelCount = -2) },
            { AudioRecorderConfig(bitRate = 0) },
            { AudioRecorderConfig(bitRate = Int.MIN_VALUE) },
            { AudioRecorderConfig(durationUpdateInterval = Duration.ZERO) },
            { AudioRecorderConfig(durationUpdateInterval = (-1).seconds) },
            { AudioRecorderConfig(durationUpdateInterval = Duration.NEGATIVE_INFINITY) },
            { AudioRecorderConfig(levelUpdateInterval = Duration.ZERO) },
            { AudioRecorderConfig(levelUpdateInterval = (-5).milliseconds) },
            { AudioRecorderConfig(levelFloorDbfs = 0f) },
            { AudioRecorderConfig(levelFloorDbfs = -0f) },
            { AudioRecorderConfig(levelFloorDbfs = 1f) },
            { AudioRecorderConfig(levelFloorDbfs = Float.NaN) },
            { AudioRecorderConfig(levelFloorDbfs = Float.NEGATIVE_INFINITY) },
            { AudioRecorderConfig(levelFloorDbfs = Float.POSITIVE_INFINITY) },
            { AudioRecorderConfig(minimumFreeSpaceBytes = -1L) },
            { AudioRecorderConfig(minimumFreeSpaceBytes = Long.MIN_VALUE) },
            { RecordingStorage(fileNamePrefix = "") },
            { RecordingStorage(fileNamePrefix = "   ") },
            { RecordingStorage(fileNamePrefix = "a/b") },
            { RecordingStorage(fileNamePrefix = "a\\b") },
            { RecordingStorage(directoryPath = "") },
            { RecordingStorage(directoryPath = "  ") },
            { RecordingStorage(directoryPath = "relative/dir") },
            { RecordingStorage(directoryName = "") },
            { RecordingStorage(directoryName = " ") },
            { RecordingStorage(directoryName = "/abs") },
            { RecordingStorage(directoryName = "..") },
            { RecordingStorage(directoryName = ".") },
            { RecordingStorage(directoryName = "a/../b") },
            { RecordingStorage(directoryName = "a/./b") },
            { RecordingStorage(directoryName = "a/..") },
            { RecordingStorage(directoryName = "../a") },
        )
        bad.forEachIndexed { index: Int, build: () -> Any ->
            assertFailsWith<IllegalArgumentException>("bad argument #$index was accepted") { build() }
        }
    }

    @Test
    fun `huge but legal config arguments are accepted`() {
        AudioRecorderConfig(sampleRate = Int.MAX_VALUE, channelCount = Int.MAX_VALUE, bitRate = Int.MAX_VALUE)
        AudioRecorderConfig(durationUpdateInterval = Duration.INFINITE, levelUpdateInterval = Duration.INFINITE)
        AudioRecorderConfig(minimumFreeSpaceBytes = Long.MAX_VALUE)
        AudioRecorderConfig(levelFloorDbfs = -Float.MAX_VALUE)
        AudioRecorderConfig(levelFloorDbfs = -Float.MIN_VALUE)
        RecordingStorage(directoryName = "...")
        RecordingStorage(directoryName = "a//b")
        RecordingStorage(fileNamePrefix = "a.b")
    }

    @Test
    fun `documented defaults and the high quality preset hold`() {
        val defaults = AudioRecorderConfig()
        assertEquals(AudioFormat.M4A, defaults.format)
        assertEquals(44_100, defaults.sampleRate)
        assertEquals(1, defaults.channelCount)
        assertEquals(128_000, defaults.bitRate)
        assertEquals(100.milliseconds, defaults.durationUpdateInterval)
        assertEquals(50.milliseconds, defaults.levelUpdateInterval)
        assertEquals(-50f, defaults.levelFloorDbfs)
        assertEquals(8L * 1024 * 1024, defaults.minimumFreeSpaceBytes)
        assertEquals("recording", defaults.storage.fileNamePrefix)
        assertNull(defaults.storage.directoryPath)
        assertNull(defaults.storage.directoryName)
        val hq: AudioRecorderConfig = AudioRecorderConfig.HIGH_QUALITY
        assertEquals(48_000, hq.sampleRate)
        assertEquals(2, hq.channelCount)
        assertEquals(256_000, hq.bitRate)
        assertEquals("m4a", AudioFormat.M4A.extension)
        assertEquals("aac", AudioFormat.AAC.extension)
        assertEquals("wav", AudioFormat.WAV.extension)
    }

    @Test
    fun `the level scale never leaves zero to one for any floor or reading`() {
        val floors: List<Float> = listOf(-Float.MIN_VALUE, -1e-30f, -1f, -50f, -120f, -Float.MAX_VALUE)
        val readings: List<Float> = listOf(
            Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, -1e30f, -120f, -50f, -49.99f, -1e-30f, -Float.MIN_VALUE,
            -0f, 0f, Float.MIN_VALUE, 3f, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN,
        )
        for (floor: Float in floors) {
            for (reading: Float in readings) {
                val level: Float = normalizedLevel(reading, floor)
                assertTrue(level in 0f..1f, "normalizedLevel($reading, $floor) = $level")
            }
        }
    }

    @Test
    fun `levelFloorDbfs at and above the floor gives zero and full scale gives one`() {
        assertEquals(0f, normalizedLevel(-50f, -50f))
        assertEquals(0f, normalizedLevel(-60f, -50f))
        assertEquals(1f, normalizedLevel(0f, -50f))
        assertEquals(0.5f, normalizedLevel(-25f, -50f))
        assertNotEquals(0f, normalizedLevel(-49f, -50f))
    }
}
