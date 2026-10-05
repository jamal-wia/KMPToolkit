package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.time.Duration

/**
 * Where an [AudioRecorder] currently sits in its lifecycle. The legal transitions between these
 * states are tabulated on [AudioRecorder].
 *
 * The states carry no live duration: a recording timer reads [AudioRecorder.elapsed] instead, so
 * that collecting [AudioRecorder.state] wakes a consumer on real transitions only and never ten
 * times a second.
 */
public sealed interface RecorderState {

    /** Nothing is prepared and no native resource is held. The state a recorder starts in. */
    public data object Idle : RecorderState

    /**
     * [AudioRecorder.prepare] is in flight. No operation is legal until it resolves to [Ready] or
     * [Failed].
     */
    public data object Preparing : RecorderState

    /**
     * The microphone and [outputPath] are open and capture can begin. Nothing has been recorded
     * yet, and the file at [outputPath] is empty or does not exist.
     */
    public data class Ready(public val outputPath: String) : RecorderState

    /** Audio is being captured into [outputPath]. */
    public data class Recording(public val outputPath: String) : RecorderState

    /**
     * Capture is suspended but [outputPath] is still open, holding [elapsed] recorded so far.
     * [AudioRecorder.resume] continues into the same file.
     */
    public data class Paused(
        public val outputPath: String,
        public val elapsed: Duration,
    ) : RecorderState

    /**
     * A recording finished and [recording] is a complete, closed file. The recorder holds no
     * native resource in this state and can be re-prepared.
     */
    public data class Completed(public val recording: RecordedFile) : RecorderState

    /**
     * The recording was ended by the system, not by the app, and [recording] is a finalized,
     * playable file holding everything captured up to that moment — [RecordedFile.duration] is
     * [AudioRecorder.elapsed] as it was frozen. The recorder holds no native resource in this state.
     *
     * Reached from [Recording] or [Paused] when the platform ended the recording: the audio
     * session was taken (a call, Siri, an alarm, another app — iOS), the input was silenced
     * (Android 10+), free space fell below the reserve the library keeps for finalizing, or the
     * media service died. [reason] says which; see [InterruptionReason]. Never the answer to one of
     * your own calls, and never emitted for a [AudioRecorder.stop] or [AudioRecorder.cancel] of
     * yours — a normal stop is not an interruption, and a discard is not a save.
     *
     * The file belongs to you, as after [Completed]: [AudioRecorder.stop] returns it again and
     * leaves the state unchanged, [AudioRecorder.cancel] deletes it, and [AudioRecorder.prepare]
     * starts a fresh recording without touching it. A long recording is usually a series of
     * segments: treat this like a finished segment and prepare the next one.
     *
     * If the file could **not** be finalized the state is [Failed] carrying
     * [RecorderError.RecordingLost] instead.
     *
     * @since 2.2.0
     */
    public data class Interrupted(
        public val recording: RecordedFile,
        public val reason: InterruptionReason,
    ) : RecorderState

    /**
     * The last operation failed with [error], or the system ended a recording whose file could not
     * be finalized ([RecorderError.RecordingLost]). The recorder holds no native resource and can be
     * re-prepared; the state is kept (rather than reset to [Idle]) so a consumer collecting
     * [AudioRecorder.state] can render the failure without also having to observe every call's
     * return value.
     *
     * @param outputPath the file the failure left behind, when it left one — a failed
     *   [AudioRecorder.stop], which keeps whatever was captured, or a [RecorderError.RecordingLost]
     *   whose file was kept (it may be unplayable). `null` whenever the failure deleted its own
     *   file or never created one. [AudioRecorder.cancel] is legal exactly when this is non-null,
     *   and deletes that file; it is how you throw the leftover away, and the path is how you find
     *   it if you would rather keep it.
     */
    public data class Failed(
        public val error: RecorderError,
        public val outputPath: String? = null,
    ) : RecorderState

    /**
     * [AudioRecorder.release] was called. Terminal: every operation from here returns
     * [RecorderError.AlreadyReleased] and no further state change is possible.
     */
    public data object Released : RecorderState
}

/** A finished recording: an existing, closed audio file and how long it ran. */
public data class RecordedFile(

    /** Absolute path of the file on the device's filesystem. */
    public val path: String,

    /**
     * Wall-clock time between `start` and `stop`, excluding time spent paused. Measured by the
     * recorder rather than read back from the encoded file, so treat it as accurate to roughly the
     * configured tick, not to the sample.
     */
    public val duration: Duration,
)

/** Whether audio is being captured right now — true only in [RecorderState.Recording]. */
public val RecorderState.isRecording: Boolean
    get() = this is RecorderState.Recording

/**
 * Whether the recorder holds an open output file, i.e. is [RecorderState.Recording] or paused.
 * `false` for [RecorderState.Interrupted]: its file is finalized and closed.
 */
public val RecorderState.isActive: Boolean
    get() = this is RecorderState.Recording || this is RecorderState.Paused

/**
 * The file this state refers to, or `null` in the states that have none: [RecorderState.Idle],
 * [RecorderState.Preparing], [RecorderState.Released], and a [RecorderState.Failed] whose failure
 * left no file behind.
 */
public val RecorderState.outputPath: String?
    // `state.outputPath` inside each branch resolves to the smart-cast subtype's own member
    // property, not back to this extension — written with an explicit receiver so that stays
    // obvious to a reader, and to a future rename.
    get() = when (val state: RecorderState = this) {
        is RecorderState.Ready -> state.outputPath
        is RecorderState.Recording -> state.outputPath
        is RecorderState.Paused -> state.outputPath
        is RecorderState.Completed -> state.recording.path
        is RecorderState.Interrupted -> state.recording.path
        is RecorderState.Failed -> state.outputPath
        RecorderState.Idle, RecorderState.Preparing, RecorderState.Released -> null
    }
