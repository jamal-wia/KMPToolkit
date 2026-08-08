package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** A fixed timestamp, so a generated file name is exactly predictable in a test. */
internal const val FIXED_EPOCH_MILLIS: Long = 1_700_000_000_000L

/** The file name [FIXED_EPOCH_MILLIS] and the default storage settings produce. */
internal const val GENERATED_FILE_NAME: String = "recording_1700000000000.m4a"

/** Directory the default storage settings resolve to with [FakeRecordingFileSystem]'s defaults. */
internal const val DEFAULT_DIRECTORY: String = "/data/app/com.example.consumer"

/** Full path the default configuration generates. */
internal const val GENERATED_PATH: String = "$DEFAULT_DIRECTORY/$GENERATED_FILE_NAME"

/**
 * A [DefaultAudioRecorder] wired to fakes for both platform seams and to a [TestTimeSource] the
 * test drives by hand, so elapsed time is exact rather than sampled from a real clock.
 */
@OptIn(ExperimentalTime::class)
internal class RecorderFixture(
    config: AudioRecorderConfig,
    val scope: CoroutineScope,
) {
    val engine: FakeRecorderEngine = FakeRecorderEngine()
    val fileSystem: FakeRecordingFileSystem = FakeRecordingFileSystem()
    val timeSource: TestTimeSource = TestTimeSource()

    val recorder: AudioRecorder = DefaultAudioRecorder(
        engine = engine,
        fileSystem = fileSystem,
        config = config,
        scope = scope,
        epochClock = EpochClock { FIXED_EPOCH_MILLIS },
        timeSource = timeSource,
    )

    /** Drives the recorder to [RecorderState.Ready] and returns the prepared path. */
    suspend fun prepared(outputPath: String? = null): String {
        val result: RecorderResult<String> = recorder.prepare(outputPath)
        return requireNotNull(result.getOrNull()) { "prepare failed: ${result.errorOrNull()}" }
    }

    /** Drives the recorder to [RecorderState.Recording] and returns the recording's path. */
    suspend fun recording(outputPath: String? = null): String {
        val path: String = prepared(outputPath)
        check(recorder.start().isSuccess) { "start failed" }
        return path
    }
}

/**
 * Runs [block] against a fresh [RecorderFixture] on the test scheduler.
 *
 * The recorder's scope is created here and cancelled in a `finally`, so a test that deliberately
 * leaves a recording running (to assert what `release` does to it, for instance) cannot leak the
 * elapsed ticker into the next test or hang the suite waiting on it.
 */
internal fun runRecorderTest(
    config: AudioRecorderConfig = AudioRecorderConfig(),
    block: suspend TestScope.(RecorderFixture) -> Unit,
): TestResult = runTest {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
    try {
        block(RecorderFixture(config, scope))
    } finally {
        scope.cancel()
    }
}
