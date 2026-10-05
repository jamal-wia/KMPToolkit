package io.github.jamal_wia.kmptoolkit.audio.recorder

import io.github.jamal_wia.kmptoolkit.core.StateMachineLock
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole of this module's behavior: the transition table from [AudioRecorder], the pre-checks
 * that turn a doomed recording into a typed error before the microphone is touched, the
 * elapsed-time bookkeeping, the input-level meter, and what happens when the system ends a
 * recording on its own. Everything platform-specific is behind [RecorderEngine] and
 * [RecordingFileSystem], which is what lets all of it be tested on the JVM and in the iOS simulator
 * against fakes.
 *
 * ### Threading
 *
 * The public contract is still "call the operations from one thread", but the recorder is entered
 * from other threads too: the ticker and meter coroutines, and whatever thread the platform reports
 * an event on. Every transition therefore runs under one [StateMachineLock] (`gate`): the public
 * operations through `exclusive`, engine events and ticker output through `submit`, which never
 * blocks a platform callback thread. Everything marked "guarded" below is touched only under it.
 *
 * Slow work never runs while holding the lock, because a callback or a main-thread call could be
 * waiting for it. Finalizing a long container (`engine.stop()`) is the slow call: the operation or
 * event that wins the lock records in [finalizing] that the session is being finished by it,
 * releases the lock, does the slow call on [workerContext], and takes the lock again to publish
 * the outcome. Events that arrive meanwhile see the marker and are dropped, so a `stop()` is never
 * reported as a failure and a `cancel()` never as a saved recording; `release()` arriving meanwhile
 * flips the state to `Released` and leaves `engine.release()` to the finalizer, so the engine is
 * never driven from two threads.
 *
 * Every `prepare()` starts a new session ([session]); engine events carry the session they were
 * registered with and a stale one is dropped, which keeps an event from one recording from ending
 * the next one even when both use the same explicit output path. The ticker, the meter and the
 * silence debounce belong to the session: the transition that ends it cancels them, and what they
 * publish is fenced by an epoch checked under the lock.
 *
 * @param workerContext the single place this module decides what thread anything runs on: the
 *   [elapsed] ticker's and [level] meter's scope, and the `withContext` that keeps
 *   `prepare`/`stop`/`cancel`'s filesystem and encoder work off the caller's thread. The engines
 *   deliberately do no dispatching of their own, so a consumer who passes a context here really
 *   does control all of it. A [Job] in it is ignored.
 * @param silenceDebounce how long the input must stay silenced before it ends the recording; a
 *   brief handover (an assistant taking the microphone for a moment) must not.
 * @param freeSpacePollInterval how often the free-space watchdog looks, at most, while recording.
 */
@OptIn(ToolkitInternalApi::class)
internal class DefaultAudioRecorder(
    private val engine: RecorderEngine,
    private val fileSystem: RecordingFileSystem,
    private val config: AudioRecorderConfig,
    workerContext: CoroutineContext,
    private val epochClock: EpochClock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val silenceDebounce: Duration = DEFAULT_SILENCE_DEBOUNCE,
    private val freeSpacePollInterval: Duration = DEFAULT_FREE_SPACE_POLL_INTERVAL,
) : AudioRecorder {

    /**
     * The consumer's context without any [Job] it carries. A Job in the context handed to the
     * factory (say a `viewModelScope`'s) would replace the [SupervisorJob] below, so [release] would
     * cancel the consumer's own Job, and every `withContext(workerContext)` run from a
     * non-cancellable finalization would become a child of it and be cancelled with it — skipping
     * `engine.release()` and leaving the microphone held. Stripped once, here, and used for the
     * scope and for every hop to the worker. (`this.` where an initializer uses it: there the
     * constructor parameter of the same name would shadow it.)
     */
    private val workerContext: CoroutineContext = workerContext.minusKey(Job)

    /**
     * Owned by this recorder and cancelled by [release]. Visible to the module's own tests so they
     * can assert that release really does stop the ticker rather than merely resetting [elapsed].
     */
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + this.workerContext)

    private val gate: StateMachineLock = StateMachineLock()

    private val _state: MutableStateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _elapsed: MutableStateFlow<Duration> = MutableStateFlow(Duration.ZERO)
    override val elapsed: StateFlow<Duration> = _elapsed.asStateFlow()

    private val _level: MutableStateFlow<Float> = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    // Not a StateFlow: a waveform needs every sample, repeats included, and a StateFlow would drop
    // each one that equals its predecessor. DROP_OLDEST keeps the meter from ever suspending on a
    // slow collector, which would stall metering for everyone.
    private val _levelSamples: MutableSharedFlow<Float> = MutableSharedFlow(
        extraBufferCapacity = LEVEL_SAMPLE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val levelSamples: Flow<Float> = _levelSamples.asSharedFlow()

    /** What the free-space watchdog keeps free, or `null` when the check is switched off. */
    private val freeSpaceReserve: Long? = freeSpaceReserveBytes(config.minimumFreeSpaceBytes)

    // --- Guarded by `gate` ---

    private var segmentStart: TimeMark? = null
    private var completedSegments: Duration = Duration.ZERO
    private var released: Boolean = false

    /** Incremented by every `prepare()`; an engine event carries the value it was registered under. */
    private var session: Long = 0

    /**
     * Non-null while an operation or event is finishing the session off the lock (the slow
     * `engine.stop()`, the file deletion); its [Finalization.done] is completed, under the lock,
     * when the outcome is published. Never a public state: [state] keeps saying what it said until
     * the outcome lands.
     */
    private var finalizing: Finalization? = null

    private var tickerJob: Job? = null
    private var tickerEpoch: Long = 0
    private var meterJob: Job? = null
    private var meterEpoch: Long = 0
    private var silenceJob: Job? = null
    private var silenceEpoch: Long = 0

    /** Where [elapsed] stood when the input went silent; only while a silence is being debounced. */
    private var silencedAt: Duration? = null

    // --- prepare ---

    override suspend fun prepare(outputPath: String?): RecorderResult<String> {
        var token: Long
        while (true) {
            when (val step: Begin<RecorderResult<String>, Long> = gate.exclusive { beginPrepare() }) {
                is Begin.Done -> return step.result
                is Begin.Wait -> step.pending.await()
                is Begin.Go -> {
                    token = step.go
                    break
                }
            }
        }

        if (!engine.hasRecordAudioPermission()) return failPrepare(RecorderError.PermissionDenied)
        if (!engine.supportsFormat(config.format)) {
            return failPrepare(RecorderError.UnsupportedFormat(config.format))
        }

        val path: String = outputPath ?: generateOutputPath()
        // Guarded before the filesystem is touched: NSURL.fileURLWithPath raises on a blank path,
        // and Kotlin/Native cannot catch an Objective-C exception, so an unchecked blank string
        // would kill the process instead of producing a RecorderError.
        if (path.isBlank() || !path.startsWith(PATH_SEPARATOR)) {
            return failPrepare(RecorderError.DirectoryNotWritable(path))
        }
        val directory: String = fileSystem.parentOf(path)
            ?: return failPrepare(RecorderError.DirectoryNotWritable(path))
        val storage: StorageCheck = try {
            withContext(workerContext) {
                if (!fileSystem.ensureWritableDirectory(directory)) {
                    StorageCheck(RecorderError.DirectoryNotWritable(directory), null)
                } else {
                    checkFreeSpace(directory)
                }
            }
        } catch (cancellation: CancellationException) {
            // Nothing is open yet, so there is nothing to undo — but Preparing must not outlive the
            // call: every operation, prepare included, is illegal from it, so a recorder left there
            // could never be used again.
            // Released is terminal: a release() that landed meanwhile keeps its state.
            gate.exclusive { if (!released) _state.value = RecorderState.Idle }
            throw cancellation
        }
        storage.error?.let { error -> return failPrepare(error) }

        val prepareFailure: Throwable?
        try {
            prepareFailure = runOnWorker { engine.prepare(path, config, storage.maxFileSizeBytes) }
        } catch (cancellation: CancellationException) {
            // Leave nothing half-open behind: the caller's coroutine is going away, and a native
            // recorder holding the microphone plus a zero-byte file would outlive it.
            gate.exclusive {
                undoPreparation(path)
                // Released is terminal: a release() that landed meanwhile keeps its state.
                if (!released) _state.value = RecorderState.Idle
            }
            throw cancellation
        }

        return gate.exclusive {
            // release() may have run while the call above was suspended — the documented threading
            // contract allows it, and Released is terminal. Undo the preparation rather than
            // resurrect a dead recorder, which would both overwrite Released and leak the handle
            // release() could not reach because the engine had not been assigned yet.
            if (released) {
                undoPreparation(path)
                return@exclusive releasedFailure(RecorderOperation.PREPARE)
            }
            if (prepareFailure != null) {
                undoPreparation(path)
                return@exclusive fail(
                    RecorderError.EngineFailure(RecorderOperation.PREPARE, prepareFailure)
                )
            }
            // Listeners go on only now: nothing the engine reports while preparing can end a
            // recording that does not exist yet.
            engine.setEventListener(listenerFor(token))
            _state.value = RecorderState.Ready(path)
            RecorderResult.Success(path)
        }
    }

    /** Lock held. */
    private fun beginPrepare(): Begin<RecorderResult<String>, Long> {
        if (released) return Begin.Done(releasedFailure(RecorderOperation.PREPARE))
        finalizing?.let { pending -> return Begin.Wait(pending.done) }
        val current: RecorderState = _state.value
        when (current) {
            RecorderState.Idle,
            is RecorderState.Ready,
            is RecorderState.Completed,
            is RecorderState.Interrupted,
            is RecorderState.Failed,
            -> Unit

            else -> return Begin.Done(illegal(current, RecorderOperation.PREPARE))
        }

        // A Ready file was opened but never recorded into, so it is this module's litter to clear
        // up. A Completed or Interrupted file belongs to the caller and is deliberately left alone,
        // as is the one a failed stop or a lost recording left behind.
        discardPreparedButUnusedFile(current)
        engine.release()
        resetTiming()
        session++
        _state.value = RecorderState.Preparing
        return Begin.Go(session)
    }

    private fun failPrepare(error: RecorderError): RecorderResult.Failure = gate.exclusive {
        if (released) releasedFailure(RecorderOperation.PREPARE) else fail(error)
    }

    // --- start / pause / resume ---

    override fun start(): RecorderResult<Unit> = gate.exclusive {
        val current: RecorderState = _state.value
        if (released) return@exclusive releasedFailure(RecorderOperation.START)
        if (finalizing != null || current !is RecorderState.Ready) {
            return@exclusive illegal(current, RecorderOperation.START)
        }

        try {
            engine.start()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            engine.release()
            fileSystem.delete(current.outputPath)
            return@exclusive fail(RecorderError.EngineFailure(RecorderOperation.START, failure))
        }

        completedSegments = Duration.ZERO
        val mark: TimeMark = timeSource.markNow()
        segmentStart = mark
        _elapsed.value = Duration.ZERO
        startTicker(base = Duration.ZERO, mark = mark, outputPath = current.outputPath)
        startMeter()
        _state.value = RecorderState.Recording(current.outputPath)
        SUCCESS
    }

    override fun pause(): RecorderResult<Unit> = gate.exclusive {
        val current: RecorderState = _state.value
        if (released) return@exclusive releasedFailure(RecorderOperation.PAUSE)
        if (finalizing != null || current !is RecorderState.Recording) {
            return@exclusive illegal(current, RecorderOperation.PAUSE)
        }

        try {
            engine.pause()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // The recording is still running — an engine that refuses to pause has not stopped
            // capturing, so reporting Paused here would desynchronize state from reality.
            return@exclusive RecorderResult.Failure(
                RecorderError.EngineFailure(RecorderOperation.PAUSE, failure)
            )
        }

        stopTicker()
        stopMeter()
        // A silence being debounced is not an interruption once nothing is captured; resume() asks
        // the engine again.
        stopSilence()
        freezeElapsed()
        _state.value = RecorderState.Paused(current.outputPath, _elapsed.value)
        SUCCESS
    }

    override fun resume(): RecorderResult<Unit> = gate.exclusive {
        val current: RecorderState = _state.value
        if (released) return@exclusive releasedFailure(RecorderOperation.RESUME)
        if (finalizing != null || current !is RecorderState.Paused) {
            return@exclusive illegal(current, RecorderOperation.RESUME)
        }

        try {
            engine.resume()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            return@exclusive RecorderResult.Failure(
                RecorderError.EngineFailure(RecorderOperation.RESUME, failure)
            )
        }

        val mark: TimeMark = timeSource.markNow()
        segmentStart = mark
        startTicker(base = completedSegments, mark = mark, outputPath = current.outputPath)
        startMeter()
        _state.value = RecorderState.Recording(current.outputPath)
        SUCCESS
    }

    // --- stop ---

    override suspend fun stop(): RecorderResult<RecordedFile> {
        var finalization: Finalization
        while (true) {
            when (val step: Begin<RecorderResult<RecordedFile>, Finalization> = gate.exclusive { beginStop() }) {
                is Begin.Done -> return step.result
                is Begin.Wait -> step.pending.await()
                is Begin.Go -> {
                    finalization = step.go
                    break
                }
            }
        }

        // Runs to completion even if the caller is cancelled on the way in or out. The ticker is
        // already stopped, so abandoning part-way would leave the state saying Recording over an
        // engine that may already have stopped. NonCancellable is applied around the whole tail, on
        // the caller's own dispatcher, and not just around the hop to the worker: a coroutine
        // cancelled while the hop ran is not resumed after it, so a state update placed after a
        // non-cancellable hop would still be skipped. The caller observes its cancellation at its next
        // suspension point at the latest.
        return withContext(NonCancellable) { finishStop(finalization) }
    }

    /** Lock held. */
    private fun beginStop(): Begin<RecorderResult<RecordedFile>, Finalization> {
        if (released) return Begin.Done(releasedFailure(RecorderOperation.STOP))
        finalizing?.let { pending -> return Begin.Wait(pending.done) }
        val current: RecorderState = _state.value
        return when (current) {
            is RecorderState.Recording -> Begin.Go(beginFinalize(current.outputPath))
            is RecorderState.Paused -> Begin.Go(beginFinalize(current.outputPath))
            // Already finalized by the system: the file is the answer, and nothing changes.
            is RecorderState.Interrupted -> Begin.Done(RecorderResult.Success(current.recording))
            else -> Begin.Done(illegal(current, RecorderOperation.STOP))
        }
    }

    private suspend fun finishStop(finalization: Finalization): RecorderResult<RecordedFile> {
        // Finalizing the container is the one genuinely slow call in the whole module.
        val stopFailure: Throwable? = runOnWorker { engine.stop() }
        return gate.exclusive {
            // The finalizer owns the engine until it publishes, even if release() landed first.
            engine.release()
            val result: RecorderResult<RecordedFile> = if (stopFailure != null) {
                // Whatever was captured up to this point stays on disk: it may be salvageable, and
                // deleting a user's audio because the encoder complained on close is not a call a
                // library gets to make. `release()` documents the same rule.
                fail(
                    RecorderError.EngineFailure(RecorderOperation.STOP, stopFailure),
                    finalization.path,
                )
            } else {
                val recording = RecordedFile(finalization.path, finalization.duration)
                // A release() that landed while the container was being finalized keeps its
                // terminal state.
                if (!released) _state.value = RecorderState.Completed(recording)
                RecorderResult.Success(recording)
            }
            endFinalize(finalization)
            result
        }
    }

    // --- cancel ---

    override suspend fun cancel(): RecorderResult<Unit> {
        var plan: CancelPlan
        while (true) {
            when (val step: Begin<RecorderResult<Unit>, CancelPlan> = gate.exclusive { beginCancel() }) {
                is Begin.Done -> return step.result
                is Begin.Wait -> step.pending.await()
                is Begin.Go -> {
                    plan = step.go
                    break
                }
            }
        }

        // Non-cancellable for the same reason as stop(), and around the whole tail for the same
        // reason: with the ticker gone, a half-finished cancel would leave a live recorder behind a
        // state that still says it is recording.
        withContext(NonCancellable) {
            try {
                withContext(workerContext) {
                    if (plan.engineActive) stopEngineQuietly()
                    if (plan.holdsEngine) engine.release()
                    fileSystem.delete(plan.path)
                }
            } finally {
                gate.exclusive {
                    resetTiming()
                    if (!released) _state.value = RecorderState.Idle
                    endFinalize(plan.finalization)
                }
            }
        }
        return SUCCESS
    }

    /** Lock held. */
    private fun beginCancel(): Begin<RecorderResult<Unit>, CancelPlan> {
        if (released) return Begin.Done(releasedFailure(RecorderOperation.CANCEL))
        finalizing?.let { pending -> return Begin.Wait(pending.done) }
        val current: RecorderState = _state.value
        return when (current) {
            is RecorderState.Ready ->
                Begin.Go(CancelPlan(beginFinalize(current.outputPath), engineActive = false, holdsEngine = true))

            is RecorderState.Recording ->
                Begin.Go(CancelPlan(beginFinalize(current.outputPath), engineActive = true, holdsEngine = true))

            is RecorderState.Paused ->
                Begin.Go(CancelPlan(beginFinalize(current.outputPath), engineActive = true, holdsEngine = true))

            // The engine was already released when the system ended the recording: only the file
            // is left to delete.
            is RecorderState.Interrupted ->
                Begin.Go(CancelPlan(beginFinalize(current.recording.path), engineActive = false, holdsEngine = false))

            is RecorderState.Failed -> {
                val path: String = current.outputPath
                    ?: return Begin.Done(illegal(current, RecorderOperation.CANCEL))
                Begin.Go(CancelPlan(beginFinalize(path), engineActive = false, holdsEngine = false))
            }

            else -> Begin.Done(illegal(current, RecorderOperation.CANCEL))
        }
    }

    // --- release ---

    override fun release() {
        gate.exclusive {
            if (released) return@exclusive
            released = true

            val current: RecorderState = _state.value
            stopTicker()
            stopMeter()
            stopSilence()
            engine.setEventListener(null)
            // Inline on the calling thread, not on workerContext: release() is not suspending (see
            // its KDoc — a teardown path has no coroutine left to launch in), so there is nowhere
            // to hand this off to that would still have finished by the time the caller's object is
            // gone. The exception is a session some operation is already finishing off the lock:
            // that finalizer owns the engine and frees it when it is done, so the engine is never
            // driven from two threads at once.
            if (finalizing == null) {
                if (current.isActive) stopEngineQuietly()
                engine.release()
                // A Recording/Paused file holds audio the user produced and is kept. A Ready file
                // holds nothing and can never be cleaned up later, because prepare() — the only
                // thing that discards it — can no longer run on a released recorder.
                discardPreparedButUnusedFile(current)
            }
            resetTiming()
            _state.value = RecorderState.Released
            scope.cancel()
        }
    }

    // --- Events from the engine and from the watchdogs ---

    /** The listener an engine reports to for [token]'s session. Never blocks the platform thread. */
    private fun listenerFor(token: Long): (EngineEvent) -> Unit = { event: EngineEvent ->
        gate.submit { handleEvent(token, event) }
    }

    /** Lock held. */
    private fun handleEvent(token: Long, event: EngineEvent) {
        if (released || token != session) return
        // Already being finished by an operation or by an earlier event: only the first thing that
        // ends a session counts. The one exception is a reason that is more specific than the
        // generic "the media service failed" the first event carried, arriving before the outcome
        // is published — platforms often report the cause right after the symptom.
        finalizing?.let { active ->
            if (event is EngineEvent.Interrupted &&
                active.reason is InterruptionReason.EngineDied &&
                event.reason !is InterruptionReason.EngineDied &&
                event.reason != InterruptionReason.MicrophoneSilenced
            ) {
                active.reason = event.reason
            }
            return
        }
        val current: RecorderState = _state.value
        when (event) {
            is EngineEvent.Interrupted -> {
                // The microphone is not captured while Paused, so silencing it ends nothing.
                if (event.reason == InterruptionReason.MicrophoneSilenced &&
                    current !is RecorderState.Recording
                ) {
                    return
                }
                endBySystem(current, event.reason, frozenAt = null)
            }

            is EngineEvent.InputSilenced -> onInputSilenced(token, current, event.silenced)
        }
    }

    /** Lock held. */
    private fun onInputSilenced(token: Long, current: RecorderState, silenced: Boolean) {
        if (current !is RecorderState.Recording) return
        if (!silenced) {
            stopSilence()
            return
        }
        if (silenceJob != null) return
        // Elapsed freezes where the silence began, not where it was reported.
        val began: Duration = currentElapsed()
        startSilenceDebounce(token, began)
    }

    /** Lock held. */
    private fun startSilenceDebounce(token: Long, began: Duration) {
        stopSilence()
        silencedAt = began
        val epoch: Long = silenceEpoch
        silenceJob = scope.launch {
            delay(silenceDebounce)
            gate.submit { onSilenceDebounced(token, epoch) }
        }
    }

    /** Lock held. */
    private fun onSilenceDebounced(token: Long, epoch: Long) {
        if (released || token != session || finalizing != null || epoch != silenceEpoch) return
        val current: RecorderState = _state.value
        if (current !is RecorderState.Recording) return
        endBySystem(current, InterruptionReason.MicrophoneSilenced, frozenAt = silencedAt)
    }

    /**
     * The system ended the session. Lock held; the slow part runs off it on a coroutine of the
     * recorder's own scope, which `release()` cancels — hence [NonCancellable], so a release
     * that lands meanwhile cannot strand the engine unreleased.
     */
    private fun endBySystem(current: RecorderState, reason: InterruptionReason, frozenAt: Duration?) {
        when (current) {
            is RecorderState.Recording -> finishBySystem(current.outputPath, reason, frozenAt)
            is RecorderState.Paused -> finishBySystem(current.outputPath, reason, frozenAt)
            is RecorderState.Ready -> {
                val finalization: Finalization = beginFinalize(current.outputPath)
                finalization.reason = reason
                scope.launch(NonCancellable) {
                    try {
                        // Nothing was captured, so there is nothing to finalize or to keep: the
                        // empty file is this module's litter.
                        withContext(workerContext) {
                            engine.release()
                            fileSystem.delete(finalization.path)
                        }
                    } finally {
                        gate.exclusive {
                            resetTiming()
                            if (!released) {
                                _state.value = RecorderState.Failed(
                                    RecorderError.RecordingLost(finalization.reason ?: reason)
                                )
                            }
                            endFinalize(finalization)
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    /** Lock held. */
    private fun finishBySystem(path: String, reason: InterruptionReason, frozenAt: Duration?) {
        val finalization: Finalization = beginFinalize(path, frozenAt)
        finalization.reason = reason
        scope.launch(NonCancellable) {
            val failure: Throwable? = runOnWorker { engine.stop() }
            gate.exclusive {
                engine.release()
                // Read here, under the lock: a more specific reason may have arrived meanwhile.
                val finalReason: InterruptionReason = finalization.reason ?: reason
                if (!released) {
                    _state.value = if (failure == null) {
                        RecorderState.Interrupted(
                            RecordedFile(finalization.path, finalization.duration),
                            finalReason,
                        )
                    } else {
                        RecorderState.Failed(RecorderError.RecordingLost(finalReason, failure), path)
                    }
                }
                endFinalize(finalization)
            }
        }
    }

    // --- Finalization marker ---

    /**
     * Takes the session over for an operation or event that will finish it off the lock: stops
     * everything the session owns, freezes elapsed (at [frozenAt] when the event knows better than
     * "now"), detaches the engine's listener and sets [finalizing]. Lock held.
     */
    private fun beginFinalize(path: String, frozenAt: Duration? = null): Finalization {
        stopTicker()
        stopMeter()
        stopSilence()
        if (_state.value.isActive) {
            if (frozenAt == null) {
                freezeElapsed()
            } else {
                _elapsed.value = frozenAt
                completedSegments = frozenAt
                segmentStart = null
            }
        }
        engine.setEventListener(null)
        val finalization = Finalization(path, _elapsed.value)
        finalizing = finalization
        return finalization
    }

    /** Publishes the end of a finalization. Lock held. */
    private fun endFinalize(finalization: Finalization) {
        finalizing = null
        finalization.done.complete(Unit)
    }

    private class Finalization(
        val path: String,
        val duration: Duration,
    ) {
        val done: CompletableDeferred<Unit> = CompletableDeferred()

        /** Why the system ended the session; `null` when an operation of the caller is finishing it. */
        var reason: InterruptionReason? = null
    }

    private class CancelPlan(
        val finalization: Finalization,
        /** The platform recorder is capturing and has to be stopped before it is released. */
        val engineActive: Boolean,
        /** The engine still holds a native handle this plan has to release. */
        val holdsEngine: Boolean,
    ) {
        val path: String get() = finalization.path
    }

    /** What a transition decided under the lock. */
    private sealed interface Begin<out R, out G> {
        /** Settled without leaving the lock. */
        class Done<R>(val result: R) : Begin<R, Nothing>

        /** Another operation or event is finishing the session: wait for it, then decide again. */
        class Wait(val pending: CompletableDeferred<Unit>) : Begin<Nothing, Nothing>

        /** Go on with the slow part, off the lock. */
        class Go<G>(val go: G) : Begin<Nothing, G>
    }

    // --- Helpers ---

    /**
     * Runs [block] on [workerContext] and returns whatever it threw, rather than letting the
     * throwable cross the `withContext` boundary.
     *
     * That boundary is exactly where kotlinx.coroutines' stacktrace recovery replaces an exception
     * with a *copy* carrying an augmented stack trace. The copy is helpful when the exception is
     * being rethrown, and wrong here: [RecorderError.EngineFailure.cause] promises the throwable
     * the platform produced, and a consumer matching on identity — or on a field of a custom
     * platform exception the copy could not reproduce — would get something else.
     * `CancellationException` is deliberately still allowed through, since it is control flow and
     * not a value.
     */
    private suspend fun runOnWorker(block: suspend () -> Unit): Throwable? =
        withContext(workerContext) {
            try {
                block()
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                failure
            }
        }

    /**
     * Undoes a preparation that will not be used. Runs inline rather than on [workerContext],
     * because one of its callers is the cancellation path of `prepare`, where `withContext`
     * would refuse to start at all. Both calls it makes are documented as non-throwing best effort
     * and neither waits on I/O of any consequence — deleting a file that was opened and never
     * written to. Lock held.
     */
    private fun undoPreparation(path: String) {
        engine.release()
        fileSystem.delete(path)
    }

    private fun discardPreparedButUnusedFile(current: RecorderState) {
        if (current is RecorderState.Ready) fileSystem.delete(current.outputPath)
    }

    private fun checkFreeSpace(directory: String): StorageCheck {
        if (config.minimumFreeSpaceBytes == 0L) return StorageCheck(null, null)
        val available: Long = fileSystem.freeSpaceBytes(directory)
        // A platform that cannot answer reports -1; refusing to record on an unknown is worse than
        // letting the recording fail later, so an unknown is treated as "enough".
        if (available < 0) return StorageCheck(null, null)
        if (available < config.minimumFreeSpaceBytes) {
            return StorageCheck(
                RecorderError.InsufficientStorage(
                    path = directory,
                    requiredBytes = config.minimumFreeSpaceBytes,
                    availableBytes = available,
                ),
                null,
            )
        }
        // The platform's own size limit, as a backstop for a disk that fills faster than the
        // watchdog polls: what is free now, less the reserve finalizing needs.
        val reserve: Long = freeSpaceReserve ?: 0L
        return StorageCheck(null, (available - reserve).coerceAtLeast(1L))
    }

    private class StorageCheck(val error: RecorderError?, val maxFileSizeBytes: Long?)

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
            // and `engine.release()` next to it frees the handle either way. There is no outcome a
            // caller could act on and no state left to corrupt.
        }
    }

    /**
     * [base] and [mark] are passed by value rather than read from the fields, so the ticker
     * coroutine shares no mutable state with the transitions: what it computes is published through
     * the lock, fenced by [tickerEpoch], so a tick computed before a pause, stop or release that
     * has since landed can never overwrite the value that transition froze. Lock held.
     *
     * The free-space watchdog lives in the same loop rather than in a coroutine of its own: one
     * coroutine per recording is all this module launches while recording, and the poll rides on
     * the tick that is already waking up.
     */
    private fun startTicker(base: Duration, mark: TimeMark, outputPath: String) {
        stopTicker()
        val epoch: Long = tickerEpoch
        val token: Long = session
        val reserve: Long? = freeSpaceReserve
        val directory: String? = if (reserve == null) null else fileSystem.parentOf(outputPath)
        // Taken here, with the lock, not when the coroutine first gets to run: the poll interval
        // counts from the moment capture began.
        val startedAt: TimeMark = timeSource.markNow()
        tickerJob = scope.launch {
            var lastSpaceCheck: TimeMark = startedAt
            while (true) {
                delay(config.durationUpdateInterval)
                val tick: Duration = base + mark.elapsedNow()
                // Do not publish a tick computed before a stopTicker() that has already landed;
                // the transition that cancelled us has the authoritative value.
                ensureActive()
                gate.submit { if (epoch == tickerEpoch) _elapsed.value = tick }
                if (reserve != null && directory != null &&
                    lastSpaceCheck.elapsedNow() >= freeSpacePollInterval
                ) {
                    lastSpaceCheck = timeSource.markNow()
                    val free: Long = fileSystem.freeSpaceBytes(directory)
                    // -1 is "the platform could not tell", which is not "low".
                    if (free in 0 until reserve) {
                        gate.submit {
                            handleEvent(token, EngineEvent.Interrupted(InterruptionReason.StorageLow))
                        }
                    }
                }
            }
        }
    }

    /** Lock held. */
    private fun stopTicker() {
        tickerEpoch++
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Meters the input for as long as someone is watching [level] or [levelSamples]. Collecting
     * the two `subscriptionCount`s is what makes metering demand-driven: a recorder whose level
     * nobody collects never calls the engine, and the first collector to arrive — or the last to
     * leave — starts or stops the sampling without any transition on the recorder itself. The two
     * flows share this one meter, so watching both does not sample the microphone twice.
     *
     * Nothing here reads a field the transitions write: the interval and floor come from the
     * immutable [config], and the engine's `peakDbfs()` is documented as safe to call concurrently
     * with the transitions. What it publishes goes through the lock, fenced by [meterEpoch]. Lock
     * held.
     */
    private fun startMeter() {
        stopMeter()
        val epoch: Long = meterEpoch
        meterJob = scope.launch {
            combine(
                _level.subscriptionCount,
                _levelSamples.subscriptionCount,
            ) { levelCollectors: Int, sampleCollectors: Int ->
                levelCollectors + sampleCollectors > 0
            }
                .distinctUntilChanged()
                .collectLatest { subscribed: Boolean ->
                    if (!subscribed) {
                        gate.submit { if (epoch == meterEpoch) _level.value = 0f }
                        return@collectLatest
                    }
                    // The peak is "since the previous call", so the first call measures an
                    // arbitrary stretch before anyone was watching. Reading it only to throw it
                    // away is what makes the first published value cover one interval.
                    engine.peakDbfs()
                    while (true) {
                        delay(config.levelUpdateInterval)
                        // null: the engine had nothing to say (not recording any more, or the
                        // platform could not answer). Keep the last value rather than draw a gap.
                        val dbfs: Float = engine.peakDbfs() ?: continue
                        publishLevel(epoch, normalizedLevel(dbfs, config.levelFloorDbfs))
                    }
                }
        }
    }

    /** Lock held. */
    private fun stopMeter() {
        meterEpoch++
        meterJob?.cancel()
        meterJob = null
        _level.value = 0f
    }

    /**
     * Cancelling the meter does not wait for a sample that is already being taken, so a value can
     * be computed just before a pause, stop or release lands and arrive just after it. Publishing
     * under the lock, behind the epoch that transition advanced, drops it: neither [level] nor
     * [levelSamples] ever shows a sample from after the transition that ended the metering.
     */
    private fun publishLevel(epoch: Long, value: Float) {
        gate.submit {
            if (epoch == meterEpoch) {
                _level.value = value
                _levelSamples.tryEmit(value)
            }
        }
    }

    /** Lock held. */
    private fun stopSilence() {
        silenceEpoch++
        silenceJob?.cancel()
        silenceJob = null
        silencedAt = null
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

    /** Lock held. A released recorder keeps its terminal state. */
    private fun fail(error: RecorderError, outputPath: String? = null): RecorderResult.Failure {
        if (!released) _state.value = RecorderState.Failed(error, outputPath)
        return RecorderResult.Failure(error)
    }

    private companion object {
        const val PATH_SEPARATOR = "/"

        /** About three seconds of samples at the default interval; see [_levelSamples]. */
        const val LEVEL_SAMPLE_BUFFER = 64
        val SUCCESS: RecorderResult<Unit> = RecorderResult.Success(Unit)
    }
}

/**
 * How long the input must stay silenced before it ends the recording. Long enough that an
 * assistant or a notification taking the microphone for a moment does not cost the user their
 * recording, short enough that a call does not leave minutes of silence in the file.
 */
internal val DEFAULT_SILENCE_DEBOUNCE: Duration = 400.milliseconds

/** How often, at most, the watchdog looks at free space while recording. */
internal val DEFAULT_FREE_SPACE_POLL_INTERVAL: Duration = 2.seconds

/** The smallest reserve the watchdog keeps free for finalizing the file. */
internal const val MIN_FREE_SPACE_RESERVE_BYTES: Long = 2L * 1024 * 1024

/**
 * What the free-space watchdog keeps free while recording, derived from
 * [AudioRecorderConfig.minimumFreeSpaceBytes], or `null` when that is `0` — the consumer switched
 * storage checking off, and the watchdog is part of it.
 *
 * Half the configured minimum, but never less than 2 MiB and never more than the minimum itself.
 * The reserve is what finalizing needs: an MPEG-4 file's index (`moov`) grows with the length of
 * the recording, a few hundred kilobytes for a very long one, so 2 MiB covers it with a margin.
 * Half the minimum scales that with a consumer who asked for more headroom, and the cap keeps a
 * small minimum from putting the reserve above the very number that let the recording start.
 */
internal fun freeSpaceReserveBytes(minimumFreeSpaceBytes: Long): Long? {
    if (minimumFreeSpaceBytes == 0L) return null
    return (minimumFreeSpaceBytes / 2)
        .coerceAtLeast(MIN_FREE_SPACE_RESERVE_BYTES)
        .coerceAtMost(minimumFreeSpaceBytes)
}
