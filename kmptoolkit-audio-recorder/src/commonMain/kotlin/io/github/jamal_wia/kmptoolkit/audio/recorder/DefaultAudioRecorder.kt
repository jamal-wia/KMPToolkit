package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The whole of this module's behavior: the transition table from [AudioRecorder], the pre-checks
 * that turn a doomed recording into a typed error before the microphone is touched, and the
 * elapsed-time bookkeeping. Everything platform-specific is behind [RecorderEngine] and
 * [RecordingFileSystem], which is what lets all of it be tested on the JVM and in the iOS
 * simulator against fakes.
 *
 * Not thread-safe by design — see the threading note on [AudioRecorder]. Every mutable field below
 * is read and written only from the caller's thread; the ticker coroutine is handed its start mark
 * by value and writes nothing but [_elapsed], so it shares no mutable state with the caller.
 *
 * @param scope owned by this recorder and cancelled by [release]; it exists solely to run the
 *   [elapsed] ticker.
 */
internal class DefaultAudioRecorder(
    private val engine: RecorderEngine,
    private val fileSystem: RecordingFileSystem,
    private val config: AudioRecorderConfig,
    private val scope: CoroutineScope,
    private val epochClock: EpochClock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AudioRecorder {

    private val _state: MutableStateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _elapsed: MutableStateFlow<Duration> = MutableStateFlow(Duration.ZERO)
    override val elapsed: StateFlow<Duration> = _elapsed.asStateFlow()

    private var segmentStart: TimeMark? = null
    private var completedSegments: Duration = Duration.ZERO
    private var tickerJob: Job? = null
    private var released: Boolean = false

    override suspend fun prepare(outputPath: String?): RecorderResult<String> {
        val current: RecorderState = _state.value
        if (released) return releasedFailure(RecorderOperation.PREPARE)
        when (current) {
            RecorderState.Idle,
            is RecorderState.Ready,
            is RecorderState.Completed,
            is RecorderState.Failed,
            -> Unit

            else -> return illegal(current, RecorderOperation.PREPARE)
        }

        // A Ready file was opened but never recorded into, so it is this module's litter to clear
        // up. A Completed file belongs to the caller and is deliberately left alone.
        discardPreparedButUnusedFile(current)
        engine.release()
        resetTiming()
        _state.value = RecorderState.Preparing

        if (!engine.hasRecordAudioPermission()) return fail(RecorderError.PermissionDenied)
        if (!engine.supportsFormat(config.format)) {
            return fail(RecorderError.UnsupportedFormat(config.format))
        }

        val path: String = outputPath ?: generateOutputPath()
        // Guarded before the filesystem is touched: NSURL.fileURLWithPath raises on a blank path,
        // and Kotlin/Native cannot catch an Objective-C exception, so an unchecked blank string
        // would kill the process instead of producing a RecorderError.
        if (path.isBlank() || !path.startsWith(PATH_SEPARATOR)) {
            return fail(RecorderError.DirectoryNotWritable(path))
        }
        val directory: String = fileSystem.parentOf(path)
            ?: return fail(RecorderError.DirectoryNotWritable(path))
        if (!fileSystem.ensureWritableDirectory(directory)) {
            return fail(RecorderError.DirectoryNotWritable(directory))
        }
        checkFreeSpace(directory)?.let { error -> return fail(error) }

        try {
            engine.prepare(path, config)
        } catch (cancellation: CancellationException) {
            // Leave nothing half-open behind: the caller's coroutine is going away, and a native
            // recorder holding the microphone plus a zero-byte file would outlive it.
            engine.release()
            fileSystem.delete(path)
            _state.value = RecorderState.Idle
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            engine.release()
            fileSystem.delete(path)
            return fail(RecorderError.EngineFailure(RecorderOperation.PREPARE, failure))
        }

        // release() may have run while the call above was suspended — the documented threading
        // contract allows it, and Released is terminal. Undo the preparation rather than resurrect
        // a dead recorder, which would both overwrite Released and leak the handle release() could
        // not reach because the engine had not been assigned yet.
        if (released) {
            engine.release()
            fileSystem.delete(path)
            return releasedFailure(RecorderOperation.PREPARE)
        }

        _state.value = RecorderState.Ready(path)
        return RecorderResult.Success(path)
    }

    override fun start(): RecorderResult<Unit> {
        val current: RecorderState = _state.value
        if (released) return releasedFailure(RecorderOperation.START)
        if (current !is RecorderState.Ready) return illegal(current, RecorderOperation.START)

        try {
            engine.start()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            engine.release()
            fileSystem.delete(current.outputPath)
            return fail(RecorderError.EngineFailure(RecorderOperation.START, failure))
        }

        completedSegments = Duration.ZERO
        val mark: TimeMark = timeSource.markNow()
        segmentStart = mark
        _elapsed.value = Duration.ZERO
        startTicker(base = Duration.ZERO, mark = mark)
        _state.value = RecorderState.Recording(current.outputPath)
        return SUCCESS
    }

    override fun pause(): RecorderResult<Unit> {
        val current: RecorderState = _state.value
        if (released) return releasedFailure(RecorderOperation.PAUSE)
        if (current !is RecorderState.Recording) return illegal(current, RecorderOperation.PAUSE)

        try {
            engine.pause()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // The recording is still running — an engine that refuses to pause has not stopped
            // capturing, so reporting Paused here would desynchronize state from reality.
            return RecorderResult.Failure(
                RecorderError.EngineFailure(RecorderOperation.PAUSE, failure)
            )
        }

        stopTicker()
        freezeElapsed()
        _state.value = RecorderState.Paused(current.outputPath, _elapsed.value)
        return SUCCESS
    }

    override fun resume(): RecorderResult<Unit> {
        val current: RecorderState = _state.value
        if (released) return releasedFailure(RecorderOperation.RESUME)
        if (current !is RecorderState.Paused) return illegal(current, RecorderOperation.RESUME)

        try {
            engine.resume()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            return RecorderResult.Failure(
                RecorderError.EngineFailure(RecorderOperation.RESUME, failure)
            )
        }

        val mark: TimeMark = timeSource.markNow()
        segmentStart = mark
        startTicker(base = completedSegments, mark = mark)
        _state.value = RecorderState.Recording(current.outputPath)
        return SUCCESS
    }

    override fun stop(): RecorderResult<RecordedFile> {
        val current: RecorderState = _state.value
        if (released) return releasedFailure(RecorderOperation.STOP)
        val path: String = when (current) {
            is RecorderState.Recording -> current.outputPath
            is RecorderState.Paused -> current.outputPath
            else -> return illegal(current, RecorderOperation.STOP)
        }

        stopTicker()
        freezeElapsed()
        try {
            engine.stop()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Whatever was captured up to this point stays on disk: it may be salvageable, and
            // deleting a user's audio because the encoder complained on close is not a call a
            // library gets to make. `release()` documents the same rule.
            engine.release()
            return fail(RecorderError.EngineFailure(RecorderOperation.STOP, failure), path)
        }

        engine.release()
        val recording = RecordedFile(path = path, duration = _elapsed.value)
        _state.value = RecorderState.Completed(recording)
        return RecorderResult.Success(recording)
    }

    override fun cancel(): RecorderResult<Unit> {
        val current: RecorderState = _state.value
        if (released) return releasedFailure(RecorderOperation.CANCEL)
        val path: String = when (current) {
            is RecorderState.Ready -> current.outputPath
            is RecorderState.Recording -> current.outputPath
            is RecorderState.Paused -> current.outputPath
            else -> return illegal(current, RecorderOperation.CANCEL)
        }

        stopTicker()
        if (current.isActive) stopEngineQuietly()
        engine.release()
        fileSystem.delete(path)
        resetTiming()
        _state.value = RecorderState.Idle
        return SUCCESS
    }

    override fun release() {
        if (released) return
        released = true

        val current: RecorderState = _state.value
        stopTicker()
        if (current.isActive) stopEngineQuietly()
        engine.release()
        // A Recording/Paused file holds audio the user produced and is kept. A Ready file holds
        // nothing and can never be cleaned up later, because prepare() — the only thing that
        // discards it — can no longer run on a released recorder.
        discardPreparedButUnusedFile(current)
        resetTiming()
        _state.value = RecorderState.Released
        scope.cancel()
    }

    private fun discardPreparedButUnusedFile(current: RecorderState) {
        if (current is RecorderState.Ready) fileSystem.delete(current.outputPath)
    }

    private fun checkFreeSpace(directory: String): RecorderError? {
        if (config.minimumFreeSpaceBytes == 0L) return null
        val available: Long = fileSystem.freeSpaceBytes(directory)
        // A platform that cannot answer reports -1; refusing to record on an unknown is worse than
        // letting the recording fail later, so an unknown is treated as "enough".
        if (available < 0) return null
        if (available >= config.minimumFreeSpaceBytes) return null
        return RecorderError.InsufficientStorage(
            path = directory,
            requiredBytes = config.minimumFreeSpaceBytes,
            availableBytes = available,
        )
    }

    private fun generateOutputPath(): String {
        val storage: RecordingStorage = config.storage
        val directory: String = storage.directoryPath ?: fileSystem.resolve(
            fileSystem.appPrivateDirectory(),
            storage.directoryName ?: fileSystem.applicationIdentifier(),
        )
        val name = "${storage.fileNamePrefix}_${epochClock.nowMillis()}.${config.format.extension}"
        return fileSystem.resolve(directory, name)
    }

    private fun stopEngineQuietly() {
        try {
            engine.stop()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Throwable) {
            // Deliberate: both `cancel()` and `release()` are already throwing the recording away,
            // and `engine.release()` on the next line frees the handle either way. There is no
            // outcome a caller could act on and no state left to corrupt.
        }
    }

    /**
     * [base] and [mark] are passed by value rather than read from the fields, so the ticker
     * coroutine shares no mutable state with the caller's thread — it only ever writes [_elapsed],
     * which is a `MutableStateFlow` and safe to write from anywhere.
     */
    private fun startTicker(base: Duration, mark: TimeMark) {
        stopTicker()
        tickerJob = scope.launch {
            while (true) {
                delay(config.durationUpdateInterval)
                val tick: Duration = base + mark.elapsedNow()
                // Do not publish a tick computed before a stopTicker() that has already landed;
                // the transition that cancelled us has the authoritative value.
                ensureActive()
                _elapsed.value = tick
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun freezeElapsed() {
        _elapsed.value = currentElapsed()
        completedSegments = _elapsed.value
        segmentStart = null
    }

    private fun currentElapsed(): Duration =
        completedSegments + (segmentStart?.elapsedNow() ?: Duration.ZERO)

    private fun resetTiming() {
        segmentStart = null
        completedSegments = Duration.ZERO
        _elapsed.value = Duration.ZERO
    }

    private fun releasedFailure(operation: RecorderOperation): RecorderResult.Failure =
        RecorderResult.Failure(RecorderError.AlreadyReleased(operation))

    private fun illegal(
        state: RecorderState,
        operation: RecorderOperation,
    ): RecorderResult.Failure = RecorderResult.Failure(RecorderError.IllegalState(state, operation))

    private fun fail(error: RecorderError, outputPath: String? = null): RecorderResult.Failure {
        _state.value = RecorderState.Failed(error, outputPath)
        return RecorderResult.Failure(error)
    }

    private companion object {
        const val PATH_SEPARATOR = "/"
        val SUCCESS: RecorderResult<Unit> = RecorderResult.Success(Unit)
    }
}
