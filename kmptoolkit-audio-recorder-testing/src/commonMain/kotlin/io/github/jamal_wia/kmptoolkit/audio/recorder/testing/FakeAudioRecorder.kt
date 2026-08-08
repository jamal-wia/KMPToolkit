package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import io.github.jamal_wia.kmptoolkit.audio.recorder.AudioRecorder
import io.github.jamal_wia.kmptoolkit.audio.recorder.AudioRecorderConfig
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecordedFile
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderError
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderOperation
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderResult
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderState
import io.github.jamal_wia.kmptoolkit.audio.recorder.RecordingStorage
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An [AudioRecorder] that records nothing, touches no microphone, and needs no coroutine
 * scheduler — for testing the code *around* a recorder: a view model, a presenter, a Decompose
 * component.
 *
 * It enforces the same transition table as the real recorder, documented on [AudioRecorder], so a
 * test that passes against this fake is testing behavior the real recorder also has. What it adds
 * is control:
 *
 * - **Time does not pass by itself.** [elapsed] only moves when a test calls [advanceElapsed], so a
 *   duration assertion is exact and no virtual clock has to be advanced.
 * - **Failures are scripted.** Set [permissionGranted] to `false`, or [failNextOperationWith] to
 *   any [RecorderError], and the next operation fails with it — including error cases (a full disk,
 *   a dead encoder) that are impossible to provoke on a real device.
 * - **Effects are observable.** [preparedPaths], [deletedPaths], [completedRecordings], and
 *   [releaseCount] record what the code under test made the recorder do.
 *
 * The one place it deliberately differs: `prepare`, `stop`, and `cancel` are `suspend` to match the
 * interface but resolve immediately, so the fake never passes through [RecorderState.Preparing].
 * That state exists in the real recorder only while a suspending call is genuinely in flight, and
 * there is no filesystem here to wait on.
 *
 * Not thread-safe, exactly like the recorder it stands in for. Drive it from the test's own thread.
 *
 * ```kotlin
 * val recorder = FakeAudioRecorder()
 * val viewModel = VoiceNoteViewModel(recorder)
 *
 * viewModel.onRecordClicked()
 * recorder.advanceElapsed(3.seconds)
 * viewModel.onStopClicked()
 *
 * assertEquals(3.seconds, recorder.completedRecordings.single().duration)
 * ```
 *
 * @param config used only to generate file names the same way the real recorder does; its encoder
 *   settings are irrelevant here because nothing is encoded.
 * @param directory directory generated paths sit in, since a fake has no filesystem to resolve an
 *   app-private path against.
 */
public class FakeAudioRecorder(
    private val config: AudioRecorderConfig = AudioRecorderConfig(),
    private val directory: String = DEFAULT_FAKE_DIRECTORY,
) : AudioRecorder {

    private val _state: MutableStateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _elapsed: MutableStateFlow<Duration> = MutableStateFlow(Duration.ZERO)
    override val elapsed: StateFlow<Duration> = _elapsed.asStateFlow()

    /** When `false`, [prepare] fails with [RecorderError.PermissionDenied]. Defaults to `true`. */
    public var permissionGranted: Boolean = true

    /**
     * Error the next otherwise-legal operation fails with, then cleared. An illegal transition is
     * still reported as [RecorderError.IllegalState] and does not consume this.
     */
    public var failNextOperationWith: RecorderError? = null

    private val _preparedPaths: MutableList<String> = mutableListOf()

    /** Every path [prepare] successfully opened, in order. */
    public val preparedPaths: List<String> get() = _preparedPaths.toList()

    private val _deletedPaths: MutableList<String> = mutableListOf()

    /**
     * Every path thrown away, in order: by [cancel], by re-preparing over a file that was never
     * recorded into, and by a scripted failure of [prepare] or [start] — all the cases in which the
     * real recorder deletes the file it had opened.
     */
    public val deletedPaths: List<String> get() = _deletedPaths.toList()

    private val _completedRecordings: MutableList<RecordedFile> = mutableListOf()

    /** Every recording [stop] finished, in order. */
    public val completedRecordings: List<RecordedFile> get() = _completedRecordings.toList()

    /** How many times [release] actually did anything — at most one, since it is idempotent. */
    public var releaseCount: Int = 0
        private set

    private var released: Boolean = false
    private var sequence: Long = 0

    override suspend fun prepare(outputPath: String?): RecorderResult<String> {
        if (released) return releasedFailure(RecorderOperation.PREPARE)
        val current: RecorderState = _state.value
        when (current) {
            RecorderState.Idle,
            is RecorderState.Ready,
            is RecorderState.Completed,
            is RecorderState.Failed,
            -> Unit

            else -> return illegal(current, RecorderOperation.PREPARE)
        }
        if (current is RecorderState.Ready) _deletedPaths += current.outputPath
        _elapsed.value = Duration.ZERO

        if (!permissionGranted) return fail(RecorderError.PermissionDenied)

        val path: String = outputPath ?: generatePath()
        consumeScriptedFailure()?.let { error ->
            // The real recorder deletes the file it had already opened before the engine failed.
            _deletedPaths += path
            return fail(error)
        }

        _preparedPaths += path
        _state.value = RecorderState.Ready(path)
        return RecorderResult.Success(path)
    }

    override fun start(): RecorderResult<Unit> {
        if (released) return releasedFailure(RecorderOperation.START)
        val current: RecorderState = _state.value
        if (current !is RecorderState.Ready) return illegal(current, RecorderOperation.START)
        consumeScriptedFailure()?.let { error ->
            _deletedPaths += current.outputPath
            return fail(error)
        }

        _elapsed.value = Duration.ZERO
        _state.value = RecorderState.Recording(current.outputPath)
        return SUCCESS
    }

    override fun pause(): RecorderResult<Unit> {
        if (released) return releasedFailure(RecorderOperation.PAUSE)
        val current: RecorderState = _state.value
        if (current !is RecorderState.Recording) return illegal(current, RecorderOperation.PAUSE)
        consumeScriptedFailure()?.let { error -> return RecorderResult.Failure(error) }

        _state.value = RecorderState.Paused(current.outputPath, _elapsed.value)
        return SUCCESS
    }

    override fun resume(): RecorderResult<Unit> {
        if (released) return releasedFailure(RecorderOperation.RESUME)
        val current: RecorderState = _state.value
        if (current !is RecorderState.Paused) return illegal(current, RecorderOperation.RESUME)
        consumeScriptedFailure()?.let { error -> return RecorderResult.Failure(error) }

        _state.value = RecorderState.Recording(current.outputPath)
        return SUCCESS
    }

    override suspend fun stop(): RecorderResult<RecordedFile> {
        if (released) return releasedFailure(RecorderOperation.STOP)
        val current: RecorderState = _state.value
        val path: String = when (current) {
            is RecorderState.Recording -> current.outputPath
            is RecorderState.Paused -> current.outputPath
            else -> return illegal(current, RecorderOperation.STOP)
        }
        consumeScriptedFailure()?.let { error -> return fail(error) }

        val recording = RecordedFile(path = path, duration = _elapsed.value)
        _completedRecordings += recording
        _state.value = RecorderState.Completed(recording)
        return RecorderResult.Success(recording)
    }

    override suspend fun cancel(): RecorderResult<Unit> {
        if (released) return releasedFailure(RecorderOperation.CANCEL)
        val current: RecorderState = _state.value
        val path: String = when (current) {
            is RecorderState.Ready -> current.outputPath
            is RecorderState.Recording -> current.outputPath
            is RecorderState.Paused -> current.outputPath
            else -> return illegal(current, RecorderOperation.CANCEL)
        }
        // No scripted-failure hook: the real cancel() is best effort and always succeeds once it
        // is legal, so offering a way to fail it here would let a test assert behavior the real
        // recorder never produces.

        _deletedPaths += path
        _elapsed.value = Duration.ZERO
        _state.value = RecorderState.Idle
        return SUCCESS
    }

    override fun release() {
        if (released) return
        released = true
        releaseCount++
        _elapsed.value = Duration.ZERO
        _state.value = RecorderState.Released
    }

    /**
     * Moves [elapsed] forward by [duration], as if that much audio had been captured.
     *
     * Only meaningful while the fake is in [RecorderState.Recording] — a paused or stopped real
     * recorder does not accumulate time, so neither does this one. Calling it in any other state is
     * ignored rather than treated as an error, so a test can advance time unconditionally between
     * steps.
     */
    public fun advanceElapsed(duration: Duration) {
        require(duration >= Duration.ZERO) { "cannot rewind elapsed time, was $duration" }
        if (_state.value !is RecorderState.Recording) return
        _elapsed.value += duration
    }

    private fun generatePath(): String {
        val storage: RecordingStorage = config.storage
        sequence++
        return "$directory/${storage.fileNamePrefix}_$sequence.${config.format.extension}"
    }

    private fun consumeScriptedFailure(): RecorderError? {
        val scripted: RecorderError? = failNextOperationWith
        failNextOperationWith = null
        return scripted
    }

    private fun releasedFailure(operation: RecorderOperation): RecorderResult.Failure =
        RecorderResult.Failure(RecorderError.AlreadyReleased(operation))

    private fun illegal(
        state: RecorderState,
        operation: RecorderOperation,
    ): RecorderResult.Failure = RecorderResult.Failure(RecorderError.IllegalState(state, operation))

    private fun fail(error: RecorderError): RecorderResult.Failure {
        _state.value = RecorderState.Failed(error)
        return RecorderResult.Failure(error)
    }

    public companion object {

        /** Directory generated paths use unless the constructor is given another. */
        public const val DEFAULT_FAKE_DIRECTORY: String = "/fake/recordings"

        private val SUCCESS: RecorderResult<Unit> = RecorderResult.Success(Unit)
    }
}
