package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
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
    workerContext: CoroutineContext,
) {
    val engine: FakeRecorderEngine = FakeRecorderEngine()
    val fileSystem: FakeRecordingFileSystem = FakeRecordingFileSystem()
    val timeSource: TestTimeSource = TestTimeSource()

    private val defaultRecorder: DefaultAudioRecorder = DefaultAudioRecorder(
        engine = engine,
        fileSystem = fileSystem,
        config = config,
        workerContext = workerContext,
        epochClock = EpochClock { FIXED_EPOCH_MILLIS },
        timeSource = timeSource,
    )

    val recorder: AudioRecorder = defaultRecorder

    /** The scope the recorder owns, so a test can assert that [AudioRecorder.release] cancels it. */
    val scope: CoroutineScope get() = defaultRecorder.scope

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
 * The recorder is released in a `finally` — it owns its scope now, and releasing is what cancels
 * it — so a test that deliberately leaves a recording running (to assert what `release` does to it,
 * for instance) cannot leak the elapsed ticker into the next test or hang the suite waiting on it.
 * `release` is idempotent, so a test that already released is unaffected.
 *
 * The worker context is a [StandardTestDispatcher] on the test's own scheduler, so the
 * `withContext` hops inside `prepare`/`stop`/`cancel` run on virtual time like everything else.
 */
internal fun runRecorderTest(
    config: AudioRecorderConfig = AudioRecorderConfig(),
    block: suspend TestScope.(RecorderFixture) -> Unit,
): TestResult = runTest {
    val fixture = RecorderFixture(config, StandardTestDispatcher(testScheduler))
    try {
        block(fixture)
    } finally {
        fixture.recorder.release()
    }
}
