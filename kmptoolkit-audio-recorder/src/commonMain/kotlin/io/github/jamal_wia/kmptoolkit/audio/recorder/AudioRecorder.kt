package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.time.Duration
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
 * | [RecorderState.Failed] | yes | no | no | no | no | no |
 * | [RecorderState.Released] | no | no | no | no | no | no |
 *
 * `prepare` from [RecorderState.Ready], [RecorderState.Completed], or [RecorderState.Failed] starts
 * a fresh recording: the previous native recorder is torn down first, and a `Ready` file that was
 * never recorded into is deleted. `prepare` never resumes or appends to an earlier recording.
 *
 * ## Ownership and release
 *
 * The recorder owns a native handle (`android.media.MediaRecorder` / `AVAudioRecorder`), the
 * microphone, and a coroutine that publishes [elapsed]. **The caller owns the recorder** and must
 * call [release] exactly once when done — from `onDestroy`, a Decompose `doOnDestroy`, a `deinit`,
 * or whatever scope holds the instance. Nothing releases it for you and no finalizer runs.
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
 * The recorder is **not** thread-safe. Call [prepare], [start], [pause], [resume], [stop],
 * [cancel], and [release] from one thread (or one single-threaded dispatcher) — the same one every
 * time. [state] and [elapsed] are `StateFlow`s and can be read and collected from anywhere.
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
     * only ever changes as a result of an operation on this recorder — it never emits a
     * duration tick, so a collector is only woken by a real transition.
     */
    public val state: StateFlow<RecorderState>

    /**
     * Time recorded into the current file so far, for driving a timer in the UI.
     *
     * Advances only while [state] is [RecorderState.Recording], polled at
     * [AudioRecorderConfig.durationUpdateInterval]. It freezes at its current value on [pause] and
     * continues from there on [resume], holds the final duration after [stop], and resets to
     * [Duration.ZERO] on [prepare], [cancel], and [release].
     *
     * This is wall-clock time between `start` and `stop`, not a measurement of the encoded file. It
     * is accurate enough for a recording timer and is not a substitute for reading the finished
     * file's real duration if you need an exact value.
     */
    public val elapsed: StateFlow<Duration>

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
     * On engine failure the state becomes [RecorderState.Failed] carrying the output path, and the
     * partial file is **kept**: a library does not delete a user's audio because the encoder
     * complained on close. Since [cancel] is illegal from that state, the path on the state is how
     * you find the file if you want to remove it yourself.
     *
     * Suspending because the platform finalizes the container here — on Android, writing the
     * MPEG-4 `moov` atom — which takes a noticeable fraction of a second on a long recording. That
     * work runs on the factory's `coroutineContext`, not on the caller's thread.
     *
     * @return the finished file and how long it ran.
     */
    public suspend fun stop(): RecorderResult<RecordedFile>

    /**
     * Abandons the current recording: the microphone is released, the partial file is deleted, and
     * [state] returns to [RecorderState.Idle] with [elapsed] reset.
     *
     * Legal from [RecorderState.Ready], [RecorderState.Recording], and [RecorderState.Paused].
     * Deliberately illegal from [RecorderState.Completed] — a finished recording is the caller's
     * file to keep or delete, and silently deleting it here would be a trap.
     *
     * Suspending because it deletes a file; the deletion runs on the factory's `coroutineContext`.
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
     * Idempotent, never fails, and never throws. See the class-level "Ownership and release" note
     * for who is expected to call it.
     */
    public fun release()
}
