package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A headless microphone recorder: one output file at a time, driven through an explicit state
 * machine, reporting every failure as a typed [RecorderError] instead of a platform exception.
 *
 * ## Lifecycle contract
 *
 * The recorder is a state machine over [RecorderState]. Every operation is legal in some states and
 * illegal in the rest; an illegal call is **not** an exception — it returns
 * [RecorderResult.Failure] carrying [RecorderError.IllegalState] and leaves the current state
 * untouched. The full table:
 *
 * | From \ Operation | `prepare` | `start` | `pause` | `resume` | `stop` | `cancel` |
 * |---|---|---|---|---|---|---|
 * | [RecorderState.Idle] | yes | no | no | no | no | no |
 * | [RecorderState.Preparing] | no | no | no | no | no | no |
 * | [RecorderState.Ready] | yes | yes | no | no | no | yes |
 * | [RecorderState.Recording] | no | no | yes | no | yes | yes |
 * | [RecorderState.Paused] | no | no | no | yes | yes | yes |
 * | [RecorderState.Completed] | yes | no | no | no | no | no |
 * | [RecorderState.Interrupted] | yes | no | no | no | yes² | yes |
 * | [RecorderState.Failed] | yes | no | no | no | no | yes¹ |
 * | [RecorderState.Released] | no | no | no | no | no | no |
 *
 * ¹ Only when [RecorderState.Failed.outputPath] is non-null: `cancel` deletes that file and returns
 * to [RecorderState.Idle]. From a `Failed` without a path it is illegal, as before.
 *
 * ² `stop` from [RecorderState.Interrupted] returns the interrupted recording again as a success
 * and leaves the state unchanged — nothing is emitted, and it is not [RecorderState.Completed].
 *
 * While the system's event is being finalized, [state] still shows the old value, and a call of
 * yours races with it: `start`, `pause` and `resume` return [RecorderError.IllegalState] carrying
 * that still-published state (`Ready`, `Recording` or `Paused`); `prepare`, `stop` and `cancel`
 * suspend — never blocking a thread — until the finalization lands, then decide again against the
 * state it published. So `IllegalState` can come from a race with a system event, not only from a
 * wiring bug; a `stop` that waited for a finalization that failed finds `Failed` and returns
 * `IllegalState` too.
 *
 * `prepare` from [RecorderState.Ready], [RecorderState.Completed], [RecorderState.Interrupted], or
 * [RecorderState.Failed] starts a fresh recording: the previous native recorder is torn down first,
 * and a `Ready` file that was never recorded into is deleted. A `Completed` or `Interrupted` file
 * is the caller's and is never deleted by `prepare`. `prepare` never resumes or appends to an
 * earlier recording.
 *
 * ## When the system ends a recording
 *
 * A recording can be ended by the system while the app is not calling anything: a call, Siri or
 * another app takes the audio session (iOS), the OS silences the input (Android 10+), free space
 * runs below the reserve the library keeps for finalizing, or the media service dies. The recorder
 * notices, tries to finalize the file, and moves [state] on its own — from [RecorderState.Recording]
 * or [RecorderState.Paused] to [RecorderState.Interrupted] when the file was finalized, or to
 * [RecorderState.Failed] with [RecorderError.RecordingLost] when it was not — and from
 * [RecorderState.Ready] to a `Failed` with no file. [InterruptionReason] says why. Only the first
 * such event of a recording counts. A [stop] or [cancel] that is already running always wins: a
 * discard is never reported as a saved recording and a normal stop never as a failure.
 *
 * ## Ownership and release
 *
 * The recorder owns a native handle (`android.media.MediaRecorder` / `AVAudioRecorder`), the
 * microphone, and the coroutines that publish [elapsed] and [level]. **The caller owns the
 * recorder** and must call [release] exactly once when done — from `onDestroy`, a Decompose
 * `doOnDestroy`, a `deinit`, or whatever scope holds the instance. Nothing releases it for you and
 * no finalizer runs.
 *
 * After [release] the instance is permanently dead: every operation returns
 * [RecorderError.AlreadyReleased] and [state] stays [RecorderState.Released]. Recording again means
 * constructing a new recorder. [release] itself is idempotent — calling it twice, or on an instance
 * that never recorded, is a no-op.
 *
 * ## Which operations suspend, and why
 *
 * **An operation that can touch the filesystem suspends; an operation that only moves recorder
 * state does not.** So [prepare] (creates the directory, opens the file), [stop] (finalizes the
 * container — on Android that means writing the MPEG-4 `moov` atom), and [cancel] (deletes the
 * partial file) are `suspend`; [start], [pause], and [resume] are not, because each is a flip of
 * the native recorder's own state.
 *
 * The rule exists so the signature carries the information: you never have to check the
 * documentation to find out whether a call can block. The suspending three do their I/O on the
 * `coroutineContext` the factory was given, so they do not block the caller's thread either.
 *
 * [release] is the deliberate exception — see its own documentation.
 *
 * ## Threading
 *
 * The public contract is unchanged: the recorder is **not** thread-safe against concurrent calls
 * of yours. Call [prepare], [start], [pause], [resume], [stop], [cancel],
 * and [release] from one thread (or one single-threaded dispatcher) — the same one every time.
 * [state], [elapsed] and [level] are `StateFlow`s, and [levelSamples] is a `Flow`; all of them can
 * be collected from anywhere. Events the system raises arrive on platform threads of its choosing;
 * the recorder serializes them with your calls internally, so you do not synchronize anything for
 * them. The slow work of an event (finalizing a long file) runs off the lock and a call of yours
 * does not block a thread on it: [prepare], [stop] and [cancel] suspend until it lands, and
 * [start], [pause] and [resume] are refused with [RecorderError.IllegalState] meanwhile.
 *
 * ## Permission
 *
 * This library does not declare `RECORD_AUDIO` in its manifest and never requests it. [prepare]
 * checks whether it has already been granted and fails with [RecorderError.PermissionDenied] if it
 * has not — it does not crash and does not show a prompt. See
 * `docs/kmptoolkit-audio-recorder/05-platform-notes.md`.
 */
public interface AudioRecorder {

    /**
     * The recorder's current position in the lifecycle above. Starts at [RecorderState.Idle] and
     * changes as a result of an operation on this recorder, or when the system ends a recording
     * (see "When the system ends a recording"): to [RecorderState.Interrupted] or to a
     * [RecorderState.Failed] carrying [RecorderError.RecordingLost]. It never emits a duration
     * tick, so a collector is only woken by a real transition.
     */
    public val state: StateFlow<RecorderState>

    /**
     * Time recorded into the current file so far, for driving a timer in the UI.
     *
     * Advances only while [state] is [RecorderState.Recording], polled at
     * [AudioRecorderConfig.durationUpdateInterval]. It freezes at its current value on [pause] and
     * continues from there on [resume], holds the final duration after [stop], and resets to
     * [Duration.ZERO] on [prepare], [cancel], and [release]. When the system ends the recording it
     * freezes at the moment the event was observed and stays there while the state is
     * [RecorderState.Interrupted]. For [InterruptionReason.MicrophoneSilenced] that moment is when
     * the silencing began, up to the debounce (about 400 ms) *before* it was reported: the ticker
     * keeps publishing while the silence is debounced, so [elapsed] steps back by up to that much
     * when the state becomes `Interrupted`. A timer that must never run backwards should clamp.
     *
     * This is wall-clock time between `start` and `stop`, not a measurement of the encoded file. It
     * is accurate enough for a recording timer and is not a substitute for reading the finished
     * file's real duration if you need an exact value.
     */
    public val elapsed: StateFlow<Duration>

    /**
     * Peak loudness of the microphone input while recording, normalised to `0f..1f`: `0f` at or
     * below [AudioRecorderConfig.levelFloorDbfs], `1f` at full scale (0 dBFS), linear in decibels
     * between — a level meter, not an amplitude, so quiet speech is visible.
     *
     * Each value is the loudest sample since the previous one, published every
     * [AudioRecorderConfig.levelUpdateInterval]. It is not smoothed: animating bars between values
     * is the UI's choice.
     *
     * Measured only while [state] is [RecorderState.Recording] **and** at least one collector of
     * this or [levelSamples] is subscribed — a recorder nobody meters does no metering work. `0f` in
     * every other state, and as soon as the last collector leaves; reading [StateFlow.value] without
     * collecting does not start metering. After [release] it is `0f` for good.
     *
     * Being a `StateFlow` it conflates equal consecutive values, so in silence it sits at `0f` and
     * emits nothing. That suits a pulsing indicator and is wrong for a waveform; use [levelSamples]
     * for that.
     *
     * @since 1.9.0
     */
    public val level: StateFlow<Float>

    /**
     * Every sample the level meter takes, on the same `0f..1f` scale as [level], one per
     * [AudioRecorderConfig.levelUpdateInterval] — repeats included, so silence keeps arriving as
     * `0f` and a waveform keeps moving while the user is quiet. Collect this to draw a waveform;
     * collect [level] to draw a meter that shows the current loudness.
     *
     * Hot and not replayed: a collector receives the samples taken after it subscribed. Emits only
     * while [state] is [RecorderState.Recording] — nothing while paused, so a waveform pauses with
     * the recording — and never after [release]. It does not complete. Collecting it starts metering
     * exactly as collecting [level] does; the two share one meter. A collector that falls more than
     * 64 samples behind loses the oldest.
     *
     * @since 1.9.0
     */
    public val levelSamples: Flow<Float>

    /**
     * Acquires the microphone and opens [outputPath] for writing, moving [state] through
     * [RecorderState.Preparing] to [RecorderState.Ready]. Nothing is recorded until [start].
     *
     * Before touching the native recorder this checks, in order: that the recorder has not been
     * released, that the operation is legal in the current state, that `RECORD_AUDIO` is granted,
     * that the requested [AudioRecorderConfig.format] is supported on this platform, that the
     * output directory exists and is writable, and that the volume has at least
     * [AudioRecorderConfig.minimumFreeSpaceBytes] free. The first check that fails ends the call.
     *
     * If the calling coroutine is cancelled mid-call, the half-prepared native recorder is
     * released and the partially created output file is deleted before `CancellationException`
     * propagates; [state] is left at [RecorderState.Idle], never [RecorderState.Preparing].
     *
     * [release] may equally land while this call is suspended. The preparation then undoes itself
     * — freeing the handle it had just created and deleting the file — and returns
     * [RecorderError.AlreadyReleased] rather than moving a released recorder to
     * [RecorderState.Ready].
     *
     * @param outputPath absolute path of the file to record into. Must be absolute and non-blank; a
     *   blank or relative path fails with [RecorderError.DirectoryNotWritable] before the filesystem
     *   is touched, because `NSURL.fileURLWithPath("")` raises an Objective-C exception that
     *   Kotlin/Native cannot catch. `null` — the default — generates a path inside the configured
     *   directory from [RecordingStorage.fileNamePrefix], the current timestamp, and the format's
     *   extension. Parent directories are created if missing.
     * @return the resolved absolute output path on success.
     */
    public suspend fun prepare(outputPath: String? = null): RecorderResult<String>

    /**
     * Begins capturing audio, moving [state] from [RecorderState.Ready] to
     * [RecorderState.Recording] and starting [elapsed].
     *
     * Legal only from [RecorderState.Ready] — in particular **not** from [RecorderState.Paused],
     * which takes [resume] instead, so that "start" always means "start from zero".
     */
    public fun start(): RecorderResult<Unit>

    /**
     * Suspends capture, keeping the output file open and [elapsed] frozen at its current value.
     *
     * Legal only from [RecorderState.Recording]. Not every platform output format supports pausing
     * — see `docs/kmptoolkit-audio-recorder/05-platform-notes.md`; where the platform refuses, this
     * returns [RecorderError.EngineFailure] and **the recording keeps running**, including
     * [elapsed], because an engine that would not pause has not stopped capturing.
     */
    public fun pause(): RecorderResult<Unit>

    /**
     * Continues capture into the same file after [pause], from where [elapsed] left off.
     *
     * Legal only from [RecorderState.Paused].
     */
    public fun resume(): RecorderResult<Unit>

    /**
     * Finalizes the file and releases the microphone, moving [state] to
     * [RecorderState.Completed].
     *
     * Legal from [RecorderState.Recording] and [RecorderState.Paused]. The recorder can be reused
     * for another recording by calling [prepare] again; it does not need to be released first.
     *
     * Also legal from [RecorderState.Interrupted], where the file is already finalized: it returns
     * that recording as a success and leaves [state] unchanged. A stop that loses the race against
     * a system event which already finalized the file returns the same way.
     *
     * On engine failure the state becomes [RecorderState.Failed] carrying the output path, and the
     * partial file is **kept**: a library does not delete a user's audio because the encoder
     * complained on close. The path on the state is how you find the file; [cancel] deletes it.
     *
     * Suspending because the platform finalizes the container here — on Android, writing the
     * MPEG-4 `moov` atom — which takes a noticeable fraction of a second on a long recording. That
     * work runs on the factory's `coroutineContext`, not on the caller's thread.
     *
     * Cancelling the calling coroutine does not abandon it half-way: the stop runs to the end, the
     * state settles; the caller observes its cancellation no later than its next suspension point.
     *
     * @return the finished file and how long it ran.
     */
    public suspend fun stop(): RecorderResult<RecordedFile>

    /**
     * Abandons the current recording: the microphone is released, the partial file is deleted, and
     * [state] returns to [RecorderState.Idle] with [elapsed] reset.
     *
     * Legal from [RecorderState.Ready], [RecorderState.Recording], and [RecorderState.Paused], from
     * [RecorderState.Interrupted] (deletes the interrupted file), and from a
     * [RecorderState.Failed] that carries an output path (deletes the file it left behind).
     * Deliberately illegal from [RecorderState.Completed] — a finished recording is the caller's
     * file to keep or delete, and silently deleting it here would be a trap — and from a `Failed`
     * without a path, where there is nothing to delete. A system event that already ended the
     * recording before this call started is not undone: the file it left is deleted, which is what
     * a discard means.
     *
     * Suspending because it deletes a file; the deletion runs on the factory's `coroutineContext`.
     * Like [stop], it is not abandoned half-way by cancelling the calling coroutine.
     */
    public suspend fun cancel(): RecorderResult<Unit>

    /**
     * Permanently disposes the recorder: releases the native handle and microphone, stops the
     * [elapsed] coroutine, and moves [state] to [RecorderState.Released].
     *
     * An in-progress recording is stopped first and its **partial file is kept**, not deleted —
     * releasing is a lifecycle event (the screen went away), not a decision that the audio was
     * unwanted. Call [cancel] first if you want the file gone. Releasing from [RecorderState.Ready]
     * is the one exception: that file was never recorded into, and [prepare] — the only thing that
     * would ever discard it — can no longer run.
     *
     * Safe to call while a [prepare] is still in flight; see that method for what happens to the
     * preparation.
     *
     * **Not suspending, unlike [stop] and [cancel], and deliberately so** — despite doing the same
     * kind of work. Release belongs on a teardown path (`onCleared`, `doOnDestroy`, `deinit`,
     * `onDestroy`), and those are exactly the places where there is no coroutine left to launch in:
     * a scope tied to the same lifecycle has already been cancelled. A suspending `release` would
     * be uncallable precisely where it is needed, so it does its work inline on the calling thread
     * instead. Keep that in mind if you release on the main thread while a long recording is open:
     * finalizing it is the one place this library can block you.
     *
     * When a finalization is in flight (a [stop], a [cancel] or a system event closing the file off
     * the lock), `release` returns at once and leaves the native recorder to that finalization,
     * which frees it when it completes; its outcome is not published over
     * [RecorderState.Released]. The microphone is then free a moment after `release` returns.
     *
     * Idempotent, never fails, and never throws. See the class-level "Ownership and release" note
     * for who is expected to call it.
     */
    public fun release()
}
