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
     * The last operation failed with [error]. The recorder holds no native resource and can be
     * re-prepared; the state is kept (rather than reset to [Idle]) so a consumer collecting
     * [AudioRecorder.state] can render the failure without also having to observe every call's
     * return value.
     */
    public data class Failed(public val error: RecorderError) : RecorderState

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

/** Whether the recorder holds an open output file, i.e. is [RecorderState.Recording] or paused. */
public val RecorderState.isActive: Boolean
    get() = this is RecorderState.Recording || this is RecorderState.Paused

/**
 * The file this state refers to, or `null` in the states that have no file yet ([RecorderState.Idle],
 * [RecorderState.Preparing], [RecorderState.Failed], [RecorderState.Released]).
 */
public val RecorderState.outputPath: String?
    get() = when (this) {
        is RecorderState.Ready -> outputPath
        is RecorderState.Recording -> outputPath
        is RecorderState.Paused -> outputPath
        is RecorderState.Completed -> recording.path
        RecorderState.Idle, RecorderState.Preparing, RecorderState.Released -> null
        is RecorderState.Failed -> null
    }
